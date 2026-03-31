package com.xianxia.sect.data.crypto

import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
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
    private const val PBKDF2_ITERATIONS = 310000
    private const val LEGACY_PBKDF2_ITERATIONS = 10000
    
    private val secureRandom = SecureRandom()
    
    fun encrypt(data: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        
        val key = deriveKey(password, salt)
        return encryptInternal(data, key, salt, iv)
    }
    
    fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        return encryptInternal(data, key.copyOf(KEY_SIZE / 8), salt, iv)
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
        
        val legacyResult = tryDecryptWithFormat(data, password, LEGACY_SALT_LENGTH, LEGACY_PBKDF2_ITERATIONS)
        if (legacyResult != null) {
            Log.d(TAG, "Decrypted with legacy format (salt=$LEGACY_SALT_LENGTH, iterations=$LEGACY_PBKDF2_ITERATIONS)")
            return legacyResult
        }
        
        Log.e(TAG, "Decryption failed with both new and legacy formats")
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
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_SIZE)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
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
            
            val derivedKey = key.copyOf(KEY_SIZE / 8)
            
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
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_SIZE)
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
}
