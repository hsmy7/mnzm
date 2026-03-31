package com.xianxia.sect.data.compression

import android.util.Log
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictTrainer
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FastDecompressor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

class CompressionException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Singleton
class DataCompressor @Inject constructor(
    private val defaultAlgorithm: CompressionAlgorithm = CompressionAlgorithm.LZ4
) {
    companion object {
        private const val TAG = "DataCompressor"
        private const val COMPRESSION_THRESHOLD = 1024
        private const val ZSTD_COMPRESSION_LEVEL = 3
        private const val ZSTD_HIGH_COMPRESSION_LEVEL = 9
        private const val MAX_DATA_SIZE = 200 * 1024 * 1024
        private const val DICT_TRAINING_SAMPLES = 100
        private const val DICT_TRAINING_MAX_SIZE = 10 * 1024 * 1024
        private const val PARALLEL_THRESHOLD = 64 * 1024
    }

    private val lz4Compressor: LZ4Compressor by lazy {
        LZ4Factory.fastestInstance().fastCompressor()
    }

    private val lz4Decompressor: LZ4FastDecompressor by lazy {
        LZ4Factory.fastestInstance().fastDecompressor()
    }
    
    private val zstdDictTrainer: ZstdDictTrainer? by lazy {
        try {
            ZstdDictTrainer(DICT_TRAINING_MAX_SIZE, DICT_TRAINING_MAX_SIZE / 10)
        } catch (e: Exception) {
            Log.w(TAG, "ZSTD dictionary training not available", e)
            null
        }
    }
    
    private val compressionDictionary = AtomicBoolean(false)
    private var trainedDictionary: ByteArray? = null
    private val dataTypeSamples = ConcurrentHashMap<DataType, MutableList<ByteArray>>()
    
    private val zstdContexts = ConcurrentHashMap<Int, ZstdCompressCtx>()
    private val zstdDecompressContexts = ConcurrentHashMap<Int, ZstdDecompressCtx>()

    fun compress(
        data: ByteArray,
        algorithm: CompressionAlgorithm = defaultAlgorithm
    ): CompressedData {
        if (data.isEmpty()) {
            return CompressedData(data, algorithm, 0, 0)
        }

        if (data.size > MAX_DATA_SIZE) {
            throw CompressionException("Data too large: ${data.size} bytes (max: $MAX_DATA_SIZE)")
        }

        if (data.size < COMPRESSION_THRESHOLD) {
            return CompressedData(data, algorithm, data.size, 0)
        }

        val startTime = System.nanoTime()

        val compressed = try {
            when (algorithm) {
                CompressionAlgorithm.LZ4 -> compressLZ4(data)
                CompressionAlgorithm.ZSTD -> compressZstd(data)
                CompressionAlgorithm.GZIP -> compressGzip(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed with ${algorithm.name}, falling back to uncompressed", e)
            return CompressedData(data, algorithm, data.size, 0)
        }

        val duration = System.nanoTime() - startTime

        if (compressed.size >= data.size) {
            Log.d(TAG, "Compression not beneficial for ${data.size} bytes, keeping original")
            return CompressedData(data, algorithm, data.size, 0)
        }

        val ratio = data.size.toDouble() / compressed.size
        Log.d(TAG, "Compressed ${data.size} -> ${compressed.size} bytes " +
              "(ratio: ${"%.2f".format(ratio)}, time: ${duration / 1_000_000}ms, algo: ${algorithm.name})")

        return CompressedData(
            data = compressed,
            algorithm = algorithm,
            originalSize = data.size,
            compressionTime = duration
        )
    }

    fun decompress(compressedData: CompressedData): ByteArray {
        if (compressedData.originalSize < COMPRESSION_THRESHOLD) {
            return compressedData.data
        }

        return try {
            when (compressedData.algorithm) {
                CompressionAlgorithm.LZ4 -> decompressLZ4(compressedData.data, compressedData.originalSize)
                CompressionAlgorithm.ZSTD -> decompressZstd(compressedData.data, compressedData.originalSize)
                CompressionAlgorithm.GZIP -> decompressGzip(compressedData.data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decompression failed with ${compressedData.algorithm.name}", e)
            throw CompressionException("Decompression failed: ${e.message}", e)
        }
    }

    fun decompress(data: ByteArray, algorithm: CompressionAlgorithm, originalSize: Int): ByteArray {
        if (originalSize < COMPRESSION_THRESHOLD) {
            return data
        }

        return try {
            when (algorithm) {
                CompressionAlgorithm.LZ4 -> decompressLZ4(data, originalSize)
                CompressionAlgorithm.ZSTD -> decompressZstd(data, originalSize)
                CompressionAlgorithm.GZIP -> decompressGzip(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decompression failed with ${algorithm.name}", e)
            throw CompressionException("Decompression failed: ${e.message}", e)
        }
    }

    fun compressWithHeader(data: ByteArray, algorithm: CompressionAlgorithm = defaultAlgorithm): ByteArray {
        val compressed = compress(data, algorithm)
        return compressed.toStorageFormat()
    }

    fun decompressWithHeader(data: ByteArray): ByteArray {
        val compressedData = CompressedData.fromStorageFormat(data)
        return decompress(compressedData)
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
        return com.github.luben.zstd.Zstd.compress(data, ZSTD_COMPRESSION_LEVEL)
    }

    private fun decompressZstd(data: ByteArray, originalSize: Int): ByteArray {
        return com.github.luben.zstd.Zstd.decompress(data, originalSize)
    }
    
    fun compressZstdWithDict(data: ByteArray, useDict: Boolean = true): ByteArray {
        val ctx = getOrCreateCompressContext(ZSTD_COMPRESSION_LEVEL)
        
        if (useDict && trainedDictionary != null) {
            ctx.loadDict(trainedDictionary!!)
        }
        
        return ctx.compress(data)
    }
    
    fun decompressZstdWithDict(data: ByteArray, originalSize: Int, useDict: Boolean = true): ByteArray {
        val ctx = getOrCreateDecompressContext()
        
        if (useDict && trainedDictionary != null) {
            ctx.loadDict(trainedDictionary!!)
        }
        
        return ctx.decompress(data, originalSize)
    }
    
    fun compressZstdParallel(data: ByteArray, level: Int = ZSTD_COMPRESSION_LEVEL): ByteArray {
        if (data.size < PARALLEL_THRESHOLD) {
            return compressZstd(data)
        }
        
        return try {
            val ctx = getOrCreateCompressContext(level)
            ctx.setWorkers(4)
            ctx.compress(data)
        } catch (e: Exception) {
            Log.w(TAG, "Parallel ZSTD compression failed, falling back to single-thread", e)
            compressZstd(data)
        }
    }
    
    fun compressZstdHigh(data: ByteArray): ByteArray {
        return try {
            Zstd.compress(data, ZSTD_HIGH_COMPRESSION_LEVEL)
        } catch (e: Exception) {
            Log.w(TAG, "High compression ZSTD failed, falling back to standard", e)
            compressZstd(data)
        }
    }
    
    fun trainDictionary(samples: List<ByteArray>): Boolean {
        return try {
            val trainer = ZstdDictTrainer(DICT_TRAINING_MAX_SIZE, DICT_TRAINING_MAX_SIZE / 10)
            samples.forEach { trainer.addSample(it) }
            trainedDictionary = trainer.trainSamples()
            compressionDictionary.set(true)
            Log.i(TAG, "Trained ZSTD dictionary with ${samples.size} samples, size: ${trainedDictionary?.size ?: 0}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to train ZSTD dictionary", e)
            false
        }
    }
    
    fun addSampleForTraining(dataType: DataType, sample: ByteArray) {
        if (sample.size > 1024 && sample.size < DICT_TRAINING_MAX_SIZE) {
            val samples = dataTypeSamples.getOrPut(dataType) { mutableListOf() }
            synchronized(samples) {
                samples.add(sample)
                if (samples.size >= DICT_TRAINING_SAMPLES) {
                    val allSamples = dataTypeSamples.values.flatten()
                    if (allSamples.size >= DICT_TRAINING_SAMPLES) {
                        trainDictionary(allSamples.take(DICT_TRAINING_SAMPLES))
                    }
                }
            }
        }
    }
    
    private fun getOrCreateCompressContext(level: Int): ZstdCompressCtx {
        return zstdContexts.getOrPut(level) {
            ZstdCompressCtx().apply {
                setLevel(level)
            }
        }
    }
    
    private fun getOrCreateDecompressContext(): ZstdDecompressCtx {
        return zstdDecompressContexts.getOrPut(0) {
            ZstdDecompressCtx()
        }
    }
    
    fun getZstdCompressionRatio(data: ByteArray): Double {
        if (data.isEmpty()) return 1.0
        val compressed = compressZstd(data)
        return data.size.toDouble() / compressed.size
    }
    
    fun hasTrainedDictionary(): Boolean = trainedDictionary != null
    
    fun getCompressionStats(): Map<String, Any> {
        return mapOf(
            "defaultAlgorithm" to defaultAlgorithm.name,
            "compressionThreshold" to COMPRESSION_THRESHOLD,
            "maxDataSize" to MAX_DATA_SIZE,
            "hasTrainedDictionary" to hasTrainedDictionary(),
            "dictionarySize" to (trainedDictionary?.size ?: 0),
            "sampleCount" to dataTypeSamples.values.sumOf { it.size }
        )
    }

    private fun compressGzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        try {
            GZIPOutputStream(bos).use { it.write(data) }
        } catch (e: IOException) {
            throw CompressionException("GZIP compression failed", e)
        }
        return bos.toByteArray()
    }

    private fun decompressGzip(data: ByteArray): ByteArray {
        return try {
            GZIPInputStream(ByteArrayInputStream(data)).use {
                it.readBytes()
            }
        } catch (e: IOException) {
            throw CompressionException("GZIP decompression failed", e)
        }
    }

    fun selectAlgorithm(dataSize: Int, dataType: DataType): CompressionAlgorithm {
        return when (dataType) {
            DataType.DISCIPLE, DataType.EQUIPMENT -> CompressionAlgorithm.LZ4
            DataType.EVENT -> CompressionAlgorithm.LZ4
            DataType.BATTLE_LOG, DataType.ARCHIVED -> CompressionAlgorithm.ZSTD
        }
    }
}

enum class DataType {
    DISCIPLE,
    EQUIPMENT,
    EVENT,
    BATTLE_LOG,
    ARCHIVED
}
