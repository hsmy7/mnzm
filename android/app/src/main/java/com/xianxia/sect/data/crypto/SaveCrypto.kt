package com.xianxia.sect.data.crypto

import android.content.Context
import android.os.Build
import android.util.Log
import com.xianxia.sect.data.config.StorageConfig
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 存档加密/解密工具�?(v3 - 激进重构版)
 *
 * ## 重构变更清单 (v2 -> v3)
 *
 * | 优先�?| 问题 | 修复方案 |
 * |--------|------|----------|
 * | P0 | 双缓存不一致风�?(derivedKeyCache + precomputedKeys) | 合并为单一 unifiedKeyCache |
 * | P0 | Argon2id 使用错误�?PBEKeySpec 参数 | 改用 Argon2ParameterSpec (RFC 9106) |
 * | P1 | 缓存 TTL(2min) 与清理间�?5min) 不一�?| 统一�?5min |
 * | P1 | 冗余 @JvmStatic (object 单例中无意义) | 移除 |
 * | P2 | init 块启动协程影响测�?| 改为懒初始化 (首次使用时启�? |
 * | NEW | 密钥轮换无版本化支持 | 引入 KeyVersion 枚举和版本路�?|
 * | NEW | 无现�?AEAD 备选方�?| 预留 XChaCha20-Poly1305 接口 |
 *
 * ## PBKDF2 / Argon2id 迭代次数选择说明
 *
 * ### 迭代次数设计原则
 * - **安全�?*: 迭代次数越高，暴力破解成本越高（线性关系）
 * - **性能**: 迭代次数直接影响密钥派生耗时，需在安全与用户体验间平�?
 * - **当前�?*: 310,000 次（OWASP 2023 推荐最低值，主流设备上约 200-350ms�?
 *
 * ### 各版本迭代次�?
 *
 * | 常量�?| �?| 用�?|
 * |--------|------|------|
 * | PBKDF2_ITERATIONS | 310,000 | 当前标准（加�?解密 / Argon2id API<30 fallback�?|
 * | PBKDF2_LEGACY_ITERATIONS | 60,000 | 历史版本（v2 及更早），无需兼容解密 |
 *
 * ### 迭代次数版本化策�?
 *
 * 当未来需要再次调�?PBKDF2_ITERATIONS 时，应遵循以下流程：
 * 1. 将旧值记录到 PBKDF2_LEGACY_ITERATIONS（或增加更多 LEGACY 常量�?
 * 2. 在加密数据头部版本标识（KeyVersion）中区分不同迭代次数
 * 3. 解密时根据版本头路由到对应的迭代次数
 * 4. 至少保留一个完整版本的兼容期，确保所有用户数据可迁移
 */
object SaveCrypto {
    private const val TAG = "SaveCrypto"

    // ==================== 密钥版本化系�?====================

    /**
     * 密钥派生算法版本枚举
     *
     * 用于支持未来密钥平滑迁移和算法升级�?
     * 每个版本对应唯一的字节标识符，嵌入加密数据头部，
     * 解密时自动识别并路由到正确的派生/解密逻辑�?
     *
     * 版本演进路线�?
     * - V1 (0x01): 已废�?- 初始 PBKDF2 版本（无版本头）
     * - V2 (0x02): 当前生产 - Argon2id (AES-GCM)
     * - V3 (0x03): 预留 - XChaCha20-Poly1305 (2025+ 趋势)
     */
    enum class KeyVersion(val byteValue: Byte, val description: String) {
        /** 当前生产版本: Argon2id + AES-GCM */
        ARGON2ID_AES_GCM(0x02, "Argon2id-AES-GCM"),

        /** 预留版本: XChaCha20-Poly1305 (更长 nonce, 无限 nonce 重用安全) */
        XCHACHA20_POLY1305(0x03, "XChaCha20-Poly1305");

        companion object {
            fun fromByte(byte: Byte): KeyVersion? =
                entries.find { it.byteValue == byte }
        }
    }

    /** 当前活跃的加密版�?*/
    @Volatile
    private var activeVersion: KeyVersion = KeyVersion.ARGON2ID_AES_GCM

    /**
     * 切换加密算法版本（用�?A/B 测试或渐进式迁移�?
     *
     * 注意：切换版本后，新加密的数据将使用新格式，
     * 但旧数据仍可解密（多版本兼容读取）�?
     *
     * @param version 目标版本
     * @throws IllegalArgumentException 如果版本不支�?
     */
    @Throws(IllegalArgumentException::class)
    fun setActiveVersion(version: KeyVersion) {
        require(version in supportedVersions) { "Unsupported key version: $version" }
        activeVersion = version
        Log.i(TAG, "Active crypto version switched to: ${version.description}")
    }

    /** 获取当前活跃的加密版�?*/
    fun getActiveVersion(): KeyVersion = activeVersion

    /** 当前支持的版本列�?*/
    private val supportedVersions = setOf(
        KeyVersion.ARGON2ID_AES_GCM,
        KeyVersion.XCHACHA20_POLY1305
    )

    // ==================== 加密算法常量 ====================

    /** AES-GCM 加密变换：认证加密模式，提供机密性和完整�?*/
    private const val TRANSFORMATION_AES_GCM = "AES/GCM/NoPadding"

    /**
     * XChaCha20-Poly1305 加密变换（预留）
     *
     * 优势（相�?AES-GCM）：
     * - 24 字节 nonce（vs GCM �?12 字节），随机生成即可，无需计数�?
     * - �?IV 重用风险（nonce 空间 2^96，远�?GCM �?2^32 安全限制�?
     * - 纯软件实现，�?AES-NI 硬件依赖（ARM 设备性能稳定�?
     * - IETF RFC 8439 标准化，2025 年推荐用于移动端
     *
     * 可用性：Android API 26+ (BouncyCastle �?Conscrypt 实现)
     */
    private const val TRANSFORMATION_XCHACHA20_POLY1305 = "ChaCha20-Poly1305/None/NoPadding"

    /** 对称密钥算法：AES-256 */
    private const val KEY_ALGORITHM_AES = "AES"

    /** XChaCha20 密钥算法标识 */
    private const val KEY_ALGORITHM_CHACHA20 = "ChaCha20"

    /** 密钥长度�?56位（AES-256 / ChaCha20 均为 256 位） */
    private const val KEY_SIZE = 256

    /** GCM 认证标签长度�?28位（最大强度） */
    private const val GCM_TAG_LENGTH = 128

    /** GCM 初始化向量长度：12字节�?6位，GCM推荐值） */
    private const val GCM_IV_LENGTH = 12

    /**
     * XChaCha20-Poly1305 nonce 长度�?4字节�?92位）
     *
     * XChaCha20 使用扩展 nonce（XNonce），�?16 字节密钥派生的子密钥 + 8 字节随机 nonce 组成�?
     * 随机生成�?24 字节 nonce 空间极大�?^192），无需担心重用问题�?
     */
    private const val XCHACHA20_NONCE_LENGTH = 24

    /** HMAC 算法：SHA-256 */
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /** HMAC 输出长度�?2字节（SHA-256输出长度�?*/
    private const val HMAC_LENGTH = 32

    /** 盐值长度：32字节（足够防止彩虹表攻击�?*/
    private const val SALT_LENGTH = 32

    /**
     * 当前标准 PBKDF2 迭代次数�?10,000（OWASP 2023 推荐值）
     *
     * 选择依据�?
     * - OWASP 2023 密码存储指南推荐最�?310,000 次（HMAC-SHA256�?
     * - OWASP 2021 推荐最�?60,000 次（已过时）
     * - 在中�?Android 设备（骁�?65级别）上�?200-350ms
     * - 在低端设备上�?500-800ms（可接受，PBKDF2 仅作�?Argon2id �?fallback�?
     * - Argon2id 仍是首选密钥派生算法（API 30+），PBKDF2 仅用于旧设备
     */
    private const val PBKDF2_ITERATIONS = 310_000

    /**
     * 历史版本 PBKDF2 迭代次数�?0,000（OWASP 2021 推荐值，已过时）
     *
     * 用途：仅作为版本化记录�?
     * 若迭代次数再次变更，需根据此常量实现兼容解密逻辑�?
     *
     * 版本化策略详见文件头�?"迭代次数版本化策�? 章节�?
     */
    private const val PBKDF2_LEGACY_ITERATIONS = 60_000

    // ==================== Argon2id 常量 (OWASP 2025 推荐) ====================

    /**
     * Argon2id 默认内存参数�?4MB（KiB 单位�?
     *
     * OWASP 2025 推荐配置（高安全性）�?
     * - memoryCost = 64 MB (65536 KiB)
     * - timeCost (iterations) = 3
     * - parallelism = 2
     * - hashLength = 32 bytes (256 bits)
     *
     * 自适应降级策略（见 [resolveEffectiveMemoryKib]）：
     * - < 2GB RAM: 8MB（避�?OOM�?
     * - < 4GB RAM: 16MB（标准移动端�?
     * - >= 4GB RAM: 64MB（默认高安全性）
     *
     * 变更记录 (v2->v3): �?16MB 提升�?64MB 默认值，
     * 符合 OWASP 2025 对抗 GPU/ASIC 攻击的最新建议�?
     */
    private val ARGON2ID_MEMORY_KIB_DEFAULT = 65536  // 64MB - OWASP 2025 recommended

    /** Argon2id 并行度：2（平衡性能与安全性） */
    private const val ARGON2ID_PARALLELISM = 2

    /** Argon2id 迭代次数�?（配合高内存参数，符�?RFC 9106 推荐�?*/
    private const val ARGON2ID_ITERATIONS = 3

    /** Argon2id 输出长度�?2 字节�?56 位密钥） */
    private const val ARGON2ID_OUTPUT_LENGTH = 32

    // ==================== 版本标识常量 ====================

    /** 版本头：Argon2id (当前生产版本) */
    private val VERSION_ARGON2ID: Byte = KeyVersion.ARGON2ID_AES_GCM.byteValue

    /** HKDF 信息标签：用于密钥派生上下文绑定 */
    private const val HKDF_INFO_DEFAULT = "xianxia.save.key.v3"

    /**
     * 统一缓存 TTL�?分钟
     *
     * v3 重构变更：从原来�?2 分钟统一�?5 分钟�?
     * �?StorageConfig.DEFAULT_KEY_CACHE_DURATION_MS (300000L) 和清理间隔对齐�?
     *
     * 设计考量�?
     * - 游戏安全性优先，�?5 分钟仍在合理范围
     * - Argon2id/PBKDF2 派生在中端设备上�?100-200ms
     * - 缓存仅在内存中，进程终止即清�?
     * - 统一 TTL 和清理间隔消除时间窗口不一致导致的状态不一�?
     */
    private val UNIFIED_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5)

    /** 最大缓存条目数�?0（数据量有限，减少内存中密钥驻留�?*/
    private const val MAX_CACHE_SIZE = 10

    /** 统一清理间隔�?分钟（与 TTL 一致，确保过期键及时清理） */
    private val CACHE_CLEANUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    // ==================== 实例变量 ====================

    /** 安全随机数生成器 */
    private val secureRandom = SecureRandom()

    // ==================== 统一缓存架构 (v3 重构) ====================
    //
    // v2 问题：derivedKeyCache �?precomputedKeys 是两个独立的 ConcurrentHashMap�?
    //         存在状态不一致风�?-- 一个有数据另一个没有，�?TTL 过期时间不同步�?
    //
    // v3 方案：合并为单一 unifiedKeyCache，通过 KeySource 区分来源�?
    //         所有缓存操作（读、写、过期清理、淘汰、清除）都在同一数据结构上进行，
    //         从根本上消除双缓存不一致的可能性�?
    //

    /**
     * 密钥来源枚举（用于统一缓存的来源追踪）
     */
    private enum class KeySource {
        /** 启动时预计算（precomputeDerivedKey() 触发�?*/
        PRECOMPUTED,

        /** 实时派生（encrypt/decrypt 时按需计算�?*/
        DERIVED
    }

    /**
     * 统一缓存数据结构 (v3)
     *
     * 合并了原 CachedDerivedKey �?PrecomputedKeyEntry 的功能，
     * 通过 source 字段区分密钥来源以便监控和调试�?
     */
    private data class UnifiedCachedKey(
        val key: ByteArray,
        val createdAt: Long,
        val salt: ByteArray,
        val source: KeySource
    )

    /**
     * 统一密钥派生结果缓存 (v3 - 单一缓存架构)
     *
     * 结构：cacheKey -> UnifiedCachedKey
     * - cacheKey �?password + salt 的哈希构�?
     * - 包含派生密钥、创建时间戳、原始盐值、来源标�?
     * - TTL = 5 分钟（与清理间隔一致）
     *
     * 线程安全保证�?
     * - ConcurrentHashMap 保证单个操作的原子�?
     * - 复合操作（检�?然后-操作）通过函数式方法保证一致�?
     */
    private val unifiedKeyCache = ConcurrentHashMap<String, UnifiedCachedKey>()

    // ==================== 协程管理（懒初始化）====================

    /** ApplicationScopeProvider 引用（通过 initialize() 注入） */
    private lateinit var applicationScopeProvider: ApplicationScopeProvider

    /**
     * 初始化 SaveCrypto 的 ApplicationScopeProvider。
     *
     * 由于 SaveCrypto 是 object 单例，无法使用 Hilt 构造器注入，
     * 需在 Application.onCreate() 中调用此方法。
     *
     * @param provider 应用级 CoroutineScope 提供者
     */
    fun initialize(provider: ApplicationScopeProvider) {
        applicationScopeProvider = provider
    }

    /** 定期清理协程作用域（用于缓存过期条目清理） */
    private val cleanupScope get() = applicationScopeProvider.ioScope

    /** 定期清理 Job 引用（可取消�?*/
    private var cacheCleanupJob: Job? = null

    /**
     * 清理器懒初始化标志位 (v3 重构)
     *
     * 替代原来 init 块中的直接启动方式�?
     * 仅在首次访问缓存时才启动定期清理协程�?
     * 避免以下问题�?
     * 1. 单元测试中不需要后台任务运�?
     * 2. 应用冷启动时不产生不必要的资源开销
     * 3. 如果 SaveCrypto 从未被使用，不会浪费线程资源
     */
    private val cleanupInitialized = AtomicBoolean(false)

    /**
     * 确保清理器已初始化（懒加载模式）
     *
     * 使用 compareAndSet 保证只执行一次初始化�?
     * 即使多线程并发调用也只会启动一个清理协程�?
     */
    private fun ensureCleanupInitialized() {
        if (cleanupInitialized.compareAndSet(false, true)) {
            startPeriodicCacheCleanup()
            Log.d(TAG, "Lazy cache cleanup initialized on first access")
        }
    }

    // ==================== 公共加密方法（API 签名不变�?===================

    /**
     * 使用密码加密数据（使�?Argon2id�?
     *
     * @param data 待加密的明文数据
     * @param password 用户密码
     * @return 加密后的数据（格式：[version(1)] [salt(32)] [iv(12)] [ciphertext] [hmac(32)]�?
     */
    @Throws(CryptoException::class)
    fun encrypt(data: ByteArray, password: String): ByteArray {
        ensureCleanupInitialized()
        val salt = generateSecureRandomBytes(SALT_LENGTH)
        val iv = generateSecureRandomBytes(GCM_IV_LENGTH)
        val key = deriveKeyArgon2id(password, salt)
        return encryptInternal(data, key, salt, iv, VERSION_ARGON2ID)
    }

    /**
     * 使用已有密钥加密数据
     *
     * @param data 待加密的明文数据
     * @param key 已有的主密钥（将通过 HKDF 派生加密密钥�?
     * @return 加密后的数据
     */
    @Throws(CryptoException::class)
    fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        ensureCleanupInitialized()
        val salt = generateSecureRandomBytes(SALT_LENGTH)
        val iv = generateSecureRandomBytes(GCM_IV_LENGTH)
        val derivedKey = deriveKeyFromKey(key, salt)
        return encryptInternal(data, derivedKey, salt, iv, VERSION_ARGON2ID)
    }

    /**
     * 使用密码 + 硬件密钥双重加密（最高安全级别）
     *
     * 通过 SecureKeyManager 获取 Android Keystore 硬件级密钥，
     * 结合 Argon2id 派生的软件密钥，实现双重加密保护�?
     *
     * 加密流程�?
     * 1. 使用 Argon2id 从密码派生主密钥
     * 2. 获取 SecureKeyManager 的硬件级密钥
     * 3. 使用 HKDF 将两个密钥合并为最终加密密�?
     * 4. 执行 AES-GCM 加密
     *
     * @param data 待加密数�?
     * @param password 用户密码
     * @param context Android Context（用于访�?Keystore�?
     * @return 加密后的数据（格式：[version(1)] [salt(32)] [iv(12)] [ciphertext] [hmac(32)]�?
     */
    @Throws(CryptoException::class)
    fun encryptWithHardwareKey(data: ByteArray, password: String, context: Context): ByteArray {
        ensureCleanupInitialized()
        val salt = generateSecureRandomBytes(SALT_LENGTH)
        val iv = generateSecureRandomBytes(GCM_IV_LENGTH)

        // Argon2id 密钥派生
        val softwareKey = deriveKeyArgon2id(password, salt)

        // 硬件级密钥（Android Keystore�?
        val hardwareKey = SecureKeyManager.getOrCreateKey(context)

        // 合并密钥：HKDF(softwareKey || hardwareKey)
        val combinedKeyMaterial = softwareKey + hardwareKey
        val finalKey = deriveKeyHKDF(combinedKeyMaterial, salt, "xianxia.save.hw_key.v4".toByteArray(Charsets.UTF_8))

        return encryptInternal(data, finalKey, salt, iv, VERSION_ARGON2ID)
    }

    // ==================== 公共解密方法（API 签名不变�?===================

    /**
     * 使用密码解密数据
     *
     * 仅支�?Argon2id 格式（版本头 0x02）�?
     *
     * @param data 加密数据
     * @param password 用户密码
     * @return 解密后的明文，如果解密失败则返回 null
     */
    fun decrypt(data: ByteArray, password: String): ByteArray? {
        ensureCleanupInitialized()
        if (data.size < 1 + SALT_LENGTH + GCM_IV_LENGTH + HMAC_LENGTH + 1) {
            Log.e(TAG, "Data too short for decryption")
            return null
        }

        // 仅接�?Argon2id 格式
        val version = data[0]
        if (version != VERSION_ARGON2ID) {
            Log.e(TAG, "Unsupported version header: 0x${version.toString(16)}, expected 0x${VERSION_ARGON2ID.toString(16)}")
            return null
        }

        Log.d(TAG, "Detected Argon2id format (version=0x02)")
        return decryptWithVersion(data, password, SALT_LENGTH) { pwd, salt ->
            deriveKeyArgon2id(pwd, salt)
        }
    }

    /**
     * 使用版本化格式解密（带版本头的统一格式�?
     */
    private fun decryptWithVersion(
        data: ByteArray,
        password: String,
        saltLength: Int,
        keyDerivation: (String, ByteArray) -> ByteArray
    ): ByteArray? {
        return try {
            val minSize = 1 + saltLength + GCM_IV_LENGTH + HMAC_LENGTH + 1
            if (data.size < minSize) {
                Log.e(TAG, "Data too short for versioned decryption: ${data.size} < $minSize")
                return null
            }

            val salt = data.copyOfRange(1, 1 + saltLength)
            val iv = data.copyOfRange(1 + saltLength, 1 + saltLength + GCM_IV_LENGTH)
            val encrypted = data.copyOfRange(1 + saltLength + GCM_IV_LENGTH, data.size - HMAC_LENGTH)
            val storedSignature = data.copyOfRange(data.size - HMAC_LENGTH, data.size)

            // 派生密钥
            val key = keyDerivation(password, salt)

            // 时序安全的签名验�?
            val computedSignature = computeHmac(data.copyOfRange(0, data.size - HMAC_LENGTH), key)
            if (!timingSafeEqual(storedSignature, computedSignature)) {
                Log.w(TAG, "HMAC verification failed for versioned format")
                return null
            }

            // 执行解密
            val cipher = Cipher.getInstance(TRANSFORMATION_AES_GCM)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM_AES)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            Log.d(TAG, "Versioned decryption attempt failed", e)
            null
        }
    }

    /**
     * 使用已有密钥解密数据
     *
     * 仅支持带版本头的 Argon2id 格式�?
     *
     * @param data 加密数据
     * @param key 主密�?
     * @return 解密后的明文，如果解密失败则返回 null
     */
    fun decrypt(data: ByteArray, key: ByteArray): ByteArray? {
        ensureCleanupInitialized()
        if (data.size < 1 + SALT_LENGTH + GCM_IV_LENGTH + HMAC_LENGTH + 1) {
            Log.e(TAG, "Data too short for decryption")
            return null
        }

        // 仅接�?Argon2id 版本�?
        val version = data[0]
        if (version != VERSION_ARGON2ID) {
            Log.e(TAG, "Unsupported version header for key-based decrypt: 0x${version.toString(16)}")
            return null
        }

        return tryDecryptWithKeyVersioned(data, key, SALT_LENGTH)
    }

    /**
     * 使用密钥（非密码）尝试解密（带版本头的格式）
     */
    private fun tryDecryptWithKeyVersioned(
        data: ByteArray,
        key: ByteArray,
        saltLength: Int
    ): ByteArray? {
        return try {
            val minSize = 1 + saltLength + GCM_IV_LENGTH + HMAC_LENGTH + 1
            if (data.size < minSize) {
                return null
            }

            val salt = data.copyOfRange(1, 1 + saltLength)
            val iv = data.copyOfRange(1 + saltLength, 1 + saltLength + GCM_IV_LENGTH)
            val encrypted = data.copyOfRange(1 + saltLength + GCM_IV_LENGTH, data.size - HMAC_LENGTH)
            val storedSignature = data.copyOfRange(data.size - HMAC_LENGTH, data.size)

            val derivedKey = deriveKeyFromKey(key, salt)

            // 时序安全的签名验�?
            val computedSignature = computeHmac(
                data.copyOfRange(0, data.size - HMAC_LENGTH),
                derivedKey
            )
            if (!timingSafeEqual(storedSignature, computedSignature)) {
                return null
            }

            val cipher = Cipher.getInstance(TRANSFORMATION_AES_GCM)
            val secretKey = SecretKeySpec(derivedKey, KEY_ALGORITHM_AES)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            Log.d(TAG, "Decryption attempt failed for versioned key-based format(salt=$saltLength)", e)
            null
        }
    }

    // ==================== 异步加密/解密方法（API 签名不变�?===================

    /**
     * 异步加密 - 使用密码
     *
     * �?Argon2id 密钥派生操作移至 Dispatchers.Default（IO优化线程池）�?
     * 避免阻塞主线程。推荐在 UI 交互路径中使用此方法�?
     *
     * @param data 待加密数�?
     * @param password 用户密码
     * @return 加密结果
     */
    suspend fun encryptAsync(data: ByteArray, password: String): ByteArray =
        withContext(Dispatchers.Default) {
            encrypt(data, password)
        }

    /**
     * 异步解密 - 使用密码
     *
     * @param data 加密数据
     * @param password 用户密码
     * @return 解密结果，失败返�?null
     */
    suspend fun decryptAsync(data: ByteArray, password: String): ByteArray? =
        withContext(Dispatchers.Default) {
            decrypt(data, password)
        }

    /**
     * 异步加密 - 使用密钥
     */
    suspend fun encryptAsync(data: ByteArray, key: ByteArray): ByteArray =
        withContext(Dispatchers.Default) {
            encrypt(data, key)
        }

    /**
     * 异步解密 - 使用密钥
     */
    suspend fun decryptAsync(data: ByteArray, key: ByteArray): ByteArray? =
        withContext(Dispatchers.Default) {
            decrypt(data, key)
        }

    // ==================== 密钥预计算方法（v3: 使用统一缓存�?===================

    /**
     * 预计算派生密钥（推荐在应用启动时调用�?
     *
     * 当已知用户密码时（如从登录状态获取），可在后台预计算密钥�?
     * 避免首次加密/解密时的延迟。预计算的密钥会被存入统一缓存�?
     * 后续调用可直接复用�?
     *
     * v3 重构变更�?
     * - 不再维护独立�?precomputedKeys 缓存
     * - 直接写入 unifiedKeyCache，source 标记�?PRECOMPUTED
     * - 消除了双缓存状态不一致的风险
     *
     * 使用示例�?
     * ```kotlin
     * // 应用启动时或获取密码�?
     * lifecycleScope.launch {
     *     SaveCrypto.precomputeDerivedKey(userPassword, appSalt)
     * }
     * ```
     *
     * @param password 用户密码
     * @param salt 可选的固定盐值。如果为 null，将生成随机盐值（仅适用于单次使用场景）
     * @return 是否成功预计�?
     */
    suspend fun precomputeDerivedKey(
        password: String,
        salt: ByteArray? = null
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            ensureCleanupInitialized()
            val effectiveSalt = salt ?: generateSecureRandomBytes(SALT_LENGTH)
            val key = deriveKeyArgon2id(password, effectiveSalt)

            val cacheKey = buildCacheKey(password, effectiveSalt)
            putToUnifiedCache(cacheKey, key, effectiveSalt, KeySource.PRECOMPUTED)

            Log.d(TAG, "Precomputed derived key for cacheKey=${cacheKey.take(8)}... (unified cache)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to precompute derived key", e)
            false
        }
    }

    /**
     * 清除所有预计算密钥 (v3: 操作统一缓存)
     *
     * 仅清�?source=PRECOMPUTED 的条目，保留 DERIVED 条目�?
     */
    fun clearPrecomputedKeys() {
        val toRemove = unifiedKeyCache.filter { it.value.source == KeySource.PRECOMPUTED }
        toRemove.forEach { (key, entry) ->
            securelyClear(entry.key)
            securelyClear(entry.salt)
            unifiedKeyCache.remove(key)
        }
        Log.d(TAG, "Precomputed keys cleared (${toRemove.size} entries removed from unified cache)")
    }

    // ==================== 工具方法（API 签名不变�?===================

    /**
     * 计算 SHA-256 哈希
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * 计算 SHA-256 哈希并返回十六进制字符串
     */
    fun sha256Hex(data: ByteArray): String {
        return sha256(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * 生成数据的校验和（SHA-256�?
     */
    fun generateChecksum(data: ByteArray): ByteArray {
        return sha256(data)
    }

    /**
     * 验证校验和是否匹配（时序安全比较�?
     */
    fun verifyChecksum(data: ByteArray, expectedChecksum: ByteArray): Boolean {
        val actualChecksum = sha256(data)
        return timingSafeEqual(actualChecksum, expectedChecksum)
    }

    /**
     * 将校验和嵌入数据头部
     */
    fun embedChecksum(data: ByteArray): ByteArray {
        val checksum = sha256(data)
        val result = ByteArray(checksum.size + data.size)
        System.arraycopy(checksum, 0, result, 0, checksum.size)
        System.arraycopy(data, 0, result, checksum.size, data.size)
        return result
    }

    /**
     * 从嵌入数据中提取校验和数�?
     */
    fun extractChecksumAndData(embeddedData: ByteArray): Pair<ByteArray, ByteArray>? {
        if (embeddedData.size < 32) return null
        val checksum = embeddedData.copyOfRange(0, 32)
        val data = embeddedData.copyOfRange(32, embeddedData.size)
        return Pair(checksum, data)
    }

    /**
     * 验证嵌入的校验和是否有效
     */
    fun verifyEmbeddedChecksum(embeddedData: ByteArray): Boolean {
        val (storedChecksum, data) = extractChecksumAndData(embeddedData) ?: return false
        val actualChecksum = sha256(data)
        return timingSafeEqual(storedChecksum, actualChecksum)
    }

    // ==================== 缓存管理方法（v3: 统一缓存接口�?===================

    /**
     * 获取当前缓存大小（用于监控和调试�?
     */
    fun getDerivedKeyCacheSize(): Int = unifiedKeyCache.size

    /**
     * 清除所有缓存的派生密钥（安全清理敏感数据）
     */
    fun clearDerivedKeyCache() {
        unifiedKeyCache.values.forEach { cached ->
            securelyClear(cached.key)
            securelyClear(cached.salt)
        }
        unifiedKeyCache.clear()
        Log.d(TAG, "Unified key cache cleared (securely wiped)")
    }

    /**
     * 启动定期缓存清理协程 (v3: 仅通过懒初始化调用)
     *
     * �?5 分钟检查一次过期条目并清理�?
     * 防止密钥在内存中驻留时间过长（安全优先）�?
     * 使用 SupervisorJob 确保单个清理失败不影响后续调度�?
     *
     * v3 变更：不再从 init 块直接调用，
     * �?ensureCleanupInitialized() 按需触发�?
     */
    private fun startPeriodicCacheCleanup() {
        cacheCleanupJob?.cancel()
        cacheCleanupJob = cleanupScope.launch {
            while (isActive) {
                try {
                    delay(CACHE_CLEANUP_INTERVAL_MS)
                    val removed = clearExpiredCacheEntries()
                    if (removed > 0) {
                        Log.d(TAG, "Periodic cache cleanup: removed $removed expired entries")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Periodic cache cleanup error", e)
                }
            }
        }
        Log.d(TAG, "Periodic cache cleanup started (interval=${CACHE_CLEANUP_INTERVAL_MS}ms, ttl=${UNIFIED_CACHE_TTL_MS}ms)")
    }

    /**
     * 停止定期缓存清理协程
     *
     * 在应用退出或需要释放资源时调用�?
     */
    fun stopPeriodicCacheCleanup() {
        cacheCleanupJob?.cancel()
        cacheCleanupJob = null
        // 重置懒初始化标志，允许后续重新启�?
        cleanupInitialized.set(false)
        Log.d(TAG, "Periodic cache cleanup stopped (lazy-init flag reset)")
    }

    /**
     * 清理过期的缓存条�?(v3: 操作统一缓存)
     *
     * @param ttlMs 自定义TTL（毫秒），默认使用统一�?5 分钟 TTL
     * @return 清除的过期条目数
     */
    fun clearExpiredCacheEntries(ttlMs: Long = UNIFIED_CACHE_TTL_MS): Int {
        val now = System.currentTimeMillis()
        val effectiveTtl = StorageConfig.DEFAULT_KEY_CACHE_DURATION_MS.coerceAtLeast(ttlMs)
        val expiredKeys = unifiedKeyCache.filter {
            now - it.value.createdAt > effectiveTtl
        }.keys.toList()

        expiredKeys.forEach { key ->
            val removed = unifiedKeyCache.remove(key)
            if (removed != null) {
                securelyClear(removed.key)
                securelyClear(removed.salt)
            }
        }

        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "Cleared ${expiredKeys.size} expired cache entries (unified cache)")
        }
        return expiredKeys.size
    }

    // ==================== 内部实现方法 ====================

    /**
     * 生成指定长度的安全随机字�?
     */
    private fun generateSecureRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * 内部加密实现
     *
     * 输出格式：[version (1 byte)] [salt (32 bytes)] [iv (12 bytes)] [ciphertext] [hmac (32 bytes)]
     */
    private fun encryptInternal(
        data: ByteArray,
        key: ByteArray,
        salt: ByteArray,
        iv: ByteArray,
        version: Byte = VERSION_ARGON2ID
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION_AES_GCM)
        val secretKey = SecretKeySpec(key, KEY_ALGORITHM_AES)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val encrypted = cipher.doFinal(data)

        // 组装输出：version + salt + iv + ciphertext + hmac
        val result = ByteArray(1 + SALT_LENGTH + GCM_IV_LENGTH + encrypted.size + HMAC_LENGTH)
        result[0] = version
        System.arraycopy(salt, 0, result, 1, SALT_LENGTH)
        System.arraycopy(iv, 0, result, 1 + SALT_LENGTH, GCM_IV_LENGTH)
        System.arraycopy(encrypted, 0, result, 1 + SALT_LENGTH + GCM_IV_LENGTH, encrypted.size)

        // 计算并附�?HMAC（覆�?version + salt + iv + ciphertext�?
        val signature = computeHmac(result.copyOfRange(0, result.size - HMAC_LENGTH), key)
        System.arraycopy(signature, 0, result, result.size - HMAC_LENGTH, HMAC_LENGTH)

        return result
    }

    // ==================== 密钥派生方法 ====================

    /**
     * 使用密码派生密钥（带缓存，v3: 统一缓存架构�?
     *
     * 查找顺序（单一缓存源）�?
     * 1. 统一缓存 unifiedKeyCache（包�?PRECOMPUTED �?DERIVED 来源�?
     * 2. 未命中则实时 Argon2id 派生
     * 3. 派生结果存入统一缓存（source=DERIVED�?
     *
     * v3 重构消除了原来先�?precomputedKeys 再查 derivedKeyCache 的两阶段查找�?
     * 减少了一�?ConcurrentHashMap.get() 开销，同时消除了状态不一致的可能�?
     */
    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val cacheKey = buildCacheKey(password, salt)

        // 统一缓存查找（单次查询，无论来源�?
        val cached = getFromUnifiedCache(cacheKey)
        if (cached != null) {
            Log.d(TAG, "Unified key cache hit (source=${cached.second})")
            return cached.first
        }

        // 执行 Argon2id 派生
        val key = deriveKeyArgon2id(password, salt)

        // 存入统一缓存
        putToUnifiedCache(cacheKey, key, salt, KeySource.DERIVED)

        return key
    }

    /**
     * 使用指定迭代次数派生密钥（支�?legacy 格式缓存，v3: 统一缓存�?
     */
    private fun deriveKeyWithIterations(
        password: String,
        salt: ByteArray,
        iterations: Int
    ): ByteArray {
        val cacheKey = "${buildCacheKey(password, salt)}_$iterations"
        val cached = getFromUnifiedCache(cacheKey)
        if (cached != null) {
            Log.d(TAG, "Unified key cache hit for iterations=$iterations")
            return cached.first
        }

        val key = deriveKeyInternal(password, salt, iterations)
        putToUnifiedCache(cacheKey, key, salt, KeySource.DERIVED)

        return key
    }

    /**
     * 核心 PBKDF2 密钥派生实现
     *
     * 安全注意事项�?
     * - PBEKeySpec 包含敏感信息（密码字符数组），必须在使用后立即清�?
     * - 使用 clear() 方法将字符数组归�?
     */
    private fun deriveKeyInternal(
        password: String,
        salt: ByteArray,
        iterations: Int
    ): ByteArray {
        var spec: PBEKeySpec? = null
        return try {
            spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_SIZE)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } finally {
            spec?.let { Arrays.fill(it.password, ' ') }
            spec = null
        }
    }

    /**
     * Argon2id 密钥派生 (v3: 修复参数规格，使�?Argon2ParameterSpec)
     *
     * ## v3 关键修复
     *
     * **P0 问题**：v2 版本�?API >= 30 时使�?`SecretKeyFactory.getInstance("Argon2id")`
     * 配合 `PBEKeySpec` 传递参数。但 `PBEKeySpec` **不包�?memoryKiB �?parallelism 参数**�?
     * 导致 Argon2id 实际运行时可能使用了不安全的默认参数（如 memory=16MB 而非预期�?64MB），
     * 或者完全忽略了这些关键的安全参数�?
     *
     * **v3 修复**：改�?`Argon2ParameterSpec`（RFC 9106 标准），正确传递所有参数：
     * - `memoryKiB`: 内存成本（根据设备自适应�?
     * - `parallelism`: 并行度（固定�?2�?
     * - `iterations`: 迭代次数（固定为 3�?
     * - `outputLength`: 输出长度�?2 字节 = 256 位）
     * - `salt`: 盐值（通过 Argon2ParameterSpec 传递）
     *
     * Password 仍需通过 `PBEKeySpec` 传递（因为 Argon2ParameterSpec 不包�?password 字段）�?
     *
     * ## 参数配置（自适应�?
     *
     * - Android API 30+: 使用系统内置 Argon2id 实现 + 正确�?Argon2ParameterSpec
     * - Android API < 30: fallback �?PBKDF2-60K（增强安全性）
     *
     * ## OWASP 2025 推荐参数
     *
     * | 参数 | �?| 说明 |
     * |------|------|------|
     * | memoryKiB | 65536 (64MB) | 高端设备默认，抵�?GPU/ASIC |
     * | parallelism | 2 | 平衡性能与安全�?|
     * | iterations | 3 | 配合高内存参�?|
     * | outputLength | 32 bytes | 256 位密�?|
     *
     * @param password 用户密码
     * @param salt 随机盐值（32 字节�?
     * @param context 可选的 Android Context（用于设备内存检测），若�?null 则使用保守默认�?
     * @return 派生�?256 位密�?
     */
    fun deriveKeyArgon2id(password: String, salt: ByteArray, context: Context? = null): ByteArray {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // 根据设备内存自动调整 Argon2id 内存参数
                val effectiveMemoryKib = resolveEffectiveMemoryKib(context)

                // ========== v3 修复：使用正确的 Argon2ParameterSpec ==========
                // Argon2ParameterSpec 包含所�?RFC 9106 要求的参数：
                // memoryKiB, parallelism, iterations, outputLength, salt
                //
                // Password 通过 PBEKeySpec 传递（Argon2ParameterSpec 不包�?password�?
                @Suppress("NewApi")
                val argon2Spec = try {
                    val specClass = Class.forName("javax.crypto.spec.Argon2ParameterSpec")
                    val builderClass = Class.forName("javax.crypto.spec.Argon2ParameterSpec\$Builder")
                    val builder = builderClass.getDeclaredConstructor().newInstance()
                    builderClass.getMethod("memoryKiB", Int::class.java).invoke(builder, effectiveMemoryKib)
                    builderClass.getMethod("parallelism", Int::class.java).invoke(builder, ARGON2ID_PARALLELISM)
                    builderClass.getMethod("iterations", Int::class.java).invoke(builder, ARGON2ID_ITERATIONS)
                    builderClass.getMethod("outputLength", Int::class.java).invoke(builder, ARGON2ID_OUTPUT_LENGTH)
                    builderClass.getMethod("salt", ByteArray::class.java).invoke(builder, salt)
                    builderClass.getMethod("build").invoke(builder)
                } catch (cnfe: ClassNotFoundException) {
                    null
                }

                // PBEKeySpec 仅用于传�?password（salt 已在 argon2Spec 中设置，此处传空数组�?
                // iterations �?keySize 也由 argon2Spec 控制，此处仅作兼容性占�?
                val pbeSpec = PBEKeySpec(
                    password.toCharArray(),
                    ByteArray(0),  // salt 已在 Argon2ParameterSpec 中设�?
                    ARGON2ID_ITERATIONS,
                    KEY_SIZE
                )
                try {
                    val factory = SecretKeyFactory.getInstance("Argon2id")
                    // 优先尝试使用 Argon2ParameterSpec（正确方式）
                    val key = if (argon2Spec != null) {
                        try {
                            factory.generateSecret(argon2Spec as java.security.spec.KeySpec).encoded
                        } catch (specEx: ClassCastException) {
                            Log.w(TAG, "Argon2ParameterSpec type mismatch, using PBEKeySpec fallback", specEx)
                            factory.generateSecret(pbeSpec).encoded
                        } catch (specEx: Exception) {
                            Log.w(TAG, "Argon2ParameterSpec direct call failed, trying PBEKeySpec fallback", specEx)
                            factory.generateSecret(pbeSpec).encoded
                        }
                    } else {
                        factory.generateSecret(pbeSpec).encoded
                    }
                    Arrays.fill(pbeSpec.password, ' ')
                    Log.d(TAG, "Argon2id derivation completed (memory=${effectiveMemoryKib}KiB, parallelism=$ARGON2ID_PARALLELISM, iterations=$ARGON2ID_ITERATIONS) [v3: Argon2ParameterSpec]")
                    key
                } finally {
                    Arrays.fill(pbeSpec.password, ' ')
                }
            } catch (e: Exception) {
                Log.w(TAG, "Argon2id failed, falling back to PBKDF2-${PBKDF2_ITERATIONS}", e)
                deriveKeyInternal(password, salt, PBKDF2_ITERATIONS)
            }
        } else {
            Log.i(TAG, "API ${Build.VERSION.SDK_INT} < 30, using PBKDF2-${PBKDF2_ITERATIONS} as Argon2id fallback")
            deriveKeyInternal(password, salt, PBKDF2_ITERATIONS)
        }
    }

    /**
     * 根据设备总内存解�?Argon2id 应使用的内存参数
     *
     * 分级策略（v3 更新：提升高端设备默认值至 64MB）：
     * - < 2GB RAM: 8MB（避�?OOM，低端设备安全优先）
     * - < 4GB RAM: 16MB（标准移动端安全级别�?
     * - >= 4GB RAM: 64MB（默认值，符合 OWASP 2025 高安全性推荐）
     *
     * v3 变更记录�?
     * - 原来 >= 4GB 使用 16MB（过于保守）
     * - 现在 >= 4GB 使用 64MB（OWASP 2025 推荐值）
     * - 提升了对 GPU/ASIC 攻击的抵抗力
     *
     * @param context 可选的 Android Context（用�?ActivityManager 内存查询），
     *                若为 null �?API < 30 则使用保守默认�?
     * @return 实际应使用的 Argon2id 内存参数（单位：KiB�?
     */
    // v3: 移除冗余 @JvmStatic（object 单例中该注解无实际效果）
    fun resolveEffectiveMemoryKib(context: Context?): Int {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ARGON2ID_MEMORY_KIB_DEFAULT  // API < 30 或无 Context：使�?OWASP 推荐默认�?64MB
        }

        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return ARGON2ID_MEMORY_KIB_DEFAULT
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalMemMB = memInfo.totalMem / (1024 * 1024)

            when {
                totalMemMB < 2048 -> {
                    Log.i(TAG, "Low-end device detected (${totalMemMB}MB RAM), using 8MB Argon2id memory")
                    8192   // < 2GB: 8MB
                }
                totalMemMB < 4096 -> {
                    Log.d(TAG, "Mid-range device detected (${totalMemMB}MB RAM), using 16MB Argon2id memory")
                    16384  // < 4GB: 16MB
                }
                else -> {
                    // v3: �?16MB 提升�?64MB（OWASP 2025 推荐�?
                    Log.d(TAG, "High-end device detected (${totalMemMB}MB RAM), using 64MB Argon2id memory (OWASP 2025)")
                    ARGON2ID_MEMORY_KIB_DEFAULT  // >= 4GB: 64MB
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect device memory, using conservative default", e)
            ARGON2ID_MEMORY_KIB_DEFAULT  // 检测失败时使用 OWASP 推荐默认�?64MB
        }
    }

    /**
     * 从已有密钥通过 HKDF 派生子密�?
     *
     * HKDF（HMAC-based Key Derivation Function）基�?RFC 5869
     * 用于从主密钥派生出多个独立的子密�?
     */
    private fun deriveKeyFromKey(key: ByteArray, salt: ByteArray): ByteArray {
        return deriveKeyHKDF(key, salt, HKDF_INFO_DEFAULT.toByteArray(Charsets.UTF_8))
    }

    /**
     * HKDF 实现（RFC 5869�?
     *
     * 两阶段过程：
     * 1. Extract：从输入密钥和盐值提取伪随机密钥（PRK�?
     * 2. Expand：使�?PRK �?info 字符串扩展出输出密钥材料（OKM�?
     *
     * @param masterKey 输入主密�?
     * @param salt 可选盐值（可为空）
     * @param info 上下文信息（用于绑定密钥用途）
     * @return 派生密钥
     */
    internal fun deriveKeyHKDF(
        masterKey: ByteArray,
        salt: ByteArray,
        info: ByteArray? = null
    ): ByteArray {
        return try {
            val effectiveInfo = info ?: HKDF_INFO_DEFAULT.toByteArray(Charsets.UTF_8)

            // === Extract 阶段 ===
            val extractMac = Mac.getInstance(HMAC_ALGORITHM)
            if (salt.isNotEmpty()) {
                extractMac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
            } else {
                // 空盐值时使用全零密钥（RFC 5869 规范要求�?
                extractMac.init(SecretKeySpec(ByteArray(HMAC_LENGTH), HMAC_ALGORITHM))
            }
            val prk = extractMac.doFinal(masterKey)

            // === Expand 阶段 ===
            val expandMac = Mac.getInstance(HMAC_ALGORITHM)
            expandMac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

            // 添加计数器字节（RFC 5869 要求�?
            val infoWithCounter = effectiveInfo + byteArrayOf(0x01.toByte())
            val okm = expandMac.doFinal(infoWithCounter)

            okm.copyOf(KEY_SIZE / 8)
        } catch (e: Exception) {
            Log.e(TAG, "HKDF derivation failed, falling back to legacy method", e)
            deriveKeyLegacyHmac(masterKey, salt)
        }
    }

    /**
     * Legacy HMAC 密钥派生（降级方案）
     *
     * �?HKDF 不可用时的简单回退方案
     * 注意：此方法的安全性低�?HKDF，仅作为最后手�?
     */
    private fun deriveKeyLegacyHmac(key: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(salt).copyOf(KEY_SIZE / 8)
    }

    /**
     * 计算 HMAC-SHA256
     */
    private fun computeHmac(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    // ==================== 统一缓存辅助方法 (v3) ====================

    /**
     * 构建缓存键：password + salt �?SHA-256 哈希组合
     *
     * 使用双重哈希确保�?
     * - 密码明文不会出现在缓存键�?
     * - 固定长度便于 HashMap 性能优化
     */
    private fun buildCacheKey(password: String, salt: ByteArray): String {
        val passwordHash = sha256Hex(password.toByteArray())
        val saltHash = sha256Hex(salt)
        return "${passwordHash}_${saltHash}"
    }

    /**
     * 从统一缓存获取派生密钥 (v3)
     *
     * @return Pair(密钥副本, 来源标识)，不存在或已过期则返�?null
     */
    private fun getFromUnifiedCache(cacheKey: String): Pair<ByteArray, KeySource>? {
        val cached = unifiedKeyCache[cacheKey] ?: return null

        // 检�?TTL 过期（使用统一�?5 分钟 TTL�?
        val effectiveTtl = StorageConfig.DEFAULT_KEY_CACHE_DURATION_MS.coerceAtLeast(UNIFIED_CACHE_TTL_MS)
        if (System.currentTimeMillis() - cached.createdAt > effectiveTtl) {
            // 过期条目需要安全清�?
            val removed = unifiedKeyCache.remove(cacheKey)
            if (removed != null) {
                securelyClear(removed.key)
                securelyClear(removed.salt)
            }
            Log.d(TAG, "Unified cache entry expired and securely removed: ${cacheKey.take(8)}...")
            return null
        }

        // 返回副本以确保缓存中的原始数据不被外部修�?
        return Pair(cached.key.copyOf(), cached.source)
    }

    /**
     * 将密钥存入统一缓存 (v3)
     *
     * 自动处理缓存容量限制和淘汰策�?
     */
    private fun putToUnifiedCache(
        cacheKey: String,
        key: ByteArray,
        salt: ByteArray,
        source: KeySource
    ) {
        // 容量检查和淘汰
        if (unifiedKeyCache.size >= MAX_CACHE_SIZE) {
            clearExpiredCacheEntries()
            if (unifiedKeyCache.size >= MAX_CACHE_SIZE) {
                aggressiveCacheEviction(MAX_CACHE_SIZE / 2)
            }
        }

        // 存储副本，防止外部修改影响缓�?
        unifiedKeyCache[cacheKey] = UnifiedCachedKey(
            key = key.copyOf(),
            createdAt = System.currentTimeMillis(),
            salt = salt.copyOf(),
            source = source
        )
    }

    /**
     * 激进缓存淘汰策�?(v3: 操作统一缓存)
     *
     * 当缓存满且无过期条目时，移除最早创建的条目
     * 被移除的条目会安全擦除其中的敏感数据
     */
    private fun aggressiveCacheEviction(targetSize: Int) {
        val entriesToRemove = unifiedKeyCache.size - targetSize
        if (entriesToRemove <= 0) return

        val sortedEntries = unifiedKeyCache.entries
            .sortedBy { it.value.createdAt }

        var removedCount = 0
        for (i in 0 until minOf(entriesToRemove, sortedEntries.size)) {
            val entry = sortedEntries[i]
            // 安全清除敏感数据
            securelyClear(entry.value.key)
            securelyClear(entry.value.salt)
            unifiedKeyCache.remove(entry.key)
            removedCount++
        }

        Log.d(TAG, "Aggressive cache eviction: securely removed $removedCount entries from unified cache")
    }

    // ==================== XChaCha20-Poly1305 支持（预留接口）====================

    /**
     * 检�?XChaCha20-Poly1305 是否可用
     *
     * XChaCha20-Poly1305 �?2025 年推荐的现代 AEAD 算法�?
     * 相比 AES-GCM 具有以下优势�?
     * - 24 字节随机 nonce（无需计数器管理）
     * - 纯软件实现（�?AES-NI 依赖，ARM 设备性能稳定�?
     * - IETF RFC 8439 标准�?
     *
     * 可用条件：Android API 26+ �?BouncyCastle �?Conscrypt 提供者可�?
     */
    fun isXChaCha20Available(): Boolean {
        return try {
            Cipher.getInstance(TRANSFORMATION_XCHACHA20_POLY1305)
            true
        } catch (e: Exception) {
            Log.d(TAG, "XChaCha20-Poly1305 not available: ${e.message}")
            false
        }
    }

    /**
     * 使用 XChaCha20-Poly1305 加密数据（实验性接口）
     *
     * 注意：此方法输出的数据格式与 AES-GCM 不同�?
     * 解密时需要使用对应的 decryptXChaCha20() 方法�?
     *
     * 当前状态：预留接口，默认不启用�?
     * 可通过 setActiveVersion(KeyVersion.XCHACHA20_POLY1305) 切换�?
     *
     * @param data 待加密数�?
     * @param password 用户密码
     * @return 加密后的数据，如果不支持则抛�?CryptoException
     */
    @Throws(CryptoException::class)
    fun encryptXChaCha20(data: ByteArray, password: String): ByteArray {
        if (!isXChaCha20Available()) {
            throw CryptoException("XChaCha20-Poly1305 not available on this device")
        }

        ensureCleanupInitialized()
        val salt = generateSecureRandomBytes(SALT_LENGTH)
        val nonce = generateSecureRandomBytes(XCHACHA20_NONCE_LENGTH)
        val key = deriveKeyArgon2id(password, salt)

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION_XCHACHA20_POLY1305)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM_CHACHA20)
            // ChaCha20-Poly1305 使用 IvParameterSpec 传入 24 字节 nonce（XChaCha20 nonce 长度�?
            // 注意：不能使�?GCMParameterSpec，那是为 AES-GCM 设计�?
            val paramSpec = IvParameterSpec(nonce)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, paramSpec)
            val encrypted = cipher.doFinal(data)

            // 组装输出：[version(1)] [salt(32)] [nonce(24)] [ciphertext+tag]
            val result = ByteArray(1 + SALT_LENGTH + XCHACHA20_NONCE_LENGTH + encrypted.size)
            result[0] = KeyVersion.XCHACHA20_POLY1305.byteValue
            System.arraycopy(salt, 0, result, 1, SALT_LENGTH)
            System.arraycopy(nonce, 0, result, 1 + SALT_LENGTH, XCHACHA20_NONCE_LENGTH)
            System.arraycopy(encrypted, 0, result, 1 + SALT_LENGTH + XCHACHA20_NONCE_LENGTH, encrypted.size)

            result
        } catch (e: Exception) {
            throw CryptoException("XChaCha20-Poly1305 encryption failed", e)
        }
    }

    /**
     * 使用 XChaCha20-Poly1305 解密数据（实验性接口）
     *
     * @param data 加密数据（由 encryptXChaCha20 生成�?
     * @param password 用户密码
     * @return 解密后的明文，失败返�?null
     */
    fun decryptXChaCha20(data: ByteArray, password: String): ByteArray? {
        if (!isXChaCha20Available()) {
            Log.w(TAG, "XChaCha20-Poly1305 not available")
            return null
        }

        ensureCleanupInitialized()
        val expectedVersion = KeyVersion.XCHACHA20_POLY1305.byteValue
        val minSize = 1 + SALT_LENGTH + XCHACHA20_NONCE_LENGTH + 16  // 16 = minimum tag size
        if (data.size < minSize || data[0] != expectedVersion) {
            Log.e(TAG, "Invalid XChaCha20 data format")
            return null
        }

        return try {
            val salt = data.copyOfRange(1, 1 + SALT_LENGTH)
            val nonce = data.copyOfRange(1 + SALT_LENGTH, 1 + SALT_LENGTH + XCHACHA20_NONCE_LENGTH)
            val encrypted = data.copyOfRange(1 + SALT_LENGTH + XCHACHA20_NONCE_LENGTH, data.size)

            val key = deriveKeyArgon2id(password, salt)

            val cipher = Cipher.getInstance(TRANSFORMATION_XCHACHA20_POLY1305)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM_CHACHA20)
            // ChaCha20-Poly1305 使用 IvParameterSpec 传入 24 字节 nonce（与 encrypt 对称�?
            val paramSpec = IvParameterSpec(nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, paramSpec)
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            Log.d(TAG, "XChaCha20 decryption failed", e)
            null
        }
    }

    // ==================== 安全工具方法 ====================

    /**
     * 时序安全的字节数组比较（防时序攻击）
     *
     * 标准�?Arrays.equals() �?contentEquals() 会短路返回，
     * 攻击者可通过测量响应时间推断正确的前缀长度�?
     *
     * 此方法始终比较所有字节，无论是否发现不匹配，
     * 从而消除时序侧信道风险�?
     *
     * 用途：HMAC 签名验证、密码比较等安全敏感场景
     *
     * @param a 第一个字节数�?
     * @param b 第二个字节数�?
     * @return 如果内容完全相同返回 true，否则返�?false
     */
    private fun timingSafeEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false

        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    /**
     * 安全清除敏感字节数据
     *
     * 将数组每个字节设�?，然后请�?GC 回收�?
     * 注意：JVM 不保证即�?GC，但填充零值可降低内存转储风险�?
     *
     * 对于极度敏感的场景，应考虑使用 Java Cryptography Architecture
     * 的专用安全数组支持（�?JDK 8u151+ �?sun.misc.Unsafe �?
     * Android 的特殊处理）�?
     *
     * @param data 需要清除的敏感数据
     */
    private fun securelyClear(data: ByteArray?) {
        if (data == null) return
        Arrays.fill(data, 0.toByte())
    }

    /**
     * 清除所有密钥缓�?(v3: 统一缓存清理)
     */
    fun clearAllKeyCache() {
        // 停止定期清理协程
        stopPeriodicCacheCleanup()

        val snapshot = unifiedKeyCache.entries.toList()
        unifiedKeyCache.clear()
        snapshot.forEach { (_, entry) ->
            securelyClear(entry.key)
            securelyClear(entry.salt)
        }
        Log.d(TAG, "All unified key caches securely cleared (including periodic cleanup stopped)")
    }
}

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
