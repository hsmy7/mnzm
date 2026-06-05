package com.xianxia.sect.core.engine.domain.production

import android.util.Log
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.FocusDomain
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
    override val focusDomain = FocusDomain.BUILDINGS
    override val settlementPhase = 2  // 中旬：锻造/炼丹/血炼池（种植/灵矿在 onMonthTick 中处理）

    private var lastRealtimeTickMs = 0L
    private val realtimeTickInterval = 200L  // BUILDINGS焦点域每200ms检测一次（约5Hz，节省CPU）

    override fun initialize() {
        Log.d(TAG, "ProductionSubsystem initialized")
    }

    override fun release() {
        Log.d(TAG, "ProductionSubsystem released")
    }

    override suspend fun clearForSlot(slotId: Int) {}

    /**
     * BUILDINGS 焦点域实时结算：每 200ms 检测生产槽位完成 + 触发自动生产。
     * 焦点域兜底，确保玩家看着建筑 Tab 时进度实时更新。
     */
    override suspend fun onPhaseTick(state: MutableGameState) {
        val now = System.currentTimeMillis()
        if (now - lastRealtimeTickMs < realtimeTickInterval) return
        lastRealtimeTickMs = now

        val year = state.gameData.gameYear
        val month = state.gameData.gameMonth
        cultivationService.processBuildingProduction(year, month)
        cultivationService.processHerbGardenGrowth(year, month)
        cultivationService.processAutoAlchemy()
        cultivationService.processAutoForge()
    }

    override suspend fun onMonthTick(state: MutableGameState) {
        cultivationService.processBuildingProduction(state.gameData.gameYear, state.gameData.gameMonth)
        cultivationService.processHerbGardenGrowth(state.gameData.gameYear, state.gameData.gameMonth)
        cultivationService.processSpiritFieldHarvest()
        cultivationService.processSpiritMineProduction()
        cultivationService.processAutoPlant()
        cultivationService.processAutoAlchemy()
        cultivationService.processAutoForge()
        cultivationService.processAutoAssign()
    }
}
