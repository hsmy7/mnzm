package com.xianxia.sect.core.util

/**
 * NativeRngChannel — RNG 分区抽取的 native 委托通道接口
 * （AUTHORITATIVE 单一真相源接缝）。
 *
 * AUTHORITATIVE 过渡模式下，Kotlin 引擎的全部随机抽取经此通道写入 C++
 * RngManager 分区（PCG-XSH-RR 标量通道），保证：
 * - C++ 核心结算与 Kotlin 残留执行器共享同一条确定性序列（逐位统一）
 * - 事务回滚守卫（RngSnapshotPort → snapshot/restore）语义自动保持——
 *   快照/恢复同样走通道，回滚即回退 native 分区状态
 * - 存档导出（exportStates）读到的是 native 真相源状态，存读档确定性不变
 *
 * 生产实现绑定 [com.xianxia.sect.core.nativebridge.GameCoreBridge] 标量通道；
 * 测试用手写 Fake 注入。引擎未初始化时实现方须自行降级（返回 0/忽略写）。
 */
interface NativeRngChannel {
    /** 指定分区抽取下一个 32 位整数（PCG-XSH-RR 原始输出） */
    fun nextInt(partitionId: Int): Int

    /** 读取分区当前状态（对应 [DeterministicRng.snapshot]） */
    fun snapshot(partitionId: Int): Long

    /** 写入分区状态（对应 [DeterministicRng.restore]）；非法分区须忽略 */
    fun restore(partitionId: Int, state: Long)

    /** 重置系统种子（各分区 seed+partitionId 重播；新档 initSystemSeed 对应） */
    fun initSystemSeed(seed: Long)
}

/**
 * NativeBackedRng — 委托式分区 PRNG（[DeterministicRng] 子类）。
 *
 * 只覆盖原始三件套（[nextInt]/[snapshot]/[restore]）：bound/double/gaussian
 * 等上层公式继承自父类、经虚分派消费委托后的原始流——消耗次数与产出与
 * 本地实现逐位一致。本地 state 字段不使用（真相源在 native 侧）。
 *
 * @param partitionId RngPartition.id（native 分区键）
 * @param channel native 委托通道
 */
class NativeBackedRng(
    private val partitionId: Int,
    private val channel: NativeRngChannel
) : DeterministicRng(state = 0L) {

    override fun nextInt(): Int = channel.nextInt(partitionId)

    override fun snapshot(): Long = channel.snapshot(partitionId)

    override fun restore(savedState: Long) {
        channel.restore(partitionId, savedState)
    }

    override fun toString(): String =
        "NativeBackedRng(partition=$partitionId, state=${channel.snapshot(partitionId)})"
}
