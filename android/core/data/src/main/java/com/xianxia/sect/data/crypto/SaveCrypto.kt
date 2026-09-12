package com.xianxia.sect.data.crypto

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
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
    internal const val TAG = "SaveCrypto"

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



    /** 当前支持的版本列�?*/

    // ==================== 加密算法常量 ====================

    /** AES-GCM 加密变换：认证加密模式，提供机密性和完整�?*/
    internal const val TRANSFORMATION_AES_GCM = "AES/GCM/NoPadding"

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

    /** 对称密钥算法：AES-256 */
    internal const val KEY_ALGORITHM_AES = "AES"

    /** XChaCha20 密钥算法标识 */

    /** 密钥长度�?56位（AES-256 / ChaCha20 均为 256 位） */
    internal const val KEY_SIZE = 256

    /** GCM 认证标签长度�?28位（最大强度） */
    internal const val GCM_TAG_LENGTH = 128

    /** GCM 初始化向量长度：12字节�?6位，GCM推荐值） */
    internal const val GCM_IV_LENGTH = 12

    /**
     * XChaCha20-Poly1305 nonce 长度�?4字节�?92位）
     *
     * XChaCha20 使用扩展 nonce（XNonce），�?16 字节密钥派生的子密钥 + 8 字节随机 nonce 组成�?
     * 随机生成�?24 字节 nonce 空间极大�?^192），无需担心重用问题�?
     */

    /** HMAC 算法：SHA-256 */
    internal const val HMAC_ALGORITHM = "HmacSHA256"

    /** HMAC 输出长度�?2字节（SHA-256输出长度�?*/
    internal const val HMAC_LENGTH = 32

    /** 盐值长度：32字节（足够防止彩虹表攻击�?*/
    internal const val SALT_LENGTH = 32

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
    internal const val PBKDF2_ITERATIONS = 310_000

    /**
     * 历史版本 PBKDF2 迭代次数�?0,000（OWASP 2021 推荐值，已过时）
     *
     * 用途：仅作为版本化记录�?
     * 若迭代次数再次变更，需根据此常量实现兼容解密逻辑�?
     *
     * 版本化策略详见文件头�?"迭代次数版本化策�? 章节�?
     */

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
    internal const val ARGON2ID_MEMORY_KIB_DEFAULT = 65536  // 64MB - OWASP 2025 recommended

    /** Argon2id 并行度：2（平衡性能与安全性） */
    internal const val ARGON2ID_PARALLELISM = 2

    /** Argon2id 迭代次数�?（配合高内存参数，符�?RFC 9106 推荐�?*/
    internal const val ARGON2ID_ITERATIONS = 3

    /** Argon2id 输出长度�?2 字节�?56 位密钥） */
    internal const val ARGON2ID_OUTPUT_LENGTH = 32

    // ==================== 版本标识常量 ====================

    /** 版本头：Argon2id (当前生产版本) */
    internal val VERSION_ARGON2ID: Byte = KeyVersion.ARGON2ID_AES_GCM.byteValue

    /** HKDF 信息标签：用于密钥派生上下文绑定 */
    internal const val HKDF_INFO_DEFAULT = "xianxia.save.key.v3"

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

    // ==================== 协程管理（懒初始化）====================

    /** CoroutineScopeProvider 引用（通过 initialize() 注入） */


    /** 定期清理协程作用域（用于缓存过期条目清理） */

    /** 定期清理 Job 引用（可取消�?*/

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
        SaveCryptoKeyCache.ensureCleanupInitialized()
        val salt = generateSecureRandomBytes(SALT_LENGTH)
        val iv = generateSecureRandomBytes(GCM_IV_LENGTH)
        val key = SaveCryptoKeyDerivation.deriveKeyArgon2id(password, salt)
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
        SaveCryptoKeyCache.ensureCleanupInitialized()
        val salt = generateSecureRandomBytes(SALT_LENGTH)
        val iv = generateSecureRandomBytes(GCM_IV_LENGTH)
        val derivedKey = SaveCryptoKeyDerivation.deriveKeyFromKey(key, salt)
        return encryptInternal(data, derivedKey, salt, iv, VERSION_ARGON2ID)
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
        SaveCryptoKeyCache.ensureCleanupInitialized()
        if (data.size < 1 + SALT_LENGTH + GCM_IV_LENGTH + HMAC_LENGTH + 1) {
            Log.e(TAG, "Data too short for decryption")
            return null
        }

        // 仅接�?Argon2id 格式
        val version = data[0]
        if (version != VERSION_ARGON2ID) {
            Log.e(TAG,
                "Unsupported version header: 0x${version.toString(16)}, expected 0x${VERSION_ARGON2ID.toString(16)}")
            return null
        }

        Log.d(TAG, "Detected Argon2id format (version=0x02)")
        return decryptWithVersion(data, password, SALT_LENGTH) { pwd, salt ->
            SaveCryptoKeyDerivation.deriveKeyArgon2id(pwd, salt)
        }
    }

    /**
     * 使用版本化格式解密（带版本头的统一格式�?
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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
            val computedSignature = SaveCryptoKeyDerivation.computeHmac(
                data.copyOfRange(0, data.size - SaveCrypto.HMAC_LENGTH), key)
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
        SaveCryptoKeyCache.ensureCleanupInitialized()
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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

            val derivedKey = SaveCryptoKeyDerivation.deriveKeyFromKey(key, salt)

            // 时序安全的签名验�?
            val computedSignature = SaveCryptoKeyDerivation.computeHmac(
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
     * 清除所有预计算密钥 (v3: 操作统一缓存)
     *
     * 仅清�?source=PRECOMPUTED 的条目，保留 DERIVED 条目�?
     */

    // ==================== 工具方法（委托到 CryptoHashUtils）====================








    // ==================== 缓存管理方法（v3: 统一缓存接口�?===================






    // ==================== 内部实现方法 ====================

    /**
     * 生成指定长度的安全随机字�?
     */
    internal fun generateSecureRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * 内部加密实现
     *
     * 输出格式：[version (1 byte)] [salt (32 bytes)] [iv (12 bytes)] [ciphertext] [hmac (32 bytes)]
     */


    // ==================== 密钥派生方法 ====================











    // ==================== 统一缓存辅助方法 (v3) ====================




    // ==================== XChaCha20-Poly1305 支持（预留接口）====================




    // ==================== 安全工具方法（委托到 SecurityPrimitives.kt 顶层函数）====================

}

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
/**
 * 加密封装原语:输出 [version(1)] [salt(32)] [iv(12)] [ciphertext] [hmac(32)] 信封。
 * (SaveCrypto 的对象体内存编排剥离至此的纯函数形态)
 */
internal fun encryptInternal(
    data: ByteArray,
    key: ByteArray,
    salt: ByteArray,
    iv: ByteArray,
    version: Byte = SaveCrypto.VERSION_ARGON2ID
): ByteArray {
    val cipher = Cipher.getInstance(SaveCrypto.TRANSFORMATION_AES_GCM)
    val secretKey = SecretKeySpec(key, SaveCrypto.KEY_ALGORITHM_AES)
    val gcmSpec = GCMParameterSpec(SaveCrypto.GCM_TAG_LENGTH, iv)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

    val encrypted = cipher.doFinal(data)

    // 组装输出：version + salt + iv + ciphertext + hmac
    val result = ByteArray(
        1 + SaveCrypto.SALT_LENGTH + SaveCrypto.GCM_IV_LENGTH + encrypted.size + SaveCrypto.HMAC_LENGTH
    )
    result[0] = version
    System.arraycopy(salt, 0, result, 1, SaveCrypto.SALT_LENGTH)
    System.arraycopy(iv, 0, result, 1 + SaveCrypto.SALT_LENGTH, SaveCrypto.GCM_IV_LENGTH)
    System.arraycopy(encrypted, 0, result, 1 + SaveCrypto.SALT_LENGTH + SaveCrypto.GCM_IV_LENGTH, encrypted.size)

    // 计算并附加 HMAC（覆盖 version + salt + iv + ciphertext）
    val signature = SaveCryptoKeyDerivation.computeHmac(
        result.copyOfRange(0, result.size - SaveCrypto.HMAC_LENGTH), key)
    System.arraycopy(signature, 0, result, result.size - SaveCrypto.HMAC_LENGTH, SaveCrypto.HMAC_LENGTH)

    return result
}
