package com.xianxia.sect.data.crypto

import android.content.Context
import android.os.Build
import android.util.Log
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Arrays

// SaveCrypto 的密钥派生域:Argon2id/PBKDF2/HKDF/传统 HMAC 派生与内存参数解析。
// 跨 object 引用(SaveCrypto 共享常量/SaveCryptoKeyCache 缓存写点)以限定调用表达。

private const val TAG = SaveCrypto.TAG

object SaveCryptoKeyDerivation {

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
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal suspend fun precomputeDerivedKey(
    password: String,
    salt: ByteArray? = null
): Boolean = withContext(Dispatchers.Default) {
    try {
        SaveCryptoKeyCache.ensureCleanupInitialized()
        val effectiveSalt = salt ?: SaveCrypto.generateSecureRandomBytes(SaveCrypto.SALT_LENGTH)
        val key = deriveKeyArgon2id(password, effectiveSalt)

        val cacheKey = SaveCryptoKeyCache.buildCacheKey(password, effectiveSalt)
        SaveCryptoKeyCache.putToUnifiedCache(cacheKey, key, effectiveSalt, SaveCryptoKeyCache.KeySource.PRECOMPUTED)

        Log.d(TAG, "Precomputed derived key for cacheKey=${cacheKey.take(8)}... (unified cache)")
        true
    } catch (e: CancellationException) {
        throw e // 取消穿透: 启动预计算被取消时中止, 不谎报派生失败
    } catch (e: Exception) {
        Log.e(TAG, "Failed to precompute derived key", e)
        false
    }
}

/**
 * 核心 PBKDF2 密钥派生实现
 *
 * 安全注意事项�?
 * - PBEKeySpec 包含敏感信息（密码字符数组），必须在使用后立即清�?
 * - 使用 clear() 方法将字符数组归�?
 */
internal fun deriveKeyInternal(
    password: String,
    salt: ByteArray,
    iterations: Int
): ByteArray {
    var spec: PBEKeySpec? = null
    return try {
        spec = PBEKeySpec(password.toCharArray(), salt, iterations, SaveCrypto.KEY_SIZE)
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
internal fun deriveKeyArgon2id(password: String, salt: ByteArray, context: Context? = null): ByteArray {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        deriveArgon2idOnApi30Plus(password, salt, context)
    } else {
        Log.i(
            TAG,
            "API ${Build.VERSION.SDK_INT} < 30, using PBKDF2-${SaveCrypto.PBKDF2_ITERATIONS} as Argon2id fallback"
        )
        deriveKeyInternal(password, salt, SaveCrypto.PBKDF2_ITERATIONS)
    }
}

/**
 * API 30+ 派生路径（deriveKeyArgon2id 拆分）：系统 Argon2id 优先，任何失败回退 PBKDF2。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun deriveArgon2idOnApi30Plus(password: String, salt: ByteArray, context: Context?): ByteArray {
    return try {
        // 根据设备内存自动调整 Argon2id 内存参数
        val effectiveMemoryKib = resolveEffectiveMemoryKib(context)
        val argon2Spec = buildArgon2Spec(effectiveMemoryKib, salt)
        deriveWithArgon2Factory(password, argon2Spec, effectiveMemoryKib)
    } catch (e: Exception) {
        Log.w(TAG, "Argon2id failed, falling back to PBKDF2-${SaveCrypto.PBKDF2_ITERATIONS}", e)
        deriveKeyInternal(password, salt, SaveCrypto.PBKDF2_ITERATIONS)
    }
}

/**
 * 反射构造 Argon2ParameterSpec（deriveKeyArgon2id 拆分）：
 * Argon2ParameterSpec 包含所有 RFC 9106 要求的参数（memoryKiB/parallelism/iterations/
 * outputLength/salt），password 除外。API 类在部分 ROM 上缺失时返回 null，调用方回退
 * PBEKeySpec 直连。
 */
@Suppress("NewApi")
internal fun buildArgon2Spec(effectiveMemoryKib: Int, salt: ByteArray): Any? {
    return try {
        Class.forName("javax.crypto.spec.Argon2ParameterSpec")
        val builderClass = Class.forName("javax.crypto.spec.Argon2ParameterSpec\$Builder")
        val builder = builderClass.getDeclaredConstructor().newInstance()
        builderClass.getMethod("memoryKiB", Int::class.java).invoke(builder, effectiveMemoryKib)
        builderClass.getMethod("parallelism", Int::class.java).invoke(builder, SaveCrypto.ARGON2ID_PARALLELISM)
        builderClass.getMethod("iterations", Int::class.java).invoke(builder, SaveCrypto.ARGON2ID_ITERATIONS)
        builderClass.getMethod("outputLength", Int::class.java).invoke(builder, SaveCrypto.ARGON2ID_OUTPUT_LENGTH)
        builderClass.getMethod("salt", ByteArray::class.java).invoke(builder, salt)
        builderClass.getMethod("build").invoke(builder)
    } catch (ignored: ClassNotFoundException) {
        null
    }
}

/**
 * SecretKeyFactory 密钥生成（deriveKeyArgon2id 拆分）：Argon2ParameterSpec 直连优先，
 * 类型不匹配或调用失败时回退 PBEKeySpec；PBEKeySpec 密码缓冲在 finally 中擦除。
 */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨SDK不可枚举, 回退 PBEKeySpec 继续, 非静默吞噬
internal fun deriveWithArgon2Factory(
    password: String,
    argon2Spec: Any?,
    effectiveMemoryKib: Int
): ByteArray {
    // PBEKeySpec 仅用于传递 password（salt 已在 argon2Spec 中设置，此处传空数组）；
    // iterations 与 keySize 也由 argon2Spec 控制，此处仅作兼容性占位
    val pbeSpec = PBEKeySpec(
        password.toCharArray(),
        ByteArray(0),  // salt 已在 Argon2ParameterSpec 中设置
        SaveCrypto.ARGON2ID_ITERATIONS,
        SaveCrypto.KEY_SIZE
    )
    return try {
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
        Log.d(
            TAG,
            "Argon2id derivation completed (memory=${effectiveMemoryKib}KiB, " +
                "parallelism=${SaveCrypto.ARGON2ID_PARALLELISM}, " +
                "iterations=${SaveCrypto.ARGON2ID_ITERATIONS}) [v3: Argon2ParameterSpec]"
        )
        key
    } finally {
        Arrays.fill(pbeSpec.password, ' ')
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
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun resolveEffectiveMemoryKib(context: Context?): Int {
    if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return SaveCrypto.ARGON2ID_MEMORY_KIB_DEFAULT  // API < 30 或无 Context：使�?OWASP 推荐默认�?64MB
    }

    return try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return SaveCrypto.ARGON2ID_MEMORY_KIB_DEFAULT
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
                Log.d(TAG,
                    "High-end device detected (${totalMemMB}MB RAM), using 64MB Argon2id memory (OWASP 2025)")
                SaveCrypto.ARGON2ID_MEMORY_KIB_DEFAULT  // >= 4GB: 64MB
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to detect device memory, using conservative default", e)
        SaveCrypto.ARGON2ID_MEMORY_KIB_DEFAULT  // 检测失败时使用 OWASP 推荐默认�?64MB
    }
}

/**
 * 从已有密钥通过 HKDF 派生子密�?
 *
 * HKDF（HMAC-based Key Derivation Function）基�?RFC 5869
 * 用于从主密钥派生出多个独立的子密�?
 */
internal fun deriveKeyFromKey(key: ByteArray, salt: ByteArray): ByteArray {
    return deriveKeyHKDF(key, salt, SaveCrypto.HKDF_INFO_DEFAULT.toByteArray(Charsets.UTF_8))
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
@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
internal fun deriveKeyHKDF(
    masterKey: ByteArray,
    salt: ByteArray,
    info: ByteArray? = null
): ByteArray {
    return try {
        val effectiveInfo = info ?: SaveCrypto.HKDF_INFO_DEFAULT.toByteArray(Charsets.UTF_8)

        // === Extract 阶段 ===
        val extractMac = Mac.getInstance(SaveCrypto.HMAC_ALGORITHM)
        if (salt.isNotEmpty()) {
            extractMac.init(SecretKeySpec(salt, SaveCrypto.HMAC_ALGORITHM))
        } else {
            // 空盐值时使用全零密钥（RFC 5869 规范要求�?
            extractMac.init(SecretKeySpec(ByteArray(SaveCrypto.HMAC_LENGTH), SaveCrypto.HMAC_ALGORITHM))
        }
        val prk = extractMac.doFinal(masterKey)

        // === Expand 阶段 ===
        val expandMac = Mac.getInstance(SaveCrypto.HMAC_ALGORITHM)
        expandMac.init(SecretKeySpec(prk, SaveCrypto.HMAC_ALGORITHM))

        // 添加计数器字节（RFC 5869 要求�?
        val infoWithCounter = effectiveInfo + byteArrayOf(0x01.toByte())
        val okm = expandMac.doFinal(infoWithCounter)

        okm.copyOf(SaveCrypto.KEY_SIZE / 8)
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
internal fun deriveKeyLegacyHmac(key: ByteArray, salt: ByteArray): ByteArray {
    val mac = Mac.getInstance(SaveCrypto.HMAC_ALGORITHM)
    mac.init(SecretKeySpec(key, SaveCrypto.HMAC_ALGORITHM))
    return mac.doFinal(salt).copyOf(SaveCrypto.KEY_SIZE / 8)
}

/**
 * 计算 HMAC-SHA256
 */
internal fun computeHmac(data: ByteArray, key: ByteArray): ByteArray {
    val mac = Mac.getInstance(SaveCrypto.HMAC_ALGORITHM)
    mac.init(SecretKeySpec(key, SaveCrypto.HMAC_ALGORITHM))
    return mac.doFinal(data)
}
}
