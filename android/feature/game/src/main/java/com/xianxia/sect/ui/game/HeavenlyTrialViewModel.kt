package com.xianxia.sect.ui.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.battle.HeavenlyTrialService
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.HeavenlyTrialSaveData
import com.xianxia.sect.core.model.ManualProficiencyData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HeavenlyTrialViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val battleSystem: BattleSystem,
    val trialService: HeavenlyTrialService
) : BaseViewModel() {

    private val _currentScreen = MutableStateFlow<Screen>(Screen.Panel)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    val trialState: StateFlow<HeavenlyTrialSaveData> = gameEngine.gameData
        .map { it.heavenlyTrialState }
        .stateIn(viewModelScope, sharingStarted, HeavenlyTrialSaveData())

    val aliveDisciples: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .map { it.filter { d -> d.isAlive } }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    var selectedLevelIndex by mutableStateOf(0)
    var selectedPhaseIndex by mutableStateOf(0)

    var playerCombatants by mutableStateOf<List<Combatant>>(emptyList())
    var enemyCombatants by mutableStateOf<List<Combatant>>(emptyList())

    var showResult by mutableStateOf(false)
    var resultWon by mutableStateOf(false)
    var resultDuration by mutableStateOf(0L)

    sealed class Screen {
        object Panel : Screen()
        data class BattlePrep(val levelIndex: Int) : Screen()
        data class DiscipleSelect(val levelIndex: Int, val phaseIndex: Int) : Screen()
        data class Combat(val levelIndex: Int, val phaseIndex: Int) : Screen()
    }

    fun enterBattlePrep(levelIndex: Int) {
        selectedLevelIndex = levelIndex
        _currentScreen.value = Screen.BattlePrep(levelIndex)
    }

    fun startDiscipleSelect(phaseIndex: Int) {
        selectedPhaseIndex = phaseIndex
        _currentScreen.value = Screen.DiscipleSelect(selectedLevelIndex, phaseIndex)
    }

    fun startCombat(disciples: List<DiscipleAggregate>) {
        val enemies = trialService.getEnemiesForPhase(selectedLevelIndex, selectedPhaseIndex)
        val equipMap = gameEngine.equipmentInstances.value.associateBy { it.id }
        val manualMap = gameEngine.manualInstances.value.associateBy { it.id }
        val allProficiencies: Map<String, Map<String, ManualProficiencyData>> =
            gameEngine.gameDataSnapshot.manualProficiencies.mapValues { (_, list) ->
                list.associateBy { it.manualId }
            }
        val playerStats = disciples.map { d ->
            val disciple = d.toDisciple()
            battleSystem.convertDiscipleToCombatant(disciple, equipMap, manualMap, allProficiencies)
        }
        playerCombatants = playerStats
        enemyCombatants = enemies
        _currentScreen.value = Screen.Combat(selectedLevelIndex, selectedPhaseIndex)
    }

    fun onCombatFinished(won: Boolean) {
        if (won) {
            viewModelScope.launch {
                trialService.recordPhaseClear(selectedLevelIndex, selectedPhaseIndex)
            }
            if (selectedPhaseIndex == 1) {
                _currentScreen.value = Screen.Panel
            } else {
                startDiscipleSelect(1)
            }
        } else {
            _currentScreen.value = Screen.BattlePrep(selectedLevelIndex)
        }
    }

    fun showBattleResult(won: Boolean, durationSeconds: Long) {
        resultWon = won
        resultDuration = durationSeconds
        showResult = true
    }

    fun dismissResult() {
        showResult = false
        if (resultWon) {
            if (selectedPhaseIndex == 0) startDiscipleSelect(1)
            else onCombatFinished(true)
        } else {
            _currentScreen.value = Screen.BattlePrep(selectedLevelIndex)
        }
    }

    fun dismiss() { _currentScreen.value = Screen.Panel }

    fun dismissDiscipleSelect() {
        _currentScreen.value = Screen.BattlePrep(selectedLevelIndex)
    }
}
