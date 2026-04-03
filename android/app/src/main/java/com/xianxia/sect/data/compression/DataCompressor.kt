package com.xianxia.sect.data.compression

import android.util.Log
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FastDecompressor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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
        private const val MAX_DATA_SIZE = 200 * 1024 * 1024
    }

    private val lz4Compressor: LZ4Compressor by lazy {
        LZ4Factory.fastestInstance().fastCompressor()
    }

    private val lz4Decompressor: LZ4FastDecompressor by lazy {
        LZ4Factory.fastestInstance().fastDecompressor()
    }

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

    fun getCompressionStats(): Map<String, Any> {
        return mapOf(
            "defaultAlgorithm" to defaultAlgorithm.name,
            "compressionThreshold" to COMPRESSION_THRESHOLD,
            "maxDataSize" to MAX_DATA_SIZE
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
        return CompressionAlgorithm.LZ4
    }
}

enum class DataType {
    DISCIPLE,
    EQUIPMENT,
    EVENT,
    BATTLE_LOG,
    ARCHIVED
}
