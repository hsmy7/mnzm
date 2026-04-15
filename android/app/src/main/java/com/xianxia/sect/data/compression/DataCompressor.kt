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

/**
 * 压缩使用场景枚举
 *
 * 用于 selectAlgorithm() 根据业务场景推荐最优压缩算法。
 */
enum class UseCase {
    /** 自动存档（高频、低延迟优先）-> LZ4 */
    AUTO_SAVE,
    /** 完整存档（压缩比优先）-> ZSTD */
    FULL_SAVE,
    /** 云存档上传（压缩比优先，减少带宽）-> ZSTD */
    CLOUD_UPLOAD,
    /** Legacy 兼容格式 -> GZIP */
    LEGACY
}

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
                CompressionAlgorithm.ZSTD -> compressZstd(data)
                CompressionAlgorithm.NONE -> data
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
                CompressionAlgorithm.ZSTD -> decompressZstd(compressedData.data)
                CompressionAlgorithm.NONE -> compressedData.data
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
                CompressionAlgorithm.ZSTD -> decompressZstd(data)
                CompressionAlgorithm.NONE -> data
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

    /**
     * 根据使用场景推荐最优压缩算法。
     *
     * 算法选择策略：
     * - AUTO_SAVE: LZ4（速度优先，~500MB/s，适合高频自动存档）
     * - FULL_SAVE: ZSTD（压缩比优先，3-5x 比率，减少磁盘占用）
     * - CLOUD_UPLOAD: ZSTD（压缩比优先，减少网络传输时间）
     * - LEGACY: GZIP（兼容性优先，确保旧版可读）
     *
     * @param dataSize 数据大小（字节），可用于进一步优化
     * @param dataType 数据类型（预留参数，未来可根据数据特征优化）
     * @param useCase 使用场景（必填）
     * @return 推荐的压缩算法
     */
    fun selectAlgorithm(dataSize: Int, dataType: DataType, useCase: UseCase): CompressionAlgorithm {
        return when (useCase) {
            UseCase.AUTO_SAVE -> CompressionAlgorithm.LZ4
            UseCase.FULL_SAVE -> {
                // 如果 ZSTD 不可用，降级到 GZIP（比 LZ4 压缩率更高）
                if (isZstdAvailable()) CompressionAlgorithm.ZSTD else CompressionAlgorithm.GZIP
            }
            UseCase.CLOUD_UPLOAD -> {
                // 云上传优先使用 ZSTD 获得最佳压缩率
                if (isZstdAvailable()) CompressionAlgorithm.ZSTD else CompressionAlgorithm.GZIP
            }
            UseCase.LEGACY -> CompressionAlgorithm.GZIP
        }
    }

    /**
     * 便捷方法：仅根据使用场景选择算法（不依赖 dataSize 和 dataType）。
     */
    fun selectAlgorithm(useCase: UseCase): CompressionAlgorithm {
        return selectAlgorithm(0, DataType.ARCHIVED, useCase)
    }

    // ==================== ZSTD 压缩/解压实现（wrapper 模式）====================

    /**
     * ZSTD 反射调用封装器（编译期安全 wrapper）。
     *
     * 设计目标：
     * - init() 时一次性完成所有反射查找和可用性检测，缓存结果
     * - 后续 compress/decompress 调用直接使用缓存的 Method 引用，零反射开销
     * - 若 zstd-jni 库不存在，init() 即确定 [isAvailable]=false，后续跳过所有反射路径
     * - 线程安全：object 单例 + @Volatile 标志位
     */
    private object ZstdWrapper {
        private const val TAG = "ZstdWrapper"
        private const val ZSTD_CLASS_NAME = "com.github.luben.zstd.Zstd"
        private const val DEFAULT_COMPRESSION_LEVEL = 3

        @Volatile
        var isAvailable = false
            private set

        // 缓存的反射引用（init 成功后不再变化）
        private var compressMethod: java.lang.reflect.Method? = null
        private var decompressMethod: java.lang.reflect.Method? = null

        /**
         * 一次性初始化：检测库存在性 + 缓存 Method 引用。
         * 必须在任何 compress/decompress 调用之前执行。
         */
        fun init() {
            if (isAvailable) return // 已初始化过

            try {
                val zstdClass = Class.forName(ZSTD_CLASS_NAME)
                compressMethod = zstdClass.getMethod("compress", ByteArray::class.java, Int::class.java)
                decompressMethod = zstdClass.getMethod("decompress", ByteArray::class.java)
                isAvailable = true
                Log.i(TAG, "ZstdWrapper initialized successfully: methods cached")
            } catch (e: ClassNotFoundException) {
                isAvailable = false
                Log.w(TAG, "ZSTD library not found: $ZSTD_CLASS_NAME. " +
                          "ZSTD operations will fallback to GZIP. " +
                          "To enable ZSTD, add to build.gradle: implementation 'com.github.luben:zstd-jni:1.5.6-6'")
            } catch (e: Exception) {
                isAvailable = false
                Log.e(TAG, "ZstdWrapper initialization failed", e)
            }
        }

        /**
         * 使用缓存的 Method 引用执行 ZSTD 压缩。
         *
         * @return 压缩后的字节数组，或 null 表示应 fallback 到 GZIP
         */
        fun compress(data: ByteArray): ByteArray? {
            if (!isAvailable || compressMethod == null) return null

            return try {
                compressMethod!!.invoke(null, data, DEFAULT_COMPRESSION_LEVEL) as ByteArray
            } catch (e: Exception) {
                Log.e(TAG, "ZSTD compress invocation failed", e)
                null
            }
        }

        /**
         * 使用缓存的 Method 引用执行 ZSTD 解压。
         *
         * @return 解压后的原始数据，或 null 表示应 fallback 到 GZIP
         */
        fun decompress(compressedData: ByteArray): ByteArray? {
            if (!isAvailable || decompressMethod == null) return null

            return try {
                decompressMethod!!.invoke(null, compressedData) as ByteArray
            } catch (e: Exception) {
                Log.e(TAG, "ZSTD decompress invocation failed", e)
                null
            }
        }
    }

    /**
     * 检查 ZSTD 库是否可用（委托给 ZstdWrapper）。
     */
    private fun isZstdAvailable(): Boolean {
        ZstdWrapper.init()
        return ZstdWrapper.isAvailable
    }

    /**
     * 使用 ZSTD 算法压缩数据（带自动 fallback）。
     *
     * 压缩参数：
     * - 压缩级别：3（中等压缩级别，平衡速度和压缩率）
     *
     * Fallback 策略：
     * - 若 zstd-jni 库不可用或调用失败，自动降级到 GZIP 压缩
     *
     * @param data 原始数据
     * @return 压缩后的数据（可能是 ZSTD 或 GZIP 格式）
     */
    private fun compressZstd(data: ByteArray): ByteArray {
        ZstdWrapper.init()

        if (!ZstdWrapper.isAvailable) {
            Log.w(TAG, "ZSTD not available, falling back to GZIP for compression (${data.size} bytes)")
            return compressGzip(data)
        }

        return ZstdWrapper.compress(data) ?: run {
            Log.e(TAG, "ZSTD compression failed, falling back to GZIP")
            compressGzip(data)
        }.also {
            Log.d(TAG, "ZSTD compression successful: ${data.size} -> ${it.size} bytes (level=3)")
        }
    }

    /**
     * 使用 ZSTD 算法解压数据（带自动 fallback）。
     *
     * 注意事项：
     * - 若数据实际是 GZIP 格式（因压缩时 fallback），会自动尝试 GZIP 解压
     *
     * @param compressedData 压缩数据（ZSTD 或 GZIP 格式）
     * @return 解压后的原始数据
     * @throws CompressionException 若解压失败
     */
    private fun decompressZstd(compressedData: ByteArray): ByteArray {
        ZstdWrapper.init()

        if (!ZstdWrapper.isAvailable) {
            Log.w(TAG, "ZSTD not available, attempting GZIP decompression (${compressedData.size} bytes)")
            return decompressGzip(compressedData)
        }

        return ZstdWrapper.decompress(compressedData) ?: run {
            Log.e(TAG, "ZSTD decompression failed, trying GZIP as fallback")
            try {
                decompressGzip(compressedData)
            } catch (gzipEx: Exception) {
                Log.e(TAG, "GZIP fallback decompression also failed", gzipEx)
                throw CompressionException("All decompression methods failed (ZSTD + GZIP fallback)", gzipEx)
            }
        }.also {
            Log.d(TAG, "ZSTD decompression successful: ${compressedData.size} -> ${it.size} bytes")
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

data class CompressedData(
    val data: ByteArray,
    val algorithm: CompressionAlgorithm,
    val originalSize: Int,
    val compressionTime: Long = 0
) {
    fun toStorageFormat(): ByteArray {
        val header = ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(header)
        dos.writeByte(algorithm.ordinal)
        dos.writeInt(originalSize)
        dos.writeLong(compressionTime)
        dos.writeInt(data.size)
        dos.flush()
        return header.toByteArray() + data
    }

    companion object {
        fun fromStorageFormat(bytes: ByteArray): CompressedData {
            val dis = java.io.DataInputStream(ByteArrayInputStream(bytes))
            val algorithm = CompressionAlgorithm.entries[dis.readByte().toInt()]
            val originalSize = dis.readInt()
            val compressionTime = dis.readLong()
            val dataSize = dis.readInt()
            val data = ByteArray(dataSize)
            dis.readFully(data)
            return CompressedData(data, algorithm, originalSize, compressionTime)
        }
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
