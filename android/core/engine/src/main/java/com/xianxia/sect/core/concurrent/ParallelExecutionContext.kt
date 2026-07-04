package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.UnifiedGameState

/**
 * 并行执行的只读上下文快照。
 *
 * 在 tick 的 compute 阶段创建，供所有 Worker 线程并发读取。
 * 禁止在 compute 阶段修改状态——所有写操作推迟到 apply 阶段，
 * 在游戏主线程的 [stateStore.update] 内原子完成。
 *
 * 设计原则：零拷贝引用传递，只在真正需要时懒加载。
 */
class ParallelExecutionContext private constructor(
    /** 快照时刻的游戏年月旬 */
    val gameYear: Int,
    val gameMonth: Int,
    val gamePhase: Int,
    /** 快照时刻的弟子表（只读引用，compute 阶段不修改） */
    val discipleTables: DiscipleTables,
    /** 快照时刻的 GameData（只读引用） */
    val gameData: GameData,
    /** UnifiedGameState 的快照（懒加载，按需获取） */
    private val _unifiedState: Lazy<UnifiedGameState>
) {
    /**
     * UnifiedGameState 快照（只读，compute 阶段使用）。
     * 仅首次访问时构建，降低 compute 阶段状态为空时的开销。
     */
    val unifiedState: UnifiedGameState by _unifiedState

    companion object {
        /**
         * 从 [GameStateStore] 创建快照。
         * 在 tick 的 compute 阶段开始前调用，捕获此刻的完整状态。
         */
        fun snapshot(store: GameStateStore, activeDomains: Set<Any>? = null): ParallelExecutionContext {
            val gd = store.gameData.value
            return ParallelExecutionContext(
                gameYear = gd.gameYear,
                gameMonth = gd.gameMonth,
                gamePhase = gd.gamePhase,
                discipleTables = store.discipleTables,
                gameData = gd,
                _unifiedState = lazy { store.unifiedState.value }
            )
        }
    }
}

/**
 * 并行计算的结果容器。
 * 每个 System 的 [computePhaseTick] 返回此类型，
 * 其 [apply] 方法在 [stateStore.update] 块中被调用。
 */
interface ParallelPhaseResult {
    /**
     * 将计算结果写入选定状态。
     * 此方法在 [stateStore.update] 块内、游戏主线程上串行调用。
     */
    suspend fun apply(state: com.xianxia.sect.core.state.MutableGameState)
}

/**
 * 空的并行计算结果（适用于无写操作的系统）。
 */
class NoOpResult private constructor() : ParallelPhaseResult {
    override suspend fun apply(state: com.xianxia.sect.core.state.MutableGameState) {}

    companion object {
        val INSTANCE = NoOpResult()
    }
}
