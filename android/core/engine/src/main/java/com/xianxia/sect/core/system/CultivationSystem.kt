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
        // 实时轨每旬检测突破：焦点域弟子 + 进度≥80%弟子
        // HP/MP已在上一步恢复，修炼满+状志满即突破
        if (phasesToSettle == 1) {
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
