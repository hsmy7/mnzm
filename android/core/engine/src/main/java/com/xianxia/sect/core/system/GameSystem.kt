package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.concurrent.ParallelExecutionContext
import com.xianxia.sect.core.concurrent.ParallelPhaseResult
import com.xianxia.sect.core.state.MutableGameState

interface GameSystem {
    val systemName: String
    /** 该系统在哪一旬结算（1=上旬, 2=中旬, 3=下旬, 0=每旬都结算） */
    val settlementPhase: Int get() = 0

    /**
     * 此系统是否支持并行 compute/apply 模式。
     * 返回 true 表示实现了 [computePhaseTick]，框架将调用 compute → 并行 → apply 流程。
     * 返回 false 表示仍然使用旧的 [onPhaseTick] 串行执行。
     *
     * 系统逐步迁移：初始只有 CultivationTickSystem 实现，后续逐步推广。
     */
    val supportsParallelTick: Boolean get() = false

    fun initialize() {}
    fun release() {}
    suspend fun clear() {}
    suspend fun clearForSlot(slotId: Int) { clear() }

    /**
     * 并行 compute 阶段（只读）。
     *
     * 仅在 [supportsParallelTick] 返回 true 时被调用。
     * 在此方法中读取 [ctx] 的只读快照，计算结果通过 [ParallelPhaseResult] 返回，
     * 稍后在游戏主线程的 [stateStore.update] 块中调用 [ParallelPhaseResult.apply]。
     *
     * 约束：
     * - 禁止修改任何 [ParallelExecutionContext] 中的状态
     * - 返回的 [ParallelPhaseResult] 必须是线程安全的（或不可变）
     *
     * @param ctx 只读的上下文快照
     * @param phasesToSettle 需结算的旬数
     */
    suspend fun computePhaseTick(
        ctx: ParallelExecutionContext,
        phasesToSettle: Int
    ): ParallelPhaseResult = error("${this::class.simpleName} 未实现 computePhaseTick")

    /**
     * 旬级 tick。焦点域每 100ms 执行，非焦点域每 30s 执行。
     *
     * 对于 [supportsParallelTick] = true 的系统，此方法不再被调用，
     * 框架会调用 [computePhaseTick] 和结果的 [apply] 替代。
     *
     * @param phasesToSettle 需结算的旬数：焦点域=1，非焦点域批量触发时=上次执行后跳过的旬数
     */
    suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int = 1) {}
    suspend fun onMonthTick(state: MutableGameState) {}
    suspend fun onYearTick(state: MutableGameState) {}
}
