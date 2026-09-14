package com.xianxia.sect.core.engine


import com.xianxia.sect.core.model.BattleLog


// ── Battle facade delegates ─────────────────────────────────────────

suspend fun GameEngine.processBattleCasualties(deadMemberIds: Set<String>, survivorHpMap: Map<String, Int>,
    survivorMpMap: Map<String, Int> = emptyMap()) {
    battleFacade.processBattleCasualties(deadMemberIds, survivorHpMap, survivorMpMap)
    // 战斗死亡后清理 Gate 注册表
    deadMemberIds.forEach { assignmentGate.release(it) }
}
fun GameEngine.getTotalBattlesCount(): Int = battleFacade.getTotalBattlesCount()
fun GameEngine.getRecentBattles(count: Int = 10): List<BattleLog> = battleFacade.getRecentBattles(count)
fun GameEngine.getWinRate(lastNBattles: Int = 50): Double = battleFacade.getWinRate(lastNBattles)
fun GameEngine.clearPendingBattleResult() = battleFacade.clearPendingBattleResult()
