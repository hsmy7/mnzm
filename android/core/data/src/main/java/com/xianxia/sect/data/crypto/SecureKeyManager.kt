package com.xianxia.sect.data.crypto

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


object SecureKeyManager {
    internal const val TAG = "SecureKeyManager"

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


    internal const val PREFS_NAME = "secure_key_prefs"
    internal const val KEY_PREF_KEY = "derived_key_hash"
    internal const val KEY_SIZE = 256
    
    

    // 密钥版本化支持
    internal const val KEY_VERSION_CURRENT = 2
    internal const val KEY_VERSION_PREF_KEY = "key_version"
    
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
    

    

    

    






    

    

    

    



    
    

    

    

    

    

    

    

    

    

    

    

    

    

    

    
    

    

    

    

    
    // ========== 密钥健康状态查询 ==========
    

    

    


    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
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

    private fun getOrCreateDerivedKey(context: Context): ByteArray {
        if (!SecureKeyFileStore.checkFileSystemHealth(context)) {
            throw KeyFileSystemException("File system is not healthy or accessible")
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyFile = File(context.filesDir, SecureKeyFileStore.KEY_FILE_NAME)
        val backupFile = File(context.filesDir, SecureKeyFileStore.BACKUP_FILE_NAME)

        // P-2 拆分：读现有密钥 + 校验 + 备份恢复提取
        val existingKey = readExistingKeyOrRecover(context, prefs, keyFile, backupFile)
        if (existingKey != null) {
            return existingKey
        }

        // 行为保持（原 260-268）：密钥文件不存在 → 备份恢复/直接生成（不经过预警）
        if (!keyFile.exists()) {
            if (backupFile.exists()) {
                Log.i(TAG, "Key file missing but backup exists, attempting recovery")
                val recoveredKey = SecureKeyFileStore.tryRecoverFromBackup(context, backupFile, prefs)
                if (recoveredKey != null) {
                    return recoveredKey
                }
            }
            return SecureKeyFileStore.generateNewKey(context, prefs, keyFile, backupFile)
        }

        // 密钥文件存在但不可恢复 → 丢失预警 + 用户决策（原 193-257）
        return handleKeyLossAndRegenerate(context, prefs, keyFile, backupFile)
    }

    /**
     * P-2：读取现有密钥文件（含完整性校验），失败时尝试备份恢复。
     *
     * @return 有效密钥；密钥文件不存在或不可恢复时返回 null
     */
    // [合并] 前者: 异常源跨IO/SDK不可枚举; 后者: 多步骤事务/异常翻译边界：各 throw 对应不同失败路径的领域错误，刻意独立抛出保归因清晰，非疏忽计数超标 // 防御兜底: 异常源跨IO/SDK不可枚举,
    // 降级继续+日志留痕, 非静默吞噬
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun readExistingKeyOrRecover(
        context: Context,
        prefs: android.content.SharedPreferences,
        keyFile: File,
        backupFile: File
    ): ByteArray? {
        if (!keyFile.exists()) {
            // 密钥文件缺失但备份存在：尝试备份恢复
            if (backupFile.exists()) {
                Log.i(TAG, "Key file missing but backup exists, attempting recovery")
                val recoveredKey = SecureKeyFileStore.tryRecoverFromBackup(context, backupFile, prefs)
                if (recoveredKey != null) {
                    return recoveredKey
                }
            }
            return null
        }
        val keyFromFile = try {
            SecureKeyFileStore.readKeyFileVerified(
                context = context,
                prefs = prefs,
                keyFile = keyFile,
                backupFile = backupFile
            )
        } catch (e: javax.crypto.AEADBadTagException) {
            Log.w(TAG, "Key decryption failed (AEADBadTagException), device secret may have changed", e)
            null
        } catch (e: java.security.InvalidKeyException) {
            Log.w(TAG, "Key decryption failed (InvalidKeyException), key material invalid", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Key data appears corrupted or truncated", e)
            null
        } catch (e: KeyIntegrityException) {
            throw e
        } catch (e: KeyPermissionException) {
            throw e
        } catch (e: KeyFileSystemException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read existing key, attempting recovery from backup", e)
            null
        }
        return keyFromFile ?: SecureKeyFileStore.tryRecoverFromBackup(context, backupFile, prefs)
    }

    /**
     * P-2：密钥丢失预警 + 用户/回调决策 + 生成新密钥。
     *
     * 原问题：直接 SecureKeyFileStore.generateNewKey() 会导致所有旧存档永久丢失且无用户确认。
     * 修复方案：通过 recoveryCallback 让用户/调用方决策是否允许生成新密钥。
     */
    @Suppress("ThrowsCount") // 多步骤事务/异常翻译边界：各 throw 对应不同失败路径的领域错误，刻意独立抛出保归因清晰，非疏忽计数超标
    private fun handleKeyLossAndRegenerate(
        context: Context,
        prefs: android.content.SharedPreferences,
        keyFile: File,
        backupFile: File
    ): ByteArray {
        // ========== 密钥丢失预警机制 ==========
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
                    Log.w(TAG, "[USER CONFIRMED] User explicitly confirmed key regeneration. Old saves will be " +
                        "permanently lost.")
                }
                KeyRecoveryDecision.RETRY -> {
                    // 用户选择重试，再次尝试从备份恢复
                    Log.i(TAG, "User requested retry, attempting backup recovery again")
                    val retryKey = SecureKeyFileStore.tryRecoverFromBackup(context, backupFile, prefs)
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
        Log.w(TAG, "[FINAL CONFIRMATION] Executing SecureKeyFileStore.generateNewKey(). This action is irreversible.")

        if (keyFile.exists()) keyFile.delete()
        if (backupFile.exists()) backupFile.delete()
        return SecureKeyFileStore.generateNewKey(context, prefs, keyFile, backupFile)
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

    @Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
    fun exportKeyRecoveryToken(context: Context): String {
        return synchronized(keyLock) {
            try {
                val key = getOrCreateKey(context)
                
                val accountFactor = DeviceBindingIdentity.getAccountBindingFactor(context)
                val deviceFingerprint = DeviceBindingIdentity.getDeviceFingerprint(context)
                
                val tokenCipher = Cipher.getInstance("AES/GCM/NoPadding")
                val tokenKey = MessageDigest.getInstance("SHA-256")
                    .digest("${accountFactor}:${deviceFingerprint}:recovery".toByteArray(Charsets.UTF_8))
                val tokenKeySpec = SecretKeySpec(tokenKey, "AES")
                val tokenIv = ByteArray(DeviceBindingIdentity.GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
                
                tokenCipher.init(
                    Cipher.ENCRYPT_MODE, tokenKeySpec,
                    GCMParameterSpec(DeviceBindingIdentity.GCM_TAG_LENGTH, tokenIv))
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun importKeyRecoveryToken(token: String, context: Context): Boolean {
        return synchronized(keyLock) {
            try {
                require(token.isNotEmpty()) { "Token must not be empty" }

                val tokenBytes = android.util.Base64.decode(token, android.util.Base64.NO_WRAP)
                require(tokenBytes.size >= DeviceBindingIdentity.GCM_IV_LENGTH + 32) { "Invalid token format" }
                
                val accountFactor = DeviceBindingIdentity.getAccountBindingFactor(context)
                val deviceFingerprint = DeviceBindingIdentity.getDeviceFingerprint(context)
                
                val tokenKey = MessageDigest.getInstance("SHA-256")
                    .digest("${accountFactor}:${deviceFingerprint}:recovery".toByteArray(Charsets.UTF_8))
                val tokenKeySpec = SecretKeySpec(tokenKey, "AES")
                
                val tokenIv = tokenBytes.copyOfRange(0, DeviceBindingIdentity.GCM_IV_LENGTH)
                val encryptedKey = tokenBytes.copyOfRange(DeviceBindingIdentity.GCM_IV_LENGTH, tokenBytes.size)
                
                val tokenCipher = Cipher.getInstance("AES/GCM/NoPadding")
                tokenCipher.init(
                    Cipher.DECRYPT_MODE, tokenKeySpec,
                    GCMParameterSpec(DeviceBindingIdentity.GCM_TAG_LENGTH, tokenIv))
                val recoveredKey = tokenCipher.doFinal(encryptedKey)
                
                require(recoveredKey.size == KEY_SIZE / 8) { "Recovered key has invalid size" }
                
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val keyFile = File(context.filesDir, SecureKeyFileStore.KEY_FILE_NAME)
                val backupFile = File(context.filesDir, SecureKeyFileStore.BACKUP_FILE_NAME)
                
                val deviceSecret = DeviceBindingIdentity.getDeviceSecret(context)
                val encryptedStoredKey = DeviceBindingIdentity.encryptKey(recoveredKey, deviceSecret)
                
                SecureKeyFileStore.writeKeyFileAtomically(context, keyFile, backupFile, encryptedStoredKey)
                
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
}
