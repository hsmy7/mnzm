package com.xianxia.sect.data.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

    // SecureKeyManager 的设备绑定与密钥加密域:设备指纹/硬件密钥库/账号锚定因子 +
    // AES-GCM 密钥加密原语。跨 object 引用(SecureKeyManager 共享常量)以限定调用表达。

    private const val TAG = SecureKeyManager.TAG

object DeviceBindingIdentity {

    internal const val GCM_IV_LENGTH = 12
    internal const val GCM_TAG_LENGTH = 128

    private const val KEYSTORE_ALIAS = "xianxia_device_secret"
    private const val SECRET_PREFS_NAME = "device_secret_prefs"
    private const val SECRET_KEY = "hw_secret_hash"
    private const val FALLBACK_ID_KEY = "fallback_account_id"

    @Volatile
    var accountBindingProvider: com.xianxia.sect.core.util.AccountBindingProvider? = null

    fun getDeviceSecret(context: Context): ByteArray {
    val hwSecret = getHardwareBackedSecret(context)
    val hybridBinding = getHybridBindingFactor(context)
    val combined = "${hwSecret}:${hybridBinding}:${context.packageName}"
    return MessageDigest.getInstance("SHA-256").digest(combined.toByteArray(Charsets.UTF_8))
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun getAccountBindingFactor(context: Context): String {
    return try {
        val provider = accountBindingProvider
        if (provider != null && provider.isLoggedIn()) {
            val userId = provider.getAccountUserId()
            if (!userId.isNullOrEmpty()) {
                Log.d(TAG, "Using account binding factor")
                return userId
            }
        }
        
        val prefs = context.getSharedPreferences(SecureKeyManager.PREFS_NAME, Context.MODE_PRIVATE)
        val fallbackId = prefs.getString(FALLBACK_ID_KEY, null)
        if (!fallbackId.isNullOrEmpty()) {
            Log.d(TAG, "Using fallback account binding factor")
            return fallbackId
        }
        
        Log.d(TAG, "No account factor available, using no_account")
        "no_account"
    } catch (e: Exception) {
        Log.w(TAG, "Failed to get account binding factor, using no_account", e)
        "no_account"
    }
    }

    fun getHybridBindingFactor(context: Context): String {
    val deviceFingerprint = getDeviceFingerprint(context)
    val accountFactor = getAccountBindingFactor(context)
    
    val combined = "${deviceFingerprint}:${accountFactor}:${context.packageName}"
    return MessageDigest.getInstance("SHA-256")
        .digest(combined.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun getHardwareBackedSecret(context: Context): String {
    return try {
        generateKeyStoreSecret(context)
    } catch (e: Exception) {
        Log.w(TAG, "Hardware secret generation failed, fallback to secure random", e)
        generateSecureRandomSecret(context)
    }
    }

    @Suppress("NewApi")
    fun generateKeyStoreSecret(context: Context): String {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val builder = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)

        try {
            // Migration: Android Keystore deprecated API replacement pending
            // 当前使用的 setUserAuthenticationValidityDurationSeconds() 已在 Android R (API 30) 废弃
            //
            // 替代方案（按优先级）：
            // 1. [推荐] 使用 BiometricPrompt API 进行用户认证
            //    - 优势：支持生物识别（指纹/面部），用户体验更好
            //    - 实现：使用 androidx.biometric:biometric 库
            //    - 参考文档：https://developer.android.com/training/sign-in/biometric-auth
            //
            // 2. 使用 setUserAuthenticationParameters() (Android S+) [已在此实现]
            //    - 优势：新的官方替代API
            //    - 限制：仅支持 Android 12 (API 31) 及以上
            //
            // 3. 移除用户认证要求（如果业务允许）
            //    - 优势：简单，无需用户交互
            //    -劣势：安全性降低，密钥可被无认证访问
            //
            // 当前措施：
            // - API >= 31: 使用新的 setUserAuthenticationParameters() API
            // - API < 31: 保持废弃 API 以兼容 Android 6-10 设备，通过 @Suppress 抑制警告

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31): 使用新的认证参数 API
                builder.setUserAuthenticationParameters(300, KeyProperties.AUTH_BIOMETRIC_STRONG)
                Log.d(TAG, "Using setUserAuthenticationParameters() for API ${Build.VERSION.SDK_INT}")
            } else {
                // Android 6-11 (API 23-30): 使用废弃 API（保持向后兼容）
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(300)
                Log.d(TAG, "Using deprecated setUserAuthenticationValidityDurationSeconds() for " +
                    "API ${Build.VERSION.SDK_INT}")
            }
        } catch (e: java.security.InvalidAlgorithmParameterException) {
            Log.w(TAG, "Device does not support user authentication, generating key without auth requirement", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                builder.setIsStrongBoxBacked(false)
            } catch (e: java.security.InvalidAlgorithmParameterException) {
                Log.w(TAG, "StrongBox not available on this device", e)
            }
        }

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }
    
    val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
    var secretHash = prefs.getString(SECRET_KEY, null)
    
    if (secretHash == null) {
        val rawSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        secretHash = MessageDigest.getInstance("SHA-256")
            .digest(rawSecret)
            .joinToString("") { "%02x".format(it) }
        rawSecret.fill(0)
        prefs.edit().putString(SECRET_KEY, secretHash).apply()
    }
    
    return checkNotNull(secretHash) { "Failed to store hardware secret" }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun generateSecureRandomSecret(context: Context): String {
    val prefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
    var secretHash = prefs.getString(SECRET_KEY, null)
    
    if (secretHash == null) {
        val entropySources = mutableListOf<ByteArray>()
        entropySources.add(ByteArray(32).also { SecureRandom().nextBytes(it) })
        try {
            entropySources.add(System.currentTimeMillis().toString().toByteArray())
            entropySources.add(java.util.UUID.randomUUID().toString().toByteArray())
            val runtime = Runtime.getRuntime()
            entropySources.add(runtime.freeMemory().toString().toByteArray())
            entropySources.add(runtime.totalMemory().toString().toByteArray())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Entropy source collection incomplete (non-critical)", e)
        }
        
        val combined = entropySources.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val hash = MessageDigest.getInstance("SHA-512").digest(combined)
        secretHash = hash.joinToString("") { "%02x".format(it) }
        entropySources.forEach { it.fill(0) }
        combined.fill(0)
        prefs.edit().putString(SECRET_KEY, secretHash).apply()
    }
    
    return checkNotNull(secretHash) { "Failed to generate secure random secret" }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    @SuppressLint("HardwareIds")
    fun getDeviceFingerprint(context: Context): String {
    val parts = mutableListOf<String>()

    try {
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        parts.add(deviceId)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Failed to read ANDROID_ID for fingerprint", e)
    }

    try {
        parts.add(Build.BRAND)
        parts.add(Build.MODEL)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Failed to read Build info for fingerprint", e)
    }

    try {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") android.content.pm.PackageManager.GET_SIGNATURES
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sigInfo = packageInfo.signingInfo
            val apkContentsSigners = sigInfo?.apkContentsSigners
            if (apkContentsSigners != null && apkContentsSigners.isNotEmpty()) {
                val certDigest = MessageDigest.getInstance("SHA-256")
                    .digest(apkContentsSigners[0].toByteArray())
                parts.add(certDigest.joinToString("") { "%02x".format(it) })
            }
        } else {
            @Suppress("DEPRECATION")
            val signatures = packageInfo.signatures
            if (signatures != null && signatures.isNotEmpty()) {
                val certDigest = MessageDigest.getInstance("SHA-256")
                    .digest(signatures[0].toByteArray())
                parts.add(certDigest.joinToString("") { "%02x".format(it) })
            }
        }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Failed to extract signing certificate for fingerprint", e)
    }

    return if (parts.isNotEmpty()) {
        MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } else {
        "default_fingerprint"
    }
    }

    fun encryptKey(key: ByteArray, secret: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val secretKeySpec = SecretKeySpec(secret, "AES")
    val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
    
    cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, GCMParameterSpec(GCM_TAG_LENGTH, iv))
    val encrypted = cipher.doFinal(key)
    
    // 版本化加密格式: [版本号(1字节)] [IV(12字节)] [密文+GCM标签]
    val result = ByteArray(1 + GCM_IV_LENGTH + encrypted.size)
    result[0] = SecureKeyManager.KEY_VERSION_CURRENT.toByte()
    System.arraycopy(iv, 0, result, 1, GCM_IV_LENGTH)
    System.arraycopy(encrypted, 0, result, 1 + GCM_IV_LENGTH, encrypted.size)
    
    return result
    }

    fun decryptKey(encryptedKey: ByteArray, secret: ByteArray): ByteArray {
    // 自动检测版本头：新格式首字节为版本号(1-3)，旧格式无版本头
    val headerOffset = if (encryptedKey.size > (GCM_IV_LENGTH + 16 + 1) && 
                           encryptedKey[0].toInt() in 1..3) 1 else 0
    
    val minLen = headerOffset + GCM_IV_LENGTH + 16
    require(encryptedKey.size >= minLen) { "Invalid encrypted key length: ${encryptedKey.size}, min: $minLen" }
    
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val secretKeySpec = SecretKeySpec(secret, "AES")
    val iv = encryptedKey.copyOfRange(headerOffset, headerOffset + GCM_IV_LENGTH)
    val encrypted = encryptedKey.copyOfRange(headerOffset + GCM_IV_LENGTH, encryptedKey.size)
    
    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, GCMParameterSpec(GCM_TAG_LENGTH, iv))
    return cipher.doFinal(encrypted)
    }
}
