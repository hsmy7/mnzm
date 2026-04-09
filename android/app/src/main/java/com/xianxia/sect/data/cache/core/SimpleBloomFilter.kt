package com.xianxia.sect.data.cache.core

import java.util.BitSet

/**
 * 简化版 BloomFilter 实现
 *
 * 用于缓存穿透防护，从 GameDataCacheManager 中提取的独立组件。
 * 使用双重哈希（Murmur-style 简化版）减少误判率。
 *
 * ## 设计原理
 * BloomFilter 是一种空间效率很高的概率型数据结构：
 * - **插入 (put)**: 将元素通过 k 个哈希函数映射到位数组的 k 个位置，置为 1
 * - **查询 (mightContain)**: 检查 k 个位置是否都为 1，若有任一为 0 则一定不存在
 * - **特性**: 不存在误判（false negative），但可能存在误判（false positive）
 *
 * ## 适用场景
 * - 缓存穿透防护：快速判断 key 是否可能存在，避免无效查询
 * - 去重判断：大规模数据去重
 * - 爬虫 URL 去重
 *
 * ## 参数调优建议
 * - expectedItems: 预期插入数量（影响位数组大小）
 * - falsePositiveRate: 可接受的误判率（影响哈希函数数量）
 *   - 0.01 (1%): 适合大多数缓存场景
 *   - 0.001 (0.1%): 高精度要求场景
 *   - 0.05 (5%): 容忍度较高的场景
 *
 * @param expectedItems 预期插入的元素数量
 * @param falsePositiveRate 可接受的误判率 (0-1)
 */
class SimpleBloomFilter(
    private val expectedItems: Int = 10000,
    private val falsePositiveRate: Double = 0.01
) {
    /** 位数组大小（根据预期元素数和误判率自动计算） */
    private val bitSetSize: Int = calculateOptimalSize(expectedItems, falsePositiveRate)

    /** 哈希函数数量（根据位数组大小和预期元素数自动计算） */
    private val numHashFunctions: Int = calculateOptimalHashCount(bitSetSize, expectedItems)

    /** 底层位数组存储 */
    private val bits: BitSet = BitSet(bitSetSize)

    /** 已插入元素计数（近似值） */
    @Volatile
    private var itemCount: Int = 0

    /**
     * 插入一个元素到 BloomFilter
     *
     * 将元素的哈希值映射到位数组的多个位置并置为 1。
     * 时间复杂度: O(k)，其中 k 为哈希函数数量
     *
     * @param item 要插入的字符串元素
     */
    fun put(item: String) {
        val hashes = computeHashes(item)
        hashes.forEach { hash ->
            bits.set(hash % bitSetSize)
        }
        itemCount++
    }

    /**
     * 检查元素是否可能存在
     *
     * 返回 true 表示元素**可能**存在（有误判可能）
     * 返回 false 表示元素**一定**不存在（无漏判）
     *
     * 典型使用模式:
     * ```kotlin
     * if (!bloomFilter.mightContain(key)) {
     *     // 一定不存在，可以安全跳过查询
     *     return null
     * }
     * // 可能存在，需要进一步查询确认
     * return cache.get(key)
     * ```
     *
     * @param item 要检查的字符串元素
     * @return true=可能存在, false=一定不存在
     */
    fun mightContain(item: String): Boolean {
        if (itemCount == 0) return false

        val hashes = computeHashes(item)
        return hashes.all { hash ->
            bits.get(hash % bitSetSize)
        }
    }

    /**
     * 清空 BloomFilter
     *
     * 重置所有状态，清空位数组和计数器。
     * 注意：标准 BloomFilter 不支持删除单个元素。
     */
    fun clear() {
        bits.clear()
        itemCount = 0
    }

    /**
     * 获取已插入元素的近似数量
     *
     * 注意：此值为插入计数的近似值，
     * 由于重复插入不会增加实际覆盖范围，实际唯一元素数可能小于此值。
     *
     * @return 近似元素数量
     */
    fun approximateSize(): Int = itemCount

    /**
     * 计算双重哈希值列表
     *
     * 使用双重哈希技术（Double Hashing）生成 k 个独立的哈希值：
     * - h1 = hashCode(item)
     * - h2 = hashCode(reverse(item))
     * - hi = h1 + i * h2 (i = 0, 1, ..., k-1)
     *
     * 这种方法只需计算两个基础哈希值，通过线性组合生成多个哈希值，
     * 比使用 k 个独立哈希函数更高效。
     *
     * @param item 输入字符串
     * @return 哈希值列表（长度为 numHashFunctions）
     */
    private fun computeHashes(item: String): List<Int> {
        val baseHash = item.hashCode()
        // 使用反转字符串作为第二个哈希种子，增加哈希多样性
        val hash2 = baseHash xor item.reversed().hashCode()

        return (0 until numHashFunctions).map { i ->
            baseHash + i * hash2
        }
    }

    companion object {
        /**
         * 计算最优位数组大小
         *
         * 公式: m = -n * ln(p) / (ln(2))^2
         *
         * 其中:
         * - n = 预期元素数量
         * - p = 目标误判率
         * - m = 位数组大小（bit 数）
         *
         * 最小限制为 64 bits，避免过小的数组导致性能问题
         *
         * @param n 预期元素数量
         * @param p 目标误判率
         * @return 最优位数组大小
         */
        private fun calculateOptimalSize(n: Int, p: Double): Int {
            require(n > 0) { "Expected items must be positive" }
            require(p in 0.0..1.0) { "False positive rate must be between 0 and 1" }

            val size = (-n * kotlin.math.ln(p) / (kotlin.math.ln(2.0).pow(2))).toInt()
            return size.coerceAtLeast(64)
        }

        /**
         * 计算最优哈希函数数量
         *
         * 公式: k = (m/n) * ln(2)
         *
         * 其中:
         * - m = 位数组大小
         * - n = 预期元素数量
         * - k = 哈希函数数量
         *
         * 限制在 [1, 8] 范围内：
         * - 最少 1 个哈希函数
         * - 最多 8 个哈希函数（过多会降低性能）
         *
         * @param m 位数组大小
         * @param n 预期元素数量
         * @return 最优哈希函数数量
         */
        private fun calculateOptimalHashCount(m: Int, n: Int): Int {
            require(m > 0) { "Bit set size must be positive" }
            require(n > 0) { "Expected items must be positive" }

            val count = (m.toDouble() / n * kotlin.math.ln(2.0)).toInt()
            return count.coerceIn(1, 8)
        }

        /**
         * Double 的整数幂运算（扩展函数）
         *
         * 计算 this^exp，其中 exp 为非负整数
         *
         * @param exp 指数（非负整数）
         * @return 幂运算结果
         */
        private fun Double.pow(exp: Int): Double {
            var result = 1.0
            repeat(exp) {
                result *= this
            }
            return result
        }
    }
}
