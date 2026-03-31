package com.xianxia.sect.data.compression

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompressionManager @Inject constructor(
    private val compressor: DataCompressor
) {
    companion object {
        private const val TAG = "CompressionManager"
        private const val MAX_CACHE_SIZE = 50
    }

    private val statsCache = ConcurrentHashMap<String, CompressionStats>()
    private val totalCompressed = AtomicLong(0)
    private val totalOriginalBytes = AtomicLong(0)
    private val totalCompressedBytes = AtomicLong(0)
    private val totalCompressionTime = AtomicLong(0)

    data class CompressionStats(
        val totalOperations: Long = 0,
        val totalOriginalBytes: Long = 0,
        val totalCompressedBytes: Long = 0,
        val totalSavedBytes: Long = 0,
        val averageRatio: Double = 0.0,
        val averageTime: Long = 0
    )

    suspend fun compressData(
        data: ByteArray,
        dataType: DataType,
        key: String? = null
    ): CompressedData = withContext(Dispatchers.Default) {
        val config = CompressionStrategy.selectStrategy(dataType)
        val result = if (config.shouldCompress(data.size)) {
            compressor.compress(data, config.algorithm)
        } else {
            CompressedData(data, config.algorithm, data.size, 0)
        }

        updateStats(key, data.size, result.data.size, result.compressionTime)
        result
    }

    suspend fun compressData(
        data: ByteArray,
        config: CompressionConfig,
        key: String? = null
    ): CompressedData = withContext(Dispatchers.Default) {
        val result = if (config.shouldCompress(data.size)) {
            compressor.compress(data, config.algorithm)
        } else {
            CompressedData(data, config.algorithm, data.size, 0)
        }

        updateStats(key, data.size, result.data.size, result.compressionTime)
        result
    }

    suspend fun decompressData(compressedData: CompressedData): ByteArray = withContext(Dispatchers.Default) {
        compressor.decompress(compressedData)
    }

    suspend fun decompressData(data: ByteArray, algorithm: CompressionAlgorithm, originalSize: Int): ByteArray = 
        withContext(Dispatchers.Default) {
            compressor.decompress(data, algorithm, originalSize)
        }

    fun compressSync(data: ByteArray, dataType: DataType): CompressedData {
        val config = CompressionStrategy.selectStrategy(dataType)
        return if (config.shouldCompress(data.size)) {
            compressor.compress(data, config.algorithm)
        } else {
            CompressedData(data, config.algorithm, data.size, 0)
        }
    }

    fun decompressSync(compressedData: CompressedData): ByteArray {
        return compressor.decompress(compressedData)
    }

    fun compressWithHeader(data: ByteArray, dataType: DataType): ByteArray {
        val config = CompressionStrategy.selectStrategy(dataType)
        return compressor.compressWithHeader(data, config.algorithm)
    }

    fun decompressWithHeader(data: ByteArray): ByteArray {
        return compressor.decompressWithHeader(data)
    }

    fun getStats(key: String? = null): CompressionStats {
        return if (key != null) {
            statsCache[key] ?: CompressionStats()
        } else {
            val operations = totalCompressed.get()
            val original = totalOriginalBytes.get()
            val compressed = totalCompressedBytes.get()
            val time = totalCompressionTime.get()
            CompressionStats(
                totalOperations = operations,
                totalOriginalBytes = original,
                totalCompressedBytes = compressed,
                totalSavedBytes = original - compressed,
                averageRatio = if (compressed > 0) original.toDouble() / compressed else 1.0,
                averageTime = if (operations > 0) time / operations else 0
            )
        }
    }

    fun clearStats() {
        statsCache.clear()
        totalCompressed.set(0)
        totalOriginalBytes.set(0)
        totalCompressedBytes.set(0)
        totalCompressionTime.set(0)
    }

    private fun updateStats(key: String?, originalSize: Int, compressedSize: Int, time: Long) {
        totalCompressed.incrementAndGet()
        totalOriginalBytes.addAndGet(originalSize.toLong())
        totalCompressedBytes.addAndGet(compressedSize.toLong())
        totalCompressionTime.addAndGet(time)

        if (key != null && statsCache.size < MAX_CACHE_SIZE) {
            val current = statsCache[key] ?: CompressionStats()
            val newTotal = current.totalOperations + 1
            val newOriginal = current.totalOriginalBytes + originalSize
            val newCompressed = current.totalCompressedBytes + compressedSize
            val newSaved = current.totalSavedBytes + (originalSize - compressedSize)
            val newAvgRatio = if (newCompressed > 0) newOriginal.toDouble() / newCompressed else 1.0
            val newAvgTime = (current.averageTime * current.totalOperations + time) / newTotal

            statsCache[key] = CompressionStats(
                totalOperations = newTotal,
                totalOriginalBytes = newOriginal,
                totalCompressedBytes = newCompressed,
                totalSavedBytes = newSaved,
                averageRatio = newAvgRatio,
                averageTime = newAvgTime
            )
        }

        val saved = totalOriginalBytes.get() - totalCompressedBytes.get()
        Log.v(TAG, "Compression stats: total=${totalCompressed.get()}, saved=${saved / 1024}KB")
    }

    fun selectAlgorithm(dataSize: Int, dataType: DataType): CompressionAlgorithm {
        return compressor.selectAlgorithm(dataSize, dataType)
    }

    fun getRecommendedConfig(dataType: DataType): CompressionConfig {
        return CompressionStrategy.selectStrategy(dataType)
    }
}
