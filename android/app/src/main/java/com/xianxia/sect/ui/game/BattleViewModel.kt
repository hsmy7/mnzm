package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.AISectAttackManager
import com.xianxia.sect.core.engine.GameEngineAdapter
import com.xianxia.sect.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BattleViewModel @Inject constructor(
    private val gameEngine: GameEngineAdapter
) : ViewModel() {
    
    companion object {
        private const val TAG = "BattleViewModel"
    }
    
    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), gameEngine.gameData.value)
    
    val disciples: StateFlow<List<Disciple>> = gameEngine.disciples
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()
    
    private val _battleTeamMoveMode = MutableStateFlow(false)
    val battleTeamMoveMode: StateFlow<Boolean> = _battleTeamMoveMode.asStateFlow()
    
    private val _battleTeamSlots = MutableStateFlow<List<BattleTeamSlot>>(buildList {
        repeat(2) { index ->
            add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
        }
        repeat(8) { index ->
            add(BattleTeamSlot(index + 2, slotType = BattleSlotType.DISCIPLE))
        }
    })
    val battleTeamSlots: StateFlow<List<BattleTeamSlot>> = _battleTeamSlots.asStateFlow()
    
    fun clearErrorMessage() {
        _errorMessage.value = null
    }
    
    fun clearSuccessMessage() {
        _successMessage.value = null
    }
    
    fun hasBattleTeam(): Boolean {
        return gameEngine.gameData.value.battleTeam != null
    }
    
    fun hasBattleTeamAtSect(): Boolean {
        val team = gameEngine.gameData.value.battleTeam
        return team != null && team.isAtSect
    }
    
    fun getBattleTeam(): BattleTeam? {
        return gameEngine.gameData.value.battleTeam
    }
    
    fun getAvailableDisciplesForBattleTeamSlot(slotIndex: Int): List<Disciple> {
        val battleTeam = gameEngine.gameData.value.battleTeam ?: return emptyList()
        val currentSlotDiscipleIds = battleTeam.slots.mapNotNull { it.discipleId }
        
        return disciples.value.filter { disciple ->
            disciple.isAlive &&
            disciple.realmLayer > 0 &&
            disciple.status == DiscipleStatus.IDLE &&
            !currentSlotDiscipleIds.contains(disciple.id) &&
            !isPositionWorkStatus(disciple.id)
        }.sortedWith(compareBy({ -it.realm }, { -it.realmLayer }))
    }
    
    fun formBattleTeam(elderIds: List<String>, discipleIds: List<String>): Boolean {
        val currentStones = gameEngine.gameData.value.spiritStones
        val cost = 1000L
        
        if (currentStones < cost) {
            _errorMessage.value = "灵石不足1000，无法组建战斗队伍"
            return false
        }
        
        if (elderIds.isEmpty() && discipleIds.isEmpty()) {
            _errorMessage.value = "请至少选择一名成员"
            return false
        }
        
        viewModelScope.launch {
            try {
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
                
                val battleTeam = BattleTeam(
                    id = java.util.UUID.randomUUID().toString(),
                    slots = slots,
                    status = "idle",
                    isAtSect = true
                )
                
                gameEngine.updateGameData { 
                    it.copy(
                        spiritStones = it.spiritStones - cost,
                        battleTeam = battleTeam
                    )
                }
                
                allMemberIds.forEach { memberId ->
                    gameEngine.updateDiscipleStatus(memberId, DiscipleStatus.BATTLE)
                }
                
                _battleTeamSlots.value = slots
                _successMessage.value = "战斗队伍组建成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "组建队伍失败"
            }
        }
        return true
    }
    
    fun disbandBattleTeam(): Boolean {
        val team = gameEngine.gameData.value.battleTeam
        if (team == null) {
            _errorMessage.value = "没有可解散的战斗队伍"
            return false
        }
        
        if (!team.isIdle) {
            _errorMessage.value = "队伍正在移动或战斗中，无法解散"
            return false
        }
        
        if (!team.isAtSect) {
            _errorMessage.value = "队伍不在宗门，无法解散"
            return false
        }
        
        viewModelScope.launch {
            try {
                gameEngine.updateGameData { it.copy(battleTeam = null) }
                gameEngine.syncAllDiscipleStatuses()
                
                _battleTeamSlots.value = buildList {
                    repeat(2) { index ->
                        add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
                    }
                    repeat(8) { index ->
                        add(BattleTeamSlot(index + 2, slotType = BattleSlotType.DISCIPLE))
                    }
                }
                
                _successMessage.value = "战斗队伍已解散"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "解散队伍失败"
            }
        }
        return true
    }
    
    fun addDiscipleToBattleTeamSlot(slotIndex: Int, discipleId: String) {
        val battleTeam = gameEngine.gameData.value.battleTeam ?: return
        val disciple = disciples.value.find { it.id == discipleId } ?: return
        
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
        
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(battleTeam = battleTeam.copy(slots = updatedSlots)) }
            gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.BATTLE)
        }
    }
    
    fun removeDiscipleFromBattleTeamSlot(slotIndex: Int) {
        val battleTeam = gameEngine.gameData.value.battleTeam ?: return
        val slot = battleTeam.slots.find { it.index == slotIndex } ?: return
        val discipleId = slot.discipleId ?: return
        
        val updatedSlots = battleTeam.slots.map { s ->
            if (s.index == slotIndex) {
                s.copy(
                    discipleId = null,
                    discipleName = "",
                    discipleRealm = "",
                    isAlive = true
                )
            } else {
                s
            }
        }
        
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(battleTeam = battleTeam.copy(slots = updatedSlots)) }
            gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.IDLE)
        }
    }
    
    fun startBattleTeamMoveMode() {
        val team = gameEngine.gameData.value.battleTeam
        if (team == null || !team.isIdle || !team.isAtSect) {
            _errorMessage.value = "战斗队伍无法移动"
            return
        }
        _battleTeamMoveMode.value = true
    }
    
    fun cancelBattleTeamMoveMode() {
        _battleTeamMoveMode.value = false
    }
    
    fun selectBattleTeamTarget(targetSectId: String) {
        val data = gameEngine.gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val targetSect = data.worldMapSects.find { it.id == targetSectId }
        
        if (playerSect == null || targetSect == null) {
            _errorMessage.value = "无效的目标宗门"
            _battleTeamMoveMode.value = false
            return
        }
        
        if (targetSect.isPlayerSect) {
            _errorMessage.value = "不能攻击自己的宗门"
            _battleTeamMoveMode.value = false
            return
        }
        
        if (targetSect.isPlayerOccupied) {
            _errorMessage.value = "该宗门已被占领"
            _battleTeamMoveMode.value = false
            return
        }
        
        if (!playerSect.connectedSectIds.contains(targetSectId)) {
            _errorMessage.value = "目标宗门不在可达路线上"
            _battleTeamMoveMode.value = false
            return
        }
        
        viewModelScope.launch {
            gameEngine.startBattleTeamMove(targetSectId)
            _battleTeamMoveMode.value = false
        }
    }
    
    fun getMovableTargetSectIds(): List<String> {
        val data = gameEngine.gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return emptyList()
        
        return data.worldMapSects.filter { sect ->
            !sect.isPlayerSect && !sect.isPlayerOccupied && 
            AISectAttackManager.isRouteConnected(playerSect, sect, data)
        }.map { it.id }
    }
    
    fun isPositionWorkStatus(discipleId: String): Boolean {
        return DisciplePositionHelper.isPositionWorkStatus(discipleId, gameEngine.gameData.value)
    }
    
    fun getDisciplePosition(discipleId: String): String? {
        return DisciplePositionHelper.getDisciplePosition(discipleId, gameEngine.gameData.value)
    }
    
    fun hasDisciplePosition(discipleId: String): Boolean {
        return DisciplePositionHelper.hasDisciplePosition(discipleId, gameEngine.gameData.value)
    }
}
