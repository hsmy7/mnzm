package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SettingsDelegate(
    private val gameEngine: GameEngine,
    private val discipleFacade: DiscipleFacade,
    private val scope: CoroutineScope
) {

    fun setPatrolBattleResultPopup(enabled: Boolean) {
        scope.launch { gameEngine.updateGameData { it.copy(patrolBattleResultPopup = enabled) } }
    }

    fun setAutoSellMidGradeForPurchase(enabled: Boolean) {
        scope.launch { gameEngine.updateGameData { it.copy(autoSellMidGradeForPurchase = enabled) } }
    }

    fun setAutoSellHighGradeForPurchase(enabled: Boolean) {
        scope.launch { gameEngine.updateGameData { it.copy(autoSellHighGradeForPurchase = enabled) } }
    }

    fun setShowAllAvailableDisciples(enabled: Boolean) {
        scope.launch { gameEngine.updateGameData { it.copy(showAllAvailableDisciples = enabled) } }
    }

    suspend fun releaseDiscipleFromAllSlotsAtomic(discipleId: String) {
        gameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId)
    }

    val showAllAvailableDisciplesSnapshot: Boolean
        get() = gameEngine.gameDataSnapshot?.showAllAvailableDisciples ?: false

    val battleAndExplorationIdsSnapshot: Set<String>
        get() {
            val data = gameEngine.gameDataSnapshot ?: return emptySet()
            val battleIds = data.battleTeams.flatMap { t ->
                t.slots.mapNotNull { s -> s.discipleId.takeIf(String::isNotEmpty) }
            }
            val explorationIds = gameEngine.teams.value?.flatMap { it.memberIds } ?: emptyList()
            return (battleIds + explorationIds).toSet()
        }

    fun setActiveTab(tab: String) { gameEngine.setActiveTab(tab) }

    fun consumeBloodRefiningMaterial(name: String, rarity: Int, quantity: Int) {
        scope.launch { gameEngine.consumeMaterialByName(name, rarity, quantity) }
    }

    fun setYearlySalary(realm: Int, amount: Int) {
        scope.launch {
            val data = gameEngine.gameData.value
            val newSalary = data.yearlySalary.toMutableMap()
            newSalary[realm] = amount
            gameEngine.updateYearlySalary(newSalary)
        }
    }

    fun setYearlySalaryEnabled(realm: Int, enabled: Boolean) {
        scope.launch { discipleFacade.updateYearlySalaryEnabled(realm, enabled) }
    }
}
