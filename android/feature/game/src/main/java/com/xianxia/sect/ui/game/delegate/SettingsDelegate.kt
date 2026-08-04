package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade

class SettingsDelegate(
    private val gameEngine: GameEngine,
    private val discipleFacade: DiscipleFacade,
    private val audioConfig: AudioConfig
) {

    fun setPatrolBattleResultPopup(enabled: Boolean) {
        gameEngine.launchOnEngine { gameEngine.updateGameData { it.copy(patrolBattleResultPopup = enabled) } }
    }

    fun setAutoSellMidGradeForPurchase(enabled: Boolean) {
        gameEngine.launchOnEngine { gameEngine.updateGameData { it.copy(autoSellMidGradeForPurchase = enabled) } }
    }

    fun setAutoSellHighGradeForPurchase(enabled: Boolean) {
        gameEngine.launchOnEngine { gameEngine.updateGameData { it.copy(autoSellHighGradeForPurchase = enabled) } }
    }

    fun setShowAllAvailableDisciples(enabled: Boolean) {
        gameEngine.launchOnEngine { gameEngine.updateGameData { it.copy(showAllAvailableDisciples = enabled) } }
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
        gameEngine.launchOnEngine { gameEngine.consumeMaterialByName(name, rarity, quantity) }
    }

    fun setYearlySalary(realm: Int, amount: Int) {
        gameEngine.launchOnEngine {
            val data = gameEngine.gameData.value
            val newSalary = data.yearlySalary.toMutableMap()
            newSalary[realm] = amount
            gameEngine.updateYearlySalary(newSalary)
        }
    }

    fun setYearlySalaryEnabled(realm: Int, enabled: Boolean) {
        gameEngine.launchOnEngine { discipleFacade.updateYearlySalaryEnabled(realm, enabled) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        gameEngine.launchOnEngine {
            audioConfig.soundEnabled = enabled
            val data = gameEngine.gameData.value
            if (data.soundEnabled != enabled) {
                gameEngine.updateGameData { it.copy(soundEnabled = enabled) }
            }
        }
    }

    fun setMusicEnabled(enabled: Boolean) {
        gameEngine.launchOnEngine {
            audioConfig.musicEnabled = enabled
            val data = gameEngine.gameData.value
            if (data.musicEnabled != enabled) {
                gameEngine.updateGameData { it.copy(musicEnabled = enabled) }
            }
        }
    }
}
