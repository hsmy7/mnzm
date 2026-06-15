package com.xianxia.sect.data.crypto

import org.junit.Assert.*
import org.junit.Test
import java.util.Arrays
import java.util.concurrent.CountDownLatch

class CryptoHashUtilsTest {

    private val testPassword = "MySecurePassword123!"

    // ==================== sha256 ====================

    @Test
    fun `sha256 - returns 32 bytes`() {
        val data = "test input".toByteArray(Charsets.UTF_8)
        val hash = CryptoHashUtils.sha256(data)
        assertEquals(32, hash.size)
    }

    @Test
    fun `sha256 - same input produces same output`() {
        val data = "consistent hashing".toByteArray(Charsets.UTF_8)
        val hash1 = CryptoHashUtils.sha256(data)
        val hash2 = CryptoHashUtils.sha256(data)
        assertTrue(Arrays.equals(hash1, hash2))
    }

    @Test
    fun `sha256 - different input produces different output`() {
        val data1 = "input A".toByteArray(Charsets.UTF_8)
        val data2 = "input B".toByteArray(Charsets.UTF_8)
        val hash1 = CryptoHashUtils.sha256(data1)
        val hash2 = CryptoHashUtils.sha256(data2)
        assertFalse(Arrays.equals(hash1, hash2))
    }

    @Test
    fun `sha256 - empty input produces valid 32-byte hash`() {
        val hash = CryptoHashUtils.sha256(ByteArray(0))
        assertEquals(32, hash.size)
    }

    @Test
    fun `sha256 - known test vector`() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val data = "abc".toByteArray(Charsets.UTF_8)
        val hash = CryptoHashUtils.sha256(data)
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, hash.joinToString("") { "%02x".format(it) })
    }

    // ==================== sha256Hex ====================

    @Test
    fun `sha256Hex - returns 64 character hex string`() {
        val data = "hex encoding test".toByteArray(Charsets.UTF_8)
        val hex = CryptoHashUtils.sha256Hex(data)
        assertEquals(64, hex.length)
    }

    @Test
    fun `sha256Hex - all lowercase hex characters`() {
        val data = "charset check".toByteArray(Charsets.UTF_8)
        val hex = CryptoHashUtils.sha256Hex(data)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ==================== checksum ====================

    @Test
    fun `generateChecksum and verifyChecksum - correct checksum passes`() {
        val data = "checksum verification data".toByteArray(Charsets.UTF_8)
        val checksum = CryptoHashUtils.generateChecksum(data)
        assertTrue(CryptoHashUtils.verifyChecksum(data, checksum))
    }

    @Test
    fun `generateChecksum and verifyChecksum - wrong checksum fails`() {
        val data = "original data".toByteArray(Charsets.UTF_8)
        val wrongChecksum = ByteArray(32) { 0xFF.toByte() }
        assertFalse(CryptoHashUtils.verifyChecksum(data, wrongChecksum))
    }

    @Test
    fun `embedChecksum and extractChecksumAndData - extracted values are correct`() {
        val originalData = "payload after checksum header".toByteArray(Charsets.UTF_8)
        val embedded = CryptoHashUtils.embedChecksum(originalData)
        val result = CryptoHashUtils.extractChecksumAndData(embedded)
        assertNotNull(result)
        val (extractedChecksum, extractedData) = result!!
        assertEquals(32, extractedChecksum.size)
        assertTrue(Arrays.equals(originalData, extractedData))
        val expectedChecksum = CryptoHashUtils.sha256(originalData)
        assertTrue(Arrays.equals(expectedChecksum, extractedChecksum))
    }

    @Test
    fun `verifyEmbeddedChecksum - full flow succeeds`() {
        val original = "integrity check payload".toByteArray(Charsets.UTF_8)
        val embedded = CryptoHashUtils.embedChecksum(original)
        assertTrue(CryptoHashUtils.verifyEmbeddedChecksum(embedded))
    }

    @Test
    fun `verifyEmbeddedChecksum - tampered data fails`() {
        val original = "integrity check payload".toByteArray(Charsets.UTF_8)
        val embedded = CryptoHashUtils.embedChecksum(original)
        // 篡改 payload 部分（跳过 32 字节 checksum 头）
        embedded[35] = (embedded[35].toInt() xor 0xFF).toByte()
        assertFalse(CryptoHashUtils.verifyEmbeddedChecksum(embedded))
    }

    @Test
    fun `extractChecksumAndData returns null for short data less than 32 bytes`() {
        val shortData = ByteArray(16) { it.toByte() }
        assertNull(CryptoHashUtils.extractChecksumAndData(shortData))
    }

    // ==================== timingSafeEqual ====================

    @Test
    fun `timingSafeEqual - equal arrays returns true`() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(timingSafeEqual(a, b))
    }

    @Test
    fun `timingSafeEqual - different arrays returns false`() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 6)
        assertFalse(timingSafeEqual(a, b))
    }

    @Test
    fun `timingSafeEqual - different length returns false`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 3, 4)
        assertFalse(timingSafeEqual(a, b))
    }

    @Test
    fun `timingSafeEqual - empty arrays returns true`() {
        assertTrue(timingSafeEqual(ByteArray(0), ByteArray(0)))
    }

    // ==================== securelyClear ====================

    @Test
    fun `securelyClear - fills array with zeros`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        securelyClear(data)
        assertTrue(data.all { it == 0.toByte() })
    }

    @Test
    fun `securelyClear - null input does not throw`() {
        securelyClear(null)
    }

    // ==================== 线程安全 ====================

    @Test
    fun `sha256 - thread safe under concurrent access`() {
        val threadCount = 8
        val iterationsPerThread = 50
        val latch = CountDownLatch(threadCount)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        repeat(threadCount) {
            Thread {
                try {
                    repeat(iterationsPerThread) { i ->
                        val data = "concurrent-$i".toByteArray()
                        val hash1 = CryptoHashUtils.sha256(data)
                        val hash2 = CryptoHashUtils.sha256(data)
                        if (!Arrays.equals(hash1, hash2)) {
                            errors.add(AssertionError("Hash mismatch in thread $it iteration $i"))
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        latch.await()
        assertTrue("Concurrent errors: $errors", errors.isEmpty())
    }
}
