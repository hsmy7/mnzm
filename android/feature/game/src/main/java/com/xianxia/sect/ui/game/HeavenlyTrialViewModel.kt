package com.xianxia.sect.ui.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.battle.HeavenlyTrialService
import com.xianxia.sect.core.engine.domain.battle.ClaimClearRewardResult
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.HeavenlyTrialSaveData
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.RewardCardItem
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

    // 通关奖励弹窗状态
    var showClearRewardDialog by mutableStateOf(false)

    val claimableLevels: StateFlow<List<Int>> = trialState
        .map { state -> (0 until 8).filter { state.canClaimReward(it) } }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val hasClaimableRewards: StateFlow<Boolean> = claimableLevels
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, sharingStarted, false)

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
        // 导航和阶段通关记录统一由 CombatScreen 的 onFinished
        // → onCombatFinished 处理，避免在此处提前修改
        // selectedPhaseIndex 导致 recordPhaseClear 记录错误的阶段
    }

    fun dismiss() { _currentScreen.value = Screen.Panel }

    fun dismissDiscipleSelect() {
        _currentScreen.value = Screen.BattlePrep(selectedLevelIndex)
    }

    // region Clear Rewards

    fun openClearRewards() {
        showClearRewardDialog = true
    }

    fun dismissClearRewards() {
        showClearRewardDialog = false
    }

    fun claimClearReward(
        levelIndex: Int,
        onCardsReady: (List<RewardCardItem>) -> Unit
    ) {
        viewModelScope.launch {
            when (val result = trialService.claimClearReward(levelIndex)) {
                is ClaimClearRewardResult.Success -> {
                    onCardsReady(result.cards)
                }
                is ClaimClearRewardResult.CapacityInsufficient -> {
                    showError(result.message ?: "领取失败")
                }
                is ClaimClearRewardResult.AlreadyClaimed -> {
                    // 按钮状态已阻止此路径
                }
                is ClaimClearRewardResult.LevelNotCleared -> {
                    // 按钮状态已阻止此路径
                }
            }
        }
    }

    // endregion
}
