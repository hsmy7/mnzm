package com.xianxia.sect.data.compression

import org.junit.Assert.*
import org.junit.Test

class CompressionAlgorithmTest {

    @Test
    fun `all four values exist`() {
        val values = CompressionAlgorithm.values()
        assertEquals(4, values.size)
        assertTrue(values.contains(CompressionAlgorithm.LZ4))
        assertTrue(values.contains(CompressionAlgorithm.GZIP))
        assertTrue(values.contains(CompressionAlgorithm.ZSTD))
        assertTrue(values.contains(CompressionAlgorithm.NONE))
    }

    @Test
    fun `recommendForSize returns NONE for data smaller than 1024`() {
        assertEquals(CompressionAlgorithm.NONE, CompressionAlgorithm.recommendForSize(0))
        assertEquals(CompressionAlgorithm.NONE, CompressionAlgorithm.recommendForSize(1))
        assertEquals(CompressionAlgorithm.NONE, CompressionAlgorithm.recommendForSize(1023))
    }

    @Test
    fun `recommendForSize returns LZ4 for data between 1024 and 100KB`() {
        assertEquals(CompressionAlgorithm.LZ4, CompressionAlgorithm.recommendForSize(1024))
        assertEquals(CompressionAlgorithm.LZ4, CompressionAlgorithm.recommendForSize(2048))
        assertEquals(CompressionAlgorithm.LZ4, CompressionAlgorithm.recommendForSize(50 * 1024))
        assertEquals(CompressionAlgorithm.LZ4, CompressionAlgorithm.recommendForSize(100 * 1024 - 1))
    }

    @Test
    fun `recommendForSize returns GZIP for data larger than or equal to 100KB`() {
        assertEquals(CompressionAlgorithm.GZIP, CompressionAlgorithm.recommendForSize(100 * 1024))
        assertEquals(CompressionAlgorithm.GZIP, CompressionAlgorithm.recommendForSize(200 * 1024))
        assertEquals(CompressionAlgorithm.GZIP, CompressionAlgorithm.recommendForSize(Int.MAX_VALUE))
    }

    @Test
    fun `DEFAULT is LZ4`() {
        assertEquals(CompressionAlgorithm.LZ4, CompressionAlgorithm.DEFAULT)
    }
}
