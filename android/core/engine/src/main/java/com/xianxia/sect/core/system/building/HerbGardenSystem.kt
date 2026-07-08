package com.xianxia.sect.core.engine.system.building

import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 灵植阁系统 — 月变时检查灵草生长进度。
 * 打开 HerbGardenDialog 时由 ViewModel 额外触发惰性结算。
 */
@Singleton
@SystemPriority(order = 212)
class HerbGardenSystem @Inject constructor(
    private val cultivationService: CultivationService
) : GameSystem {
    override val systemName = "HerbGardenSystem"

    override suspend fun onMonthlyEvent(state: MutableGameState) {
        cultivationService.processHerbGardenGrowth(state)
    }
}
