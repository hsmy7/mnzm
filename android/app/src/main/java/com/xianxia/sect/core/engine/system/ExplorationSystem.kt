package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

// TickSystem: "ExplorationTickSystem"
@Singleton
@SystemPriority(order = 240)
class ExplorationTickSystem @Inject constructor(
    private val explorationService: ExplorationService
) : GameSystem {
    override val systemName: String = "ExplorationTickSystem"

    override suspend fun onMonthTick(state: MutableGameState) {
        explorationService.processMonthlyWorldLevels(state)
    }
}
