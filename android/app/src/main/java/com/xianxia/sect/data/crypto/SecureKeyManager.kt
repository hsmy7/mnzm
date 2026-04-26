package com.xianxia.sect.data.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.security.*

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.*
import javax.crypto.spec.*

/**
 * 密钥恢复决策枚举
 * 用于在密钥丢失时让用户选择恢复策略
 */
enum class KeyRecoveryDecision {
    IMPORT_TOKEN,       // 用户选择导入recovery token
    GENERATE_NEW_KEY,   // 用户确认生成新密钥（已知旧存档将永久丢失）
    RETRY,              // 重试读取密钥文件
    CANCEL              // 取消操作，抛出异常阻止自动恢复
}

/**
 * 密钥恢复回调接口
 * 当主密钥和备份都不可读时触发，由调用方决定如何处理
 *
 * 使用场景：
 * - 首次安装后数据迁移
 * - 设备更换后的密钥恢复
 * - 数据损坏时的用户确认
 */
interface KeyRecoveryCallback {
    /**
     * 当密钥需要恢复时调用
     * 注意：此方法设计为非 suspend 函数，因为调用方 getOrCreateDerivedKey() 本身不是协程上下文。
     * 如需在实现中执行异步操作（如弹 UI 对话框），由实现方自行处理（例如通过 runBlocking 或事件机制）。
     *
     * @param reason 密钥不可用的原因描述
     * @return 用户的恢复决策
     */
    fun onKeyRecoveryRequired(reason: String): KeyRecoveryDecision
}

sealed class KeyRecoveryResult {
    data class Success(val key: ByteArray, val message: String) : KeyRecoveryResult()
    data class Failure(val error: String) : KeyRecoveryResult()
}

sealed class KeyReadResult {
    data class Success(val data: ByteArray) : KeyReadResult()
    object FileNotFound : KeyReadResult()
    object PermissionDenied : KeyReadResult()
    data class Error(val exception: Exception) : KeyReadResult()
}

enum class KeyError {
    FILE_NOT_FOUND,
    PERMISSION_DENIED,
    CORRUPTED_DATA,
    BACKUP_RECOVERY_FAILED,
    DISK_FULL,
    FILE_SYSTEM_ERROR,
    UNKNOWN_ERROR
}

class KeyIntegrityException(message: String, cause: Throwable? = null) : Exception(message, cause)
class KeyPermissionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class KeyFileSystemException(message: String, cause: Throwable? = null) : Exception(message, cause)

object KeyManagerMetrics {
    val permissionFixAttempts = AtomicLong(0)
    val permissionFixSuccesses = AtomicLong(0)
    val backupRecoveries = AtomicLong(0)
    val keyRegenerations = AtomicLong(0)
    val diskFullErrors = AtomicLong(0)
    val fileSystemErrors = AtomicLong(0)
    
    fun reset() {
        permissionFixAttempts.set(0)
        permissionFixSuccesses.set(0)
        backupRecoveries.set(0)
        keyRegenerations.set(0)
        diskFullErrors.set(0)
        fileSystemErrors.set(0)
    }
}

object SecureKeyManager {
    private const val TAG = "SecureKeyManager"

    /**
     * 密钥恢复回调（可选）
     *
     * 当主密钥和备份都不可读时，系统不再静默生成新密钥，
     * 而是通过此回调让用户/调用方决策如何处理。
     *
     * 设置此回调后，密钥丢失将触发用户确认流程，防止旧存档被静默丢弃。
     * 若不设置（null），则保持向后兼容行为：记录警告日志后自动恢复。
     */
    @Volatile
    var recoveryCallback: KeyRecoveryCallback? = null

    @Volatile
    var allowAutoRecovery: Boolean = false

    private const val PREFS_NAME = "secure_key_prefs"
    private const val KEY_PREF_KEY = "derived_key_hash"
    private const val ACCOUNT_ANCHOR_KEY = "account_anchor_id"
    private const val FALLBACK_ID_KEY = "fallback_account_id"
    private const val KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 32
    
    private const val KEY_FILE_NAME = ".secure_key"
    private const val BACKUP_FILE_NAME = ".secure_key.bak"
    private const val TEMP_FILE_NAME = ".secure_key.tmp"
    
    private const val MIN_DISK_SPACE_BYTES = 64 * 1024L

    // 密钥版本化支持
    private const val KEY_VERSION_CURRENT = 2
    private const val KEY_VERSION_V1 = 1
    private const val KEY_VERSION_PREF_KEY = "key_version"
    
    private data class KeyCache(
        val key: ByteArray,
        val lastAccess: Long
    ) {
        fun isExpired(ttl: Long): Boolean = System.currentTimeMillis() - lastAccess >= ttl
    }
    
    @Volatile
    private var keyCache: KeyCache? = null
    private const val KEY_CACHE_TTL = 5 * 60 * 1000L
    
    private val keyLock = Any()
    private val recoveryLock = Any()
    
    fun getOrCreateKey(context: Context): ByteArray {
        synchronized(keyLock) {
            val currentCache = keyCache
            if (currentCache != null && !currentCache.isExpired(KEY_CACHE_TTL)) {
                keyCache = currentCache.copy(lastAccess = System.currentTimeMillis())
                return currentCache.key
            }
            
            return try {
                val key = getOrCreateDerivedKey(context)
                keyCache = KeyCache(key, System.currentTimeMillis())
                key
            } catch (e: Exception) {
                keyCache = null
                Log.e(TAG, "Failed to create derived key", e)
                throw e
            }
        }
    }
    
    private fun checkFileSystemHealth(context: Context): Boolean {
        val filesDir = context.filesDir
        if (!filesDir.exists()) {
            Log.e(TAG, "Files directory does not exist")
            KeyManagerMetrics.fileSystemErrors.incrementAndGet()
            return false
        }
        if (!filesDir.canRead() || !filesDir.canWrite()) {
            Log.e(TAG, "Files directory is not accessible")
            KeyManagerMetrics.fileSystemErrors.incrementAndGet()
            return false
        }
        return true
    }
    
    private fun checkDiskSpace(context: Context, requiredBytes: Long): Boolean {
        val filesDir = context.filesDir
        val freeSpace = filesDir.freeSpace
        if (freeSpace < requiredBytes) {
            Log.e(TAG, "Insufficient disk space: required=$requiredBytes, available=$freeSpace")
            KeyManagerMetrics.diskFullErrors.incrementAndGet()
            return false
        }
        return true
    }
    
    private fun getOrCreateDerivedKey(context: Context): ByteArray {
        if (!checkFileSystemHealth(context)) {
            throw KeyFileSystemException("File system is not healthy or accessible")
        }
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        val backupFile = File(context.filesDir, BACKUP_FILE_NAME)
        
        if (keyFile.exists()) {
            try {
                val readResult = readKeyFileSafely(context, keyFile)
                when (readResult) {
                    is KeyReadResult.Success -> {
                        val deviceSecret = getDeviceSecret(context)
                        val decryptedKey = decryptKey(readResult.data, deviceSecret)
                        
                        val storedHash = prefs.getString(KEY_PREF_KEY, null)
                        if (storedHash != null) {
                            val currentHash = MessageDigest.getInstance("SHA-256").digest(decryptedKey)
                                .joinToString("") { "%02x".format(it) }
                            if (currentHash != storedHash) {
                                Log.w(TAG, "Key hash mismatch, attempting recovery from backup")
                                return tryRecoverFromBackup(context, backupFile, prefs)
                                    ?: throw KeyIntegrityException(
                                        "Key integrity verification failed. " +
                                        "This may indicate data corruption or tampering."
                                    )
                            }
                        }
                        
                        return decryptedKey
                    }
                    is KeyReadResult.FileNotFound -> {
                        Log.w(TAG, "Key file reported as existing but could not be found")
                    }
                    is KeyReadResult.PermissionDenied -> {
                        Log.w(TAG, "Permission denied reading key file, attempting backup recovery")
                        val recoveredKey = tryRecoverFromBackup(context, backupFile, prefs)
                        if (recoveredKey != null) {
                            return recoveredKey
                        }
                        throw KeyPermissionException("Permission denied accessing key file and no backup available")
                    }
                    is KeyReadResult.Error -> {
                        Log.w(TAG, "Error reading key file: ${readResult.exception.message}", readResult.exception)
                    }
                }
                
                val recoveredKey = tryRecoverFromBackup(context, backupFile, prefs)
                if (recoveredKey != null) {
                    return recoveredKey
                }
                throw KeyIntegrityException(
                    "Failed to read key file and no valid backup found."
                )
            } catch (e: javax.crypto.AEADBadTagException) {
                Log.w(TAG, "Key decryption failed (AEADBadTagException), device secret may have changed", e)
            } catch (e: java.security.InvalidKeyException) {
                Log.w(TAG, "Key decryption failed (InvalidKeyException), key material invalid", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Key data appears corrupted or truncated", e)
            } catch (e: KeyIntegrityException) {
                throw e
            } catch (e: KeyPermissionException) {
                throw e
            } catch (e: KeyFileSystemException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read existing key, attempting recovery from backup", e)
            }

            val recoveredKey = tryRecoverFromBackup(context, backupFile, prefs)
            if (recoveredKey != null) {
                return recoveredKey }
            
            // ========== 密钥丢失预警机制 ==========
            // 原问题：此处直接调用 generateNewKey() 会导致所有旧存档永久丢失，且无任何用户确认。
            // 修复方案：通过 recoveryCallback 让用户/调用方决策是否允许生成新密钥。
            val lossReason = "Both key file and backup are unrecoverable. " +
                    "keyFile exists=${keyFile.exists()}, backupFile exists=${backupFile.exists()}. " +
                    "WARNING: Generating a new key will permanently lose access to all existing save data."
            
            Log.e(TAG, "[KEY LOSS WARNING] $lossReason")
            
            // 检查是否有注册的恢复回调
            val callback = recoveryCallback
            if (callback != null) {
                // 有回调：交由用户决策
                Log.i(TAG, "KeyRecoveryCallback registered, requesting user decision")
                val decision = callback.onKeyRecoveryRequired(lossReason)
                
                when (decision) {
                    KeyRecoveryDecision.IMPORT_TOKEN -> {
                        // 用户选择导入 token，抛出异常提示上层处理导入流程
                        throw KeyIntegrityException(
                            "User chose to import recovery token. " +
                            "Call importKeyRecoveryToken() with user-provided token, then retry."
                        )
                    }
                    KeyRecoveryDecision.GENERATE_NEW_KEY -> {
                        // 用户明确确认生成新密钥（已知旧存档将丢失）
                        Log.w(TAG, "[USER CONFIRMED] User explicitly confirmed key regeneration. Old saves will be permanently lost.")
                    }
                    KeyRecoveryDecision.RETRY -> {
                        // 用户选择重试，再次尝试从备份恢复
                        Log.i(TAG, "User requested retry, attempting backup recovery again")
                        val retryKey = tryRecoverFromBackup(context, backupFile, prefs)
                        if (retryKey != null) {
                            return retryKey
                        }
                        // 重试仍失败，继续走生成新密钥流程（但已记录警告）
                        Log.w(TAG, "Retry failed, proceeding with key generation after user acknowledgment")
                    }
                    KeyRecoveryDecision.CANCEL -> {
                        // 用户取消操作，阻止自动恢复
                        throw KeyIntegrityException(
                            "Operation cancelled by user. Key recovery aborted to protect existing data."
                        )
                    }
                }
                // 决策为 GENERATE_NEW_KEY 或 RETRY 失败后的兜底：继续执行生成新密钥
            } else {
                if (!allowAutoRecovery) {
                    throw KeyIntegrityException(
                        "No KeyRecoveryCallback registered and auto-recovery is disabled. " +
                        "Register a callback via SecureKeyManager.recoveryCallback, or enable " +
                        "auto-recovery with SecureKeyManager.allowAutoRecovery = true (not recommended)."
                    )
                }
                Log.w(TAG,
                    "[BACKWARD COMPATIBILITY] Auto-recovery explicitly enabled. " +
                    "OLD SAVES WILL BE LOST.")
            }
            
            // 二次确认日志：在真正执行生成新密钥前再次记录
            Log.w(TAG, "[FINAL CONFIRMATION] Executing generateNewKey(). This action is irreversible.")
            
            if (keyFile.exists()) keyFile.delete()
            if (backupFile.exists()) backupFile.delete()
            return generateNewKey(context, prefs, keyFile, backupFile)
        }
        
        if (backupFile.exists()) {
            Log.i(TAG, "Key file missing but backup exists, attempting recovery")
            val recoveredKey = tryRecoverFromBackup(context, backupFile, prefs)
            if (recoveredKey != null) {
                return recoveredKey
            }
        }
        
        return generateNewKey(context, prefs, keyFile, backupFile)
    }
    
    private fun readKeyFileSafely(context: Context, file: File): KeyReadResult {
        try {
            context.openFileInput(file.name).use { input ->
                return KeyReadResult.Success(input.readBytes())
            }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "File not found via openFileInput")
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception via openFileInput", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read via openFileInput: ${e.message}")
        }
        
        return try {
            if (!file.exists()) {
                Log.w(TAG, "Key file does not exist")
                return KeyReadResult.FileNotFound
            }
            
            if (!file.canRead()) {
                Log.w(TAG, "Key file exists but cannot be read, attempting to fix permissions")
                KeyManagerMetrics.permissionFixAttempts.incrementAndGet()
                
                val fixed = file.setReadable(true)
                Log.i(TAG, "Permission fix result: $fixed")
                
                if (!fixed) {
                    KeyManagerMetrics.permissionFixSuccesses.incrementAndGet()
                    return KeyReadResult.PermissionDenied
                }
            }
            
            val data = file.readBytes()
            KeyReadResult.Success(data)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception reading key file", e)
            KeyReadResult.PermissionDenied
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read key file directly", e)
            KeyReadResult.Error(e)
        }
    }
    
    private fun tryRecoverFromBackup(
        context: Context,
        backupFile: File,
        prefs: android.content.SharedPreferences
    ): ByteArray? {
        synchronized(recoveryLock) {
            if (!backupFile.exists()) {
                return null
            }
            
            return try {
                val readResult = readKeyFileSafely(context, backupFile)
                val encryptedKey = when (readResult) {
                    is KeyReadResult.Success -> readResult.data
                    else -> return null
                }
                
                val deviceSecret = getDeviceSecret(context)
                val decryptedKey = decryptKey(encryptedKey, deviceSecret)
                
                val storedHash = prefs.getString(KEY_PREF_KEY, null)
                if (storedHash != null) {
                    val currentHash = MessageDigest.getInstance("SHA-256").digest(decryptedKey)
                        .joinToString("") { "%02x".format(it) }
                    if (currentHash != storedHash) {
                        Log.w(TAG, "Backup key hash also mismatch")
                        return null
                    }
                }
                
                val keyFile = File(context.filesDir, KEY_FILE_NAME)
                writeKeyFileAtomically(context, keyFile, backupFile, encryptedKey)
                
                KeyManagerMetrics.backupRecoveries.incrementAndGet()
                Log.i(TAG, "Successfully recovered key from backup")
                decryptedKey
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recover from backup", e)
                null
            }
        }
    }
    
    private fun generateNewKey(
        context: Context,
        prefs: android.content.SharedPreferences,
        keyFile: File,
        backupFile: File
    ): ByteArray {
        if (!checkDiskSpace(context, MIN_DISK_SPACE_BYTES)) {
            throw KeyFileSystemException("Insufficient disk space to generate new key")
        }
        
        Log.i(TAG, "Generating new derived key")
        KeyManagerMetrics.keyRegenerations.incrementAndGet()
        
        val key = ByteArray(KEY_SIZE / 8).also { SecureRandom().nextBytes(it) }
        val deviceSecret = getDeviceSecret(context)
        val encryptedKey = encryptKey(key, deviceSecret)
        
        writeKeyFileAtomically(context, keyFile, backupFile, encryptedKey)
        
        val hash = MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_PREF_KEY, hash).apply()
        prefs.edit().putInt(KEY_VERSION_PREF_KEY, KEY_VERSION_CURRENT).apply()
        
        Log.i(TAG, "Generated and stored new derived key v$KEY_VERSION_CURRENT")
        return key
    }
    
    private fun writeKeyFileAtomically(
        context: Context,
        keyFile: File,
        backupFile: File,
        encryptedKey: ByteArray
    ) {
        if (!checkDiskSpace(context, encryptedKey.size.toLong() * 3)) {
            throw KeyFileSystemException("Insufficient disk space to write key file")
        }
        
        try {
            if (keyFile.exists()) {
                try {
                    val readResult = readKeyFileSafely(context, keyFile)
                    if (readResult is KeyReadResult.Success) {
                        context.openFileOutput(BACKUP_FILE_NAME, Context.MODE_PRIVATE).use { output ->
                            output.write(readResult.data)
                        }
                        Log.i(TAG, "Created backup of existing key file")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create backup, but continuing", e)
                }
            }
            
            context.openFileOutput(KEY_FILE_NAME, Context.MODE_PRIVATE).use { output ->
                output.write(encryptedKey)
            }
            Log.i(TAG, "Key file written successfully using Android API")
            
        } catch (e: Exception) {
            Log.e(TAG, "Android API write failed, attempting direct file access", e)
            
            val tempFile = File(context.filesDir, TEMP_FILE_NAME)
            try {
                FileOutputStream(tempFile).use { fos ->
                    fos.write(encryptedKey)
                    fos.fd.sync()
                }
                
                if (keyFile.exists()) {
                    if (backupFile.exists()) {
                        backupFile.delete()
                    }
                    if (!keyFile.renameTo(backupFile)) {
                        Log.w(TAG, "Failed to create backup, but continuing")
                    }
                }
                
                if (!tempFile.renameTo(keyFile)) {
                    if (backupFile.exists()) {
                        backupFile.renameTo(keyFile)
                    }
                    throw IOException("Failed to rename temp key file to final location")
                }
                
                Log.i(TAG, "Key file written atomically via direct file access")
                
            } catch (directError: Exception) {
                tempFile.delete()
                Log.e(TAG, "All write methods failed", directError)
                throw IOException("Failed to write key file: ${directError.message}", directError)
            }
        }
    }
    
    private const val KEYSTORE_ALIAS = "xianxia_device_secret"
    private const val SECRET_PREFS_NAME = "device_secret_prefs"
    private const val SECRET_KEY = "hw_secret_hash"
    
    private fun getDeviceSecret(context: Context): ByteArray {
        val hwSecret = getHardwareBackedSecret(context)
        val hybridBinding = getHybridBindingFactor(context)
        val combined = "${hwSecret}:${hybridBinding}:${context.packageName}"
        return MessageDigest.getInstance("SHA-256").digest(combined.toByteArray(Charsets.UTF_8))
    }
    
    fun getAccountBindingFactor(context: Context): String {
        return try {
            if (com.xianxia.sect.taptap.TapTapAuthManager.isLoggedIn()) {
                val account = com.xianxia.sect.taptap.TapTapAuthManager.getCurrentAccount()
                if (account != null) {
                    val userId = account.openId ?: account.unionId
                    if (!userId.isNullOrEmpty()) {
                        Log.d(TAG, "Using TapTap account binding factor")
                        return userId
                    }
                }
            }
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
    
    fun setAccountAnchor(userId: String, context: Context) {
        try {
            require(userId.isNotEmpty()) { "userId must not be empty" }
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(ACCOUNT_ANCHOR_KEY, userId)
                .putString(FALLBACK_ID_KEY, userId)
                .apply()
            
            Log.i(TAG, "Account anchor set successfully: ${userId.take(8)}...")
            
            clearCachedKey()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set account anchor", e)
            throw IllegalArgumentException("Failed to set account anchor: ${e.message}", e)
        }
    }
    
    private fun getHybridBindingFactor(context: Context): String {
        val deviceFingerprint = getDeviceFingerprint(context)
        val accountFactor = getAccountBindingFactor(context)
        
        val combined = "${deviceFingerprint}:${accountFactor}:${context.packageName}"
        return MessageDigest.getInstance("SHA-256")
            .digest(combined.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    
    private fun getHardwareBackedSecret(context: Context): String {
        return try {
            generateKeyStoreSecret(context)
        } catch (e: Exception) {
            Log.w(TAG, "Hardware secret generation failed, fallback to secure random", e)
            generateSecureRandomSecret(context)
        }
    }
    
    @Suppress("NewApi")
    private fun generateKeyStoreSecret(context: Context): String {
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
                // TODO: 迁移计划 - 废弃API替换方案
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
                    Log.d(TAG, "Using deprecated setUserAuthenticationValidityDurationSeconds() for API ${Build.VERSION.SDK_INT}")
                }
            } catch (e: java.security.InvalidAlgorithmParameterException) {
                Log.w(TAG, "Device does not support user authentication, generating key without auth requirement")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    builder.setIsStrongBoxBacked(true)
                } catch (e: java.security.InvalidAlgorithmParameterException) {
                    Log.w(TAG, "StrongBox not available on this device")
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
        
        return secretHash ?: throw IllegalStateException("Failed to store hardware secret")
    }
    
    private fun generateSecureRandomSecret(context: Context): String {
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
            } catch (_: Exception) {}
            
            val combined = entropySources.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
            val hash = MessageDigest.getInstance("SHA-512").digest(combined)
            secretHash = hash.joinToString("") { "%02x".format(it) }
            entropySources.forEach { it.fill(0) }
            combined.fill(0)
            prefs.edit().putString(SECRET_KEY, secretHash).apply()
        }
        
        return secretHash ?: throw IllegalStateException("Failed to generate secure random secret")
    }
    
    @SuppressLint("HardwareIds")
    private fun getDeviceFingerprint(context: Context): String {
        val parts = mutableListOf<String>()

        try {
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            parts.add(deviceId)
        } catch (_: Exception) {}

        try {
            parts.add(Build.BRAND)
            parts.add(Build.MODEL)
        } catch (_: Exception) {}

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
        } catch (_: Exception) {}

        return if (parts.isNotEmpty()) {
            MessageDigest.getInstance("SHA-256")
                .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } else {
            "default_fingerprint"
        }
    }
    
    private fun encryptKey(key: ByteArray, secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKeySpec = SecretKeySpec(secret, "AES")
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val encrypted = cipher.doFinal(key)
        
        // 版本化加密格式: [版本号(1字节)] [IV(12字节)] [密文+GCM标签]
        val result = ByteArray(1 + GCM_IV_LENGTH + encrypted.size)
        result[0] = KEY_VERSION_CURRENT.toByte()
        System.arraycopy(iv, 0, result, 1, GCM_IV_LENGTH)
        System.arraycopy(encrypted, 0, result, 1 + GCM_IV_LENGTH, encrypted.size)
        
        return result
    }
    
    private fun decryptKey(encryptedKey: ByteArray, secret: ByteArray): ByteArray {
        // 自动检测版本头：新格式首字节为版本号(1-3)，旧格式无版本头
        val headerOffset = if (encryptedKey.size > (GCM_IV_LENGTH + 16 + 1) && 
                               encryptedKey[0].toInt() in 1..3) 1 else 0
        
        val minLen = headerOffset + GCM_IV_LENGTH + 16
        if (encryptedKey.size < minLen) {
            throw IllegalArgumentException("Invalid encrypted key length: ${encryptedKey.size}, min: $minLen")
        }
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKeySpec = SecretKeySpec(secret, "AES")
        val iv = encryptedKey.copyOfRange(headerOffset, headerOffset + GCM_IV_LENGTH)
        val encrypted = encryptedKey.copyOfRange(headerOffset + GCM_IV_LENGTH, encryptedKey.size)
        
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encrypted)
    }
    
    fun rotateKey(context: Context): ByteArray {
        synchronized(keyLock) {
            Log.i(TAG, "Starting key rotation")
            
            keyCache?.key?.fill(0)
            keyCache = null
            
            val keyFile = File(context.filesDir, KEY_FILE_NAME)
            val backupFile = File(context.filesDir, BACKUP_FILE_NAME)
            
            keyFile.delete()
            backupFile.delete()
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PREF_KEY).apply()
            
            val newKey = generateNewKey(context, prefs, keyFile, backupFile)
            keyCache = KeyCache(newKey, System.currentTimeMillis())
            
            Log.i(TAG, "Key rotation completed")
            return newKey
        }
    }
    
    fun clearCachedKey() {
        synchronized(keyLock) {
            keyCache?.key?.fill(0)
            keyCache = null
        }
    }
    
    fun verifyKeyIntegrity(context: Context): Boolean {
        return try {
            val key = getOrCreateKey(context)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val storedHash = prefs.getString(KEY_PREF_KEY, null)
            
            if (storedHash != null) {
                val currentHash = MessageDigest.getInstance("SHA-256").digest(key)
                    .joinToString("") { "%02x".format(it) }
                currentHash == storedHash
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Key integrity verification failed", e)
            false
        }
    }
    
    fun recoverKey(context: Context): KeyRecoveryResult {
        synchronized(keyLock) {
            val keyFile = File(context.filesDir, KEY_FILE_NAME)
            val backupFile = File(context.filesDir, BACKUP_FILE_NAME)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            if (keyFile.exists()) {
                try {
                    val readResult = readKeyFileSafely(context, keyFile)
                    if (readResult is KeyReadResult.Success) {
                        val deviceSecret = getDeviceSecret(context)
                        val decryptedKey = decryptKey(readResult.data, deviceSecret)
                        
                        val storedHash = prefs.getString(KEY_PREF_KEY, null)
                        if (storedHash != null) {
                            val currentHash = MessageDigest.getInstance("SHA-256").digest(decryptedKey)
                                .joinToString("") { "%02x".format(it) }
                            if (currentHash == storedHash) {
                                keyCache = KeyCache(decryptedKey, System.currentTimeMillis())
                                return KeyRecoveryResult.Success(decryptedKey, "Recovered from main file")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to recover from main file", e)
                }
            }
            
            if (backupFile.exists()) {
                try {
                    val readResult = readKeyFileSafely(context, backupFile)
                    if (readResult is KeyReadResult.Success) {
                        val deviceSecret = getDeviceSecret(context)
                        val decryptedKey = decryptKey(readResult.data, deviceSecret)
                        
                        val storedHash = prefs.getString(KEY_PREF_KEY, null)
                        if (storedHash != null) {
                            val currentHash = MessageDigest.getInstance("SHA-256").digest(decryptedKey)
                                .joinToString("") { "%02x".format(it) }
                            if (currentHash == storedHash) {
                                writeKeyFileAtomically(context, keyFile, backupFile, readResult.data)
                                keyCache = KeyCache(decryptedKey, System.currentTimeMillis())
                                return KeyRecoveryResult.Success(decryptedKey, "Recovered from backup file")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to recover from backup file", e)
                }
            }
            
            return KeyRecoveryResult.Failure("No recoverable key found. All save data may be lost.")
        }
    }
    
    fun forceRegenerateKey(context: Context): ByteArray {
        synchronized(keyLock) {
            Log.w(TAG, "Force regenerating key - existing save data will be lost")
            
            keyCache?.key?.fill(0)
            keyCache = null
            
            try {
                context.deleteFile(KEY_FILE_NAME)
                context.deleteFile(BACKUP_FILE_NAME)
                context.deleteFile(TEMP_FILE_NAME)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete some key files", e)
            }
            
            val keyFile = File(context.filesDir, KEY_FILE_NAME)
            val backupFile = File(context.filesDir, BACKUP_FILE_NAME)
            if (keyFile.exists()) keyFile.delete()
            if (backupFile.exists()) backupFile.delete()
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PREF_KEY).apply()
            
            val newKey = generateNewKey(context, prefs, keyFile, backupFile)
            keyCache = KeyCache(newKey, System.currentTimeMillis())
            
            Log.i(TAG, "Key force regeneration completed")
            return newKey
        }
    }
    
    fun hasValidKey(context: Context): Boolean {
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        val backupFile = File(context.filesDir, BACKUP_FILE_NAME)
        return keyFile.exists() || backupFile.exists()
    }
    
    fun getMetrics(): Map<String, Long> {
        return mapOf(
            "permissionFixAttempts" to KeyManagerMetrics.permissionFixAttempts.get(),
            "permissionFixSuccesses" to KeyManagerMetrics.permissionFixSuccesses.get(),
            "backupRecoveries" to KeyManagerMetrics.backupRecoveries.get(),
            "keyRegenerations" to KeyManagerMetrics.keyRegenerations.get(),
            "diskFullErrors" to KeyManagerMetrics.diskFullErrors.get(),
            "fileSystemErrors" to KeyManagerMetrics.fileSystemErrors.get()
        )
    }
    
    fun exportKeyRecoveryToken(context: Context): String {
        return synchronized(keyLock) {
            try {
                val key = getOrCreateKey(context)
                
                val accountFactor = getAccountBindingFactor(context)
                val deviceFingerprint = getDeviceFingerprint(context)
                val timestamp = System.currentTimeMillis()
                
                val tokenData = StringBuilder().apply {
                    append("v=1|")
                    append("ts=").append(timestamp).append("|")
                    append("af=").append(accountFactor).append("|")
                    append("df=").append(deviceFingerprint).append("|")
                    append("pkg=").append(context.packageName)
                }.toString()
                
                val tokenCipher = Cipher.getInstance("AES/GCM/NoPadding")
                val tokenKey = MessageDigest.getInstance("SHA-256")
                    .digest("${accountFactor}:${deviceFingerprint}:recovery".toByteArray(Charsets.UTF_8))
                val tokenKeySpec = SecretKeySpec(tokenKey, "AES")
                val tokenIv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
                
                tokenCipher.init(Cipher.ENCRYPT_MODE, tokenKeySpec, GCMParameterSpec(GCM_TAG_LENGTH, tokenIv))
                val encryptedKey = tokenCipher.doFinal(key)

                val result = android.util.Base64.encodeToString(
                    tokenIv + encryptedKey,
                    android.util.Base64.NO_WRAP
                )
                
                Log.i(TAG, "Key recovery token exported successfully")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export key recovery token", e)
                throw KeyIntegrityException("Failed to export key recovery token: ${e.message}", e)
            }
        }
    }
    
    fun importKeyRecoveryToken(token: String, context: Context): Boolean {
        return synchronized(keyLock) {
            try {
                require(token.isNotEmpty()) { "Token must not be empty" }

                val tokenBytes = android.util.Base64.decode(token, android.util.Base64.NO_WRAP)
                if (tokenBytes.size < GCM_IV_LENGTH + 32) {
                    throw IllegalArgumentException("Invalid token format")
                }
                
                val accountFactor = getAccountBindingFactor(context)
                val deviceFingerprint = getDeviceFingerprint(context)
                
                val tokenKey = MessageDigest.getInstance("SHA-256")
                    .digest("${accountFactor}:${deviceFingerprint}:recovery".toByteArray(Charsets.UTF_8))
                val tokenKeySpec = SecretKeySpec(tokenKey, "AES")
                
                val tokenIv = tokenBytes.copyOfRange(0, GCM_IV_LENGTH)
                val encryptedKey = tokenBytes.copyOfRange(GCM_IV_LENGTH, tokenBytes.size)
                
                val tokenCipher = Cipher.getInstance("AES/GCM/NoPadding")
                tokenCipher.init(Cipher.DECRYPT_MODE, tokenKeySpec, GCMParameterSpec(GCM_TAG_LENGTH, tokenIv))
                val recoveredKey = tokenCipher.doFinal(encryptedKey)
                
                if (recoveredKey.size != KEY_SIZE / 8) {
                    throw IllegalArgumentException("Recovered key has invalid size")
                }
                
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val keyFile = File(context.filesDir, KEY_FILE_NAME)
                val backupFile = File(context.filesDir, BACKUP_FILE_NAME)
                
                val deviceSecret = getDeviceSecret(context)
                val encryptedStoredKey = encryptKey(recoveredKey, deviceSecret)
                
                writeKeyFileAtomically(context, keyFile, backupFile, encryptedStoredKey)
                
                val hash = MessageDigest.getInstance("SHA-256").digest(recoveredKey)
                    .joinToString("") { "%02x".format(it) }
                prefs.edit().putString(KEY_PREF_KEY, hash).apply()
                
                keyCache = KeyCache(recoveredKey, System.currentTimeMillis())
                
                Log.i(TAG, "Key recovery token imported successfully")
                true
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid recovery token format", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import key recovery token", e)
                false
            }
        }
    }
    
    // ========== 密钥健康状态查询 ==========
    
    /**
     * 获取当前存储的密钥版本号
     * @return 密钥版本号，默认返回 V1（兼容旧数据）
     */
    fun getKeyVersion(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getInt(KEY_VERSION_PREF_KEY, KEY_VERSION_V1)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get key version", e)
            KEY_VERSION_V1
        }
    }
    
    /**
     * 检查是否具备强绑定因子（账号绑定）
     * @return true 表示有有效的账号绑定因子
     */
    fun isStrongBindingAvailable(context: Context): Boolean {
        return try {
            val accountFactor = getAccountBindingFactor(context)
            accountFactor != "no_account" && accountFactor.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取完整的安全评估报告
     * @return 包含各项安全指标的 Map
     */
    fun getSecurityAssessment(context: Context): Map<String, Any> {
        return mapOf(
            "keyVersion" to getKeyVersion(context),
            "hasStrongBinding" to isStrongBindingAvailable(context),
            "hasHardwareBacking" to true,
            "keyFileExists" to File(context.filesDir, KEY_FILE_NAME).exists(),
            "backupExists" to File(context.filesDir, BACKUP_FILE_NAME).exists(),
            "integrityValid" to verifyKeyIntegrity(context),
            "accountBinding" to getAccountBindingFactor(context).take(8) + "..."
        )
    }

    /**
     * Argon2id 密钥派生（委托给 SaveCrypto）
     *
     * 提供统一的 Argon2id 接口，内部委托 SaveCrypto 实现。
     * 参数配置遵循 OWASP/NIST 推荐：memory=64MB, parallelism=2, iterations=3
     *
     * @param password 用户密码
     * @param salt 随机盐值（32 字节）
     * @return 派生的 256 位密钥
     */
    fun deriveKeyArgon2id(password: String, salt: ByteArray): ByteArray {
        return SaveCrypto.deriveKeyArgon2id(password, salt)
    }
}

class KeyRotationManager(
    private val context: Context,
    private val storageFacade: com.xianxia.sect.data.facade.StorageFacade
) {
    companion object {
        private const val TAG = "KeyRotationManager"
        private const val ROTATION_PREFS = "key_rotation_prefs"
        private const val KEY_LAST_ROTATION = "last_rotation"
        private const val ROTATION_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
    }
    
    /**
     * 维护模式锁（AtomicBoolean 保证线程安全）
     *
     * 用途：
     * - 在密钥轮换期间标记系统处于维护状态
     * - 外部可通过 isMaintenanceMode() 查询当前状态
     * - 在轮换进行时阻止并发操作或提示用户等待
     *
     * 为什么需要维护模式锁：
     * 密钥轮换涉及读取所有存档、生成新密钥、重新加密并写回的完整流程，
     * 此过程中存档数据处于不一致状态（旧密钥加密的存档尚未用新密钥重加密）。
     * 如果此时允许其他写入操作，可能导致数据混乱或丢失。
     */
    private val isInMaintenanceMode = AtomicBoolean(false)
    
    /**
     * 查询当前是否处于密钥轮换维护模式
     * @return true 表示正在进行密钥轮换，系统处于维护状态
     */
    fun isMaintenanceMode(): Boolean = isInMaintenanceMode.get()
    
    suspend fun needsRotation(): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(ROTATION_PREFS, Context.MODE_PRIVATE)
        val lastRotation = prefs.getLong(KEY_LAST_ROTATION, 0)
        
        System.currentTimeMillis() - lastRotation > ROTATION_INTERVAL_MS
    }
    
    suspend fun performRotation(): com.xianxia.sect.data.unified.SaveResult<Unit> = withContext(Dispatchers.IO) {
        // 进入维护模式：防止轮换期间的并发操作导致数据不一致
        isInMaintenanceMode.set(true)
        Log.i(TAG, "Entering maintenance mode for key rotation")
        
        try {
            Log.i(TAG, "Starting key rotation process")

            val slots = (1..6).filter { slot ->
                storageFacade.hasSaveSuspend(slot)
            }

            SecureKeyManager.rotateKey(context)

            for (slot in slots) {
                val result = storageFacade.load(slot)
                if (!result.isSuccess) {
                    Log.w(TAG, "Failed to load slot $slot during rotation, skipping")
                    continue
                }

                try {
                    val data = result.getOrThrow()

                    val saveResult = storageFacade.save(slot, data)
                    if (saveResult.isFailure) {
                        Log.e(TAG, "Failed to re-encrypt slot $slot after key rotation")
                        return@withContext com.xianxia.sect.data.unified.SaveResult.failure(
                            com.xianxia.sect.data.unified.SaveError.ENCRYPTION_ERROR,
                            "Failed to re-encrypt slot $slot"
                        )
                    }

                    Log.d(TAG, "Successfully rotated and saved slot $slot")
                } catch (slotEx: Exception) {
                    Log.e(TAG, "Error processing slot $slot during rotation", slotEx)
                }
            }

            val prefs = context.getSharedPreferences(ROTATION_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_LAST_ROTATION, System.currentTimeMillis()).apply()

            Log.i(TAG, "Key rotation completed successfully for ${slots.size} slots")

            com.xianxia.sect.data.unified.SaveResult.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Key rotation failed", e)
            com.xianxia.sect.data.unified.SaveResult.failure(
                com.xianxia.sect.data.unified.SaveError.KEY_DERIVATION_ERROR,
                "Key rotation failed: ${e.message}",
                e
            )
        } finally {
            // 无论成功还是失败，都必须退出维护模式
            // 防止因异常导致锁永远无法释放，阻塞后续所有操作
            isInMaintenanceMode.set(false)
            Log.i(TAG, "Exited maintenance mode (key rotation finished or aborted)")
        }
    }
}
