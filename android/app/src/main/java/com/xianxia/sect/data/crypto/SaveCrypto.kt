package com.xianxia.sect.data.crypto

import android.util.Log
import com.xianxia.sect.data.config.StorageConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SaveCrypto {
    private const val TAG = "SaveCrypto"
    
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
    private const val HKDF_INFO_DEFAULT = "xianxia.save.key.v2"
    
    private val secureRandom = SecureRandom()
    
    /**
     * 密钥派生结果缓存
     * 基于 password+salt 的组合键，TTL 由 StorageConfig.keyCacheDurationMs 控制（默认5分钟）
     */
    private val derivedKeyCache = ConcurrentHashMap<String, CachedDerivedKey>()
    
    private data class CachedDerivedKey(
        val key: ByteArray,
        val createdAt: Long,
        val salt: ByteArray
    )
    
    fun encrypt(data: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        
        val key = deriveKey(password, salt)
        return encryptInternal(data, key, salt, iv)
    }
    
    fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val derivedKey = deriveKeyFromKey(key, salt)
        return encryptInternal(data, derivedKey, salt, iv)
    }
    
    private fun deriveKeyFromKey(key: ByteArray, salt: ByteArray): ByteArray {
        return deriveKeyHKDF(key, salt, HKDF_INFO_DEFAULT.toByteArray(Charsets.UTF_8))
    }
    
    internal fun deriveKeyHKDF(
        masterKey: ByteArray,
        salt: ByteArray,
        info: ByteArray? = null
    ): ByteArray {
        return try {
            val effectiveInfo = info ?: HKDF_INFO_DEFAULT.toByteArray(Charsets.UTF_8)
            
            val extractMac = Mac.getInstance(HMAC_ALGORITHM)
            if (salt.isNotEmpty()) {
                extractMac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
            } else {
                extractMac.init(SecretKeySpec(ByteArray(32), HMAC_ALGORITHM))
            }
            val prk = extractMac.doFinal(masterKey)
            
            val expandMac = Mac.getInstance(HMAC_ALGORITHM)
            expandMac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
            
            val infoWithCounter = effectiveInfo + byteArrayOf(0x01.toByte())
            val okm = expandMac.doFinal(infoWithCounter)
            
            okm.copyOf(KEY_SIZE / 8)
        } catch (e: Exception) {
            Log.e(TAG, "HKDF derivation failed, falling back to legacy method", e)
            deriveKeyLegacyHmac(masterKey, salt)
        }
    }
    
    private fun deriveKeyLegacyHmac(key: ByteArray, salt: ByteArray): ByteArray {
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
    
    fun decrypt(data: ByteArray, password: String): ByteArray? {
        if (data.size < LEGACY_SALT_LENGTH + GCM_IV_LENGTH + HMAC_LENGTH + 1) {
            Log.e(TAG, "Data too short for decryption")
            return null
        }
        
        val result = tryDecryptWithFormat(data, password, SALT_LENGTH, PBKDF2_ITERATIONS)
        if (result != null) {
            Log.d(TAG, "Decrypted with new format (salt=$SALT_LENGTH, iterations=$PBKDF2_ITERATIONS)")
            return result
        }
        
        val veryLegacyResult = tryDecryptWithFormat(data, password, SALT_LENGTH, VERY_LEGACY_PBKDF2_ITERATIONS)
        if (veryLegacyResult != null) {
            Log.d(TAG, "Decrypted with very legacy format (salt=$SALT_LENGTH, iterations=$VERY_LEGACY_PBKDF2_ITERATIONS)")
            return veryLegacyResult
        }
        
        val legacyResult = tryDecryptWithFormat(data, password, LEGACY_SALT_LENGTH, LEGACY_PBKDF2_ITERATIONS)
        if (legacyResult != null) {
            Log.d(TAG, "Decrypted with legacy format (salt=$LEGACY_SALT_LENGTH, iterations=$LEGACY_PBKDF2_ITERATIONS)")
            return legacyResult
        }
        
        Log.e(TAG, "Decryption failed with all formats")
        return null
    }
    
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
            
            val key = deriveKeyWithIterations(password, salt, iterations)
            
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
    
    private fun deriveKeyWithIterations(password: String, salt: ByteArray, iterations: Int): ByteArray {
        // 对于非标准迭代次数（legacy 格式），不使用缓存以避免复杂性
        if (iterations != PBKDF2_ITERATIONS) {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_SIZE)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        }
        
        // 标准迭代次数，尝试缓存
        val cacheKey = buildCacheKey(password, salt)
        val cached = getFromCache(cacheKey)
        if (cached != null) {
            Log.d(TAG, "Derived key cache hit for iterations=$iterations")
            return cached
        }
        
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_SIZE)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        
        putToCache(cacheKey, key, salt)
        
        return key
    }
    
    fun decrypt(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.size < LEGACY_SALT_LENGTH + GCM_IV_LENGTH + HMAC_LENGTH + 1) {
            Log.e(TAG, "Data too short for decryption")
            return null
        }
        
        val result = tryDecryptWithKeyFormat(data, key, SALT_LENGTH)
        if (result != null) {
            Log.d(TAG, "Decrypted with new format (salt=$SALT_LENGTH)")
            return result
        }
        
        val legacyResult = tryDecryptWithKeyFormat(data, key, LEGACY_SALT_LENGTH)
        if (legacyResult != null) {
            Log.d(TAG, "Decrypted with legacy format (salt=$LEGACY_SALT_LENGTH)")
            return legacyResult
        }
        
        Log.e(TAG, "Decryption failed with both new and legacy formats")
        return null
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
    
    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        // 尝试从缓存获取
        val cacheKey = buildCacheKey(password, salt)
        val cached = getFromCache(cacheKey)
        if (cached != null) {
            Log.d(TAG, "Derived key cache hit for password-based derivation")
            return cached
        }
        
        // 缓存未命中，执行 PBKDF2 派生
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        
        // 存入缓存
        putToCache(cacheKey, key, salt)
        
        return key
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
    
    fun sha256Hex(data: ByteArray): String {
        return sha256(data).joinToString("") { "%02x".format(it) }
    }
    
    fun generateChecksum(data: ByteArray): ByteArray {
        return sha256(data)
    }
    
    fun verifyChecksum(data: ByteArray, expectedChecksum: ByteArray): Boolean {
        val actualChecksum = sha256(data)
        return actualChecksum.contentEquals(expectedChecksum)
    }
    
    fun embedChecksum(data: ByteArray): ByteArray {
        val checksum = sha256(data)
        val result = ByteArray(checksum.size + data.size)
        System.arraycopy(checksum, 0, result, 0, checksum.size)
        System.arraycopy(data, 0, result, checksum.size, data.size)
        return result
    }
    
    fun extractChecksumAndData(embeddedData: ByteArray): Pair<ByteArray, ByteArray>? {
        if (embeddedData.size < 32) return null
        val checksum = embeddedData.copyOfRange(0, 32)
        val data = embeddedData.copyOfRange(32, embeddedData.size)
        return Pair(checksum, data)
    }
    
    fun verifyEmbeddedChecksum(embeddedData: ByteArray): Boolean {
        val (storedChecksum, data) = extractChecksumAndData(embeddedData) ?: return false
        val actualChecksum = sha256(data)
        return storedChecksum.contentEquals(actualChecksum)
    }
    
    // ==================== 异步加密/解密方法（推荐使用） ====================
    
    /**
     * 异步加密 - 使用密码
     * 将 PBKDF2 密钥派生操作移至 Dispatchers.Default，避免阻塞主线程
     */
    suspend fun encryptAsync(data: ByteArray, password: String): ByteArray = withContext(Dispatchers.Default) {
        encrypt(data, password)
    }
    
    /**
     * 异步解密 - 使用密码
     */
    suspend fun decryptAsync(data: ByteArray, password: String): ByteArray? = withContext(Dispatchers.Default) {
        decrypt(data, password)
    }
    
    /**
     * 异步加密 - 使用密钥
     */
    suspend fun encryptAsync(data: ByteArray, key: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        encrypt(data, key)
    }
    
    /**
     * 异步解密 - 使用密钥
     */
    suspend fun decryptAsync(data: ByteArray, key: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        decrypt(data, key)
    }
    
    // ==================== 密钥派生缓存管理 ====================
    
    /**
     * 构建缓存键：基于 password + salt 的哈希
     */
    private fun buildCacheKey(password: String, salt: ByteArray): String {
        val passwordHash = sha256Hex(password.toByteArray())
        val saltHash = sha256Hex(salt)
        return "${passwordHash}_${saltHash}"
    }
    
    /**
     * 从缓存获取派生密钥
     * @return 缓存的密钥副本，如果不存在或已过期则返回 null
     */
    private fun getFromCache(cacheKey: String): ByteArray? {
        val cached = derivedKeyCache[cacheKey] ?: return null
        
        // 检查是否过期
        val ttlMs = StorageConfig.DEFAULT_KEY_CACHE_DURATION_MS
        if (System.currentTimeMillis() - cached.createdAt > ttlMs) {
            derivedKeyCache.remove(cacheKey)
            Log.d(TAG, "Cache entry expired: $cacheKey")
            return null
        }
        
        // 返回密钥的副本以确保安全性
        return cached.key.copyOf()
    }
    
    /**
     * 将派生存入缓存
     */
    private fun putToCache(cacheKey: String, key: ByteArray, salt: ByteArray) {
        val maxCacheSize = 50
        if (derivedKeyCache.size >= maxCacheSize) {
            clearExpiredCacheEntries()
            if (derivedKeyCache.size >= maxCacheSize) {
                aggressiveCacheEviction(maxCacheSize / 2)
            }
        }
        
        derivedKeyCache[cacheKey] = CachedDerivedKey(
            key = key.copyOf(),
            createdAt = System.currentTimeMillis(),
            salt = salt.copyOf()
        )
    }
    
    private fun aggressiveCacheEviction(targetSize: Int) {
        val entriesToRemove = derivedKeyCache.size - targetSize
        if (entriesToRemove <= 0) return
        
        val sortedEntries = derivedKeyCache.entries
            .sortedBy { it.value.createdAt }
        
        for (i in 0 until minOf(entriesToRemove, sortedEntries.size)) {
            val entry = sortedEntries[i]
            entry.value.key.fill(0)
            entry.value.salt.fill(0)
            derivedKeyCache.remove(entry.key)
        }
        
        Log.d(TAG, "Aggressive cache eviction: removed $entriesToRemove entries")
    }
    
    /**
     * 清除所有缓存的派生密钥
     */
    fun clearDerivedKeyCache() {
        derivedKeyCache.clear()
        Log.d(TAG, "Derived key cache cleared")
    }
    
    /**
     * 获取缓存大小（用于监控）
     */
    fun getDerivedKeyCacheSize(): Int = derivedKeyCache.size
    
    /**
     * 清理过期的缓存条目
     */
    fun clearExpiredCacheEntries(ttlMs: Long = StorageConfig.DEFAULT_KEY_CACHE_DURATION_MS) {
        val now = System.currentTimeMillis()
        val expiredKeys = derivedKeyCache.filter { 
            now - it.value.createdAt > ttlMs 
        }.keys
        
        expiredKeys.forEach { key ->
            derivedKeyCache.remove(key)
        }
        
        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "Cleared ${expiredKeys.size} expired cache entries")
        }
    }
}
