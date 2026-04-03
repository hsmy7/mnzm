package com.xianxia.sect.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.KeyStore
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object SecureKeyManagerV2 {
    private const val TAG = "SecureKeyManagerV2"
    private const val KEY_ALIAS = "xianxia_save_key_v2"
    private const val KEY_ALIAS_LEGACY = "xianxia_save_key_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_SIZE = 256
    private const val KEY_ROTATION_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
    private const val METADATA_FILE = "key_metadata.bin"
    
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
    
    private var _cachedKey: SecretKey? = null
    private val keyLock = Mutex()
    private var metadata: KeyMetadata? = null
    
    data class KeyMetadata(
        val createdAt: Long,
        val version: Int,
        val rotatedAt: Long = 0,
        val rotationCount: Int = 0
    )
    
    suspend fun getOrCreateKey(context: Context): SecretKey {
        _cachedKey?.let { return it }
        
        return keyLock.withLock {
            _cachedKey ?: createOrRotateKey(context).also { _cachedKey = it }
        }
    }
    
    fun getOrCreateKeySync(context: Context): SecretKey {
        _cachedKey?.let { return it }
        
        val key = tryGetKeystoreKey() ?: migrateFromLegacyKey(context)
        _cachedKey = key
        return key
    }
    
    private suspend fun createOrRotateKey(context: Context): SecretKey {
        val meta = loadMetadata(context)
        
        if (meta != null && shouldRotateKey(meta)) {
            Log.i(TAG, "Rotating key after ${meta.rotationCount + 1} rotations")
            return rotateKey(context, meta)
        }
        
        val existingKey = tryGetKeystoreKey()
        if (existingKey != null) {
            if (meta == null) {
                saveMetadata(context, KeyMetadata(
                    createdAt = System.currentTimeMillis(),
                    version = 2
                ))
            }
            return existingKey
        }
        
        return migrateFromLegacyKey(context)
    }
    
    private fun shouldRotateKey(metadata: KeyMetadata): Boolean {
        val now = System.currentTimeMillis()
        val age = now - metadata.createdAt
        val lastRotation = if (metadata.rotatedAt > 0) metadata.rotatedAt else metadata.createdAt
        
        return age > KEY_ROTATION_INTERVAL_MS && (now - lastRotation) > KEY_ROTATION_INTERVAL_MS
    }
    
    private suspend fun rotateKey(context: Context, oldMetadata: KeyMetadata): SecretKey {
        val newKey = generateNewKey()
        
        saveMetadata(context, KeyMetadata(
            createdAt = oldMetadata.createdAt,
            version = 2,
            rotatedAt = System.currentTimeMillis(),
            rotationCount = oldMetadata.rotationCount + 1
        ))
        
        Log.i(TAG, "Key rotation completed, rotation count: ${oldMetadata.rotationCount + 1}")
        return newKey
    }
    
    private fun generateNewKey(): SecretKey {
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            
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
                .setRandomizedEncryptionRequired(false)
                .build()
            
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate new key", e)
            throw SecurityException("Key generation failed: ${e.message}")
        }
    }
    
    private fun tryGetKeystoreKey(): SecretKey? {
        return try {
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                return null
            }
            
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Keystore key", e)
            null
        }
    }
    
    private fun migrateFromLegacyKey(context: Context): SecretKey {
        Log.i(TAG, "Migrating from legacy key to new key")
        
        val legacyKey = tryGetLegacyKeystoreKey()
        if (legacyKey != null) {
            Log.i(TAG, "Found legacy Keystore key, creating new key")
            return generateNewKey()
        }
        
        val legacyDerivedKey = SecureKeyManager.getOrCreateKey(context)
        val secretKey = SecretKeySpec(legacyDerivedKey, "AES")
        
        try {
            val newKey = generateNewKey()
            Log.i(TAG, "Created new Keystore key, legacy key will be used for decryption of old saves")
            return newKey
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create new key, using legacy derived key", e)
            return secretKey
        }
    }
    
    private fun tryGetLegacyKeystoreKey(): SecretKey? {
        return try {
            if (!keyStore.containsAlias(KEY_ALIAS_LEGACY)) {
                return null
            }
            
            val entry = keyStore.getEntry(KEY_ALIAS_LEGACY, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get legacy Keystore key", e)
            null
        }
    }
    
    fun getLegacyKey(context: Context): ByteArray? {
        return try {
            SecureKeyManager.getOrCreateKey(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get legacy key", e)
            null
        }
    }
    
    private fun loadMetadata(context: Context): KeyMetadata? {
        if (metadata != null) return metadata
        
        val metaFile = File(context.filesDir, METADATA_FILE)
        if (!metaFile.exists()) return null
        
        return try {
            val bytes = metaFile.readBytes()
            if (bytes.size >= 32) {
                val createdAt = bytes.copyOfRange(0, 8).let { 
                    java.nio.ByteBuffer.wrap(it).long 
                }
                val version = bytes.copyOfRange(8, 12).let { 
                    java.nio.ByteBuffer.wrap(it).int 
                }
                val rotatedAt = bytes.copyOfRange(12, 20).let { 
                    java.nio.ByteBuffer.wrap(it).long 
                }
                val rotationCount = bytes.copyOfRange(20, 24).let { 
                    java.nio.ByteBuffer.wrap(it).int 
                }
                
                KeyMetadata(createdAt, version, rotatedAt, rotationCount).also { 
                    metadata = it 
                }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load key metadata", e)
            null
        }
    }
    
    private fun saveMetadata(context: Context, meta: KeyMetadata) {
        metadata = meta
        
        val metaFile = File(context.filesDir, METADATA_FILE)
        try {
            val buffer = java.nio.ByteBuffer.allocate(32)
            buffer.putLong(meta.createdAt)
            buffer.putInt(meta.version)
            buffer.putLong(meta.rotatedAt)
            buffer.putInt(meta.rotationCount)
            
            metaFile.writeBytes(buffer.array())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save key metadata", e)
        }
    }
    
    fun clearCache() {
        _cachedKey = null
        metadata = null
    }
    
    fun hasKey(): Boolean = _cachedKey != null
    
    fun getKeyInfo(context: Context): KeyInfo? {
        val meta = loadMetadata(context) ?: return null
        
        return KeyInfo(
            version = meta.version,
            createdAt = meta.createdAt,
            rotationCount = meta.rotationCount,
            lastRotatedAt = if (meta.rotatedAt > 0) meta.rotatedAt else meta.createdAt,
            needsRotation = shouldRotateKey(meta)
        )
    }
    
    data class KeyInfo(
        val version: Int,
        val createdAt: Long,
        val rotationCount: Int,
        val lastRotatedAt: Long,
        val needsRotation: Boolean
    )
}
