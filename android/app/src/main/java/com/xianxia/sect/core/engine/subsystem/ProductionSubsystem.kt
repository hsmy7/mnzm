package com.xianxia.sect.core.engine.subsystem

import android.util.Log
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

@SystemPriority(order = 205)
@Singleton
class ProductionSubsystem @Inject constructor(
    private val cultivationService: CultivationService
) : GameSystem {

    companion object {
        private const val TAG = "ProductionSubsystem"
        const val SYSTEM_NAME = "ProductionSubsystem"
    }

    override val systemName: String = SYSTEM_NAME

    override fun initialize() {
        Log.d(TAG, "ProductionSubsystem initialized")
    }

    override fun release() {
        Log.d(TAG, "ProductionSubsystem released")
    }

    override suspend fun clear() {}

    override suspend fun onMonthTick(state: MutableGameState) {
        cultivationService.processBuildingProduction(state.gameData.gameYear, state.gameData.gameMonth)
        cultivationService.processHerbGardenGrowth(state.gameData.gameYear, state.gameData.gameMonth)
        cultivationService.processSpiritMineProduction()
        cultivationService.processAutoPlant()
        cultivationService.processAutoAlchemy()
        cultivationService.processAutoForge()
    }
}
