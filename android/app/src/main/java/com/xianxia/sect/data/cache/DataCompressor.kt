package com.xianxia.sect.data.cache

import android.util.Log
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FastDecompressor
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class CompressionAlgorithm(
    val displayName: String,
    val speedRatio: Double,
    val compressionRatio: Double
) {
    LZ4("LZ4", 10.0, 2.5),
    ZSTD("Zstandard", 3.0, 3.5)
}

data class CompressedData(
    val data: ByteArray,
    val algorithm: CompressionAlgorithm,
    val originalSize: Int,
    val compressionTime: Long = 0
) {
    val compressionRatio: Double
        get() = if (data.isNotEmpty() && originalSize > 0) {
            originalSize.toDouble() / data.size
        } else 1.0

    fun toStorageFormat(): ByteArray {
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(algorithm.ordinal)
        header.putInt(originalSize)
        header.putInt(data.size)
        return header.array() + data
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CompressedData
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}

class DataCompressor(
    private val defaultAlgorithm: CompressionAlgorithm = CompressionAlgorithm.LZ4
) {
    companion object {
        private const val TAG = "DataCompressor"
        private const val COMPRESSION_THRESHOLD = 512
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
        if (data.size < COMPRESSION_THRESHOLD) {
            return CompressedData(data, CompressionAlgorithm.LZ4, data.size, 0)
        }

        val startTime = System.nanoTime()

        val compressed = when (algorithm) {
            CompressionAlgorithm.LZ4 -> compressLZ4(data)
            CompressionAlgorithm.ZSTD -> compressZstd(data)
        }

        val duration = System.nanoTime() - startTime
        val ratio = if (compressed.size < data.size) {
            data.size.toDouble() / compressed.size
        } else {
            1.0
        }

        if (compressed.size >= data.size) {
            Log.d(TAG, "Compression not beneficial for ${data.size} bytes, using LZ4")
            val lz4Compressed = compressLZ4(data)
            return CompressedData(lz4Compressed, CompressionAlgorithm.LZ4, data.size, 0)
        }

        Log.d(TAG, "Compressed ${data.size} -> ${compressed.size} bytes " +
                "(ratio: ${"%.2f".format(ratio)}, time: ${duration / 1_000_000}ms)")

        return CompressedData(
            data = compressed,
            algorithm = algorithm,
            originalSize = data.size,
            compressionTime = duration
        )
    }

    fun decompress(compressedData: CompressedData): ByteArray {
        return when (compressedData.algorithm) {
            CompressionAlgorithm.LZ4 -> decompressLZ4(compressedData.data, compressedData.originalSize)
            CompressionAlgorithm.ZSTD -> decompressZstd(compressedData.data, compressedData.originalSize)
        }
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
        return com.github.luben.zstd.Zstd.compress(data)
    }

    private fun decompressZstd(data: ByteArray, originalSize: Int): ByteArray {
        return com.github.luben.zstd.Zstd.decompress(data, originalSize)
    }
}

object CompressionStrategy {
    val HOT_DATA = CompressionConfig(
        algorithm = CompressionAlgorithm.LZ4,
        minSize = 256,
        maxSize = 10 * 1024 * 1024
    )

    val WARM_DATA = CompressionConfig(
        algorithm = CompressionAlgorithm.LZ4,
        minSize = 128,
        maxSize = 50 * 1024 * 1024
    )

    val COLD_DATA = CompressionConfig(
        algorithm = CompressionAlgorithm.ZSTD,
        minSize = 64,
        maxSize = 100 * 1024 * 1024
    )

    fun selectStrategy(dataType: String): CompressionConfig {
        return when (dataType) {
            CacheKey.TYPE_DISCIPLE, CacheKey.TYPE_EQUIPMENT -> HOT_DATA
            CacheKey.TYPE_EVENT, CacheKey.TYPE_TEAM -> WARM_DATA
            CacheKey.TYPE_BATTLE_LOG -> COLD_DATA
            else -> WARM_DATA
        }
    }
}

data class CompressionConfig(
    val algorithm: CompressionAlgorithm,
    val minSize: Int,
    val maxSize: Int
)
