package com.xianxia.sect.core.engine.system.building

import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SystemPriority(order = 211)
class ForgeSystem @Inject constructor(
    private val cultivationService: CultivationService
) : GameSystem {
    override val systemName = "ForgeSystem"
    override val focusDomains = setOf(FocusDomain.FORGE)

    override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
        if (phasesToSettle == 1) {
            cultivationService.processAutoForge()
            cultivationService.processBuildingProduction(
                state.gameData.gameYear, state.gameData.gameMonth
            )
        }
    }
}
