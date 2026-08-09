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
 * 锻造系统 — 月变时自动锻造。
 *
 * 生产完成结算统一由 [AlchemySystem] 触发（双系统各自调用 processBuildingProduction
 * 会导致每月每槽位完整结算两次——双 RNG 消耗、双产出、双晋升计数，2026-08-09 对抗性审查修复），
 * 本系统仅负责自动锻造排班。
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
    }
}
