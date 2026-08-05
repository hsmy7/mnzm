package com.xianxia.sect.core.state

/**
 * RNG 事务钩子：游戏状态事务失败回滚时，同步回滚分区 PRNG 状态。
 *
 * 确定性契约（K 项根治）：结算事务（每旬突破/月度事件/生产判定）在
 * [GameStateStore.update] 事务内大量消费分区 RNG。事务中途异常时 COW
 * 缓冲丢弃（状态回滚），但 RNG 已前进——状态与随机序列永久分叉，
 * 读档重放不可复现。本接口让状态层在事务失败时恢复事务开始时的
 * 全部分区 PRNG 快照（8×Long，成本可忽略），保证"无异常路径"与
 * "异常后继续路径"随机序列逐位一致。
 *
 * 依赖方向：本接口定义于 :core:domain（零平台依赖），真实实现由 :app
 * 模块装配（委托 [com.xianxia.sect.core.util.GameRngManager] 的
 * exportStates/restoreStates），满足依赖反转。
 */
interface RngSnapshotPort {

    /**
     * 事务开始时调用：返回全部分区 PRNG 当前状态。
     * 调用方应将该快照与事务绑定，供失败时恢复。
     *
     * @return 分区 id → PRNG 状态（Long），数量等于分区数（8）
     */
    fun snapshot(): Map<Int, Long>

    /**
     * 事务失败回滚时调用：恢复全部分区 PRNG 状态到 [snapshot] 时点。
     * 实现必须保证：恢复失败不抛异常（由调用方吞掉并记录日志），
     * 且未出现在快照中的分区保持现状。
     *
     * @param states [snapshot] 返回的快照
     */
    fun restore(states: Map<Int, Long>)
}

/**
 * 空实现：不追踪 RNG 状态（快照/恢复均为 no-op）。
 *
 * 用途：非注入构造的测试环境（单元测试直接 new [GameStateStoreImpl] 时
 * 使用默认参数），以及不需要确定性事务语义的场景。
 */
object NoopRngSnapshotPort : RngSnapshotPort {
    override fun snapshot(): Map<Int, Long> = emptyMap()
    override fun restore(states: Map<Int, Long>) = Unit
}
