package com.xianxia.sect.core.engine.system.building

import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 锻造系统 — 月变时检查进度完成 + 自动锻造。
 * 打开 ForgeDialog 时由 ViewModel 额外触发惰性结算。
 */
@Singleton
@SystemPriority(order = 211)
class ForgeSystem @Inject constructor(
    private val cultivationService: CultivationService,
    private val scopeProvider: CoroutineScopeProvider
) : GameSystem {
    override val systemName = "ForgeSystem"

    override fun onMonthlyEvent(state: MutableGameState) {
        scopeProvider.scope.launch {
            cultivationService.processAutoForge()
        }
        cultivationService.processBuildingProduction(
            state.gameData.gameYear, state.gameData.gameMonth
        )
    }
}
