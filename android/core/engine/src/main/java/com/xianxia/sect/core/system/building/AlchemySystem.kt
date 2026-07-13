package com.xianxia.sect.core.engine.system.building

import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 炼丹系统 — 月变时检查进度完成 + 自动炼丹。
 * 打开 AlchemyDialog 时由 ViewModel 额外触发惰性结算。
 */
@Singleton
@SystemPriority(order = 210)
class AlchemySystem @Inject constructor(
    private val cultivationService: CultivationService
) : GameSystem {
    override val systemName = "AlchemySystem"

    override fun onMonthlyEvent(state: MutableGameState) {
        cultivationService.processAutoAlchemy()
        cultivationService.processBuildingProduction(
            state.gameData.gameYear, state.gameData.gameMonth
        )
    }
}
