package com.xianxia.sect.core.engine.domain.battle

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.state.BattleResultUIData
import kotlinx.coroutines.flow.StateFlow

interface BattleFacade {
    val battleLogs: StateFlow<List<BattleLog>>
    val pendingBattleResult: StateFlow<BattleResultUIData?>

    suspend fun processBattleCasualties(
        deadMemberIds: Set<String>,
        survivorHpMap: Map<String, Int>,
        survivorMpMap: Map<String, Int> = emptyMap()
    )
    fun getTotalBattlesCount(): Int
    fun getRecentBattles(count: Int = 10): List<BattleLog>
    fun getWinRate(lastNBattles: Int = 50): Double
    fun clearPendingBattleResult()
}
