package com.xianxia.sect.data.crypto

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class CryptoModule @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Volatile
    private var cachedKey: ByteArray? = null
    private val keyLock = Any()

    var recoveryCallback: KeyRecoveryCallback?
        get() = SecureKeyManager.recoveryCallback
        set(value) { SecureKeyManager.recoveryCallback = value }

    fun encrypt(data: ByteArray, password: String): ByteArray =
        SaveCrypto.encrypt(data, password)

    fun encrypt(data: ByteArray, key: ByteArray): ByteArray =
        SaveCrypto.encrypt(data, key)

    fun decrypt(data: ByteArray, password: String): ByteArray? =
        SaveCrypto.decrypt(data, password)

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray? =
        SaveCrypto.decrypt(data, key)

    suspend fun encryptAsync(data: ByteArray, password: String): ByteArray =
        SaveCrypto.encryptAsync(data, password)

    suspend fun decryptAsync(data: ByteArray, password: String): ByteArray? =
        SaveCrypto.decryptAsync(data, password)

    fun getOrCreateKey(context: Context): ByteArray {
        synchronized(keyLock) {
            cachedKey?.let { return it.copyOf() }
            val key = SecureKeyManager.getOrCreateKey(context)
            cachedKey = key.copyOf()
            return key
        }
    }

    fun computeChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    fun verifyChecksum(data: ByteArray, expectedHex: String): Boolean {
        val actual = computeChecksum(data)
        return constantTimeEquals(actual, expectedHex)
    }

    fun computeDataHash(data: Any): String =
        computeMerkleRoot(data)

    fun computeFullDataSignature(data: Any, key: ByteArray): String =
        IntegrityValidator.computeFullDataSignature(data, key)

    fun verifyFullDataSignature(data: String, signature: String, key: ByteArray): Boolean =
        IntegrityValidator.verifyFullDataSignature(data, signature, key)

    fun signPayload(data: Any, key: ByteArray): SignedPayload =
        IntegrityValidator.createSignedPayload(data, key)

    fun verifySignedPayload(data: Any, payload: SignedPayload, key: ByteArray, maxAgeMs: Long): VerificationResult =
        IntegrityValidator.verifySignedPayload(data, payload, key, maxAgeMs)

    suspend fun precomputeDerivedKey(password: String, salt: ByteArray?): Boolean =
        SaveCryptoKeyDerivation.precomputeDerivedKey(password, salt)

    fun clearKeyCache() {
        synchronized(keyLock) {
            cachedKey?.let { Arrays.fill(it, 0.toByte()) }
            cachedKey = null
        }
        SaveCryptoKeyCache.clearAllKeyCache()
    }

    fun verifyKeyIntegrity(context: Context): Boolean =
        SecureKeyManager.verifyKeyIntegrity(context)

    fun exportKeyRecoveryToken(context: Context): String =
        SecureKeyManager.exportKeyRecoveryToken(context)

    fun importKeyRecoveryToken(context: Context, token: String): Boolean =
        SecureKeyManager.importKeyRecoveryToken(token, context)
}

object IntegrityValidator {
    private const val TAG = "IntegrityValidator"
    private const val SIGNATURE_VERSION = 1

    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun computeFullDataSignature(data: Any, key: ByteArray): String {
        val dataJson = dataToJsonString(data)
        val canonicalJson = canonicalizeJson(dataJson)
        // 在签名 payload 前添加版本号前缀，防止跨版本 kotlinx.serialization 不兼容
        val payloadWithVersion = "${SIGNATURE_VERSION}|${canonicalJson}"
        return computeHmacHex(payloadWithVersion.toByteArray(Charsets.UTF_8), key)
    }

    fun computeFullDataSignatureBytes(data: Any, key: ByteArray): ByteArray {
        val dataJson = dataToJsonString(data)
        val canonicalJson = canonicalizeJson(dataJson)
        // 在签名 payload 前添加版本号前缀，与 computeFullDataSignature() 保持一致
        val payloadWithVersion = "${SIGNATURE_VERSION}|${canonicalJson}"
        return computeHmac(payloadWithVersion.toByteArray(Charsets.UTF_8), key)
    }

    fun verifyFullDataSignature(data: Any, expectedSignature: String, key: ByteArray): Boolean {
        val actualSignature = computeFullDataSignature(data, key)
        return constantTimeEquals(actualSignature, expectedSignature)
    }

    fun createSignedPayload(data: Any, key: ByteArray, metadata: Map<String, String> = emptyMap()): SignedPayload {
        val timestamp = System.currentTimeMillis()
        val version = SIGNATURE_VERSION

        val dataJson = dataToJsonString(data)
        val dataHash = sha256Hex(dataJson.toByteArray(Charsets.UTF_8))
        val merkleRoot = computeMerkleRoot(data)

        val payloadToSign = buildString {
            append(version.toString())
            append("|")
            append(timestamp.toString())
            append("|")
            append(dataHash)
            append("|")
            append(merkleRoot)
            metadata.toSortedMap().forEach { (k, v) ->
                append("|")
                append(k)
                append("=")
                append(v)
            }
        }

        val signature = computeHmacHex(payloadToSign.toByteArray(Charsets.UTF_8), key)

        return SignedPayload(
            version = version,
            timestamp = timestamp,
            dataHash = dataHash,
            merkleRoot = merkleRoot,
            signature = signature,
            metadata = metadata
        )
    }

    fun verifySignedPayload(
        data: Any,
        payload: SignedPayload,
        key: ByteArray,
        maxAgeMs: Long = Long.MAX_VALUE
    ): VerificationResult {
        verifyVersionAndAge(payload, maxAgeMs)?.let { return it }
        verifyPayloadIntegrity(data, payload)?.let { return it }
        return verifyPayloadSignature(payload, key)
    }

    /** 版本与时效校验：不支持的未来版本或超龄载荷即失败 */
    private fun verifyVersionAndAge(payload: SignedPayload, maxAgeMs: Long): VerificationResult? {
        if (payload.version > SIGNATURE_VERSION) {
            return VerificationResult.Invalid("Unsupported signature version: ${payload.version}")
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - payload.timestamp > maxAgeMs) {
            return VerificationResult.Expired(payload.timestamp, currentTime)
        }

        return null
    }

    /** 数据完整性与防篡改校验：数据哈希与 Merkle 根双侧恒时比对 */
    private fun verifyPayloadIntegrity(data: Any, payload: SignedPayload): VerificationResult? {
        val dataJson = dataToJsonString(data)
        val currentDataHash = sha256Hex(dataJson.toByteArray(Charsets.UTF_8))

        if (!constantTimeEquals(currentDataHash, payload.dataHash)) {
            Log.w(TAG, "Data hash mismatch: expected=${payload.dataHash}, actual=$currentDataHash")
            return VerificationResult.Tampered("Data hash mismatch")
        }

        val currentMerkleRoot = computeMerkleRoot(data)
        if (!constantTimeEquals(currentMerkleRoot, payload.merkleRoot)) {
            Log.w(TAG, "Merkle root mismatch: expected=${payload.merkleRoot}, actual=$currentMerkleRoot")
            return VerificationResult.Tampered("Merkle root mismatch")
        }

        return null
    }

    /** 签名校验：metadata 规范化重排后 HMAC 恒时比对 */
    private fun verifyPayloadSignature(payload: SignedPayload, key: ByteArray): VerificationResult {
        val payloadToVerify = buildString {
            append(payload.version.toString())
            append("|")
            append(payload.timestamp.toString())
            append("|")
            append(payload.dataHash)
            append("|")
            append(payload.merkleRoot)
            payload.metadata.toSortedMap().forEach { (k, v) ->
                append("|")
                append(k)
                append("=")
                append(v)
            }
        }

        val expectedSignature = computeHmacHex(payloadToVerify.toByteArray(Charsets.UTF_8), key)
        if (!constantTimeEquals(payload.signature, expectedSignature)) {
            Log.w(TAG, "Signature mismatch")
            return VerificationResult.Tampered("Signature mismatch")
        }

        return VerificationResult.Valid
    }

    /**
     * 计算字符串的HMAC-SHA256十六进制表示（公共方法，供StorageValidator使用）
     * @param data 要签名的字符串数据
     * @param key HMAC密钥
     * @return 十六进制格式的HMAC签名
     */
    fun computeHmacForString(data: String, key: ByteArray): String {
        return computeHmacHex(data.toByteArray(Charsets.UTF_8), key)
    }
}

@Serializable
data class SignedPayload(
    val version: Int,
    val timestamp: Long,
    val dataHash: String,
    val merkleRoot: String,
    val signature: String,
    val metadata: Map<String, String> = emptyMap()
) {
    fun toJson(): String {
        return com.xianxia.sect.data.crypto.IntegrityValidator.json.encodeToString(this)
    }

    companion object {
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
        fun fromJson(json: String): SignedPayload? {
            return try {
                com.xianxia.sect.data.crypto.IntegrityValidator.json.decodeFromString<SignedPayload>(json)
            } catch (ignored: Exception) {
                null
            }
        }
    }
}

sealed class VerificationResult {
    data object Valid : VerificationResult()
    data class Invalid(val reason: String) : VerificationResult()
    data class Expired(val signedAt: Long, val currentTime: Long) : VerificationResult()
    data class Tampered(val reason: String) : VerificationResult()

    val isValid: Boolean get() = this is Valid
    val isTampered: Boolean get() = this is Tampered
    val isExpired: Boolean get() = this is Expired
}

data class IntegrityReport(
    val isValid: Boolean,
    val dataHash: String,
    val merkleRoot: String,
    val signatureValid: Boolean,
    val hashValid: Boolean,
    val merkleValid: Boolean,
    val errors: List<String> = emptyList()
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}
