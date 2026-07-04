package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.concurrent.CultivationBatchResult
import com.xianxia.sect.core.concurrent.ParallelExecutionContext
import com.xianxia.sect.core.concurrent.ParallelPhaseResult
import com.xianxia.sect.core.engine.service.CultivationCore
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

// TickSystem: "CultivationTickSystem"
@Singleton
@SystemPriority(order = 200)
class CultivationTickSystem @Inject constructor(
    private val cultivationService: CultivationService,
    private val cultivationCore: CultivationCore
) : GameSystem {
    override val systemName: String = "CultivationTickSystem"
    override val settlementPhase = 1  // 上旬：玩家弟子修炼

    /**
     * 标志位：本 tick 的 batchSettleCultivation 是否已由并行 compute 完成。
     * [computePhaseTick] 执行前设为 false，结果 [apply] 后设为 true，
     * [onPhaseTick] 检查此标志跳过重复结算。
     */
    @Volatile
    private var batchSettledByParallel = false

    override val supportsParallelTick: Boolean get() = true

    /**
     * 并行 compute 阶段 — 在 [Dispatchers.Default] 上执行。
     *
     * 对弟子表做 deepCopy 后计算修炼/功法/装备增量，
     * 返回 [CultivationBatchResult] 供主线程 [apply]。
     */
    override suspend fun computePhaseTick(
        ctx: ParallelExecutionContext,
        phasesToSettle: Int
    ): ParallelPhaseResult {
        batchSettledByParallel = false

        // 从快照中读取所需数据
        val gameData = ctx.gameData
        val sourceTables = ctx.discipleTables
        val equipmentInstances = ctx.unifiedState.equipmentInstances
        val manualInstances = ctx.unifiedState.manualInstances

        // deepCopy 后在此副本上并行计算
        val workingTables = sourceTables.deepCopy()
        return cultivationCore.computeBatchCultivationDelta(
            workingTables = workingTables,
            gameData = gameData,
            equipmentInstances = equipmentInstances,
            manualInstances = manualInstances,
            phasesToSettle = phasesToSettle
        ).let { result ->
            // 包装结果，apply 完成后设置标志位
            object : ParallelPhaseResult {
                override suspend fun apply(state: MutableGameState) {
                    result.apply(state)
                    batchSettledByParallel = true
                }
            }
        }
    }

    override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
        cultivationService.advancePhase(state)

        // 如果并行 compute 已完成 batchSettle，跳过重复结算
        if (!batchSettledByParallel) {
            cultivationService.batchSettleCultivation(state, phasesToSettle)
        }
        batchSettledByParallel = false

        cultivationService.recoverHpMpForAllDisciples(state, phasesToSettle)

        // 实时轨专用操作：
        // 自动装备/学习 → 自动服用丹药 → 突破检测
        if (phasesToSettle == 1) {
            cultivationService.processAutoFromWarehouseRealtime(state)
            cultivationService.processAutoPillsRealtime(state)
            cultivationService.processBreakthroughs(state)
        }
    }

    override suspend fun onMonthTick(state: MutableGameState) {
        cultivationService.advanceMonth(state)
    }

    override suspend fun onYearTick(state: MutableGameState) {
        cultivationService.advanceYear(state)
    }
}
