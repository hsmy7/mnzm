package com.xianxia.sect.data.serialization.unified

import android.util.Log
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import com.github.luben.zstd.ZstdDictTrainer
import net.jpountz.lz4.LZ4Factory
import java.util.concurrent.ConcurrentHashMap
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
        private const val ZSTD_DEFAULT_LEVEL = 3
        private const val ZSTD_HIGH_LEVEL = 9
        private const val DICT_TRAINING_SAMPLES = 100
        private const val DICT_TRAINING_MAX_SIZE = 10 * 1024 * 1024
        private const val MIN_COMPRESSION_SIZE = 64
    }
    
    private val lz4Compressor = LZ4Factory.fastestInstance().fastCompressor()
    private val lz4Decompressor = LZ4Factory.fastestInstance().fastDecompressor()
    
    private val zstdCompressContexts = ConcurrentHashMap<Int, ZstdCompressCtx>()
    private val zstdDecompressContext = ZstdDecompressCtx()
    
    private var trainedDictionary: ByteArray? = null
    private val sampleBuffer = mutableListOf<ByteArray>()
    private val sampleLock = Any()
    
    private val totalCompressed = AtomicLong(0)
    private val totalOriginalBytes = AtomicLong(0)
    private val totalCompressedBytes = AtomicLong(0)
    private val totalCompressionTime = AtomicLong(0)
    
    fun compress(
        data: ByteArray,
        algorithm: CompressionType = CompressionType.ZSTD
    ): Pair<ByteArray, CompressionType> {
        if (data.size < MIN_COMPRESSION_SIZE) {
            return compressLZ4(data) to CompressionType.LZ4
        }
        
        val startTime = System.currentTimeMillis()
        
        val compressed = when (algorithm) {
            CompressionType.LZ4 -> compressLZ4(data)
            CompressionType.ZSTD -> compressZstd(data)
        }
        
        val compressionTime = System.currentTimeMillis() - startTime
        
        if (compressed.size >= data.size) {
            return compressLZ4(data) to CompressionType.LZ4
        }
        
        totalCompressed.incrementAndGet()
        totalOriginalBytes.addAndGet(data.size.toLong())
        totalCompressedBytes.addAndGet(compressed.size.toLong())
        totalCompressionTime.addAndGet(compressionTime)
        
        return Pair(compressed, algorithm)
    }
    
    fun decompress(
        data: ByteArray,
        algorithm: CompressionType,
        originalSize: Int
    ): ByteArray {
        return when (algorithm) {
            CompressionType.LZ4 -> decompressLZ4(data, originalSize)
            CompressionType.ZSTD -> decompressZstd(data, originalSize)
        }
    }
    
    fun compressWithResult(
        data: ByteArray,
        algorithm: CompressionType = CompressionType.ZSTD
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
    
    private fun compressZstd(data: ByteArray): ByteArray {
        val ctx = getOrCreateCompressContext(ZSTD_DEFAULT_LEVEL)
        return ctx.compress(data)
    }
    
    private fun decompressZstd(data: ByteArray, originalSize: Int): ByteArray {
        return zstdDecompressContext.decompress(data, originalSize)
    }
    
    fun compressZstdWithDict(data: ByteArray): ByteArray {
        val dict = trainedDictionary ?: return compressZstd(data)
        val ctx = getOrCreateCompressContext(ZSTD_DEFAULT_LEVEL)
        ctx.loadDict(dict)
        return ctx.compress(data)
    }
    
    fun decompressZstdWithDict(data: ByteArray, originalSize: Int): ByteArray {
        val dict = trainedDictionary ?: return decompressZstd(data, originalSize)
        zstdDecompressContext.loadDict(dict)
        return zstdDecompressContext.decompress(data, originalSize)
    }
    
    fun compressZstdHigh(data: ByteArray): ByteArray {
        val ctx = getOrCreateCompressContext(ZSTD_HIGH_LEVEL)
        return ctx.compress(data)
    }
    
    fun compressZstdParallel(data: ByteArray, workers: Int = 4): ByteArray {
        if (data.size < 64 * 1024) {
            return compressZstd(data)
        }
        
        return try {
            val ctx = getOrCreateCompressContext(ZSTD_DEFAULT_LEVEL)
            ctx.setWorkers(workers)
            ctx.compress(data)
        } catch (e: Exception) {
            Log.w(TAG, "Parallel ZSTD compression failed, falling back", e)
            compressZstd(data)
        }
    }
    
    fun trainDictionary(samples: List<ByteArray>): Boolean {
        if (samples.size < DICT_TRAINING_SAMPLES) {
            Log.w(TAG, "Not enough samples for dictionary training: ${samples.size}")
            return false
        }
        
        return try {
            val trainer = ZstdDictTrainer(DICT_TRAINING_MAX_SIZE, DICT_TRAINING_MAX_SIZE / 10)
            samples.take(DICT_TRAINING_SAMPLES).forEach { trainer.addSample(it) }
            trainedDictionary = trainer.trainSamples()
            Log.i(TAG, "Trained ZSTD dictionary with ${samples.size} samples, size: ${trainedDictionary?.size ?: 0}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to train ZSTD dictionary", e)
            false
        }
    }
    
    fun addSampleForTraining(sample: ByteArray) {
        if (sample.size < 1024 || sample.size > DICT_TRAINING_MAX_SIZE) return
        
        synchronized(sampleLock) {
            sampleBuffer.add(sample)
            if (sampleBuffer.size >= DICT_TRAINING_SAMPLES) {
                trainDictionary(sampleBuffer.toList())
                sampleBuffer.clear()
            }
        }
    }
    
    fun hasTrainedDictionary(): Boolean = trainedDictionary != null
    
    fun getDictionarySize(): Int = trainedDictionary?.size ?: 0
    
    private fun getOrCreateCompressContext(level: Int): ZstdCompressCtx {
        return zstdCompressContexts.getOrPut(level) {
            ZstdCompressCtx().apply { setLevel(level) }
        }
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
            hasDictionary = hasTrainedDictionary(),
            dictionarySize = getDictionarySize()
        )
    }
    
    fun clearStats() {
        totalCompressed.set(0)
        totalOriginalBytes.set(0)
        totalCompressedBytes.set(0)
        totalCompressionTime.set(0)
    }
    
    fun selectBestAlgorithm(dataSize: Int, dataType: com.xianxia.sect.data.compression.DataType): CompressionType {
        return when {
            dataSize < 512 -> CompressionType.LZ4
            dataSize > 64 * 1024 -> CompressionType.ZSTD
            dataType == com.xianxia.sect.data.compression.DataType.BATTLE_LOG -> CompressionType.ZSTD
            dataType == com.xianxia.sect.data.compression.DataType.ARCHIVED -> CompressionType.ZSTD
            else -> CompressionType.LZ4
        }
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
