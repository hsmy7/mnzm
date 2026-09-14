package com.xianxia.sect.core.util

/**
 * RngRandomAdapter — [DeterministicRng] → kotlin.random.Random 适配器。
 *
 * 供 MissionSystem 奖励模板选择（generateRandomPill/
 * EquipmentDatabase.generateRandom/ManualDatabase.generateRandom）等接收
 * kotlin.random.Random 的生成函数使用（接收 kotlin.random.Random 的生成
 * 函数），抽取序与 C++ 侧 mission_completion.h 逐位一致：
 *   - nextInt(bound) → [DeterministicRng.nextInt]（Lemire 无偏回绝，非
 *     kotlin.random 的 nextBits 取模——必须重写以保序）
 *   - nextDouble() → [DeterministicRng.nextDouble]
 *
 * 线程契约：包装的分区 rng 非线程安全——调用方在引擎线程/事务内使用
 * （与 DeterministicRng 的 @Synchronized 语义一致）。
 */
class RngRandomAdapter(private val rng: DeterministicRng) : kotlin.random.Random() {
    override fun nextBits(bitCount: Int): Int {
        require(bitCount in 1..32) { "bitCount must be in 1..32, was $bitCount" }
        return rng.nextInt() ushr (32 - bitCount)
    }

    override fun nextInt(): Int = rng.nextInt()

    override fun nextInt(until: Int): Int {
        require(until > 0) { "until must be positive, was $until" }
        return rng.nextInt(until)
    }

    override fun nextDouble(): Double = rng.nextDouble()
}
