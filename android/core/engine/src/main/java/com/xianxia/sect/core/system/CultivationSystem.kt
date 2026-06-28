package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

// TickSystem: "CultivationTickSystem"
@Singleton
@SystemPriority(order = 200)
class CultivationTickSystem @Inject constructor(
    private val cultivationService: CultivationService
) : GameSystem {
    override val systemName: String = "CultivationTickSystem"
    override val settlementPhase = 1  // 上旬：玩家弟子修炼

    override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
        cultivationService.advancePhase(state)
        cultivationService.batchSettleCultivation(state, phasesToSettle)
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
