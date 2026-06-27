package com.xianxia.sect.core.engine.system.building

import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SystemPriority(order = 213)
class SpiritMineSystem @Inject constructor() : GameSystem {
    override val systemName = "SpiritMineSystem"

    override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
        // 灵矿产出已移至月度结算 CultivationEventProcessor.processMonthlyEvents
    }
}
