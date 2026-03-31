package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngineAdapter
import com.xianxia.sect.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiscipleViewModel @Inject constructor(
    private val gameEngine: GameEngineAdapter
) : ViewModel() {
    
    companion object {
        private const val TAG = "DiscipleViewModel"
    }
    
    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), gameEngine.gameData.value)
    
    val disciples: StateFlow<List<Disciple>> = gameEngine.disciples
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()
    
    private val _selectedDiscipleId = MutableStateFlow<String?>(null)
    val selectedDiscipleId: StateFlow<String?> = _selectedDiscipleId.asStateFlow()
    
    fun clearErrorMessage() {
        _errorMessage.value = null
    }
    
    fun clearSuccessMessage() {
        _successMessage.value = null
    }
    
    fun selectDisciple(discipleId: String?) {
        _selectedDiscipleId.value = discipleId
    }
    
    fun getSelectedDisciple(): Disciple? {
        return _selectedDiscipleId.value?.let { id ->
            disciples.value.find { it.id == id }
        }
    }
    
    fun getDiscipleById(id: String): Disciple? {
        return disciples.value.find { it.id == id }
    }
    
    val aliveDisciples: List<Disciple>
        get() = disciples.value.filter { it.isAlive }
    
    val idleDisciples: List<Disciple>
        get() = disciples.value.filter { it.isAlive && it.status == DiscipleStatus.IDLE }
    
    val workingDisciples: List<Disciple>
        get() = disciples.value.filter { it.isAlive && it.status != DiscipleStatus.IDLE }
    
    fun getDisciplesByStatus(status: DiscipleStatus): List<Disciple> {
        return disciples.value.filter { it.isAlive && it.status == status }
    }
    
    fun getDisciplesByRealm(minRealm: Int, maxRealm: Int = 0): List<Disciple> {
        return disciples.value.filter { 
            it.isAlive && it.realm >= minRealm && (maxRealm == 0 || it.realm <= maxRealm)
        }
    }
    
    fun getDisciplesForBattleTeamSlot(slotIndex: Int, currentSlotDiscipleIds: List<String>): List<Disciple> {
        return disciples.value.filter { disciple ->
            disciple.isAlive &&
            disciple.realmLayer > 0 &&
            disciple.status == DiscipleStatus.IDLE &&
            !currentSlotDiscipleIds.contains(disciple.id) &&
            !isPositionWorkStatus(disciple.id)
        }.sortedWith(compareBy({ -it.realm }, { -it.realmLayer }))
    }
    
    fun getEligibleScoutDisciples(): List<Disciple> {
        return disciples.value.filter { 
            it.isAlive && it.status == DiscipleStatus.IDLE 
        }
    }
    
    fun getEligibleEnvoyDisciples(sectLevel: Int): List<Disciple> {
        val requiredRealm = gameEngine.getEnvoyRealmRequirement(sectLevel)
        return disciples.value.filter { 
            it.isAlive && 
            it.status == DiscipleStatus.IDLE &&
            it.realm <= requiredRealm
        }
    }
    
    fun getEligibleRequestDisciples(): List<Disciple> {
        return disciples.value.filter { 
            it.isAlive && 
            it.status == DiscipleStatus.IDLE &&
            it.realm <= 7
        }
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
    
    fun isReserveDisciple(discipleId: String): Boolean {
        return DisciplePositionHelper.isReserveDisciple(discipleId, gameEngine.gameData.value)
    }
    
    fun recruitDisciple() {
        viewModelScope.launch {
            try {
                gameEngine.recruitDisciple()
                _successMessage.value = "成功招募弟子"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "招募失败"
            }
        }
    }
    
    fun dismissDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = getDiscipleById(discipleId) ?: return@launch
                gameEngine.dismissDisciple(discipleId)
                _successMessage.value = "已将${disciple.name}逐出宗门"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "逐出失败"
            }
        }
    }
    
    fun giveItemToDisciple(discipleId: String, itemId: String, itemType: String) {
        viewModelScope.launch {
            try {
                gameEngine.giveItemToDisciple(discipleId, itemId, itemType)
                _successMessage.value = "物品使用成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "使用失败"
            }
        }
    }
    
    fun equipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.equipItem(discipleId, equipmentId)
                _successMessage.value = "装备成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "装备失败"
            }
        }
    }
    
    fun unequipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.unequipItemById(discipleId, equipmentId)
                _successMessage.value = "卸下装备成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸下失败"
            }
        }
    }
    
    fun assignManual(discipleId: String, manualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignManual(discipleId, manualId)
                _successMessage.value = "功法分配成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "分配失败"
            }
        }
    }
    
    fun removeManual(discipleId: String, manualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.removeManual(discipleId, manualId)
                _successMessage.value = "功法已收回"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "收回失败"
            }
        }
    }
    
    fun startBreakthrough(discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = getDiscipleById(discipleId) ?: return@launch
                gameEngine.usePill(discipleId, discipleId)
                _successMessage.value = "${disciple.name}尝试突破"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "突破失败"
            }
        }
    }
    
    fun resetAllDisciplesStatus() {
        gameEngine.resetAllDisciplesStatus()
    }
    
    fun syncAllDiscipleStatuses() {
        gameEngine.syncAllDiscipleStatuses()
    }
    
    fun getEquipmentById(id: String): Equipment? {
        return gameEngine.equipment.value.find { it.id == id }
    }
    
    fun getManualById(id: String): Manual? {
        return gameEngine.manuals.value.find { it.id == id }
    }
    
    fun getPillById(id: String): Pill? {
        return gameEngine.pills.value.find { it.id == id }
    }
    
    fun getMaterialById(id: String): Material? {
        return gameEngine.materials.value.find { it.id == id }
    }
    
    fun getEquipmentByOwner(discipleId: String): List<Equipment> {
        return gameEngine.equipment.value.filter { it.ownerId == discipleId }
    }
    
    fun getManualsByOwner(discipleId: String): List<Manual> {
        return gameEngine.manuals.value.filter { it.ownerId == discipleId }
    }
}
