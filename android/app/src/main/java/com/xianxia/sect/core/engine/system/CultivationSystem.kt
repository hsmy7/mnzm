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
    override val focusDomain = FocusDomain.DISCIPLES

    override suspend fun onPhaseTick(state: MutableGameState) {
        cultivationService.advancePhase(state)
    }

    override suspend fun onMonthTick(state: MutableGameState) {
        cultivationService.advanceMonth(state)
    }

    override suspend fun onYearTick(state: MutableGameState) {
        cultivationService.advanceYear(state)
    }
}
