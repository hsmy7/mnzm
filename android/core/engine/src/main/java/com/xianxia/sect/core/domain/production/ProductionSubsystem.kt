package com.xianxia.sect.core.engine.domain.production

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        DomainLog.d(TAG, "ProductionSubsystem initialized")
    }

    override fun release() {
        DomainLog.d(TAG, "ProductionSubsystem released")
    }

    override suspend fun clearForSlot(slotId: Int) {}

    /**
     * BUILDINGS 域结算。
     * - 实时轨 (phasesToSettle == 1)：每 200ms 检测生产槽位完成 + 自动炼器/炼丹
     * - 批量轨 (phasesToSettle >= 3)：执行完整月度生产周期 × months
     */
    override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
        if (phasesToSettle >= 3) {
            // 批量轨 — 完整月度生产
            val months = phasesToSettle / 3
            repeat(months) { processMonthlyProduction(state) }
            return
        }

        // 实时轨 — 200ms 节流
        val now = System.currentTimeMillis()
        if (now - lastRealtimeTickMs < realtimeTickInterval) return
        lastRealtimeTickMs = now

        val year = state.gameData.gameYear
        val month = state.gameData.gameMonth
        cultivationService.processBuildingProduction(year, month)
        cultivationService.processHerbGardenGrowth(state)
        cultivationService.processAutoAlchemy()
        cultivationService.processAutoForge()
    }

    /** 单月完整生产周期 */
    private suspend fun processMonthlyProduction(state: MutableGameState) {
        // 组 A（并行）：autoAlchemy + autoForge
        coroutineScope {
            val alchemyJob = async(Dispatchers.Default) { cultivationService.processAutoAlchemy() }
            val forgeJob = async(Dispatchers.Default) { cultivationService.processAutoForge() }
            awaitAll(alchemyJob, forgeJob)
        }

        // 组 A 后续（串行）：spiritMineProduction + autoAssign
        cultivationService.processSpiritMineProduction(state)
        cultivationService.processAutoAssign()

        // 组 B（串行）：buildingProduction + herbGardenGrowth
        cultivationService.processBuildingProduction(state.gameData.gameYear, state.gameData.gameMonth)
        cultivationService.processHerbGardenGrowth(state)

        // 组 C（串行）：spiritFieldHarvest + autoPlant
        cultivationService.processSpiritFieldHarvest(state)
        cultivationService.processAutoPlant(state)
    }
}
