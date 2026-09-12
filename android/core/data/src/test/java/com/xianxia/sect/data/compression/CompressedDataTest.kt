package com.xianxia.sect.data.compression

import org.junit.Assert.*
import org.junit.Test

class CompressedDataTest {

    @Test
    fun `constructor sets fields correctly`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val compressed = CompressedData(
            data = data,
            algorithm = CompressionAlgorithm.LZ4,
            originalSize = 100,
            compressionTime = 42L
        )
        assertArrayEquals(data, compressed.data)
        assertEquals(CompressionAlgorithm.LZ4, compressed.algorithm)
        assertEquals(100, compressed.originalSize)
        assertEquals(42L, compressed.compressionTime)
    }

    @Test
    fun `toStorageFormat and fromStorageFormat roundtrip preserves all fields`() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val original = CompressedData(data, CompressionAlgorithm.GZIP, 50, 123L)
        val storageBytes = original.toStorageFormat()
        val restored = CompressedData.fromStorageFormat(storageBytes)
        assertArrayEquals(original.data, restored.data)
        assertEquals(original.algorithm, restored.algorithm)
        assertEquals(original.originalSize, restored.originalSize)
        assertEquals(original.compressionTime, restored.compressionTime)
    }

    @Test
    fun `toStorageFormat output is larger than raw data due to header`() {
        val data = byteArrayOf(10, 20, 30)
        val compressed = CompressedData(data, CompressionAlgorithm.GZIP, 50)
        assertTrue(compressed.toStorageFormat().size > data.size)
    }

    @Test
    fun `fromStorageFormat throws on bytes too short for header`() {
        try {
            CompressedData.fromStorageFormat(byteArrayOf(1, 2, 3))
            fail("Expected an exception for input too short for header")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `equals uses reference identity`() {
        val data = byteArrayOf(1, 2, 3)
        val a = CompressedData(data, CompressionAlgorithm.LZ4, 100, 10L)
        val b = CompressedData(data.copyOf(), CompressionAlgorithm.LZ4, 100, 10L)
        assertNotEquals(a, b)
    }

    @Test
    fun `same reference returns true for equals`() {
        val data = byteArrayOf(1)
        val compressed = CompressedData(data, CompressionAlgorithm.LZ4, 1)
        assertTrue(compressed === compressed)
        assertEquals(compressed, compressed)
    }

    @Test
    fun `two different instances with different data are not equal`() {
        val a = CompressedData(byteArrayOf(1, 2, 3), CompressionAlgorithm.LZ4, 3)
        val b = CompressedData(byteArrayOf(4, 5, 6), CompressionAlgorithm.LZ4, 3)
        assertNotEquals(a, b)
    }

    @Test
    fun `compressionTime defaults to zero`() {
        val compressed = CompressedData(byteArrayOf(1), CompressionAlgorithm.LZ4, 1)
        assertEquals(0L, compressed.compressionTime)
    }

    @Test
    fun `equals returns false for different type`() {
        val compressed = CompressedData(byteArrayOf(1), CompressionAlgorithm.LZ4, 1)
        assertNotEquals(compressed, "not a CompressedData")
        assertNotEquals(compressed, null)
    }

    @Test
    fun `toStorageFormat fromStorageFormat roundtrip with NONE algorithm`() {
        val data = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        val original = CompressedData(data, CompressionAlgorithm.NONE, data.size, 0L)
        val restored = CompressedData.fromStorageFormat(original.toStorageFormat())
        assertArrayEquals(data, restored.data)
        assertEquals(CompressionAlgorithm.NONE, restored.algorithm)
        assertEquals(data.size, restored.originalSize)
        assertEquals(0L, restored.compressionTime)
    }

    @Test
    fun `toStorageFormat fromStorageFormat roundtrip with ZSTD algorithm`() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val original = CompressedData(data, CompressionAlgorithm.ZSTD, 100, 999L)
        val restored = CompressedData.fromStorageFormat(original.toStorageFormat())
        assertArrayEquals(data, restored.data)
        assertEquals(CompressionAlgorithm.ZSTD, restored.algorithm)
        assertEquals(100, restored.originalSize)
        assertEquals(999L, restored.compressionTime)
    }
}
