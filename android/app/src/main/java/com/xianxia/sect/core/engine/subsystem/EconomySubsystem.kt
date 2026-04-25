package com.xianxia.sect.core.engine.subsystem

import android.util.Log
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

@SystemPriority(order = 206)
@Singleton
class EconomySubsystem @Inject constructor(
    private val cultivationService: CultivationService
) : GameSystem {

    companion object {
        private const val TAG = "EconomySubsystem"
        const val SYSTEM_NAME = "EconomySubsystem"
    }

    override val systemName: String = SYSTEM_NAME

    override fun initialize() {
        Log.d(TAG, "EconomySubsystem initialized")
    }

    override fun release() {
        Log.d(TAG, "EconomySubsystem released")
    }

    override suspend fun clearForSlot(slotId: Int) {}

    override suspend fun onMonthTick(state: MutableGameState) {
        cultivationService.processPolicyCosts()
        cultivationService.processSalaryPayment(state.gameData.gameYear, state.gameData.gameMonth)
    }
}
