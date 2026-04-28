package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorldMapViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : BaseViewModel() {

    val worldMapRenderData: StateFlow<WorldMapRenderData> = gameEngine.worldMapRenderData
        .stateIn(viewModelScope, sharingStarted, WorldMapRenderData())

    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, sharingStarted, GameData())

    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, sharingStarted, emptyList())

    private val _showScoutDialog = MutableStateFlow(false)
    val showScoutDialog: StateFlow<Boolean> = _showScoutDialog.asStateFlow()

    private val _selectedScoutSectId = MutableStateFlow<String?>(null)
    val selectedScoutSectId: StateFlow<String?> = _selectedScoutSectId.asStateFlow()

    private val _showAllianceDialog = MutableStateFlow(false)
    val showAllianceDialog: StateFlow<Boolean> = _showAllianceDialog.asStateFlow()

    private val _selectedAllianceSectId = MutableStateFlow<String?>(null)
    val selectedAllianceSectId: StateFlow<String?> = _selectedAllianceSectId.asStateFlow()

    private val _showEnvoyDiscipleSelectDialog = MutableStateFlow(false)
    val showEnvoyDiscipleSelectDialog: StateFlow<Boolean> = _showEnvoyDiscipleSelectDialog.asStateFlow()

    private val _battleTeamMoveMode = MutableStateFlow(false)
    val battleTeamMoveMode: StateFlow<Boolean> = _battleTeamMoveMode.asStateFlow()

    private val _selectedTeamId = MutableStateFlow<String?>(null)
    val selectedTeamId: StateFlow<String?> = _selectedTeamId.asStateFlow()

    enum class TeamInteractionMode { NONE, MOVE, ATTACK }

    private val _teamInteractionMode = MutableStateFlow(TeamInteractionMode.NONE)
    val teamInteractionMode: StateFlow<TeamInteractionMode> = _teamInteractionMode.asStateFlow()

    fun toggleTeamExpanded(teamId: String) {
        if (_selectedTeamId.value == teamId) {
            _selectedTeamId.value = null
            _teamInteractionMode.value = TeamInteractionMode.NONE
        } else {
            _selectedTeamId.value = teamId
            _teamInteractionMode.value = TeamInteractionMode.NONE
        }
    }

    fun clearTeamExpansion() {
        _selectedTeamId.value = null
        _teamInteractionMode.value = TeamInteractionMode.NONE
    }

    fun startTeamMoveMode(teamId: String) {
        _selectedTeamId.value = teamId
        _teamInteractionMode.value = TeamInteractionMode.MOVE
    }

    fun startTeamAttackMode(teamId: String) {
        _selectedTeamId.value = teamId
        _teamInteractionMode.value = TeamInteractionMode.ATTACK
    }

    fun getMoveTargetSectIds(): List<String> {
        val data = gameEngine.gameData.value
        val playerSectId = data.worldMapSects.find { it.isPlayerSect }?.id ?: ""
        return data.worldMapSects.filter { sect ->
            sect.isPlayerSect || (sect.isPlayerOccupied && sect.occupierSectId == playerSectId)
        }.map { it.id }
    }

    fun getAttackTargetSectIds(): List<String> = getMovableTargetSectIds()

    fun getHighlightedSectIds(): Set<String> {
        return when (_teamInteractionMode.value) {
            TeamInteractionMode.MOVE -> getMoveTargetSectIds().toSet()
            TeamInteractionMode.ATTACK -> getAttackTargetSectIds().toSet()
            TeamInteractionMode.NONE -> if (_battleTeamMoveMode.value) getMovableTargetSectIds().toSet() else emptySet()
        }
    }

    fun selectTeamTarget(targetSectId: String) {
        val teamId = _selectedTeamId.value ?: return
        when (_teamInteractionMode.value) {
            TeamInteractionMode.MOVE -> {
                gameEngine.startBattleTeamMove(teamId, targetSectId)
                clearTeamExpansion()
            }
            TeamInteractionMode.ATTACK -> {
                gameEngine.startBattleTeamMove(teamId, targetSectId)
                clearTeamExpansion()
            }
            TeamInteractionMode.NONE -> {}
        }
    }

    fun openScoutDialog(sectId: String) {
        _selectedScoutSectId.value = sectId
        _showScoutDialog.value = true
    }

    fun closeScoutDialog() {
        _showScoutDialog.value = false
        _selectedScoutSectId.value = null
    }

    fun startScoutMission(memberIds: List<String>, sectId: String) {
        val data = gameData.value
        val targetSect = data.worldMapSects.find { it.id == sectId }
        if (targetSect != null) {
            gameEngine.startScoutMission(memberIds, targetSect, data.gameYear, data.gameMonth, data.gameDay)
            closeScoutDialog()
        }
    }

    fun getEligibleScoutDisciples(): List<DiscipleAggregate> {
        return discipleAggregates.value.filter {
            it.isAlive && it.status == DiscipleStatus.IDLE
        }
    }

    fun giftSpiritStones(sectId: String, tier: Int) {
        viewModelScope.launch {
            try {
                gameEngine.giftSpiritStones(sectId, tier)
            } catch (e: Exception) {
                showError(e.message ?: "送礼失败")
            }
        }
    }

    fun openAllianceDialog(sectId: String) {
        _selectedAllianceSectId.value = sectId
        _showAllianceDialog.value = true
    }

    fun closeAllianceDialog() {
        _showAllianceDialog.value = false
        _selectedAllianceSectId.value = null
    }

    fun openEnvoyDiscipleSelectDialog() {
        _showEnvoyDiscipleSelectDialog.value = true
    }

    fun closeEnvoyDiscipleSelectDialog() {
        _showEnvoyDiscipleSelectDialog.value = false
    }

    fun requestAlliance(sectId: String, envoyDiscipleId: String) {
        viewModelScope.launch {
            try {
                val (success, message) = gameEngine.requestAlliance(sectId, envoyDiscipleId)
                if (success) {
                    closeEnvoyDiscipleSelectDialog()
                    closeAllianceDialog()
                } else {
                    showError(message)
                }
            } catch (e: Exception) {
                showError(e.message ?: "结盟失败")
            }
        }
    }

    fun dissolveAlliance(sectId: String) {
        viewModelScope.launch {
            try {
                val (success, message) = gameEngine.dissolveAlliance(sectId)
                if (success) {
                    closeAllianceDialog()
                } else {
                    showError(message)
                }
            } catch (e: Exception) {
                showError(e.message ?: "解除结盟失败")
            }
        }
    }

    fun getEligibleEnvoyDisciples(sectLevel: Int): List<DiscipleAggregate> {
        val requiredRealm = gameEngine.getEnvoyRealmRequirement(sectLevel)
        return discipleAggregates.value.filter {
            it.isAlive &&
            it.status == DiscipleStatus.IDLE &&
            it.realm <= requiredRealm
        }
    }

    fun getAllianceCost(sectLevel: Int): Long {
        return gameEngine.getAllianceCost(sectLevel)
    }

    fun getEnvoyRealmRequirement(sectLevel: Int): Int {
        return gameEngine.getEnvoyRealmRequirement(sectLevel)
    }

    fun isAlly(sectId: String): Boolean {
        return gameEngine.isAlly(sectId)
    }

    fun getAllianceRemainingYears(sectId: String): Int {
        return gameEngine.getAllianceRemainingYears(sectId)
    }

    fun getPlayerAllies(): List<WorldSect> {
        val allyIds = gameEngine.getPlayerAllies()
        val data = gameData.value
        return data.worldMapSects.filter { allyIds.contains(it.id) }
    }

    fun startCaveExploration(cave: CultivatorCave, selectedDisciples: List<DiscipleAggregate>) {
        viewModelScope.launch {
            // TODO(U-01 Phase3): GameEngine.startCaveExploration 应接受 DiscipleAggregate
            gameEngine.startCaveExploration(cave, selectedDisciples.map { it.toDisciple() })
        }
    }

    fun startBattleTeamMoveMode() {
        _battleTeamMoveMode.value = true
    }

    fun cancelBattleTeamMoveMode() {
        _battleTeamMoveMode.value = false
    }

    fun selectBattleTeamTarget(teamId: String, targetSectId: String) {
        val data = gameEngine.gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val targetSect = data.worldMapSects.find { it.id == targetSectId }

        if (playerSect == null || targetSect == null) {
            showError("无效的目标宗门")
            _battleTeamMoveMode.value = false
            return
        }

        if (targetSect.isPlayerSect) {
            showError("不能攻击自己的宗门")
            _battleTeamMoveMode.value = false
            return
        }

        val playerSectId = playerSect.id
        if (targetSect.isPlayerOccupied && targetSect.occupierSectId == playerSectId) {
            showError("不能攻击自己占领的宗门")
            _battleTeamMoveMode.value = false
            return
        }

        viewModelScope.launch {
            gameEngine.startBattleTeamMove(teamId, targetSectId)
            _battleTeamMoveMode.value = false
        }
    }

    fun getMovableTargetSectIds(): List<String> {
        val data = gameEngine.gameData.value
        val playerSectId = data.worldMapSects.find { it.isPlayerSect }?.id ?: ""

        return data.worldMapSects.filter { sect ->
            !sect.isPlayerSect && !(sect.isPlayerOccupied && sect.occupierSectId == playerSectId)
        }.map { it.id }
    }

    private val _showSectTradeDialog = MutableStateFlow(false)
    val showSectTradeDialog: StateFlow<Boolean> = _showSectTradeDialog.asStateFlow()

    private val _selectedTradeSectId = MutableStateFlow<String?>(null)
    val selectedTradeSectId: StateFlow<String?> = _selectedTradeSectId.asStateFlow()

    private val _sectTradeItems = MutableStateFlow<List<MerchantItem>>(emptyList())
    val sectTradeItems: StateFlow<List<MerchantItem>> = _sectTradeItems.asStateFlow()

    fun openSectTradeDialog(sectId: String) {
        _selectedTradeSectId.value = sectId
        _sectTradeItems.value = gameEngine.getOrRefreshSectTradeItems(sectId)
        _showSectTradeDialog.value = true
    }

    fun closeSectTradeDialog() {
        _showSectTradeDialog.value = false
        _selectedTradeSectId.value = null
    }

    fun buyFromSectTrade(itemId: String, quantity: Int = 1) {
        viewModelScope.launch {
            try {
                val sectId = _selectedTradeSectId.value ?: return@launch
                gameEngine.buyFromSectTradeSync(sectId, itemId, quantity)
                _sectTradeItems.value = gameEngine.getOrRefreshSectTradeItems(sectId)
            } catch (e: Exception) {
                showError(e.message ?: "购买失败")
            }
        }
    }

    private val _showGiftDialog = MutableStateFlow(false)
    val showGiftDialog: StateFlow<Boolean> = _showGiftDialog.asStateFlow()

    private val _selectedGiftSectId = MutableStateFlow<String?>(null)
    val selectedGiftSectId: StateFlow<String?> = _selectedGiftSectId.asStateFlow()

    fun openGiftDialog(sectId: String) {
        _selectedGiftSectId.value = sectId
        _showGiftDialog.value = true
    }

    fun closeGiftDialog() {
        _showGiftDialog.value = false
        _selectedGiftSectId.value = null
    }

    private val _showOuterTournamentDialog = MutableStateFlow(false)
    val showOuterTournamentDialog: StateFlow<Boolean> = _showOuterTournamentDialog.asStateFlow()

    private var _isOuterTournamentManuallyClosed = false

    fun openOuterTournamentDialog() {
        if (_isOuterTournamentManuallyClosed) {
            return
        }
        _showOuterTournamentDialog.value = true
    }

    private fun closeOuterTournamentDialogUi() {
        _isOuterTournamentManuallyClosed = true
        _showOuterTournamentDialog.value = false
    }

    fun closeOuterTournamentDialog() {
        closeOuterTournamentDialogUi()
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(pendingCompetitionResults = emptyList()) }
        }
    }

    fun resetOuterTournamentClosedFlag() {
        _isOuterTournamentManuallyClosed = false
    }

    fun promoteSelectedDisciplesToInner(selectedDiscipleIds: Set<String>) {
        viewModelScope.launch {
            try {
                val promotedToInnerIds = mutableListOf<String>()
                selectedDiscipleIds.forEach { discipleId ->
                    gameEngine.updateDisciple(discipleId) { disciple ->
                        if (disciple.discipleType == "outer") {
                            promotedToInnerIds.add(discipleId)
                            disciple.copy(discipleType = "inner")
                        } else {
                            disciple
                        }
                    }
                }

                if (promotedToInnerIds.isNotEmpty()) {
                    val currentSpiritMineSlots = gameEngine.gameData.value.spiritMineSlots
                    var slotsChanged = false
                    val updatedSpiritMineSlots = currentSpiritMineSlots.map { slot ->
                        if (slot.discipleId in promotedToInnerIds) {
                            slotsChanged = true
                            slot.copy(discipleId = "", discipleName = "")
                        } else {
                            slot
                        }
                    }
                    if (slotsChanged) {
                        gameEngine.updateGameData { it.copy(spiritMineSlots = updatedSpiritMineSlots) }
                    }
                    gameEngine.syncAllDiscipleStatuses()
                }

                closeOuterTournamentDialogUi()
                gameEngine.updateGameData { it.copy(pendingCompetitionResults = emptyList()) }
            } catch (e: Exception) {
                showError("晋升弟子失败: ${e.message}")
                closeOuterTournamentDialogUi()
                gameEngine.updateGameData { it.copy(pendingCompetitionResults = emptyList()) }
            }
        }
    }
}
