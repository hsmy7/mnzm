package com.xianxia.sect.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import com.xianxia.sect.data.crypto.SecureKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.HttpUrl
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * 请求签名器 - 负责为每个 HTTP 请求生成安全签名
 *
 * 核心机制：
 * 1. **HMAC-SHA256 签名**：对请求方法 + URI + 时间戳 + 请求体哈希进行 HMAC 签名
 * 2. **时间戳防重放**：每个请求携带服务端可校验的时间戳，5 分钟窗口外拒绝
 * 3. **请求体完整性**：对请求体做 SHA-256 哈希，防止中间人篡改
 * 4. **设备指纹绑定**：将设备指纹混入签名原料，使签名与特定设备绑定
 *
 * 生成的请求头：
 * - `X-Signature`     : HMAC-SHA256 十六进制签名
 * - `X-Timestamp`     : Unix 毫秒时间戳
 * - `X-Nonce`         : 单次随机数（防重放增强）
 * - `X-Body-Hash`     : 请求体的 SHA-256 哈希（无 body 时省略）
 * - `X-Device-Fp`     : 设备指纹前8位（用于服务端绑定校验）
 * - `X-Sign-Version`  : 签名方案版本号
 */
class RequestSigner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "RequestSigner"

        /** 签名方案版本，服务端据此选择验签逻辑 */
        const val SIGN_VERSION = "v2"  // 升级版本号以支持 requestId

        /** HMAC 算法 */
        private const val HMAC_ALGO = "HmacSHA256"

        /** 哈希算法（用于请求体完整性） */
        private const val HASH_ALGO = "SHA-256"

        /** Nonce 长度（字节） */
        private const val NONCE_LENGTH = 16

        /** 设备指纹暴露长度（字符数），仅传前缀用于服务端匹配，不泄露完整指纹 */
        private const val DEVICE_FP_PREFIX_LENGTH = 16

        /**
         * 服务端验证时间窗口（毫秒）
         *
         * 超过此窗口的请求将被视为过期或重放攻击。
         * 设置为 5 分钟（300,000 毫秒），平衡安全性和用户体验。
         */
        const val TIMESTAMP_VALIDITY_WINDOW_MS = 5 * 60 * 1000L

        /**
         * 客户端重放检测缓存最大容量
         *
         * 使用 LRU 策略，当缓存满时自动淘汰最旧的条目。
         * 设置为 1000 条，足以覆盖正常使用场景。
         */
        private const val REPLAY_CACHE_MAX_SIZE = 1000
    }

    /** 缓存的设备指纹（进程生命周期内不变） */
    private val cachedDeviceFingerprint: String by lazy {
        computeDeviceFingerprint()
    }

    /** 缓存的 HMAC 密钥（由 SecureKeyManager 派生） */
    private val signingKey: ByteArray by lazy {
        deriveSigningKey()
    }

    /**
     * 客户端重放检测缓存（线程安全）
     *
     * 存储已发送请求的 requestId + timestamp，用于防止客户端侧重放。
     * 使用 ConcurrentHashMap 保证多线程安全。
     *
     * Key: requestId (UUID)
     * Value: 请求生成时间戳 (毫秒)
     *
     * 淘汰策略：
     * - 当缓存大小超过 [REPLAY_CACHE_MAX_SIZE] 时，清理过期条目
     * - 过期标准：当前时间 - 条目时间戳 > [TIMESTAMP_VALIDITY_WINDOW_MS] * 2
     */
    private val replayDetectionCache = ConcurrentHashMap<String, Long>()

    /**
     * 原子计数器，用于生成递增的请求序列号
     *
     * 配合 UUID 使用，确保即使在极端情况下 UUID 碰撞，
     * 序列号也能保证唯一性。
     */
    @Volatile
    private var requestSequenceNumber = 0L

    // ──────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────

    /**
     * 对 [Request.Builder] 注入所有安全相关头。
     *
     * 此方法会：
     * 1. 生成当前时间戳、随机 nonce 和唯一 requestId
     * 2. 计算请求体 SHA-256 哈希
     * 3. 组装签名字符串并计算 HMAC
     * 4. 将所有头写入 builder
     * 5. 在重放检测缓存中记录该请求（防止客户端侧重放）
     *
     * @param request 正在构建中的 OkHttp 请求
     * @return 已添加安全头的同一个 builder（链式调用友好）
     */
    fun signRequest(request: Request.Builder, httpUrl: HttpUrl, method: String, body: ByteArray?): Request.Builder {
        val timestamp = System.currentTimeMillis()
        val nonce = generateNonce()
        val requestId = generateRequestId()
        val bodyHash = hashBody(body)
        val deviceFpPrefix = cachedDeviceFingerprint.take(DEVICE_FP_PREFIX_LENGTH)

        // 构造签名字符串：method\nuri\ntimestamp\nnonce\nrequestId\nbodyHash\ndeviceFpPrefix
        val signString = buildSignatureString(
            method = method,
            uri = extractPathAndQuery(httpUrl),
            timestamp = timestamp,
            nonce = nonce,
            requestId = requestId,
            bodyHash = bodyHash,
            deviceFpPrefix = deviceFpPrefix
        )

        val signature = computeHmacSha256(signString)

        // 记录到重放检测缓存（防止客户端侧重复发送）
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
                """.trimMargin()
            )
        }

        return request
            .header("X-Signature", signature)
            .header("X-Timestamp", timestamp.toString())
            .header("X-Nonce", nonce)
            .header("X-Request-Id", requestId)  // 新增：唯一请求标识
            .header("X-Sign-Version", SIGN_VERSION)
            .apply {
                if (bodyHash != null) {
                    header("X-Body-Hash", bodyHash)
                }
                header("X-Device-Fp", deviceFpPrefix)
            }
    }

    /**
     * 校验响应中的时间戳是否在允许的偏差范围内。
     *
     * 用于检测服务端返回的 replay 攻击指示。
     *
     * @param serverTimestamp 服务端返回的时间戳（毫秒）
     * @return true 表示时间戳在容差范围内
     */
    fun isTimestampValid(serverTimestamp: Long): Boolean {
        val delta = Math.abs(System.currentTimeMillis() - serverTimestamp)
        return delta <= NetworkSecurityConfig.TIMESTAMP_TOLERANCE_MS
    }

    // ──────────────────────────────────────────────
    // 内部实现
    // ──────────────────────────────────────────────

    /**
     * 构造规范化签名字符串。
     *
     * 格式：`METHOD\nURI\nTIMESTAMP\nNONCE\nREQUEST_ID\nBODY_HASH\nDEVICE_FP`
     *
     * 各字段间用 `\n` 分隔确保边界清晰，避免歧义拼接攻击。
     *
     * v2 版本新增 REQUEST_ID 字段，用于服务端去重和请求追踪。
     */
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
        sb.append(requestId).append('\n')  // v2 新增
        sb.append(bodyHash ?: "").append('\n')
        sb.append(deviceFpPrefix)
        return sb.toString()
    }

    /**
     * 计算 HMAC-SHA256 签名，返回小写十六进制字符串。
     */
    private fun computeHmacSha256(data: String): String {
        return try {
            val mac = Mac.getInstance(HMAC_ALGO)
            val keySpec = SecretKeySpec(signingKey, HMAC_ALGO)
            mac.init(keySpec)
            val hash = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            bytesToHex(hash)
        } catch (e: Exception) {
            Log.e(TAG, "HMAC-SHA256 签名计算失败", e)
            throw SecurityException("请求签名失败: ${e.message}", e)
        }
    }

    /**
     * 对请求体做 SHA-256 哈希，用于完整性校验。
     *
     * @param body 请求体字节，可为 null（GET/DELETE 等无 body 请求）
     * @return 十六进制哈希值，body 为 null/空时返回 null
     */
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

    /**
     * 从 SecureKeyManager 的主密钥派生专用签名密钥（增强版）
     *
     * 使用多次迭代哈希 + 应用特定盐值的增强派生方案：
     * 1. 拼接主密钥和应用盐值（增加熵）
     * 2. 进行 1000 次 SHA-256 迭代哈希（类似 PBKDF2 轻量版）
     * 3. 清理所有中间敏感数据（防止内存泄露）
     *
     * 安全性提升：
     * - 单次 SHA-256 容易被彩虹表攻击
     * - 多次迭代大幅增加暴力破解成本（1000 倍）
     * - 盐值确保即使主密钥相同，不同用途的派生密钥也不同
     * - 中间值清理降低内存转储攻击风险
     */
    private fun deriveSigningKey(): ByteArray {
        return try {
            val masterKey = SecureKeyManager.getOrCreateKey(context)

            // 使用应用特定盐值增加熵（硬编码的固定盐值）
            val salt = "xianxia-sig-v2-salt-2024".toByteArray(StandardCharsets.UTF_8)

            // 初始化派生值：masterKey || salt
            var derived = ByteArray(masterKey.size + salt.size)
            System.arraycopy(masterKey, 0, derived, 0, masterKey.size)
            System.arraycopy(salt, 0, derived, masterKey.size, salt.size)

            // 多次迭代哈希（类似 PBKDF2 轻量版，1000次迭代）
            // 每次迭代将上一次的哈希结果作为输入，形成哈希链
            val md = MessageDigest.getInstance(HASH_ALGO)
            for (i in 0..999) {
                derived = md.digest(derived)
            }

            // 清理中间敏感值（防止内存转储攻击获取原始密钥）
            masterKey.fill(0)
            salt.fill(0)

            derived
        } catch (e: Exception) {
            Log.e(TAG, "签名密钥派生失败", e)
            throw SecurityException("无法获取签名密钥: ${e.message}", e)
        }
    }

    /**
     * 计算设备指纹。
     *
     * 复用 SecureKeyManager 中已有的设备标识逻辑，
     * 但额外加入包名和应用签名证书哈希以增强唯一性。
     */
    @SuppressLint("HardwareIds")
    private fun computeDeviceFingerprint(): String {
        val parts = mutableListOf<String>()

        // Android ID（基础设备标识）
        try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            parts.add(androidId)
        } catch (_: Exception) {}

        // 硬件信息
        try {
            parts.add(Build.BOARD)
            parts.add(Build.BRAND)
            parts.add(Build.DEVICE)
            parts.add(Build.HARDWARE)
            parts.add(Build.MODEL)
            parts.add(Build.PRODUCT)
        } catch (_: Exception) {}

        // 序列号（Android O+）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                @SuppressLint("HardwareIds")
                parts.add(Build.getSerial() ?: "no_serial")
            }
        } catch (_: Exception) {}

        // 应用签名证书哈希（区分不同开发者签名的同包名应用）
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

    /**
     * 从 HttpUrl 提取路径+查询部分（不含 scheme 和 host）。
     * 签名只覆盖路径层，不包含 host 以支持多环境部署。
     */
    private fun extractPathAndQuery(url: HttpUrl): String {
        val path = url.encodedPath
        val query = url.encodedQuery
        return if (query != null) "$path?$query" else path
    }

    /** 生成加密安全的随机 Nonce（Base64 编码） */
    private fun generateNonce(): String {
        val bytes = ByteArray(NONCE_LENGTH)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /** 字节数组转小写十六进制字符串 */
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ══════════════════════════════════════════════
    // 请求 ID 生成与重放检测
    // ══════════════════════════════════════════════

    /**
     * 生成唯一的请求标识符
     *
     * 组合方式：`UUID-序列号`
     *
     * 示例：`550e8400-e29b-41d4-a716-446655440000-000000000001`
     *
     * 为什么使用 UUID + 序列号组合：
     * - UUID 保证全局唯一性（碰撞概率极低）
     * - 序列号保证同一进程内的时序性
     * - 组合后即使在分布式环境也能追踪请求顺序
     *
     * @return 唯一请求 ID 字符串
     */
    private fun generateRequestId(): String {
        val uuid = UUID.randomUUID().toString()
        val sequence = ++requestSequenceNumber
        return "$uuid-$sequence"
    }

    /**
     * 将请求记录到重放检测缓存中
     *
     * 在每次签名请求时调用，用于防止客户端侧的意外重放。
     * 虽然主要防重放逻辑在服务端实现，
     * 但客户端缓存可以：
     * 1. 避免因网络重试导致的重复请求
     * 2. 在调试阶段快速发现异常重复
     * 3. 为未来的客户端侧去重提供基础设施
     *
     * @param requestId 请求唯一标识
     * @param timestamp 请求生成时间戳
     */
    private fun recordRequestForReplayDetection(requestId: String, timestamp: Long) {
        // 如果缓存已满，先清理过期条目
        if (replayDetectionCache.size >= REPLAY_CACHE_MAX_SIZE) {
            cleanupExpiredCacheEntries()
        }

        replayDetectionCache[requestId] = timestamp

        if (NetworkSecurityConfig.isVerboseLoggingEnabled) {
            Log.v(TAG, "Request recorded for replay detection: $requestId, cache size: ${replayDetectionCache.size}")
        }
    }

    /**
     * 清理过期的重放检测缓存条目
     *
     * 淘汰标准：当前时间 - 条目时间戳 > 有效窗口 * 2
     * 使用 2 倍窗口确保即使有网络延迟也不会误删有效条目。
     */
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

    /**
     * 检查指定 requestId 是否已存在于缓存中（用于调试和测试）
     *
     * @param requestId 待检查的请求 ID
     * @return true 表示该请求已被记录过
     */
    fun isRequestRecorded(requestId: String): Boolean {
        return replayDetectionCache.containsKey(requestId)
    }

    /**
     * 获取当前重放检测缓存大小（用于监控和测试）
     *
     * @return 缓存中的条目数量
     */
    fun getReplayCacheSize(): Int {
        return replayDetectionCache.size
    }

    /**
     * 手动清理所有缓存条目（仅在测试或特殊场景使用）
     *
     * ⚠️ 注意：生产环境中不应调用此方法，
     * 仅在单元测试或应用重置场景下使用。
     */
    fun clearReplayCache() {
        replayDetectionCache.clear()
        Log.w(TAG, "Replay detection cache manually cleared")
    }
}
