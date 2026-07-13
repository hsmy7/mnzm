package com.xianxia.sect.core.util

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
    private var state: Long,
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
    fun nextInt(): Int {
        val oldState = state
        state = oldState * MULTIPLIER + increment
        val xorShifted = ((oldState ushr 18) xor oldState) ushr 27
        val rot = oldState ushr 59
        return ((xorShifted ushr rot.toInt()) or (xorShifted shl ((-rot.toInt()) and 31))).toInt()
    }

    /** [0, bound) 范围随机整数 */
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
    fun nextLong(bound: Long = Long.MAX_VALUE): Long {
        if (bound <= 0) return 0L
        if (bound == Long.MAX_VALUE) return nextInt().toLong() and Long.MAX_VALUE
        return (nextInt().toLong() and 0xFFFFFFFFL) % bound
    }

    /** [0.0, 1.0) 范围随机 Double */
    fun nextDouble(): Double {
        return (nextInt().toLong() and 0x7FFFFFFFL) / 2147483648.0
    }

    /** 当前状态快照（用于序列化到存档） */
    fun snapshot(): Long = state

    /** 从快照恢复状态 */
    fun restore(savedState: Long) {
        state = savedState
    }

    override fun toString(): String = "DeterministicRng(state=$state, inc=$increment)"
}
