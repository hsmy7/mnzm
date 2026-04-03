package com.xianxia.sect.data.serialization.unified

import android.util.Log
import net.jpountz.lz4.LZ4Factory
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class CompressionResult(
    val data: ByteArray,
    val originalSize: Int,
    val compressedSize: Int,
    val algorithm: CompressionType,
    val compressionTimeMs: Long
) {
    val ratio: Double
        get() = if (originalSize > 0) compressedSize.toDouble() / originalSize else 1.0
    
    val savedBytes: Int
        get() = (originalSize - compressedSize).coerceAtLeast(0)
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompressionResult) return false
        return data.contentEquals(other.data)
    }
    
    override fun hashCode(): Int = data.contentHashCode()
}

@Singleton
class UnifiedCompressionLayer @Inject constructor() {
    companion object {
        private const val TAG = "UnifiedCompression"
        private const val MIN_COMPRESSION_SIZE = 64
    }
    
    private val lz4Compressor = LZ4Factory.fastestInstance().fastCompressor()
    private val lz4Decompressor = LZ4Factory.fastestInstance().fastDecompressor()
    
    private val totalCompressed = AtomicLong(0)
    private val totalOriginalBytes = AtomicLong(0)
    private val totalCompressedBytes = AtomicLong(0)
    private val totalCompressionTime = AtomicLong(0)
    
    fun compress(
        data: ByteArray,
        algorithm: CompressionType = CompressionType.LZ4
    ): Pair<ByteArray, CompressionType> {
        if (data.size < MIN_COMPRESSION_SIZE) {
            return compressLZ4(data) to CompressionType.LZ4
        }
        
        val startTime = System.currentTimeMillis()
        val compressed = compressLZ4(data)
        val compressionTime = System.currentTimeMillis() - startTime
        
        if (compressed.size >= data.size) {
            return compressLZ4(data) to CompressionType.LZ4
        }
        
        totalCompressed.incrementAndGet()
        totalOriginalBytes.addAndGet(data.size.toLong())
        totalCompressedBytes.addAndGet(compressed.size.toLong())
        totalCompressionTime.addAndGet(compressionTime)
        
        return Pair(compressed, CompressionType.LZ4)
    }
    
    fun decompress(
        data: ByteArray,
        algorithm: CompressionType,
        originalSize: Int
    ): ByteArray {
        return decompressLZ4(data, originalSize)
    }
    
    fun compressWithResult(
        data: ByteArray,
        algorithm: CompressionType = CompressionType.LZ4
    ): CompressionResult {
        val startTime = System.currentTimeMillis()
        val (compressed, usedAlgorithm) = compress(data, algorithm)
        val compressionTime = System.currentTimeMillis() - startTime
        
        return CompressionResult(
            data = compressed,
            originalSize = data.size,
            compressedSize = compressed.size,
            algorithm = usedAlgorithm,
            compressionTimeMs = compressionTime
        )
    }
    
    private fun compressLZ4(data: ByteArray): ByteArray {
        val maxCompressedLength = lz4Compressor.maxCompressedLength(data.size)
        val compressed = ByteArray(maxCompressedLength)
        val compressedLength = lz4Compressor.compress(data, 0, data.size, compressed, 0)
        return compressed.copyOf(compressedLength)
    }
    
    private fun decompressLZ4(data: ByteArray, originalSize: Int): ByteArray {
        val decompressed = ByteArray(originalSize)
        lz4Decompressor.decompress(data, 0, decompressed, 0, originalSize)
        return decompressed
    }
    
    fun getStats(): CompressionStats {
        val operations = totalCompressed.get()
        val original = totalOriginalBytes.get()
        val compressed = totalCompressedBytes.get()
        val time = totalCompressionTime.get()
        
        return CompressionStats(
            totalOperations = operations,
            totalOriginalBytes = original,
            totalCompressedBytes = compressed,
            totalSavedBytes = original - compressed,
            averageRatio = if (compressed > 0) original.toDouble() / compressed else 1.0,
            averageTimeMs = if (operations > 0) time / operations else 0,
            hasDictionary = false,
            dictionarySize = 0
        )
    }
    
    fun clearStats() {
        totalCompressed.set(0)
        totalOriginalBytes.set(0)
        totalCompressedBytes.set(0)
        totalCompressionTime.set(0)
    }
    
    fun selectBestAlgorithm(dataSize: Int, dataType: com.xianxia.sect.data.compression.DataType): CompressionType {
        return CompressionType.LZ4
    }
}

data class CompressionStats(
    val totalOperations: Long = 0,
    val totalOriginalBytes: Long = 0,
    val totalCompressedBytes: Long = 0,
    val totalSavedBytes: Long = 0,
    val averageRatio: Double = 1.0,
    val averageTimeMs: Long = 0,
    val hasDictionary: Boolean = false,
    val dictionarySize: Int = 0
)
