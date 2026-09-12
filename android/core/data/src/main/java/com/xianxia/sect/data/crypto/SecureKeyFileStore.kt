package com.xianxia.sect.data.crypto

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

    // SecureKeyManager 的密钥文件存储域:文件系统健康检查/密钥文件读写/原子写入/备份恢复/
    // 哈希校验。跨 object 引用(SecureKeyManager 共享常量/DeviceBindingIdentity 密钥加密)
    // 以限定调用表达,行为逐字节不变。

    private const val TAG = SecureKeyManager.TAG

object SecureKeyFileStore {

    internal const val KEY_FILE_NAME = ".secure_key"
    internal const val BACKUP_FILE_NAME = ".secure_key.bak"
    internal const val TEMP_FILE_NAME = ".secure_key.tmp"

    private const val MIN_DISK_SPACE_BYTES = 64 * 1024L

    private val recoveryLock = Any()

    fun checkFileSystemHealth(context: Context): Boolean {
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

    fun checkDiskSpace(context: Context, requiredBytes: Long): Boolean {
    val filesDir = context.filesDir
    val freeSpace = filesDir.freeSpace
    if (freeSpace < requiredBytes) {
        Log.e(TAG, "Insufficient disk space: required=$requiredBytes, available=$freeSpace")
        KeyManagerMetrics.diskFullErrors.incrementAndGet()
        return false
    }
    return true
    }

    /**
 * 读取密钥文件并校验：解密 + 哈希校验；
 * 分支读取失败时以备份恢复兜底，恢复失败抛对应异常。
 */
    fun readKeyFileVerified(
    context: Context,
    prefs: android.content.SharedPreferences,
    keyFile: File,
    backupFile: File
    ): ByteArray {
    val fromFile = when (val readResult = readKeyFileSafely(context, keyFile)) {
        is KeyReadResult.Success -> {
            val deviceSecret = DeviceBindingIdentity.getDeviceSecret(context)
            val decryptedKey = DeviceBindingIdentity.decryptKey(readResult.data, deviceSecret)
            if (verifyKeyHash(decryptedKey = decryptedKey, prefs = prefs)) {
                decryptedKey
            } else {
                Log.w(TAG, "Key hash mismatch, attempting recovery from backup")
                recoverKeyOrThrow(
                    context = context,
                    backupFile = backupFile,
                    prefs = prefs,
                    onFailure = {
                        KeyIntegrityException(
                            "Key integrity verification failed. " +
                            "This may indicate data corruption or tampering."
                        )
                    }
                )
            }
        }
        is KeyReadResult.FileNotFound -> {
            Log.w(TAG, "Key file reported as existing but could not be found")
            null
        }
        is KeyReadResult.PermissionDenied -> {
            Log.w(TAG, "Permission denied reading key file, attempting backup recovery")
            recoverKeyOrThrow(
                context = context,
                backupFile = backupFile,
                prefs = prefs,
                onFailure = {
                    KeyPermissionException("Permission denied accessing key file and no backup available")
                }
            )
        }
        is KeyReadResult.Error -> {
            Log.w(TAG, "Error reading key file: ${readResult.exception.message}", readResult.exception)
            null
        }
    }
    return fromFile ?: recoverKeyOrThrow(
        context = context,
        backupFile = backupFile,
        prefs = prefs,
        onFailure = {
            KeyIntegrityException("Failed to read key file and no valid backup found.")
        }
    )
    }

    /**
 * 备份恢复兜底：恢复失败时抛出自定义异常。
 */
    fun recoverKeyOrThrow(
    context: Context,
    backupFile: File,
    prefs: android.content.SharedPreferences,
    onFailure: () -> Throwable
    ): ByteArray {
    return tryRecoverFromBackup(context, backupFile, prefs) ?: throw onFailure()
    }

    /**
 * 密钥哈希校验：无存储哈希视为通过。
 */
    fun verifyKeyHash(decryptedKey: ByteArray, prefs: android.content.SharedPreferences): Boolean {
    val storedHash = prefs.getString(SecureKeyManager.KEY_PREF_KEY, null) ?: return true
    val currentHash = MessageDigest.getInstance("SHA-256").digest(decryptedKey)
        .joinToString("") { "%02x".format(it) }
    return currentHash == storedHash
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun readKeyFileSafely(context: Context, file: File): KeyReadResult {
    try {
        context.openFileInput(file.name).use { input ->
            return KeyReadResult.Success(input.readBytes())
        }
    } catch (e: FileNotFoundException) {
        Log.w(TAG, "File not found via openFileInput", e)
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun tryRecoverFromBackup(
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
            
            val deviceSecret = DeviceBindingIdentity.getDeviceSecret(context)
            val decryptedKey = DeviceBindingIdentity.decryptKey(encryptedKey, deviceSecret)
            
            val storedHash = prefs.getString(SecureKeyManager.KEY_PREF_KEY, null)
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

    fun generateNewKey(
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
    
    val key = ByteArray(SecureKeyManager.KEY_SIZE / 8).also { SecureRandom().nextBytes(it) }
    val deviceSecret = DeviceBindingIdentity.getDeviceSecret(context)
    val encryptedKey = DeviceBindingIdentity.encryptKey(key, deviceSecret)
    
    writeKeyFileAtomically(context, keyFile, backupFile, encryptedKey)
    
    val hash = MessageDigest.getInstance("SHA-256").digest(key)
        .joinToString("") { "%02x".format(it) }
    prefs.edit().putString(SecureKeyManager.KEY_PREF_KEY, hash).apply()
    prefs.edit().putInt(SecureKeyManager.KEY_VERSION_PREF_KEY, SecureKeyManager.KEY_VERSION_CURRENT).apply()
    
    Log.i(TAG, "Generated and stored new derived key v$SecureKeyManager.KEY_VERSION_CURRENT")
    return key
    }

    // 前者: 异常源跨IO/SDK不可枚举; 后者: 多步骤事务/异常翻译边界：各 throw 对应不同失败路径的领域错误，刻意独立抛出保归因清晰，非疏忽计数超标 // 防御兜底: 异常源跨IO/SDK不可枚举,
    // 降级继续+日志留痕, 非静默吞噬
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun writeKeyFileAtomically(
    context: Context,
    keyFile: File,
    backupFile: File,
    encryptedKey: ByteArray
    ) {
    if (!checkDiskSpace(context, encryptedKey.size.toLong() * 3)) {
        throw KeyFileSystemException("Insufficient disk space to write key file")
    }

    try {
        backupExistingKeyFile(context, keyFile)

        context.openFileOutput(KEY_FILE_NAME, Context.MODE_PRIVATE).use { output ->
            output.write(encryptedKey)
        }
        Log.i(TAG, "Key file written successfully using Android API")

    } catch (e: Exception) {
        Log.e(TAG, "Android API write failed, attempting direct file access", e)
        writeViaDirectFileAccess(keyFile, backupFile, encryptedKey, context)
    }
    }

    /**
 * 备份既有密钥文件：读取成功才写备份；
 * 备份失败仅记录，不中断主写入。
 */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun backupExistingKeyFile(context: Context, keyFile: File) {
    if (!keyFile.exists()) return

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

    /**
 * 直写回退路径：临时文件 + fsync + 原子改名；
 * 改名失败时用既有备份恢复，最终失败翻译为 IOException。
 */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 失败翻译领域错误重抛
    fun writeViaDirectFileAccess(
    keyFile: File,
    backupFile: File,
    encryptedKey: ByteArray,
    context: Context
    ) {
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
