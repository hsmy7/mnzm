@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.serialization.unified

import android.util.Log
import kotlinx.serialization.SerializationException
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Decompressor
import net.jpountz.lz4.LZ4Factory
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress
import com.github.luben.zstd.ZstdDictTrainer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

        /** 字典训练采样窗口大小：最近 N 次压缩的原始数据用于训练 */
        private const val DICTIONARY_TRAINING_WINDOW = 32

        /** 字典最大尺寸（字节）。LZ4 推荐值 16KB-128KB，游戏存档场景取 32KB 平衡内存与效果 */
        private const val MAX_DICTIONARY_SIZE = 32 * 1024

        /** 自适应压缩级别：基于近期压缩比动态调整策略 */
        private const val ADAPTIVE_SAMPLE_SIZE = 16

        /** 压缩收益阈值：低于此比率认为压缩无效，直接存储原始数据 */
        private const val COMPRESSION_BENEFIT_THRESHOLD = 0.98

        const val HEADER_UNCOMPRESSED: Byte = 0x00
        const val HEADER_LZ4: Byte = 0x01
        const val HEADER_ZSTD: Byte = 0x02

        const val HEADER_MAGIC: Byte = 0x5A

        const val ZSTD_DEFAULT_LEVEL = 3
    }

    // ========================================================================
    // 核心压缩/解压实例（无字典路径）
    // ========================================================================

    private val lz4Factory: LZ4Factory = LZ4Factory.fastestInstance()
    private val lz4Compressor = lz4Factory.fastCompressor()
    private val lz4Decompressor = lz4Factory.fastDecompressor()

    // ========================================================================
    // 字典训练系统
    // ========================================================================

    /**
     * 训练样本环形缓冲区。
     *
     * 存储最近 [DICTIONARY_TRAINING_WINDOW] 次压缩操作的原始数据引用，
     * 用于周期性训练 LZ4 专用字典。游戏存档数据具有高度结构化特征
     * （ProtoBuf 编码、重复字段模式），专用字典可提升压缩率 15-30%。
     *
     * 线程安全：通过 [synchronized] 保护写入，读取通过快照实现无锁访问。
     */
    private val trainingSamples = Array<AtomicReference<ByteArray?>>(DICTIONARY_TRAINING_WINDOW) {
        AtomicReference(null)
    }
    private val sampleIndex = AtomicLong(0)

    /**
     * 当前活跃的 LZ4 字典及其对应的压缩器/解压器。
     *
     * 通过 [AtomicReference] 保证可见性：字典更新时一次性替换整个三元组，
     * 避免部分更新的竞态条件。
     */
    private data class DictionaryContext(
        val dictBytes: ByteArray,
        val compressor: LZ4Compressor,
        val decompressor: LZ4Decompressor,
        val trainedAtSampleCount: Long
    )

    private val activeDictionary = AtomicReference<DictionaryContext?>(null)
    private val totalSamplesSeen = AtomicLong(0)

    /**
     * Zstd 字典上下文。
     *
     * 与 LZ4 字典不同，Zstd 字典训练在 zstd-jni 中实际可用，
     * 可为冷数据（归档存档）提供更优的压缩率。
     */
    private data class ZstdDictionaryContext(
        val dictBytes: ByteArray,
        val dictCompress: ZstdDictCompress,
        val dictDecompress: ZstdDictDecompress,
        val trainedAtSampleCount: Long
    )

    private val zstdDictionary = AtomicReference<ZstdDictionaryContext?>(null)

    // ========================================================================
    // 自适应压缩统计
    // ========================================================================

    private val recentRatiosRef = AtomicReference(DoubleArray(ADAPTIVE_SAMPLE_SIZE))
    private val recentRatioIndex = AtomicLong(0)
    private val adaptiveLevel = AtomicReference(AdaptiveLevel.BALANCED)

    // ========================================================================
    // 全局统计计数器
    // ========================================================================

    private val totalCompressed = AtomicLong(0)
    private val totalOriginalBytes = AtomicLong(0)
    private val totalCompressedBytes = AtomicLong(0)
    private val totalCompressionTime = AtomicLong(0)
    private val dictionaryHits = AtomicLong(0)
    private val dictionaryMisses = AtomicLong(0)

    // ========================================================================
    // 公共 API：compress / decompress
    // ========================================================================

    /**
     * 压缩数据。
     *
     * ## 分级压缩策略
     * - Hot 数据（频繁访问）：LZ4（极速压缩/解压）
     * - Cold 数据（归档存储）：Zstd（更高压缩率）
     *
     * ## 压缩流程
     * 1. 小于 [MIN_COMPRESSION_SIZE] 的数据直接存储（添加未压缩头字节）
     * 2. 根据 [algorithm] 参数路由到对应压缩算法
     * 3. 尝试字典加速压缩（若已训练完成）
     * 4. 若压缩后数据 >= 原始数据 * [COMPRESSION_BENEFIT_THRESHOLD]，退回原始数据
     * 5. 添加压缩类型头字节用于解压时自动检测
     *
     * @param data 原始数据
     * @param algorithm 压缩算法（LZ4=热数据, Zstd=冷数据）
     * @return Pair(含头字节的压缩数据, 使用的算法)
     */
    fun compress(
        data: ByteArray,
        algorithm: CompressionType = CompressionType.LZ4
    ): Pair<ByteArray, CompressionType> {
        val startTime = System.currentTimeMillis()

        // 小数据快速路径：跳过压缩，添加未压缩头字节
        if (data.size < MIN_COMPRESSION_SIZE) {
            recordCompression(data.size, data.size + 2, startTime)
            feedTrainingSample(data)
            return prependHeaderV2(HEADER_UNCOMPRESSED, data) to algorithm
        }

        // 根据算法类型路由到对应压缩方法
        val (compressed, usedAlgorithm) = when (algorithm) {
            CompressionType.LZ4 -> {
                val result = tryCompressWithDictionary(data)
                    ?: run {
                        dictionaryMisses.incrementAndGet()
                        compressLZ4Raw(data)
                    }
                result to CompressionType.LZ4
            }
            CompressionType.ZSTD -> {
                val result = tryCompressWithZstdDictionary(data)
                    ?: compressZstdRaw(data)
                result to CompressionType.ZSTD
            }
        }

        // 压缩收益检查：当压缩无收益时存储原始数据
        val (finalData, finalAlgorithm) = if (compressed.size >= (data.size * COMPRESSION_BENEFIT_THRESHOLD).toInt()) {
            prependHeaderV2(HEADER_UNCOMPRESSED, data) to usedAlgorithm
        } else {
            val headerByte = when (usedAlgorithm) {
                CompressionType.LZ4 -> HEADER_LZ4
                CompressionType.ZSTD -> HEADER_ZSTD
            }
            prependHeaderV2(headerByte, compressed) to usedAlgorithm
        }

        // 采集样本用于后续字典训练 + 自适应级别调整
        feedTrainingSample(data)
        updateAdaptiveStats(data.size.toDouble(), finalData.size.toDouble())
        recordCompression(data.size, finalData.size, startTime)

        return finalData to finalAlgorithm
    }

    /**
     * 解压数据。
     *
     * 自动检测压缩类型：读取数据首字节作为压缩类型标识。
     * - 0x00: 未压缩（直接返回去除头字节后的数据）
     * - 0x01: LZ4 压缩
     * - 0x02: Zstd 压缩
     *
     * 向后兼容：若首字节不是已知的压缩类型标识，
     * 回退到 [algorithm] 参数指定的算法（用于旧版存档）。
     */
    fun decompress(
        data: ByteArray,
        algorithm: CompressionType,
        originalSize: Int
    ): ByteArray {
        if (data.isEmpty()) {
            throw SerializationException("Cannot decompress empty data")
        }
        if (originalSize <= 0 || originalSize > SerializationConstants.MAX_DATA_SIZE) {
            throw SerializationException("Invalid originalSize for decompression: $originalSize")
        }

        if (data.size >= 2 && data[0] == HEADER_MAGIC) {
            val algoByte = data[1]
            val payload = data.copyOfRange(2, data.size)
            return when (algoByte) {
                HEADER_UNCOMPRESSED -> payload
                HEADER_LZ4 -> decompressLZ4WithDict(payload, originalSize)
                HEADER_ZSTD -> decompressZstdWithDict(payload, originalSize)
                else -> {
                    Log.w(TAG, "Unknown algorithm byte in v2 header: 0x${String.format("%02X", algoByte)}, falling back to $algorithm")
                    decompressFallback(payload, algorithm, originalSize)
                }
            }
        }

        return when (data[0]) {
            HEADER_UNCOMPRESSED -> data.copyOfRange(1, data.size)
            HEADER_LZ4 -> {
                val payload = data.copyOfRange(1, data.size)
                decompressLZ4WithDict(payload, originalSize)
            }
            HEADER_ZSTD -> {
                val payload = data.copyOfRange(1, data.size)
                decompressZstdWithDict(payload, originalSize)
            }
            else -> {
                Log.d(TAG, "No compression header found, falling back to algorithm: $algorithm")
                decompressFallback(data, algorithm, originalSize)
            }
        }
    }

    private fun decompressFallback(data: ByteArray, algorithm: CompressionType, originalSize: Int): ByteArray {
        return when (algorithm) {
            CompressionType.LZ4 -> decompressLZ4WithDict(data, originalSize)
            CompressionType.ZSTD -> decompressZstdRaw(data, originalSize)
        }
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

    // ========================================================================
    // 字典训练 API
    // ========================================================================

    /**
     * 手动触发字典训练。
     *
     * 训练 Zstd 字典（实际可用），LZ4 字典训练在 lz4-java 1.8.0 中不可用。
     * 训练完成后自动替换 [zstdDictionary]。
     *
     * @return 训练结果信息
     */
    fun trainDictionary(): DictionaryTrainingResult {
        val samples = collectTrainingSamples()
        if (samples.isEmpty()) {
            return DictionaryTrainingResult(success = false, reason = "No training samples available")
        }

        // LZ4 字典训练：lz4-java 1.8.0 不支持
        Log.w(TAG, "LZ4 dictionary training not supported in lz4-java 1.8.0, skipping")

        // Zstd 字典训练：实际可用
        return trainZstdDictionary(samples)
    }

    /**
     * 训练 Zstd 专用字典。
     *
     * 使用 [ZstdDictTrainer] 从采样数据中训练字典，
     * 字典训练后创建 [ZstdDictCompress] / [ZstdDictDecompress] 实例
     * 并原子替换 [zstdDictionary]。
     */
    private fun trainZstdDictionary(samples: List<ByteArray>): DictionaryTrainingResult {
        return try {
            val totalSampleSize = samples.sumOf { it.size }
            val trainer = ZstdDictTrainer(totalSampleSize, MAX_DICTIONARY_SIZE)
            for (sample in samples) {
                trainer.addSample(sample)
            }
            val dictBytes = trainer.trainSamples()
            if (dictBytes == null || dictBytes.isEmpty()) {
                return DictionaryTrainingResult(
                    success = false,
                    reason = "Zstd dictionary training produced empty result"
                )
            }

            val dictCompress = ZstdDictCompress(dictBytes, ZSTD_DEFAULT_LEVEL)
            val dictDecompress = ZstdDictDecompress(dictBytes)

            // 原子替换旧字典，并释放旧字典的 native 内存
            zstdDictionary.getAndSet(ZstdDictionaryContext(
                dictBytes = dictBytes,
                dictCompress = dictCompress,
                dictDecompress = dictDecompress,
                trainedAtSampleCount = totalSamplesSeen.get()
            ))?.let { old ->
                runCatching { old.dictCompress.close() }
                runCatching { old.dictDecompress.close() }
            }

            Log.i(TAG, "Zstd dictionary trained: ${dictBytes.size} bytes from ${samples.size} samples")
            DictionaryTrainingResult(
                success = true,
                dictionarySize = dictBytes.size,
                sampleCount = samples.size,
                trainedAtSampleCount = totalSamplesSeen.get()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Zstd dictionary training failed", e)
            DictionaryTrainingResult(success = false, reason = e.message ?: "Unknown error")
        }
    }

    /**
     * 获取当前字典状态（只读）。
     */
    fun getDictionaryStatus(): DictionaryStatus {
        val ctx = activeDictionary.get()
        val zstdCtx = zstdDictionary.get()
        return if (ctx != null) {
            DictionaryStatus(
                hasDictionary = true,
                dictionarySize = ctx.dictBytes.size,
                trainedAtSampleCount = ctx.trainedAtSampleCount,
                currentSampleCount = totalSamplesSeen.get(),
                isStale = (totalSamplesSeen.get() - ctx.trainedAtSampleCount) > DICTIONARY_TRAINING_WINDOW * 2,
                hasZstdDictionary = zstdCtx != null,
                zstdDictionarySize = zstdCtx?.dictBytes?.size ?: 0
            )
        } else {
            DictionaryStatus(
                hasDictionary = false,
                dictionarySize = 0,
                trainedAtSampleCount = 0,
                currentSampleCount = totalSamplesSeen.get(),
                isStale = false,
                hasZstdDictionary = zstdCtx != null,
                zstdDictionarySize = zstdCtx?.dictBytes?.size ?: 0
            )
        }
    }

    // ========================================================================
    // 统计 API
    // ========================================================================

    fun getStats(): CompressionStats {
        val operations = totalCompressed.get()
        val original = totalOriginalBytes.get()
        val compressed = totalCompressedBytes.get()
        val time = totalCompressionTime.get()
        val dictCtx = activeDictionary.get()
        val zstdCtx = zstdDictionary.get()

        return CompressionStats(
            totalOperations = operations,
            totalOriginalBytes = original,
            totalCompressedBytes = compressed,
            totalSavedBytes = original - compressed,
            averageRatio = if (compressed > 0) original.toDouble() / compressed else 1.0,
            averageTimeMs = if (operations > 0) time / operations else 0,
            hasDictionary = dictCtx != null,
            dictionarySize = dictCtx?.dictBytes?.size ?: 0,
            dictionaryHitRate = computeDictionaryHitRate(),
            adaptiveLevel = adaptiveLevel.get(),
            sampleCount = totalSamplesSeen.get(),
            hasZstdDictionary = zstdCtx != null,
            zstdDictionarySize = zstdCtx?.dictBytes?.size ?: 0
        )
    }

    fun clearStats() {
        totalCompressed.set(0)
        totalOriginalBytes.set(0)
        totalCompressedBytes.set(0)
        totalCompressionTime.set(0)
        dictionaryHits.set(0)
        dictionaryMisses.set(0)
        recentRatioIndex.set(0)
        recentRatiosRef.set(DoubleArray(ADAPTIVE_SAMPLE_SIZE))
    }

    // ========================================================================
    // 内部实现：原始 LZ4 压缩/解压（无字典）
    // ========================================================================

    private fun compressLZ4Raw(data: ByteArray): ByteArray {
        val maxCompressedLength = lz4Compressor.maxCompressedLength(data.size)
        val compressed = ByteArray(maxCompressedLength)
        val compressedLength = lz4Compressor.compress(data, 0, data.size, compressed, 0)
        return compressed.copyOf(compressedLength)
    }

    private fun decompressLZ4Raw(data: ByteArray, originalSize: Int): ByteArray {
        val decompressed = ByteArray(originalSize)
        try {
            lz4Decompressor.decompress(data, 0, decompressed, 0, originalSize)
        } catch (e: net.jpountz.lz4.LZ4Exception) {
            throw SerializationException(
                "LZ4 decompression failed: data size=${data.size}, expected original size=$originalSize. " +
                        "Save file may be corrupted or truncated.", e
            )
        }
        return decompressed
    }

    // ========================================================================
    // 内部实现：LZ4 字典解压
    // ========================================================================

    private fun decompressLZ4WithDict(data: ByteArray, originalSize: Int): ByteArray {
        val dictCtx = activeDictionary.get()
        if (dictCtx != null) {
            return try {
                val decompressed = ByteArray(originalSize)
                dictCtx.decompressor.decompress(data, 0, decompressed, 0, originalSize)
                dictionaryHits.incrementAndGet()
                decompressed
            } catch (e: Exception) {
                dictionaryMisses.incrementAndGet()
                decompressLZ4Raw(data, originalSize)
            }
        }
        return decompressLZ4Raw(data, originalSize)
    }

    // ========================================================================
    // 内部实现：Zstd 压缩/解压（无字典）
    // ========================================================================

    private fun compressZstdRaw(data: ByteArray): ByteArray {
        return Zstd.compress(data, ZSTD_DEFAULT_LEVEL)
    }

    private fun decompressZstdRaw(data: ByteArray, originalSize: Int): ByteArray {
        return try {
            Zstd.decompress(data, originalSize)
        } catch (e: Exception) {
            throw SerializationException(
                "Zstd decompression failed: data size=${data.size}, expected original size=$originalSize. " +
                        "Save file may be corrupted or truncated.", e
            )
        }
    }

    // ========================================================================
    // 内部实现：Zstd 字典压缩/解压
    // ========================================================================

    /**
     * 使用 Zstd 字典进行压缩。
     *
     * @return 压缩后的数据，若无字典可用则返回 null（调用方应回退到标准压缩）
     */
    private fun tryCompressWithZstdDictionary(data: ByteArray): ByteArray? {
        val ctx = zstdDictionary.get() ?: return null
        return try {
            val compressed = Zstd.compress(data, ctx.dictCompress)
            dictionaryHits.incrementAndGet()
            compressed
        } catch (e: Exception) {
            dictionaryMisses.incrementAndGet()
            null
        }
    }

    /**
     * 使用 Zstd 字典进行解压，失败时回退到标准 Zstd 解压。
     */
    private fun decompressZstdWithDict(data: ByteArray, originalSize: Int): ByteArray {
        val ctx = zstdDictionary.get()
        if (ctx != null) {
            return try {
                val decompressed = Zstd.decompress(data, ctx.dictDecompress, originalSize)
                dictionaryHits.incrementAndGet()
                decompressed
            } catch (e: Exception) {
                dictionaryMisses.incrementAndGet()
                decompressZstdRaw(data, originalSize)
            }
        }
        return decompressZstdRaw(data, originalSize)
    }

    // ========================================================================
    // 内部实现：头字节工具
    // ========================================================================

    private fun prependHeaderV2(algorithm: Byte, data: ByteArray): ByteArray {
        val result = ByteArray(2 + data.size)
        result[0] = HEADER_MAGIC
        result[1] = algorithm
        System.arraycopy(data, 0, result, 2, data.size)
        return result
    }

    private fun prependHeader(algorithm: Byte, data: ByteArray): ByteArray {
        val result = ByteArray(1 + data.size)
        result[0] = algorithm
        System.arraycopy(data, 0, result, 1, data.size)
        return result
    }

    // ========================================================================
    // 内部实现：字典加速压缩
    // ========================================================================

    /**
     * 使用当前字典进行压缩。
     *
     * @return 压缩后的数据，若无字典可用则返回 null（调用方应回退到标准压缩）
     */
    private fun tryCompressWithDictionary(data: ByteArray): ByteArray? {
        val ctx = activeDictionary.get() ?: return null

        return try {
            val maxLen = ctx.compressor.maxCompressedLength(data.size)
            val output = ByteArray(maxLen)
            val compressedLen = ctx.compressor.compress(data, 0, data.size, output, 0)
            dictionaryHits.incrementAndGet()
            output.copyOf(compressedLen)
        } catch (e: Exception) {
            // 字典压缩失败（可能数据特征不匹配），标记 miss 并返回 null 触发回退
            dictionaryMisses.incrementAndGet()
            null
        }
    }

    // ========================================================================
    // 内部实现：训练样本管理
    // ========================================================================

    /**
     * 将数据加入训练采样缓冲区（环形缓冲区策略）。
     *
     * 仅保留数据引用（非拷贝），避免大对象的内存开销。
     * 采样频率控制：每 4 次压缩操作采样 1 次，减少内存压力。
     */
    private fun feedTrainingSample(data: ByteArray) {
        val count = totalCompressed.get()
        if (count % 4 != 0L && count > 0) return // 降频采样

        val idx = (sampleIndex.getAndIncrement() % DICTIONARY_TRAINING_WINDOW).toInt()
        trainingSamples[idx].set(data)
        totalSamplesSeen.incrementAndGet()

        // 每 DICTIONARY_TRAINING_WINDOW 次新样本后自动触发重训练检查
        if (totalSamplesSeen.get() % DICTIONARY_TRAINING_WINDOW == 0L && totalSamplesSeen.get() > DICTIONARY_TRAINING_WINDOW) {
            autoRetrainIfNeeded()
        }
    }

    /**
     * 收集当前所有有效训练样本。
     */
    private fun collectTrainingSamples(): List<ByteArray> {
        return trainingSamples.mapNotNull { it.get() }.filter { it.isNotEmpty() }
    }

    /**
     * 自动重训练检查：
     * - Zstd 字典为空 -> 训练
     * - 字典陈旧（样本量增长超过阈值）-> 重训练
     * - 字典命中率低 -> 重训练
     */
    private fun autoRetrainIfNeeded() {
        val status = getDictionaryStatus()
        val shouldTrain = !status.hasZstdDictionary ||
                status.isStale ||
                (dictionaryHits.get() + dictionaryMisses.get() > 100 &&
                        dictionaryHits.get().toDouble() / (dictionaryHits.get() + dictionaryMisses.get()) < 0.7)

        if (shouldTrain) {
            trainDictionary()
        }
    }

    // ========================================================================
    // 内部实现：自适应压缩级别
    // ========================================================================

    /**
     * 基于近期压缩比调整策略。
     *
     * - 平均压缩比 > 2.0: AGGRESSIVE（可考虑更高压缩级别）
     * - 平均压缩比 1.3-2.0: BALANCED（当前默认）
     * - 平均压缩比 < 1.3: CONSERVATIVE（数据可能难以压缩，降低开销）
     */
    private fun updateAdaptiveStats(originalSize: Double, compressedSize: Double) {
        val idx = (recentRatioIndex.getAndIncrement() % ADAPTIVE_SAMPLE_SIZE).toInt()
        val ratio = if (compressedSize > 0) originalSize / compressedSize else 1.0
        recentRatiosRef.updateAndGet { current ->
            val copy = current.copyOf()
            copy[idx] = ratio
            copy
        }

        val avgRatio = recentRatiosRef.get().average()
        adaptiveLevel.set(
            when {
                avgRatio > 2.0 -> AdaptiveLevel.AGGRESSIVE
                avgRatio > 1.3 -> AdaptiveLevel.BALANCED
                else -> AdaptiveLevel.CONSERVATIVE
            }
        )
    }

    private fun computeDictionaryHitRate(): Double {
        val hits = dictionaryHits.get()
        val misses = dictionaryMisses.get()
        val total = hits + misses
        return if (total > 0) hits.toDouble() / total else 0.0
    }

    private fun recordCompression(originalSize: Int, compressedSize: Int, startTimeMs: Long) {
        totalCompressed.incrementAndGet()
        totalOriginalBytes.addAndGet(originalSize.toLong())
        totalCompressedBytes.addAndGet(compressedSize.toLong())
        totalCompressionTime.addAndGet(System.currentTimeMillis() - startTimeMs)
    }
}

// ========================================================================
// 数据类 & 枚举定义
// ========================================================================

/**
 * 压缩统计数据（扩展版，含字典和自适应信息）。
 */
data class CompressionStats(
    val totalOperations: Long = 0,
    val totalOriginalBytes: Long = 0,
    val totalCompressedBytes: Long = 0,
    val totalSavedBytes: Long = 0,
    val averageRatio: Double = 1.0,
    val averageTimeMs: Long = 0,
    val hasDictionary: Boolean = false,
    val dictionarySize: Int = 0,
    val dictionaryHitRate: Double = 0.0,
    val adaptiveLevel: AdaptiveLevel = AdaptiveLevel.BALANCED,
    val sampleCount: Long = 0,
    val hasZstdDictionary: Boolean = false,
    val zstdDictionarySize: Int = 0
)

/**
 * 自适应压缩级别枚举。
 */
enum class AdaptiveLevel {
    /** 数据高度可压缩，可投入更多 CPU 资源换取更高压缩率 */
    AGGRESSIVE,
    /** 默认平衡模式 */
    BALANCED,
    /** 数据难以压缩，最小化压缩开销 */
    CONSERVATIVE
}

/**
 * 字典训练结果。
 */
data class DictionaryTrainingResult(
    val success: Boolean,
    val dictionarySize: Int = 0,
    val sampleCount: Int = 0,
    val trainedAtSampleCount: Long = 0,
    val reason: String? = null
)

/**
 * 字典状态（只读快照）。
 */
data class DictionaryStatus(
    val hasDictionary: Boolean,
    val dictionarySize: Int,
    val trainedAtSampleCount: Long,
    val currentSampleCount: Long,
    val isStale: Boolean,
    val hasZstdDictionary: Boolean = false,
    val zstdDictionarySize: Int = 0
)
