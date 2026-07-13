package com.xianxia.sect.core.engine.system.building

import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 灵田系统 — 月变时检查灵田成熟 + 自动种植。
 * 打开 PlantingDialog 时由 ViewModel 额外触发惰性结算。
 */
@Singleton
@SystemPriority(order = 214)
class PlantingSystem @Inject constructor(
    private val cultivationService: CultivationService
) : GameSystem {
    override val systemName = "PlantingSystem"

    override fun onMonthlyEvent(state: MutableGameState) {
        cultivationService.processSpiritFieldHarvest(state)
    }
}
