package com.xianxia.sect.data.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Arrays

class SaveCryptoTest {

    private val testPassword = "MySecurePassword123!"
    private val wrongPassword = "WrongPassword456!"
    private val testKey = ByteArray(32) { (it * 7 + 13).toByte() }

    @Before
    fun setUp() {
        SaveCrypto.clearAllKeyCache()
    }

    @Test
    fun `encrypt with password - roundtrip restores original data`() {
        val original = "Hello, this is sensitive save data!".toByteArray(Charsets.UTF_8)
        val encrypted = SaveCrypto.encrypt(original, testPassword)
        val decrypted = SaveCrypto.decrypt(encrypted, testPassword)
        assertNotNull(decrypted)
        assertTrue(Arrays.equals(original, decrypted))
    }

    @Test
    fun `encrypt with password - wrong password returns null`() {
        val original = "secret content".toByteArray(Charsets.UTF_8)
        val encrypted = SaveCrypto.encrypt(original, testPassword)
        val decrypted = SaveCrypto.decrypt(encrypted, wrongPassword)
        assertNull(decrypted)
    }

    @Test
    fun `encrypt with password - empty byte array can be encrypted and decrypted`() {
        val original = byteArrayOf()
        val encrypted = SaveCrypto.encrypt(original, testPassword)
        val decrypted = SaveCrypto.decrypt(encrypted, testPassword)
        assertNotNull(decrypted)
        assertEquals(0, decrypted!!.size)
    }

    @Test
    fun `encrypt with password - large data 100KB roundtrip works`() {
        val original = ByteArray(100 * 1024) { (it % 256).toByte() }
        val encrypted = SaveCrypto.encrypt(original, testPassword)
        val decrypted = SaveCrypto.decrypt(encrypted, testPassword)
        assertNotNull(decrypted)
        assertTrue(Arrays.equals(original, decrypted))
    }

    @Test
    fun `encrypt with password - encrypted data is larger than original`() {
        val original = "short".toByteArray(Charsets.UTF_8)
        val encrypted = SaveCrypto.encrypt(original, testPassword)
        assertTrue(encrypted.size > original.size)
    }

    @Test
    fun `encrypt with key - roundtrip restores original data`() {
        val original = "data encrypted with raw key".toByteArray(Charsets.UTF_8)
        val encrypted = SaveCrypto.encrypt(original, testKey)
        val decrypted = SaveCrypto.decrypt(encrypted, testKey)
        assertNotNull(decrypted)
        assertTrue(Arrays.equals(original, decrypted))
    }

    @Test
    fun `sha256 - returns 32 bytes`() {
        val data = "test input".toByteArray(Charsets.UTF_8)
        val hash = SaveCrypto.sha256(data)
        assertEquals(32, hash.size)
    }

    @Test
    fun `sha256 - same input produces same output`() {
        val data = "consistent hashing".toByteArray(Charsets.UTF_8)
        val hash1 = SaveCrypto.sha256(data)
        val hash2 = SaveCrypto.sha256(data)
        assertTrue(Arrays.equals(hash1, hash2))
    }

    @Test
    fun `sha256 - different input produces different output`() {
        val data1 = "input A".toByteArray(Charsets.UTF_8)
        val data2 = "input B".toByteArray(Charsets.UTF_8)
        val hash1 = SaveCrypto.sha256(data1)
        val hash2 = SaveCrypto.sha256(data2)
        assertFalse(Arrays.equals(hash1, hash2))
    }

    @Test
    fun `sha256Hex - returns 64 character hex string`() {
        val data = "hex encoding test".toByteArray(Charsets.UTF_8)
        val hex = SaveCrypto.sha256Hex(data)
        assertEquals(64, hex.length)
    }

    @Test
    fun `sha256Hex - all lowercase hex characters`() {
        val data = "charset check".toByteArray(Charsets.UTF_8)
        val hex = SaveCrypto.sha256Hex(data)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `generateChecksum and verifyChecksum - correct checksum passes`() {
        val data = "checksum verification data".toByteArray(Charsets.UTF_8)
        val checksum = SaveCrypto.generateChecksum(data)
        assertTrue(SaveCrypto.verifyChecksum(data, checksum))
    }

    @Test
    fun `generateChecksum and verifyChecksum - wrong checksum fails`() {
        val data = "original data".toByteArray(Charsets.UTF_8)
        val wrongChecksum = ByteArray(32) { 0xFF.toByte() }
        assertFalse(SaveCrypto.verifyChecksum(data, wrongChecksum))
    }

    @Test
    fun `embedChecksum and extractChecksumAndData - extracted values are correct`() {
        val originalData = "payload after checksum header".toByteArray(Charsets.UTF_8)
        val embedded = SaveCrypto.embedChecksum(originalData)
        val result = SaveCrypto.extractChecksumAndData(embedded)
        assertNotNull(result)
        val (extractedChecksum, extractedData) = result!!
        assertEquals(32, extractedChecksum.size)
        assertTrue(Arrays.equals(originalData, extractedData))
        val expectedChecksum = SaveCrypto.sha256(originalData)
        assertTrue(Arrays.equals(expectedChecksum, extractedChecksum))
    }

    @Test
    fun `verifyEmbeddedChecksum - full flow succeeds`() {
        val original = "integrity check payload".toByteArray(Charsets.UTF_8)
        val embedded = SaveCrypto.embedChecksum(original)
        assertTrue(SaveCrypto.verifyEmbeddedChecksum(embedded))
    }

    @Test
    fun `clearDerivedKeyCache - cache size becomes zero after clear`() {
        val dummy = "cache test".toByteArray(Charsets.UTF_8)
        SaveCrypto.encrypt(dummy, testPassword)
        SaveCrypto.clearDerivedKeyCache()
        assertEquals(0, SaveCrypto.getDerivedKeyCacheSize())
    }

    @Test
    fun `clearAllKeyCache - clears all caches`() {
        val data = "pre-cache encryption".toByteArray(Charsets.UTF_8)
        SaveCrypto.encrypt(data, testPassword)
        assertTrue(SaveCrypto.getDerivedKeyCacheSize() >= 0)
        SaveCrypto.clearAllKeyCache()
        assertEquals(0, SaveCrypto.getDerivedKeyCacheSize())
    }

    @Test
    fun `decrypt - malformed format data returns null without crash`() {
        val garbage = ByteArray(100) { (it * 31 + 17).toByte() }
        val result = SaveCrypto.decrypt(garbage, testPassword)
        assertNull(result)
    }

    @Test
    fun `decrypt - too short data returns null`() {
        val tooShort = ByteArray(10) { it.toByte() }
        val result = SaveCrypto.decrypt(tooShort, testPassword)
        assertNull(result)
    }
}
