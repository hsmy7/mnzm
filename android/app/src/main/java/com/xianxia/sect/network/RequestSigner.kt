package com.xianxia.sect.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.data.crypto.SecureKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

class RequestSigner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "RequestSigner"

        const val SIGN_VERSION = "v3"

        private const val HMAC_ALGO = "HmacSHA256"

        private const val HASH_ALGO = "SHA-256"

        private const val NONCE_LENGTH = 16

        private const val DEVICE_FP_PREFIX_LENGTH = 8

        const val TIMESTAMP_VALIDITY_WINDOW_MS = 5 * 60 * 1000L

        private const val REPLAY_CACHE_MAX_SIZE = 1000

        private const val KEY_FETCH_TIMEOUT_SECONDS = 10L

        private const val DEFAULT_KEY_EXPIRY_MS = 3600_000L
    }

    private val cachedDeviceFingerprint: String by lazy {
        computeDeviceFingerprint()
    }

    @Volatile
    private var hmacKey: SecretKeySpec? = null

    @Volatile
    private var keyExpiry: Long = 0

    private val keyLock = Mutex()

    private val secureRandom = SecureRandom()

    private val keyFetchClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(KEY_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(KEY_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val replayDetectionCache = ConcurrentHashMap<String, Long>()

    @Volatile
    private var requestSequenceNumber = 0L

    fun signRequest(request: Request.Builder, httpUrl: HttpUrl, method: String, body: ByteArray?): Request.Builder {
        val timestamp = System.currentTimeMillis()
        val nonce = generateNonce()
        val requestId = generateRequestId()
        val bodyHash = hashBody(body)
        val deviceFpPrefix = cachedDeviceFingerprint.take(DEVICE_FP_PREFIX_LENGTH)

        val signString = buildSignatureString(
            method = method,
            uri = extractPathAndQuery(httpUrl),
            timestamp = timestamp,
            nonce = nonce,
            requestId = requestId,
            bodyHash = bodyHash,
            deviceFpPrefix = deviceFpPrefix
        )

        val key = getSigningKey()
        val signature = computeHmacSha256(signString, key)

        recordRequestForReplayDetection(requestId, timestamp)

        if (NetworkSecurityConfig.isVerboseLoggingEnabled) {
            Log.v(
                TAG,
                """[签名明细]
                |Method=$method
                |URI=${extractPathAndQuery(httpUrl)}
                |Timestamp=$timestamp
                |Nonce=$nonce
                |RequestId=$requestId
                |BodyHash=${bodyHash ?: "(none)"}
                |Signature=$signature
                |KeySource=${if (hmacKey != null && System.currentTimeMillis() < keyExpiry) "server" else "local-derived"}
                """.trimMargin()
            )
        }

        return request
            .header("X-Signature", signature)
            .header("X-Timestamp", timestamp.toString())
            .header("X-Nonce", nonce)
            .header("X-Request-Id", requestId)
            .header("X-Sign-Version", SIGN_VERSION)
            .apply {
                if (bodyHash != null) {
                    header("X-Body-Hash", bodyHash)
                }
                header("X-Device-Fp", deviceFpPrefix)
            }
    }

    fun isTimestampValid(serverTimestamp: Long): Boolean {
        val delta = Math.abs(System.currentTimeMillis() - serverTimestamp)
        return delta <= NetworkSecurityConfig.TIMESTAMP_TOLERANCE_MS
    }

    private fun getSigningKey(): SecretKeySpec {
        hmacKey?.let { if (System.currentTimeMillis() < keyExpiry) return it }

        return try {
            runBlocking(Dispatchers.IO) {
                keyLock.withLock {
                    hmacKey?.takeIf { System.currentTimeMillis() < keyExpiry } ?: fetchKeyFromServer()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch HMAC key from server, falling back to local derivation", e)
            deriveLocalSigningKey()
        }
    }

    private suspend fun fetchKeyFromServer(): SecretKeySpec = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}auth/signing-key")
            .get()
            .build()

        val response = keyFetchClient.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response from signing-key endpoint")

        val json = JSONObject(body)
        val keyBytes = android.util.Base64.decode(json.getString("key"), android.util.Base64.NO_WRAP)
        keyExpiry = json.optLong("expires_at", System.currentTimeMillis() + DEFAULT_KEY_EXPIRY_MS)

        SecretKeySpec(keyBytes, HMAC_ALGO).also { hmacKey = it }
    }

    private fun deriveLocalSigningKey(): SecretKeySpec {
        return try {
            val masterKey = SecureKeyManager.getOrCreateKey(context)
            val salt = "xianxia-sig-v3-salt-2025".toByteArray(StandardCharsets.UTF_8)

            var derived = ByteArray(masterKey.size + salt.size)
            System.arraycopy(masterKey, 0, derived, 0, masterKey.size)
            System.arraycopy(salt, 0, derived, masterKey.size, salt.size)

            val md = MessageDigest.getInstance(HASH_ALGO)
            for (i in 0..999) {
                derived = md.digest(derived)
            }

            masterKey.fill(0)
            salt.fill(0)

            val keySpec = SecretKeySpec(derived, HMAC_ALGO)
            derived.fill(0)
            keySpec
        } catch (e: Exception) {
            Log.e(TAG, "签名密钥派生失败", e)
            throw SecurityException("无法获取签名密钥: ${e.message}", e)
        }
    }

    private fun buildSignatureString(
        method: String,
        uri: String,
        timestamp: Long,
        nonce: String,
        requestId: String,
        bodyHash: String?,
        deviceFpPrefix: String
    ): String {
        val sb = StringBuilder()
        sb.append(method.uppercase(Locale.US)).append('\n')
        sb.append(uri).append('\n')
        sb.append(timestamp).append('\n')
        sb.append(nonce).append('\n')
        sb.append(requestId).append('\n')
        sb.append(bodyHash ?: "").append('\n')
        sb.append(deviceFpPrefix)
        return sb.toString()
    }

    private fun computeHmacSha256(data: String, key: SecretKeySpec): String {
        return try {
            val mac = Mac.getInstance(HMAC_ALGO)
            mac.init(key)
            val hash = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            bytesToHex(hash)
        } catch (e: Exception) {
            Log.e(TAG, "HMAC-SHA256 签名计算失败", e)
            throw SecurityException("请求签名失败: ${e.message}", e)
        }
    }

    private fun hashBody(body: ByteArray?): String? {
        if (body == null || body.isEmpty()) return null
        return try {
            val digest = MessageDigest.getInstance(HASH_ALGO)
            val hash = digest.digest(body)
            bytesToHex(hash)
        } catch (e: Exception) {
            Log.e(TAG, "请求体哈希计算失败", e)
            throw SecurityException("请求体完整性校验失败: ${e.message}", e)
        }
    }

    @SuppressLint("HardwareIds")
    private fun computeDeviceFingerprint(): String {
        val parts = mutableListOf<String>()

        try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            parts.add(androidId)
        } catch (_: Exception) {}

        try {
            @Suppress("NewApi")
            @SuppressLint("InlinedApi")
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sigInfo = packageInfo.signingInfo
                val apkContentsSigners = sigInfo?.apkContentsSigners
                if (apkContentsSigners != null && apkContentsSigners.isNotEmpty()) {
                    val certDigest = MessageDigest.getInstance(HASH_ALGO)
                        .digest(apkContentsSigners[0].toByteArray())
                    parts.add(bytesToHex(certDigest))
                }
            } else {
                @Suppress("DEPRECATION")
                val signatures = packageInfo.signatures
                if (signatures != null && signatures.isNotEmpty()) {
                    val certDigest = MessageDigest.getInstance(HASH_ALGO)
                        .digest(signatures[0].toByteArray())
                    parts.add(bytesToHex(certDigest))
                }
            }
        } catch (_: Exception) {}

        return if (parts.isNotEmpty()) {
            MessageDigest.getInstance(HASH_ALGO)
                .digest(parts.joinToString("|").toByteArray(StandardCharsets.UTF_8))
                .let { bytesToHex(it) }
        } else {
            "default_fingerprint"
        }
    }

    private fun extractPathAndQuery(url: HttpUrl): String {
        val path = url.encodedPath
        val query = url.encodedQuery
        return if (query != null) "$path?$query" else path
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(NONCE_LENGTH)
        secureRandom.nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateRequestId(): String {
        val uuid = UUID.randomUUID().toString()
        val sequence = ++requestSequenceNumber
        return "$uuid-$sequence"
    }

    private fun recordRequestForReplayDetection(requestId: String, timestamp: Long) {
        if (replayDetectionCache.size >= REPLAY_CACHE_MAX_SIZE) {
            cleanupExpiredCacheEntries()
            if (replayDetectionCache.size >= REPLAY_CACHE_MAX_SIZE) {
                replayDetectionCache.minByOrNull { it.value }?.let { oldest ->
                    replayDetectionCache.remove(oldest.key)
                }
            }
        }

        replayDetectionCache[requestId] = timestamp

        if (NetworkSecurityConfig.isVerboseLoggingEnabled) {
            Log.v(TAG, "Request recorded for replay detection: $requestId, cache size: ${replayDetectionCache.size}")
        }
    }

    private fun cleanupExpiredCacheEntries() {
        val now = System.currentTimeMillis()
        val threshold = now - (TIMESTAMP_VALIDITY_WINDOW_MS * 2)

        var removedCount = 0
        val iterator = replayDetectionCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < threshold) {
                iterator.remove()
                removedCount++
            }
        }

        if (removedCount > 0 && NetworkSecurityConfig.isVerboseLoggingEnabled) {
            Log.d(TAG, "Cleaned up $removedCount expired replay cache entries, remaining: ${replayDetectionCache.size}")
        }
    }

    fun isRequestRecorded(requestId: String): Boolean {
        return replayDetectionCache.containsKey(requestId)
    }

    fun getReplayCacheSize(): Int {
        return replayDetectionCache.size
    }

    fun clearReplayCache() {
        replayDetectionCache.clear()
        Log.w(TAG, "Replay detection cache manually cleared")
    }
}
