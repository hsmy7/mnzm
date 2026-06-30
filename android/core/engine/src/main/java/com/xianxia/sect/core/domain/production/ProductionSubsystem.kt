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

    /** 单月完整生产周期（批量轨） */
    private suspend fun processMonthlyProduction(state: MutableGameState) {
        // 注意：此方法在 transactionMutex 锁内调用，processAutoAlchemy/Forge
        // 内部会调用 stateStore.update() 修改丹药/装备状态。
        // 禁止使用 async(Dispatchers.Default)，否则 Dispatchers.Default 线程
        // 尝试获取同一把 Mutex 时会造成协程级死锁：
        // GAME_DISPATCHER 持锁等 Default → Default 等锁 ← 永远解不开。
        // 红米K80 (HyperOS 2.0) 上 Dispatchers.Default 线程易被挂起，死锁概率更高。
        cultivationService.processAutoAlchemy()
        cultivationService.processAutoForge()

        // 组 B（串行）：buildingProduction + herbGardenGrowth
        cultivationService.processBuildingProduction(state.gameData.gameYear, state.gameData.gameMonth)
        cultivationService.processHerbGardenGrowth(state)

        // 组 C（串行）：spiritFieldHarvest + autoPlant
        cultivationService.processSpiritFieldHarvest(state)
        cultivationService.processAutoPlant(state)
    }
}
