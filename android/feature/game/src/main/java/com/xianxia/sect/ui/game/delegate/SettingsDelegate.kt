package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.consumeMaterialByName
import com.xianxia.sect.core.engine.setActiveTab
import com.xianxia.sect.core.engine.setAutoSellHighGradeForPurchase
import com.xianxia.sect.core.engine.setAutoSellMidGradeForPurchase
import com.xianxia.sect.core.engine.setPatrolBattleResultPopup
import com.xianxia.sect.core.engine.setShowAllAvailableDisciples
import com.xianxia.sect.core.engine.setMusicEnabled
import com.xianxia.sect.core.engine.setSoundEnabled
import com.xianxia.sect.core.engine.updateYearlySalary
import com.xianxia.sect.core.engine.updateYearlySalaryEnabled
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade



class SettingsDelegate(
    private val gameEngine: GameEngine,
    private val discipleFacade: DiscipleFacade,
    private val audioConfig: AudioConfig
) {

    fun setPatrolBattleResultPopup(enabled: Boolean) {
        gameEngine.launchOnEngine { gameEngine.setPatrolBattleResultPopup(enabled) }
    }

    fun setAutoSellMidGradeForPurchase(enabled: Boolean) {
        gameEngine.launchOnEngine { gameEngine.setAutoSellMidGradeForPurchase(enabled) }
    }

    fun setAutoSellHighGradeForPurchase(enabled: Boolean) {
        gameEngine.launchOnEngine { gameEngine.setAutoSellHighGradeForPurchase(enabled) }
    }

    fun setShowAllAvailableDisciples(enabled: Boolean) {
        gameEngine.launchOnEngine { gameEngine.setShowAllAvailableDisciples(enabled) }
    }

    val showAllAvailableDisciplesSnapshot: Boolean
        get() = gameEngine.gameDataSnapshot?.showAllAvailableDisciples ?: false

    val battleAndExplorationIdsSnapshot: Set<String>
        get() {
            val data = gameEngine.gameDataSnapshot ?: return emptySet()
            val battleIds = data.battleTeams.flatMap { t ->
                t.slots.mapNotNull { s -> s.discipleId.takeIf(String::isNotEmpty) }
            }
            val caveExplorationIds = data.caveExplorationTeams.flatMap { it.memberIds }
            return (battleIds + caveExplorationIds).toSet()
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
            if (gameEngine.gameData.value.soundEnabled != enabled) {
                gameEngine.setSoundEnabled(enabled)
            }
        }
    }

    fun setMusicEnabled(enabled: Boolean) {
        gameEngine.launchOnEngine {
            audioConfig.musicEnabled = enabled
            if (gameEngine.gameData.value.musicEnabled != enabled) {
                gameEngine.setMusicEnabled(enabled)
            }
        }
    }
}
