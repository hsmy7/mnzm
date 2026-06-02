package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameStateStore
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BattleFacadeImpl @Inject constructor(
    private val combatService: CombatService,
    private val stateStore: GameStateStore
) : BattleFacade {
    override val battleLogs: StateFlow<List<BattleLog>> get() = stateStore.battleLogs
    override val pendingBattleResult: StateFlow<BattleResultUIData?> get() = stateStore.pendingBattleResult

    override suspend fun processBattleCasualties(deadMemberIds: Set<String>, survivorHpMap: Map<String, Int>, survivorMpMap: Map<String, Int>) =
        combatService.processBattleCasualties(deadMemberIds, survivorHpMap, survivorMpMap)

    override fun getTotalBattlesCount(): Int = combatService.getTotalBattlesCount()
    override fun getRecentBattles(count: Int): List<BattleLog> = combatService.getRecentBattles(count)
    override fun getWinRate(lastNBattles: Int): Double = combatService.getWinRate(lastNBattles)
    override fun clearPendingBattleResult() = stateStore.clearPendingBattleResult()
}
