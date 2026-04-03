package com.xianxia.sect.data.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.security.*
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.*
import javax.crypto.spec.*

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
    
    private const val PREFS_NAME = "secure_key_prefs"
    private const val KEY_PREF_KEY = "derived_key_hash"
    private const val ACCOUNT_ANCHOR_KEY = "account_anchor_id"
    private const val FALLBACK_ID_KEY = "fallback_account_id"
    private const val LEGACY_FINGERPRINT_KEY = "legacy_device_fingerprint"
    
    private const val KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 32
    
    private const val KEY_FILE_NAME = ".secure_key"
    private const val BACKUP_FILE_NAME = ".secure_key.bak"
    private const val TEMP_FILE_NAME = ".secure_key.tmp"
    
    private const val MIN_DISK_SPACE_BYTES = 64 * 1024L
    
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
            } catch (e: KeyIntegrityException) {
                throw e
            } catch (e: KeyPermissionException) {
                throw e
            } catch (e: KeyFileSystemException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read existing key, attempting recovery from backup", e)
                val recoveredKey = tryRecoverFromBackup(context, backupFile, prefs)
                if (recoveredKey != null) {
                    return recoveredKey
                }
                throw KeyIntegrityException(
                    "Failed to read key file and no valid backup found. " +
                    "Manual recovery may be required. Original error: ${e.message}",
                    e
                )
            }
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
        
        Log.i(TAG, "Generated and stored new derived key")
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                generateKeyStoreSecret(context)
            } else {
                generateSecureRandomSecret(context)
            }
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
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
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
            parts.add(Build.BOARD)
            parts.add(Build.BRAND)
            parts.add(Build.DEVICE)
            parts.add(Build.HARDWARE)
            parts.add(Build.MODEL)
            parts.add(Build.PRODUCT)
            parts.add(Build.DISPLAY)
            parts.add(Build.FINGERPRINT)
            parts.add(Build.TYPE)
            parts.add(Build.TAGS)
            parts.add(Build.USER)
        } catch (_: Exception) {}
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                parts.add(Build.getSerial() ?: "no_serial")
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
        
        val result = ByteArray(GCM_IV_LENGTH + encrypted.size)
        System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH)
        System.arraycopy(encrypted, 0, result, GCM_IV_LENGTH, encrypted.size)
        
        return result
    }
    
    private fun decryptKey(encryptedKey: ByteArray, secret: ByteArray): ByteArray {
        if (encryptedKey.size < GCM_IV_LENGTH + 16) {
            throw IllegalArgumentException("Invalid encrypted key length")
        }
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKeySpec = SecretKeySpec(secret, "AES")
        val iv = encryptedKey.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = encryptedKey.copyOfRange(GCM_IV_LENGTH, encryptedKey.size)
        
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
                
                val result = Base64.getEncoder().encodeToString(tokenIv + encryptedKey)
                
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
                
                val tokenBytes = Base64.getDecoder().decode(token)
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
    
    fun migrateLegacyKeyIfNeeded(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val legacyFingerprint = prefs.getString(LEGACY_FINGERPRINT_KEY, null)
            
            if (legacyFingerprint != null) {
                Log.i(TAG, "Legacy fingerprint found, migration already completed")
                return true
            }
            
            val currentFingerprint = getDeviceFingerprint(context)
            prefs.edit().putString(LEGACY_FINGERPRINT_KEY, currentFingerprint).apply()
            
            Log.i(TAG, "Legacy migration marker set")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check legacy migration status", e)
            false
        }
    }
}

class KeyRotationManager(
    private val context: Context,
    private val saveRepository: com.xianxia.sect.data.unified.SaveRepository
) {
    companion object {
        private const val TAG = "KeyRotationManager"
        private const val ROTATION_PREFS = "key_rotation_prefs"
        private const val KEY_LAST_ROTATION = "last_rotation"
        private const val ROTATION_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
    }
    
    suspend fun needsRotation(): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(ROTATION_PREFS, Context.MODE_PRIVATE)
        val lastRotation = prefs.getLong(KEY_LAST_ROTATION, 0)
        
        System.currentTimeMillis() - lastRotation > ROTATION_INTERVAL_MS
    }
    
    suspend fun performRotation(): com.xianxia.sect.data.unified.SaveResult<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting key rotation process")

            val slots = (1..5).filter { slot ->
                saveRepository.hasSave(slot)
            }

            SecureKeyManager.rotateKey(context)

            for (slot in slots) {
                val result = saveRepository.load(slot)
                if (!result.isSuccess) {
                    Log.w(TAG, "Failed to load slot $slot during rotation, skipping")
                    continue
                }

                try {
                    val data = result.getOrThrow()
                    val json = com.xianxia.sect.data.GsonConfig.createGson().toJson(data)
                    val encryptedBytes = json.toByteArray(Charsets.UTF_8)

                    val saveResult = saveRepository.save(slot, data)
                    if (saveResult.isFailure) {
                        Log.e(TAG, "Failed to re-encrypt slot $slot after key rotation")
                        return@withContext com.xianxia.sect.data.unified.SaveResult.failure(
                            com.xianxia.sect.data.unified.SaveError.ENCRYPTION_ERROR,
                            "Failed to re-encrypt slot $slot"
                        )
                    }

                    encryptedBytes.fill(0)

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
        }
    }
}
