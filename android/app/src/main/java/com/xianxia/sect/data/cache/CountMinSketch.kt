package com.xianxia.sect.data.cache

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.ln
import android.util.Log

/**
 * ## Count-Min Sketch 数据结构
 *
 * 用于 W-TinyLFU 缓存淘汰策略中的频率估计。
 * 通过概率数据结构以较小的内存开销近似统计元素的访问频率。
 *
 * ### 算法原理
 * - 使用 depth 行 width 列的计数表存储频率信息
 * - 每行使用独立的哈希函数将 key 映射到某一列
 * - 查询时取所有行中最小值作为估计值（保证不低估）
 * - 支持周期性衰减（reset）以适应访问模式变化
 *
 * ### 参数说明
 * - **epsilon**: 误差上界，实际频率与估计值的偏差不超过 epsilon * 总计数
 * - **confidence**: 置信度，估计值不超过真实值的概率
 * - **width**: 列数 = ceil(e / epsilon)，e 为自然常数
 * - **depth**: 行数 = ceil(ln(1 / (1 - confidence)))
 */
class CountMinSketch(
    private val width: Int = DEFAULT_WIDTH,
    private val depth: Int = DEFAULT_DEPTH,
    private val epsilon: Double = DEFAULT_EPSILON,
    private val confidence: Double = DEFAULT_CONFIDENCE,
    private val resetIntervalMs: Long = DEFAULT_RESET_INTERVAL_MS
) {
    companion object {
        private const val TAG = "CountMinSketch"

        /** 默认列数（宽度） */
        const val DEFAULT_WIDTH = 1024

        /** 默认行数（深度） */
        const val DEFAULT_DEPTH = 4

        /** 默认误差上界 */
        const val DEFAULT_EPSILON = 0.01

        /** 默认置信度 */
        const val DEFAULT_CONFIDENCE = 0.99

        /** 默认重置间隔（毫秒），约 10 分钟 */
        const val DEFAULT_RESET_INTERVAL_MS = 600_000L

        /** 触发重置的最小操作数门槛 */
        private const val MIN_OPERATIONS_FOR_RESET = 100

        /** 用于多哈希模拟的质数种子（每行一个不同质数） */
        private val HASH_SEEDS = longArrayOf(
            0x9e3779b9L, // 黄金比例相关的质数
            0x85ebca6bL, // FNV 相关的质数
            0xc2b2ae3dL, // 另一个优质哈希种子
            0xa2b1c3d5L  // 第四个独立种子
        )
    }

    /** 计数表：depth 行 x width 列 */
    private val table: Array<IntArray> = Array(depth) { IntArray(width) }

    /** 上次重置时间戳 */
    @Volatile
    private var lastResetTime: Long = System.currentTimeMillis()

    /** 原子化的总计数器（用于线程安全读取） */
    private val atomicTotalCount = AtomicLong(0L)

    /**
     * 对指定 key 的计数加一
     *
     * @param key 需要计数的键
     */
    fun increment(key: String) {
        for (i in 0 until depth) {
            val hashIndex = hash(key, i)
            synchronized(table[i]) {
                table[i][hashIndex]++
            }
        }
        atomicTotalCount.incrementAndGet()
    }

    /**
     * 获取指定 key 的估计频率（取所有行最小值）
     *
     * @param key 查询的键
     * @return 估计的访问次数
     */
    fun count(key: String): Int {
        var minCount = Int.MAX_VALUE
        for (i in 0 until depth) {
            val hashIndex = hash(key, i)
            synchronized(table[i]) {
                minCount = minOf(minCount, table[i][hashIndex])
            }
        }
        return if (minCount == Int.MAX_VALUE) 0 else minCount
    }

    /**
     * 获取归一化后的估计频率（相对于总计数）
     *
     * @param key 查询的键
     * @return 归一化的频率估计值 [0.0, 1.0]
     */
    fun estimatedFrequency(key: String): Double {
        val total = atomicTotalCount.get()
        if (total <= 0) return 0.0
        return count(key).toDouble() / total
    }

    /**
     * 重置所有计数器（衰减）
     *
     * 将所有计数减半（右移一位），实现平滑衰减而非清零，
     * 这样可以保留历史访问模式的相对关系。
     */
    fun reset() {
        for (i in 0 until depth) {
            synchronized(table[i]) {
                for (j in 0 until width) {
                    table[i][j] = table[i][j] ushr 1  // 无符号右移，相当于除以 2
                }
            }
        }
        val oldCount = atomicTotalCount.getAndSet(atomicTotalCount.get() ushr 1)
        lastResetTime = System.currentTimeMillis()

        Log.d(TAG, "Reset completed, total count: $oldCount -> ${atomicTotalCount.get()}")
    }

    /**
     * 检查是否需要执行周期性衰减
     *
     * 增加了最小操作数门槛（atomicTotalCount > 100），
     * 避免在低负载时频繁重置导致统计数据丢失。
     *
     * @return true 如果距离上次重置时间超过 resetIntervalMs 且总操作数超过门槛
     */
    fun shouldReset(): Boolean {
        val timeExceeded = System.currentTimeMillis() - lastResetTime >= resetIntervalMs
        val sufficientOperations = atomicTotalCount.get() > MIN_OPERATIONS_FOR_RESET
        return timeExceeded && sufficientOperations
    }

    /**
     * 获取当前总计数
     */
    fun getTotalCount(): Long = atomicTotalCount.get()

    /**
     * 获取上次重置时间
     */
    fun getLastResetTime(): Long = lastResetTime

    /**
     * 获取表格大小（用于监控）
     */
    fun getTableSize(): Pair<Int, Int> = Pair(depth, width)

    /**
     * 多哈希函数：使用 key.hashCode() 与不同质数种子组合模拟多路哈希
     *
     * 实际生产环境可替换为 MurmurHash/xxHash 等高质量哈希函数，
     * 此处使用 hashCode + 质数乘法的方式在保持简洁的同时提供足够的分布质量。
     *
     * @param key 输入键
     * @param row 行索引（决定使用哪个种子）
     * @return 该行内的列索引 [0, width)
     */
    private fun hash(key: String, row: Int): Int {
        val seed = HASH_SEEDS.getOrNull(row) ?: (row * 0x9e3779b9L + 0x85ebca6bL)
        val hashValue = key.hashCode().toLong() * seed
        return ((hashValue ushr 16) and 0x7FFFFFFF).toInt() % width
    }
}
