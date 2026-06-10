package com.xianxia.sect.data.compression

import net.jpountz.lz4.LZ4Factory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DataCompressorTest {

    private lateinit var compressor: DataCompressor
    private val lz4Available: Boolean by lazy {
        try {
            LZ4Factory.fastestInstance()
            true
        } catch (e: Exception) {
            false
        }
    }

    @Before
    fun setUp() {
        compressor = DataCompressor()
    }

    // ==================== compress() tests ====================

    @Test
    fun `compress empty ByteArray returns CompressedData with originalSize 0`() {
        val result = compressor.compress(ByteArray(0))
        assertEquals(0, result.originalSize)
        assertEquals(0, result.data.size)
    }

    @Test
    fun `compress data smaller than threshold returns uncompressed data`() {
        val smallData = ByteArray(100) { it.toByte() }
        val result = compressor.compress(smallData)
        assertEquals(smallData.size, result.originalSize)
        assertArrayEquals(smallData, result.data)
    }

    @Test(expected = CompressionException::class)
    fun `compress data exceeding MAX_DATA_SIZE throws CompressionException`() {
        val oversized = ByteArray(200 * 1024 * 1024 + 1) { 0 }
        compressor.compress(oversized)
    }

    @Test
    fun `compress produces compressed output smaller than original for compressible data`() {
        if (!lz4Available) return
        val repeatPattern = ByteArray(8192) { (it % 16).toByte() }
        val largeData = ByteArray(20000) { repeatPattern[it % repeatPattern.size] }
        val result = compressor.compress(largeData, CompressionAlgorithm.LZ4)
        assertTrue("compressed size (${result.data.size}) should be < original size (${largeData.size})",
            result.data.size < largeData.size)
    }

    @Test
    fun `compress then decompress returns original data - roundtrip consistency`() {
        if (!lz4Available) return
        val original = ByteArray(4096) { ((it * 37 + 13) % 256).toByte() }
        val compressed = compressor.compress(original, CompressionAlgorithm.LZ4)
        val decompressed = compressor.decompress(compressed)
        assertArrayEquals(original, decompressed)
    }

    // ==================== decompress() tests ====================

    @Test
    fun `decompress with originalSize below threshold returns data directly`() {
        val smallData = byteArrayOf(1, 2, 3, 4, 5)
        val cd = CompressedData(smallData, CompressionAlgorithm.LZ4, smallData.size)
        val result = compressor.decompress(cd)
        assertArrayEquals(smallData, result)
    }

    @Test
    fun `decompress normal data matches original`() {
        if (!lz4Available) return
        val original = ByteArray(2048) { ((it * 31 + 7) % 256).toByte() }
        val compressed = compressor.compress(original, CompressionAlgorithm.LZ4)
        val restored = compressor.decompress(compressed)
        assertArrayEquals("decompressed data must match original", original, restored)
    }

    // ==================== compressWithHeader / decompressWithHeader tests ====================

    @Test
    fun `compressWithHeader and decompressWithHeader roundtrip consistency`() {
        val original = ByteArray(512) { it.toByte() }
        val headerBytes = compressor.compressWithHeader(original)
        val restored = compressor.decompressWithHeader(headerBytes)
        assertArrayEquals(original, restored)
    }

    // ==================== getCompressionStats() tests ====================

    @Test
    fun `getCompressionStats contains expected keys and values`() {
        val stats = compressor.getCompressionStats()
        assertEquals("LZ4", stats["defaultAlgorithm"])
        assertEquals(1024, stats["compressionThreshold"])
        assertEquals(200 * 1024 * 1024, stats["maxDataSize"])
    }

    // ==================== selectAlgorithm() tests ====================

    @Test
    fun `selectAlgorithm AUTO_SAVE returns LZ4`() {
        assertEquals(CompressionAlgorithm.LZ4,
            compressor.selectAlgorithm(UseCase.AUTO_SAVE))
    }

    @Test
    fun `selectAlgorithm LEGACY returns GZIP`() {
        assertEquals(CompressionAlgorithm.GZIP,
            compressor.selectAlgorithm(UseCase.LEGACY))
    }

    @Test
    fun `selectAlgorithm FULL_SAVE returns ZSTD or GZIP fallback`() {
        val algo = compressor.selectAlgorithm(UseCase.FULL_SAVE)
        assertTrue(algo == CompressionAlgorithm.ZSTD || algo == CompressionAlgorithm.GZIP)
    }

    @Test
    fun `selectAlgorithm CLOUD_UPLOAD returns ZSTD or GZIP fallback`() {
        val algo = compressor.selectAlgorithm(UseCase.CLOUD_UPLOAD)
        assertTrue(algo == CompressionAlgorithm.ZSTD || algo == CompressionAlgorithm.GZIP)
    }

    @Test
    fun `selectAlgorithm with full parameters works`() {
        val algo = compressor.selectAlgorithm(
            dataSize = 1024,
            dataType = DataType.ARCHIVED,
            useCase = UseCase.AUTO_SAVE
        )
        assertEquals(CompressionAlgorithm.LZ4, algo)
    }

    // ==================== GZIP compression roundtrip test ====================

    @Test
    fun `gzip compress then decompress returns original data`() {
        val original = ByteArray(3000) { ((it * 17 + 3) % 256).toByte() }
        val compressed = compressor.compress(original, CompressionAlgorithm.GZIP)
        val decompressed = compressor.decompress(compressed)
        assertArrayEquals(original, decompressed)
    }

    // ==================== NONE algorithm test ====================

    @Test
    fun `NONE algorithm passes through data unchanged`() {
        val original = byteArrayOf(10, 20, 30, 40, 50)
        val result = compressor.compress(original, CompressionAlgorithm.NONE)
        assertArrayEquals(original, result.data)
        assertEquals(original.size, result.originalSize)
    }

    // ==================== decompress with explicit parameters test ====================

    @Test
    fun `decompress with explicit algorithm and originalSize works`() {
        if (!lz4Available) return
        val original = ByteArray(2048) { it.toByte() }
        val compressed = compressor.compress(original, CompressionAlgorithm.LZ4)
        val restored = compressor.decompress(
            data = compressed.data,
            algorithm = compressed.algorithm,
            originalSize = compressed.originalSize
        )
        assertArrayEquals(original, restored)
    }
}
