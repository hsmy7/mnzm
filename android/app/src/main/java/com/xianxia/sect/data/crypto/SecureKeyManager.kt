package com.xianxia.sect.data.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.security.*
import java.security.spec.*
import javax.crypto.*
import javax.crypto.spec.*

object SecureKeyManager {
    private const val TAG = "SecureKeyManager"
    
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "xianxia_sect_save_key"
    private const val KEY_ALIAS_LEGACY = "xianxia_sect_legacy_key"
    private const val PREFS_NAME = "secure_key_prefs"
    private const val KEY_PREF_KEY = "derived_key_hash"
    
    private const val KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 310000
    private const val SALT_LENGTH = 32
    
    private var cachedKey: ByteArray? = null
    private var keyLastAccess: Long = 0
    private const val KEY_CACHE_TTL = 5 * 60 * 1000L
    
    private val keyLock = Any()
    
    fun getOrCreateKey(context: Context): ByteArray {
        synchronized(keyLock) {
            if (cachedKey != null && System.currentTimeMillis() - keyLastAccess < KEY_CACHE_TTL) {
                keyLastAccess = System.currentTimeMillis()
                return cachedKey!!
            }
            
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val key = getOrCreateKeystoreKey(context)
                    cachedKey = key
                    keyLastAccess = System.currentTimeMillis()
                    key
                } else {
                    val key = getOrCreateDerivedKey(context)
                    cachedKey = key
                    keyLastAccess = System.currentTimeMillis()
                    key
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get key from Keystore, falling back to derived key", e)
                val key = getOrCreateDerivedKey(context)
                cachedKey = key
                keyLastAccess = System.currentTimeMillis()
                key
            }
        }
    }
    
    private fun getOrCreateKeystoreKey(context: Context): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            createKeystoreKey()
        }
        
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw KeyStoreException("Failed to get key entry")
        
        return entry.secretKey.encoded ?: deriveKeyFromSecretKey(entry.secretKey, context)
    }
    
    private fun createKeystoreKey() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .setRandomizedEncryptionRequired(true)
                .setKeyValidityStart(java.util.Date(System.currentTimeMillis() - 1000))
                .setKeyValidityForOriginationEnd(
                    java.util.Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
                )
                .setUserAuthenticationRequired(false)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setUnlockedDeviceRequired(true)
                    }
                }
                .build()
            
            keyGenerator.init(spec)
            keyGenerator.generateKey()
            
            Log.i(TAG, "Created new Keystore key: $KEY_ALIAS")
        }
    }
    
    private fun deriveKeyFromSecretKey(secretKey: SecretKey, context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedHash = prefs.getString(KEY_PREF_KEY, null)
        
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)
        
        val password = secretKey.encoded?.let { 
            String(it, Charsets.ISO_8859_1) 
        } ?: randomString(32)
        
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        
        val hash = MessageDigest.getInstance("SHA-256").digest(key)
        prefs.edit().putString(KEY_PREF_KEY, hash.joinToString("") { "%02x".format(it) }).apply()
        
        return key
    }
    
    private fun getOrCreateDerivedKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keyFile = File(context.filesDir, ".secure_key")
        
        if (keyFile.exists()) {
            try {
                val encryptedKey = keyFile.readBytes()
                val deviceSecret = getDeviceSecret(context)
                return decryptKey(encryptedKey, deviceSecret)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read existing key, generating new one", e)
            }
        }
        
        val key = ByteArray(KEY_SIZE / 8).also { SecureRandom().nextBytes(it) }
        val deviceSecret = getDeviceSecret(context)
        val encryptedKey = encryptKey(key, deviceSecret)
        
        keyFile.writeBytes(encryptedKey)
        keyFile.setReadable(false, false)
        keyFile.setWritable(false, false)
        
        val hash = MessageDigest.getInstance("SHA-256").digest(key)
        prefs.edit().putString(KEY_PREF_KEY, hash.joinToString("") { "%02x".format(it) }).apply()
        
        Log.i(TAG, "Generated new derived key")
        return key
    }
    
    private fun getDeviceSecret(context: Context): ByteArray {
        val deviceId = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "default_device_id"
        } catch (e: Exception) {
            "default_device_id"
        }
        
        val appSignature = try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNATURES
            )
            packageInfo.signatures?.firstOrNull()?.toCharsString() ?: "default_signature"
        } catch (e: Exception) {
            "default_signature"
        }
        
        val combined = "$deviceId:$appSignature:${context.packageName}"
        return MessageDigest.getInstance("SHA-256").digest(combined.toByteArray(Charsets.UTF_8))
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
            
            val oldKey = cachedKey
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                    keyStore.load(null)
                    
                    if (keyStore.containsAlias(KEY_ALIAS)) {
                        keyStore.deleteEntry(KEY_ALIAS)
                    }
                    
                    createKeystoreKey()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to rotate Keystore key", e)
                }
            }
            
            val keyFile = File(context.filesDir, ".secure_key")
            if (keyFile.exists()) {
                keyFile.delete()
            }
            
            val newKey = getOrCreateKey(context)
            
            Log.i(TAG, "Key rotation completed")
            return newKey
        }
    }
    
    fun clearCachedKey() {
        synchronized(keyLock) {
            cachedKey?.fill(0)
            cachedKey = null
            keyLastAccess = 0
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
    
    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars[SecureRandom().nextInt(chars.length)] }
            .joinToString("")
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
            
            val oldKey = SecureKeyManager.getOrCreateKey(context)
            
            val slots = (1..5).filter { slot ->
                saveRepository.hasSave(slot)
            }
            
            val decryptedData = mutableMapOf<Int, ByteArray>()
            
            for (slot in slots) {
                val result = saveRepository.load(slot)
                if (result.isSuccess) {
                    val data = result.getOrThrow()
                    val json = com.xianxia.sect.data.GsonConfig.createGson().toJson(data)
                    decryptedData[slot] = json.toByteArray(Charsets.UTF_8)
                }
            }
            
            SecureKeyManager.rotateKey(context)
            
            for ((slot, data) in decryptedData) {
                val saveData = com.xianxia.sect.data.GsonConfig.createGson()
                    .fromJson(String(data, Charsets.UTF_8), com.xianxia.sect.data.model.SaveData::class.java)
                
                val saveResult = saveRepository.save(slot, saveData)
                if (saveResult.isFailure) {
                    Log.e(TAG, "Failed to re-encrypt slot $slot after key rotation")
                    return@withContext com.xianxia.sect.data.unified.SaveResult.failure(
                        com.xianxia.sect.data.unified.SaveError.ENCRYPTION_ERROR,
                        "Failed to re-encrypt slot $slot"
                    )
                }
                
                data.fill(0)
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
