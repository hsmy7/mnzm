package com.xianxia.sect.core.engine.system.building

import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SystemPriority(order = 214)
class PlantingSystem @Inject constructor(
    private val cultivationService: CultivationService
) : GameSystem {
    override val systemName = "PlantingSystem"
    override val focusDomains = setOf(FocusDomain.PLANTING)

    override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
        if (phasesToSettle == 1) {
            cultivationService.processSpiritFieldHarvest(state)
            cultivationService.processAutoPlant(state)
        }
    }
}
