package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.usecase.DisciplePositionQueryUseCase
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BattleViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val disciplePositionQuery: DisciplePositionQueryUseCase
) : BaseViewModel() {
    
    companion object {
        const val BATTLE_TEAM_FORMATION_COST = 1000L
    }
    
    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value)

    /**
     * 转换后的弟子列表（使用新的 DiscipleAggregate 模型）
     * 用于 UI 层展示，避免使用废弃的 Disciple 类
     */
    val disciplesAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val disciples: StateFlow<List<DiscipleAggregate>> = disciplesAggregates
    
    private val _battleTeamMoveMode = MutableStateFlow(false)
    val battleTeamMoveMode: StateFlow<Boolean> = _battleTeamMoveMode.asStateFlow()

    private var moveModeTeamId: String = ""

    val battleTeamMoveModeTeamId: String get() = moveModeTeamId

    private val _battleTeamSlots = MutableStateFlow<List<BattleTeamSlot>>(buildList {
        repeat(GameConfig.Battle.ELDER_SLOTS) { index ->
            add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
        }
        repeat(GameConfig.Battle.DISCIPLE_SLOTS) { index ->
            add(BattleTeamSlot(index + GameConfig.Battle.ELDER_SLOTS, slotType = BattleSlotType.DISCIPLE))
        }
    })
    val battleTeamSlots: StateFlow<List<BattleTeamSlot>> = _battleTeamSlots.asStateFlow()
    
    fun getBattleTeamCount(): Int {
        return gameEngine.gameData.value.battleTeams.size
    }

    fun getBattleTeamsAtSect(): List<BattleTeam> {
        return gameEngine.gameData.value.battleTeams.filter { it.isAtSect }
    }

    fun hasBattleTeamAtSect(): Boolean {
        return gameEngine.gameData.value.battleTeams.any { it.isAtSect }
    }

    fun getBattleTeam(teamId: String): BattleTeam? {
        return gameEngine.gameData.value.battleTeams.find { it.id == teamId }
    }

    fun getAvailableDisciplesForBattleTeamSlot(teamId: String, slotIndex: Int): List<DiscipleAggregate> {
        val battleTeam = gameEngine.gameData.value.battleTeams.find { it.id == teamId } ?: return emptyList()
        val allTeams = gameEngine.gameData.value.battleTeams
        val allOccupiedIds = allTeams.flatMap { t -> t.slots.mapNotNull { it.discipleId } }.toSet()

        return disciples.value.filter { disciple ->
            disciple.isAlive &&
            disciple.realmLayer > 0 &&
            disciple.status == DiscipleStatus.IDLE &&
            !allOccupiedIds.contains(disciple.id) &&
            !disciplePositionQuery.isPositionWorkStatus(disciple.id)
        }.sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    private fun allocateTeamNumber(): Int {
        val usedNumbers = gameEngine.gameData.value.usedTeamNumbers.toSet()
        var n = 1
        while (n in usedNumbers) n++
        return n
    }

    private suspend fun releaseTeamNumber(teamNumber: Int) {
        gameEngine.updateGameData {
            it.copy(usedTeamNumbers = it.usedTeamNumbers.filter { num -> num != teamNumber })
        }
    }
    
    fun formBattleTeam(elderIds: List<String>, discipleIds: List<String>): Boolean {
        if (elderIds.isEmpty() && discipleIds.isEmpty()) {
            showError("请至少选择一名成员")
            return false
        }

        viewModelScope.launch {
            try {
                val currentStones = gameEngine.gameData.value.spiritStones
                val cost = BATTLE_TEAM_FORMATION_COST

                if (currentStones < cost) {
                    showError("灵石不足1000，无法组建战斗队伍")
                    return@launch
                }

                val allMemberIds = elderIds + discipleIds
                val slots = mutableListOf<BattleTeamSlot>()
                
                var slotIndex = 0
                for (elderId in elderIds) {
                    val elder = disciples.value.find { it.id == elderId }
                    if (elder != null) {
                        slots.add(BattleTeamSlot(
                            index = slotIndex,
                            discipleId = elder.id,
                            discipleName = elder.name,
                            discipleRealm = elder.realmName,
                            slotType = BattleSlotType.ELDER,
                            isAlive = true
                        ))
                        slotIndex++
                    }
                }
                
                for (discipleId in discipleIds) {
                    val disciple = disciples.value.find { it.id == discipleId }
                    if (disciple != null) {
                        slots.add(BattleTeamSlot(
                            index = slotIndex,
                            discipleId = disciple.id,
                            discipleName = disciple.name,
                            discipleRealm = disciple.realmName,
                            slotType = BattleSlotType.DISCIPLE,
                            isAlive = true
                        ))
                        slotIndex++
                    }
                }
                
                val teamNumber = allocateTeamNumber()
                val battleTeam = BattleTeam(
                    id = java.util.UUID.randomUUID().toString(),
                    teamNumber = teamNumber,
                    name = "战斗${teamNumber}队",
                    slots = slots,
                    status = "idle",
                    isAtSect = true
                )

                gameEngine.updateGameData {
                    it.copy(
                        spiritStones = it.spiritStones - cost,
                        battleTeams = it.battleTeams + battleTeam,
                        usedTeamNumbers = it.usedTeamNumbers + teamNumber
                    )
                }
                
                allMemberIds.forEach { memberId ->
                    gameEngine.updateDiscipleStatus(memberId, DiscipleStatus.IN_TEAM)
                }
                
                _battleTeamSlots.value = slots
                showSuccess("战斗队伍组建成功")
            } catch (e: Exception) {
                showError(e.message ?: "组建队伍失败")
            }
        }
        return true
    }
    
    fun disbandBattleTeam(teamId: String): Boolean {
        val team = gameEngine.gameData.value.battleTeams.find { it.id == teamId }
        if (team == null) {
            showError("没有可解散的战斗队伍")
            return false
        }

        val teamNumber = team.teamNumber

        if (!team.isAtSect) {
            // 队伍不在宗门，先召回再解散
            startReturnToSectAndDisband(teamId)
            return true
        }

        if (!team.isIdle) {
            showError("队伍正在移动或战斗中，无法解散")
            return false
        }

        viewModelScope.launch {
            try {
                gameEngine.updateGameData {
                    it.copy(
                        battleTeams = it.battleTeams.filter { t -> t.id != teamId },
                        usedTeamNumbers = it.usedTeamNumbers.filter { n -> n != teamNumber }
                    )
                }
                gameEngine.syncAllDiscipleStatuses()

                _battleTeamSlots.value = buildList {
                    repeat(GameConfig.Battle.ELDER_SLOTS) { index ->
                        add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
                    }
                    repeat(GameConfig.Battle.DISCIPLE_SLOTS) { index ->
                        add(BattleTeamSlot(index + GameConfig.Battle.ELDER_SLOTS, slotType = BattleSlotType.DISCIPLE))
                    }
                }

                showSuccess("战斗队伍已解散")
            } catch (e: Exception) {
                showError(e.message ?: "解散队伍失败")
            }
        }
        return true
    }

    fun startReturnToSectAndDisband(teamId: String) {
        val team = gameEngine.gameData.value.battleTeams.find { it.id == teamId } ?: return
        viewModelScope.launch {
            try {
                val data = gameEngine.gameData.value
                val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return@launch
                val updatedTeam = team.copy(
                    status = "returning",
                    isReturning = true,
                    isAtSect = false,
                    moveProgress = 0f,
                    targetX = playerSect.x,
                    targetY = playerSect.y
                )
                gameEngine.updateGameData {
                    it.copy(battleTeams = it.battleTeams.map { t -> if (t.id == teamId) updatedTeam else t })
                }
                showSuccess("队伍正在返回宗门解散")
            } catch (e: Exception) {
                showError(e.message ?: "解散队伍失败")
            }
        }
    }
    
    fun addDiscipleToBattleTeamSlot(teamId: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            val disciple = disciples.value.find { it.id == discipleId }
            if (disciple == null) return@launch

            gameEngine.updateGameData {
                val battleTeam = it.battleTeams.find { t -> t.id == teamId } ?: return@updateGameData it
                val updatedSlots = battleTeam.slots.map { slot ->
                    if (slot.index == slotIndex) {
                        slot.copy(
                            discipleId = disciple.id,
                            discipleName = disciple.name,
                            discipleRealm = disciple.realmName,
                            isAlive = true
                        )
                    } else {
                        slot
                    }
                }
                it.copy(battleTeams = it.battleTeams.map { t -> if (t.id == teamId) t.copy(slots = updatedSlots) else t })
            }
            gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.IN_TEAM)
        }
    }

    fun removeDiscipleFromBattleTeamSlot(teamId: String, slotIndex: Int) {
        viewModelScope.launch {
            var removedDiscipleId: String? = null

            gameEngine.updateGameData {
                val battleTeam = it.battleTeams.find { t -> t.id == teamId } ?: return@updateGameData it
                val slot = battleTeam.slots.find { s -> s.index == slotIndex }
                    ?: return@updateGameData it
                removedDiscipleId = slot.discipleId
                if (removedDiscipleId.isNullOrEmpty()) return@updateGameData it

                val updatedSlots = battleTeam.slots.map { s ->
                    if (s.index == slotIndex) {
                        s.copy(
                            discipleId = "",
                            discipleName = "",
                            discipleRealm = "",
                            isAlive = true
                        )
                    } else {
                        s
                    }
                }
                it.copy(battleTeams = it.battleTeams.map { t -> if (t.id == teamId) t.copy(slots = updatedSlots) else t })
            }

            removedDiscipleId?.takeIf { it.isNotEmpty() }?.let { id ->
                gameEngine.updateDiscipleStatus(id, DiscipleStatus.IDLE)
            }
        }
    }
    
    fun startBattleTeamMoveMode(teamId: String) {
        val team = gameEngine.gameData.value.battleTeams.find { it.id == teamId }
        if (team == null || !team.isIdle || !team.isAtSect) {
            showError("战斗队伍无法移动")
            return
        }
        moveModeTeamId = teamId
        _battleTeamMoveMode.value = true
    }

    fun cancelBattleTeamMoveMode() {
        _battleTeamMoveMode.value = false
        moveModeTeamId = ""
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

    fun startTeamMoveToSect(teamId: String, targetSectId: String) {
        val data = gameEngine.gameData.value
        val team = data.battleTeams.find { it.id == teamId } ?: return
        val targetSect = data.worldMapSects.find { it.id == targetSectId } ?: return
        val playerSectId = data.worldMapSects.find { it.isPlayerSect }?.id ?: ""
        val isMoveTarget = targetSect.isPlayerSect || (targetSect.isPlayerOccupied && targetSect.occupierSectId == playerSectId)
        if (!isMoveTarget) {
            showError("不能移动到该宗门")
            return
        }
        viewModelScope.launch {
            val updatedTeam = team.copy(
                status = "moving",
                targetSectId = targetSectId,
                targetX = targetSect.x,
                targetY = targetSect.y,
                originSectId = playerSectId,
                moveProgress = 0f,
                isReturning = false,
                isAtSect = false
            )
            gameEngine.updateGameData {
                it.copy(battleTeams = it.battleTeams.map { t -> if (t.id == teamId) updatedTeam else t })
            }
            gameEngine.syncAllDiscipleStatuses()
        }
    }
    
    fun isPositionWorkStatus(discipleId: String): Boolean {
        return disciplePositionQuery.isPositionWorkStatus(discipleId)
    }

    fun getDisciplePosition(discipleId: String): String? {
        return disciplePositionQuery.getDisciplePosition(discipleId)
    }

    fun hasDisciplePosition(discipleId: String): Boolean {
        return disciplePositionQuery.hasDisciplePosition(discipleId)
    }

    private val _showBattleTeamDialog = MutableStateFlow(false)
    val showBattleTeamDialog: StateFlow<Boolean> = _showBattleTeamDialog.asStateFlow()

    fun openBattleTeamDialog(teamId: String? = null) {
        if (teamId != null) {
            val existingTeam = gameEngine.gameData.value.battleTeams.find { it.id == teamId }
            if (existingTeam != null) {
                _battleTeamSlots.value = existingTeam.slots
            }
        } else {
            _battleTeamSlots.value = buildList {
                repeat(GameConfig.Battle.ELDER_SLOTS) { index ->
                    add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
                }
                repeat(GameConfig.Battle.DISCIPLE_SLOTS) { index ->
                    add(BattleTeamSlot(index + GameConfig.Battle.ELDER_SLOTS, slotType = BattleSlotType.DISCIPLE))
                }
            }
        }
        _showBattleTeamDialog.value = true
    }

    fun closeBattleTeamDialog() {
        _showBattleTeamDialog.value = false
    }

    fun getAvailableEldersForBattleTeam(): List<DiscipleAggregate> {
        val currentSlotDiscipleIds = _battleTeamSlots.value.mapNotNull { it.discipleId }
        val occupiedIds = getWorkStatusPositionIds() + currentSlotDiscipleIds

        return disciples.value
            .filter { disciple ->
                disciple.isAlive &&
                disciple.discipleType == "inner" &&
                disciple.realmLayer > 0 &&
                disciple.age >= GameConfig.Disciple.MIN_AGE &&
                disciple.status == DiscipleStatus.IDLE &&
                disciple.realm <= ElderManagementUseCase.REALM_LAW_ENFORCEMENT &&
                !occupiedIds.contains(disciple.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForBattleTeam(): List<DiscipleAggregate> {
        val currentSlotDiscipleIds = _battleTeamSlots.value.mapNotNull { it.discipleId }
        val occupiedIds = getWorkStatusPositionIds() + currentSlotDiscipleIds

        return disciples.value
            .filter { disciple ->
                disciple.isAlive &&
                disciple.realmLayer > 0 &&
                disciple.status == DiscipleStatus.IDLE &&
                !occupiedIds.contains(disciple.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun assignDiscipleToBattleTeamSlot(slotIndex: Int, disciple: DiscipleAggregate) {
        val currentSlots = _battleTeamSlots.value.toMutableList()

        if (slotIndex < 0 || slotIndex >= currentSlots.size) {
            showError("槽位索引无效")
            return
        }

        val slotType = currentSlots[slotIndex].slotType

        if (slotType == BattleSlotType.ELDER && disciple.realm > ElderManagementUseCase.REALM_LAW_ENFORCEMENT) {
            showError("战斗长老需要达到化神境界")
            return
        }

        val existingSlot = currentSlots.find { it.discipleId == disciple.id }
        if (existingSlot != null) {
            currentSlots[existingSlot.index] = BattleTeamSlot(existingSlot.index, slotType = existingSlot.slotType)
        }
        currentSlots[slotIndex] = BattleTeamSlot(
            index = slotIndex,
            discipleId = disciple.id,
            discipleName = disciple.name,
            discipleRealm = disciple.realmName,
            slotType = slotType
        )
        _battleTeamSlots.value = currentSlots
    }

    fun createBattleTeam(): Boolean {
        val currentSlots = _battleTeamSlots.value
        val filledSlots = currentSlots.count { it.discipleId.isNotEmpty() }

        if (filledSlots < GameConfig.Battle.MIN_FORMATION_SIZE) {
            showError("必须满${GameConfig.Battle.MIN_FORMATION_SIZE}名弟子才可组建队伍")
            return false
        }

        val elderSlots = currentSlots.filter { it.slotType == BattleSlotType.ELDER }
        if (elderSlots.any { it.discipleId.isEmpty() }) {
            showError("必须填满长老槽位才可组建队伍")
            return false
        }

        viewModelScope.launch {
            try {
                val teamNumber = allocateTeamNumber()
                val battleTeam = BattleTeam(
                    id = java.util.UUID.randomUUID().toString(),
                    teamNumber = teamNumber,
                    name = "战斗${teamNumber}队",
                    slots = currentSlots,
                    isAtSect = true,
                    status = "idle"
                )

                gameEngine.updateGameData {
                    it.copy(
                        battleTeams = it.battleTeams + battleTeam,
                        usedTeamNumbers = it.usedTeamNumbers + teamNumber
                    )
                }
                gameEngine.syncAllDiscipleStatuses()

                closeBattleTeamDialog()
            } catch (e: Exception) {
                showError(e.message ?: "组建队伍失败")
            }
        }
        return true
    }

    fun returnStationedBattleTeam(teamId: String) {
        val team = gameEngine.gameData.value.battleTeams.find { it.id == teamId }
        if (team == null) {
            showError("没有可召回的战斗队伍")
            return
        }

        if (!team.isStationed) {
            showError("队伍未在驻守状态，无法召回")
            return
        }

        viewModelScope.launch {
            try {
                val data = gameEngine.gameData.value
                val playerSect = data.worldMapSects.find { it.isPlayerSect }
                val occupiedSect = data.worldMapSects.find { it.id == team.occupiedSectId }

                if (playerSect != null && occupiedSect != null) {
                    val updatedTeam = team.copy(
                        status = "returning",
                        isReturning = true,
                        isAtSect = false,
                        isOccupying = false,
                        occupiedSectId = "",
                        moveProgress = 0f,
                        currentX = occupiedSect.x,
                        currentY = occupiedSect.y
                    )
                    gameEngine.updateGameData { gameData ->
                        val updatedSects = gameData.worldMapSects.map { sect ->
                            if (sect.id == occupiedSect.id) {
                                sect.copy(
                                    garrisonTeamId = "",
                                    isPlayerOccupied = false,
                                    occupierSectId = ""
                                )
                            } else {
                                sect
                            }
                        }
                        val playerSectId = gameData.worldMapSects.find { it.isPlayerSect }?.id
                        val isGameOver = if (playerSectId != null && !gameData.isGameOver) {
                            !updatedSects.any { sect ->
                                (sect.isPlayerSect && sect.occupierSectId.isEmpty()) ||
                                (sect.occupierSectId == playerSectId && !sect.isPlayerSect)
                            }
                        } else {
                            gameData.isGameOver
                        }
                        gameData.copy(
                            battleTeams = gameData.battleTeams.map { t -> if (t.id == teamId) updatedTeam else t },
                            worldMapSects = updatedSects,
                            isGameOver = isGameOver
                        )
                    }
                } else {
                    gameEngine.updateGameData {
                        it.copy(battleTeams = it.battleTeams.filter { t -> t.id != teamId })
                    }
                    gameEngine.syncAllDiscipleStatuses()
                }

                closeBattleTeamDialog()
                showSuccess("战斗队伍正在返回中")
            } catch (e: Exception) {
                showError(e.message ?: "召回队伍失败")
            }
        }
    }

    fun isReserveDisciple(discipleId: String): Boolean {
        return disciplePositionQuery.isReserveDisciple(discipleId)
    }

    private fun getWorkStatusPositionIds(): List<String> {
        return DisciplePositionHelper.getWorkStatusPositionIds(gameEngine.gameData.value)
    }
}
