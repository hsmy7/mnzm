package com.xianxia.sect.data.crypto

import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class CachedKey(
    val key: ByteArray,
    val createdAt: Long,
    val salt: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CachedKey) return false
        return key.contentEquals(other.key)
    }

    override fun hashCode(): Int = key.contentHashCode()
}

data class KeyCacheStats(
    val cacheHits: Long,
    val cacheMisses: Long,
    val cacheSize: Int,
    val evictions: Long
)

object CachedSaveCrypto {
    private const val TAG = "CachedSaveCrypto"
    
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val KEY_SIZE = 256
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HMAC_LENGTH = 32
    private const val SALT_LENGTH = 32
    private const val PBKDF2_ITERATIONS = 100000
    private const val LEGACY_PBKDF2_ITERATIONS = 10000
    private const val VERY_LEGACY_PBKDF2_ITERATIONS = 310000
    
    private const val DEFAULT_CACHE_DURATION_MS = 300_000L
    private const val MAX_CACHE_SIZE = 10
    
    private val secureRandom = SecureRandom()
    
    private val keyCache = ConcurrentHashMap<String, CachedKey>()
    private var cacheDurationMs = DEFAULT_CACHE_DURATION_MS
    private var cacheEnabled = true
    
    private var cacheHits = 0L
    private var cacheMisses = 0L
    private var evictions = 0L
    
    @Volatile
    private var lastCleanupTime = System.currentTimeMillis()
    private const val CLEANUP_INTERVAL_MS = 60_000L

    fun setCacheEnabled(enabled: Boolean) {
        cacheEnabled = enabled
        if (!enabled) {
            clearCache()
        }
        Log.i(TAG, "Key cache ${if (enabled) "enabled" else "disabled"}")
    }

    fun setCacheDuration(durationMs: Long) {
        cacheDurationMs = durationMs.coerceIn(60_000L, 3_600_000L)
        Log.i(TAG, "Key cache duration set to $cacheDurationMs ms")
    }

    fun encrypt(data: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        
        val key = getOrDeriveKey(password, salt)
        return encryptInternal(data, key, salt, iv)
    }

    fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val derivedKey = deriveKeyFromKey(key, salt)
        return encryptInternal(data, derivedKey, salt, iv)
    }

    fun decrypt(data: ByteArray, password: String): ByteArray? {
        if (data.size < SALT_LENGTH + GCM_IV_LENGTH + HMAC_LENGTH + 1) {
            Log.e(TAG, "Data too short for decryption")
            return null
        }

        val result = tryDecryptWithFormat(data, password, SALT_LENGTH, PBKDF2_ITERATIONS)
        if (result != null) {
            Log.d(TAG, "Decrypted with new format (iterations=$PBKDF2_ITERATIONS)")
            return result
        }

        val veryLegacyResult = tryDecryptWithFormat(data, password, SALT_LENGTH, VERY_LEGACY_PBKDF2_ITERATIONS)
        if (veryLegacyResult != null) {
            Log.d(TAG, "Decrypted with very legacy format (iterations=$VERY_LEGACY_PBKDF2_ITERATIONS)")
            return veryLegacyResult
        }

        val legacyResult = tryDecryptWithFormat(data, password, LEGACY_SALT_LENGTH, LEGACY_PBKDF2_ITERATIONS)
        if (legacyResult != null) {
            Log.d(TAG, "Decrypted with legacy format (iterations=$LEGACY_PBKDF2_ITERATIONS)")
            return legacyResult
        }

        Log.e(TAG, "Decryption failed with all formats")
        return null
    }

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.size < LEGACY_SALT_LENGTH + GCM_IV_LENGTH + HMAC_LENGTH + 1) {
            Log.e(TAG, "Data too short for decryption")
            return null
        }

        val result = tryDecryptWithKeyFormat(data, key, SALT_LENGTH)
        if (result != null) {
            return result
        }

        val legacyResult = tryDecryptWithKeyFormat(data, key, LEGACY_SALT_LENGTH)
        if (legacyResult != null) {
            return legacyResult
        }

        Log.e(TAG, "Decryption failed with both new and legacy formats")
        return null
    }

    private fun getOrDeriveKey(password: String, salt: ByteArray): ByteArray {
        if (!cacheEnabled) {
            cacheMisses++
            return deriveKeyWithIterations(password, salt, PBKDF2_ITERATIONS)
        }

        maybeCleanup()

        val cacheKey = computeCacheKey(password, salt)
        
        val cached = keyCache[cacheKey]
        if (cached != null) {
            val now = System.currentTimeMillis()
            if (now - cached.createdAt < cacheDurationMs && salt.contentEquals(cached.salt)) {
                cacheHits++
                Log.d(TAG, "Key cache hit for password-based key")
                return cached.key
            } else {
                keyCache.remove(cacheKey)
                evictions++
            }
        }

        cacheMisses++
        val derivedKey = deriveKeyWithIterations(password, salt, PBKDF2_ITERATIONS)
        
        if (keyCache.size >= MAX_CACHE_SIZE) {
            evictOldest()
        }
        
        keyCache[cacheKey] = CachedKey(
            key = derivedKey,
            createdAt = System.currentTimeMillis(),
            salt = salt.copyOf()
        )
        
        Log.d(TAG, "Key cache miss, derived and cached new key. Cache size: ${keyCache.size}")
        return derivedKey
    }

    private fun computeCacheKey(password: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(password.toByteArray(Charsets.UTF_8))
        digest.update(salt)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun maybeCleanup() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MS) {
            cleanupExpired()
            lastCleanupTime = now
        }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expiredKeys = keyCache.entries
            .filter { now - it.value.createdAt > cacheDurationMs }
            .map { it.key }
        
        expiredKeys.forEach { key ->
            keyCache.remove(key)
            evictions++
        }
        
        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expiredKeys.size} expired cache entries")
        }
    }

    private fun evictOldest() {
        val oldest = keyCache.entries.minByOrNull { it.value.createdAt }
        if (oldest != null) {
            keyCache.remove(oldest.key)
            evictions++
            Log.d(TAG, "Evicted oldest cache entry")
        }
    }

    private fun deriveKeyFromKey(key: ByteArray, salt: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(salt).copyOf(KEY_SIZE / 8)
    }

    private fun encryptInternal(data: ByteArray, key: ByteArray, salt: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        
        val encrypted = cipher.doFinal(data)
        
        val result = ByteArray(SALT_LENGTH + GCM_IV_LENGTH + encrypted.size + HMAC_LENGTH)
        System.arraycopy(salt, 0, result, 0, SALT_LENGTH)
        System.arraycopy(iv, 0, result, SALT_LENGTH, GCM_IV_LENGTH)
        System.arraycopy(encrypted, 0, result, SALT_LENGTH + GCM_IV_LENGTH, encrypted.size)
        
        val signature = computeHmac(result.copyOfRange(0, result.size - HMAC_LENGTH), key)
        System.arraycopy(signature, 0, result, result.size - HMAC_LENGTH, HMAC_LENGTH)
        
        return result
    }

    private const val LEGACY_SALT_LENGTH = 16

    private fun tryDecryptWithFormat(
        data: ByteArray, 
        password: String, 
        saltLength: Int,
        iterations: Int
    ): ByteArray? {
        return try {
            if (data.size < saltLength + GCM_IV_LENGTH + HMAC_LENGTH + 1) {
                return null
            }
            
            val salt = data.copyOfRange(0, saltLength)
            val iv = data.copyOfRange(saltLength, saltLength + GCM_IV_LENGTH)
            val encrypted = data.copyOfRange(saltLength + GCM_IV_LENGTH, data.size - HMAC_LENGTH)
            val storedSignature = data.copyOfRange(data.size - HMAC_LENGTH, data.size)
            
            val key = if (cacheEnabled && iterations == PBKDF2_ITERATIONS) {
                getOrDeriveKey(password, salt)
            } else {
                deriveKeyWithIterations(password, salt, iterations)
            }
            
            val computedSignature = computeHmac(data.copyOfRange(0, data.size - HMAC_LENGTH), key)
            if (!storedSignature.contentEquals(computedSignature)) {
                return null
            }
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = SecretKeySpec(key, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryDecryptWithKeyFormat(data: ByteArray, key: ByteArray, saltLength: Int): ByteArray? {
        return try {
            if (data.size < saltLength + GCM_IV_LENGTH + HMAC_LENGTH + 1) {
                return null
            }
            
            val salt = data.copyOfRange(0, saltLength)
            val iv = data.copyOfRange(saltLength, saltLength + GCM_IV_LENGTH)
            val encrypted = data.copyOfRange(saltLength + GCM_IV_LENGTH, data.size - HMAC_LENGTH)
            val storedSignature = data.copyOfRange(data.size - HMAC_LENGTH, data.size)
            
            val derivedKey = deriveKeyFromKey(key, salt)
            
            val computedSignature = computeHmac(data.copyOfRange(0, data.size - HMAC_LENGTH), derivedKey)
            if (!storedSignature.contentEquals(computedSignature)) {
                return null
            }
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = SecretKeySpec(derivedKey, KEY_ALGORITHM)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    private fun deriveKeyWithIterations(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_SIZE)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun computeHmac(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    fun clearCache() {
        val size = keyCache.size
        keyCache.clear()
        Log.i(TAG, "Cleared key cache ($size entries)")
    }

    fun getCacheStats(): KeyCacheStats {
        return KeyCacheStats(
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            cacheSize = keyCache.size,
            evictions = evictions
        )
    }

    fun getCacheHitRate(): Float {
        val total = cacheHits + cacheMisses
        return if (total > 0) cacheHits.toFloat() / total else 0f
    }

    fun resetStats() {
        cacheHits = 0
        cacheMisses = 0
        evictions = 0
    }

    fun preloadKey(password: String, salt: ByteArray) {
        if (!cacheEnabled) return
        
        val cacheKey = computeCacheKey(password, salt)
        if (!keyCache.containsKey(cacheKey)) {
            val derivedKey = deriveKeyWithIterations(password, salt, PBKDF2_ITERATIONS)
            
            if (keyCache.size >= MAX_CACHE_SIZE) {
                evictOldest()
            }
            
            keyCache[cacheKey] = CachedKey(
                key = derivedKey,
                createdAt = System.currentTimeMillis(),
                salt = salt.copyOf()
            )
            
            Log.d(TAG, "Preloaded key into cache. Cache size: ${keyCache.size}")
        }
    }
}
