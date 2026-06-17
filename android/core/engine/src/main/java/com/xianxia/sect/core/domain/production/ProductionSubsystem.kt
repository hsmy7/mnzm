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
        cultivationService.processHerbGardenGrowth(state)
        cultivationService.processAutoAlchemy()
        cultivationService.processAutoForge()
    }

    override suspend fun onMonthTick(state: MutableGameState) {
        // 组 A（并行）：纯独立方法——autoAlchemy 只写 herbs，autoForge 只写 materials
        coroutineScope {
            val alchemyJob = async(Dispatchers.Default) { cultivationService.processAutoAlchemy() }
            val forgeJob = async(Dispatchers.Default) { cultivationService.processAutoForge() }
            awaitAll(alchemyJob, forgeJob)
        }

        // spiritMineProduction 和 autoAssign 都写游戏状态，
        // 不能并行，必须串行以避免数据竞争。
        // 所有方法改为直接操作 shadow，不再使用异步协程覆盖。
        cultivationService.processSpiritMineProduction(state)
        cultivationService.processAutoAssign()

        // 组 B（串行，依赖 A 产出）
        cultivationService.processBuildingProduction(state.gameData.gameYear, state.gameData.gameMonth)
        cultivationService.processHerbGardenGrowth(state)

        // 组 C（串行）
        cultivationService.processSpiritFieldHarvest(state)
        cultivationService.processAutoPlant(state)
    }
}
