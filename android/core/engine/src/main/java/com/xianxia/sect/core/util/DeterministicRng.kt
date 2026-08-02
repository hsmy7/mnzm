package com.xianxia.sect.core.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * 可序列化确定性 PRNG — PCG-XSH-RR 算法。
 *
 * 状态仅 16 字节（2 个 Long），存档时直接序列化到 GameData.rngStates。
 * 相同初始种子在相同调用次数下保证产出完全相同的结果序列。
 *
 * 参考：PCG 算法由 Melissa O'Neill 设计 (pcg-random.org)
 */
@Serializable
class DeterministicRng(
    @Volatile private var state: Long,
    private val increment: Long = 1L
) {
    companion object {
        private const val MULTIPLIER = 6364136223846793005L
        private const val DEFAULT_INCREMENT = 1L

        /** 从种子创建实例 */
        fun fromSeed(seed: Long): DeterministicRng {
            var rng = DeterministicRng(0L, DEFAULT_INCREMENT)
            rng.state = (seed shl 1) or 1L
            rng.nextLong()  // One round to mix seed
            return rng
        }
    }

    /** 产生下一个 32 位随机整数 */
    @Synchronized
    fun nextInt(): Int {
        val oldState = state
        state = oldState * MULTIPLIER + increment
        val xorShifted = ((oldState ushr 18) xor oldState) ushr 27
        val rot = oldState ushr 59
        return ((xorShifted ushr rot.toInt()) or (xorShifted shl ((-rot.toInt()) and 31))).toInt()
    }

    /** [0, bound) 范围随机整数 */
    @Synchronized
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, was $bound" }
        // Lemire 无偏回绝采样
        val t = (nextInt().toLong() and 0xFFFFFFFFL) * bound.toLong()
        val low32 = (t and 0xFFFFFFFFL).toInt()
        if (low32 < bound) {
            val threshold = Int.MAX_VALUE.toLong() % bound.toLong()
            while (low32 < threshold.toInt()) {
                val newT = (nextInt().toLong() and 0xFFFFFFFFL) * bound.toLong()
                return (newT ushr 32).toInt()
            }
        }
        return (t ushr 32).toInt()
    }

    /** [0, bound) 范围随机 Long */
    @Synchronized
    fun nextLong(bound: Long = Long.MAX_VALUE): Long {
        if (bound <= 0) return 0L
        if (bound == Long.MAX_VALUE) return nextInt().toLong() and Long.MAX_VALUE
        return (nextInt().toLong() and 0xFFFFFFFFL) % bound
    }

    /** [0.0, 1.0) 范围随机 Double */
    @Synchronized
    fun nextDouble(): Double {
        return (nextInt().toLong() and 0x7FFFFFFFL) / 2147483648.0
    }

    /**
     * 从正态（高斯）分布生成随机值。
     * 使用 Box-Muller 变换：消耗 2 次 [nextDouble] 调用，产生 1 个标准正态偏差。
     * 不缓存配对中的第二个值，保持无状态以不影响 [snapshot]/[restore] 确定性。
     *
     * @param mean  分布均值（默认 0.0）
     * @param stddev 分布标准差（默认 1.0）
     * @return 服从 N(mean, stddev^2) 的 Double 值
     */
    @Synchronized
    fun nextGaussian(mean: Double = 0.0, stddev: Double = 1.0): Double {
        while (true) {
            val u1 = nextDouble()
            if (u1 == 0.0) continue // 避免 ln(0) = -inf
            val u2 = nextDouble()
            val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
            return z * stddev + mean
        }
    }

    /** 当前状态快照（用于序列化到存档） */
    @Synchronized
    fun snapshot(): Long = state

    /** 从快照恢复状态 */
    @Synchronized
    fun restore(savedState: Long) {
        state = savedState
    }

    override fun toString(): String = "DeterministicRng(state=$state, inc=$increment)"
}

/**
 * 将 [DeterministicRng] 适配为 [kotlin.random.Random]。
 * 用于调用 `:core:domain` 模块中仍以 `kotlin.random.Random` 为参数的 API
 *（如 [SpiritRootGenerator.generate]、[GameUtils.applyPriceFluctuation]）。
 */
fun DeterministicRng.asKotlinRandom(): kotlin.random.Random = object : kotlin.random.Random() {
    override fun nextBits(bitCount: Int): Int = (this@asKotlinRandom.nextInt() ushr (32 - bitCount))
    override fun nextInt(bound: Int): Int = this@asKotlinRandom.nextInt(bound)
    override fun nextInt(from: Int, until: Int): Int = from + this@asKotlinRandom.nextInt(until - from)
    override fun nextDouble(): Double = this@asKotlinRandom.nextDouble()
}

/**
 * 使用指定游戏分区 PRNG 打乱集合顺序（Q-7：从 RngExt.kt 合并）。
 *
 * 替代 [Iterable.shuffled]（底层使用 [kotlin.random.Random]），
 * 确保随机序列经过 [GameRngManager] 的分区 PRNG，支持存档确定性恢复。
 *
 * @param rng 游戏分区 PRNG 实例（通过 [GameRngManager.getRng] 获取）
 * @return 打乱后的新列表
 */
fun <T> Iterable<T>.shuffled(rng: DeterministicRng): List<T> {
    return map { it to rng.nextInt() }.sortedBy { it.second }.map { it.first }
}
