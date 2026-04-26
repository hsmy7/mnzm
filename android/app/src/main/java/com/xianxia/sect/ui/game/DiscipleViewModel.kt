package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiscipleViewModel @Inject constructor(
    private val gameEngine: GameEngine
) : BaseViewModel() {
    
    companion object {
        private const val TAG = "DiscipleViewModel"
    }
    
    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), gameEngine.gameData.value)
    
    val disciples: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    
    private val _selectedDiscipleId = MutableStateFlow<String?>(null)
    val selectedDiscipleId: StateFlow<String?> = _selectedDiscipleId.asStateFlow()
    
    fun selectDisciple(discipleId: String?) {
        _selectedDiscipleId.value = discipleId
    }
    
    /**
     * 获取当前选中的弟子
     *
     * @return 当前选中的弟子对象，如果未选中任何弟子或选中的弟子ID不存在则返回null
     *
     * 注意事项：
     * - 如果用户尚未选择任何弟子（selectedDiscipleId 为 null），返回 null
     * - 如果选择的弟子ID在弟子列表中不存在（例如弟子已被删除），返回 null
     * - 调用方必须检查返回值是否为 null 后再使用
     *
     * 使用示例：
     * ```kotlin
     * val disciple = viewModel.getSelectedDisciple()
     * if (disciple != null) {
     *     // 安全使用 disciple
     * } else {
     *     // 处理未选中或弟子不存在的情况
     * }
     * ```
     */
    fun getSelectedDisciple(): DiscipleAggregate? {
        return try {
            _selectedDiscipleId.value?.let { id ->
                disciples.value.find { it.id == id }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting selected disciple", e)
            showError("获取选中弟子信息失败")
            null
        }
    }

    /**
     * 根据ID获取弟子信息
     *
     * @param id 弟子ID
     * @return 弟子对象，如果ID不存在则返回null
     *
     * @throws IllegalArgumentException 如果id为空字符串
     */
    fun getDiscipleById(id: String): DiscipleAggregate? {
        if (id.isBlank()) {
            Log.w(TAG, "getDiscipleById called with blank id")
            return null
        }

        return try {
            disciples.value.find { it.id == id }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting disciple by id: $id", e)
            null
        }
    }
    
    val aliveDisciples: List<DiscipleAggregate>
        get() = disciples.value.filter { it.isAlive }

    val idleDisciples: List<DiscipleAggregate>
        get() = disciples.value.filter { it.isAlive && it.status == DiscipleStatus.IDLE }

    val workingDisciples: List<DiscipleAggregate>
        get() = disciples.value.filter { it.isAlive && it.status != DiscipleStatus.IDLE }
    
    fun getDisciplesByStatus(status: DiscipleStatus): List<DiscipleAggregate> {
        return disciples.value.filter { it.isAlive && it.status == status }
    }
    
    fun getDisciplesByRealm(minRealm: Int, maxRealm: Int = 0): List<DiscipleAggregate> {
        return disciples.value.filter { 
            it.isAlive && it.realm >= minRealm && (maxRealm == 0 || it.realm <= maxRealm)
        }
    }
    
    fun getDisciplesForBattleTeamSlot(slotIndex: Int, currentSlotDiscipleIds: List<String>): List<DiscipleAggregate> {
        return disciples.value.filter { disciple ->
            disciple.isAlive &&
            disciple.realmLayer > 0 &&
            disciple.status == DiscipleStatus.IDLE &&
            !currentSlotDiscipleIds.contains(disciple.id) &&
            !isPositionWorkStatus(disciple.id)
        }.sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }
    
    fun getEligibleScoutDisciples(): List<DiscipleAggregate> {
        return disciples.value.filter { 
            it.isAlive && it.status == DiscipleStatus.IDLE 
        }
    }
    
    fun getEligibleEnvoyDisciples(sectLevel: Int): List<DiscipleAggregate> {
        val requiredRealm = gameEngine.getEnvoyRealmRequirement(sectLevel)
        return disciples.value.filter { 
            it.isAlive && 
            it.status == DiscipleStatus.IDLE &&
            it.realm <= requiredRealm
        }
    }
    
    fun getEligibleRequestDisciples(): List<DiscipleAggregate> {
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
                showSuccess("成功招募弟子")
            } catch (e: Exception) {
                showError(e.message ?: "招募失败")
            }
        }
    }
    
    fun dismissDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = getDiscipleById(discipleId) ?: return@launch
                gameEngine.dismissDisciple(discipleId)
                showSuccess("已将${disciple.name}逐出宗门")
            } catch (e: Exception) {
                showError(e.message ?: "逐出失败")
            }
        }
    }
    
    fun giveItemToDisciple(discipleId: String, itemId: String, itemType: String) {
        viewModelScope.launch {
            try {
                gameEngine.giveItemToDisciple(discipleId, itemId, itemType)
                showSuccess("物品使用成功")
            } catch (e: Exception) {
                showError(e.message ?: "使用失败")
            }
        }
    }
    
    fun equipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.equipItem(discipleId, equipmentId)
                showSuccess("装备成功")
            } catch (e: Exception) {
                showError(e.message ?: "装备失败")
            }
        }
    }
    
    fun unequipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.unequipItemById(discipleId, equipmentId)
                showSuccess("卸下装备成功")
            } catch (e: Exception) {
                showError(e.message ?: "卸下失败")
            }
        }
    }
    
    fun assignManual(discipleId: String, manualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignManual(discipleId, manualId)
                showSuccess("功法分配成功")
            } catch (e: Exception) {
                showError(e.message ?: "分配失败")
            }
        }
    }
    
    fun removeManual(discipleId: String, manualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.removeManual(discipleId, manualId)
                showSuccess("功法已收回")
            } catch (e: Exception) {
                showError(e.message ?: "收回失败")
            }
        }
    }
    
    fun startBreakthrough(discipleId: String, pillId: String) {
        viewModelScope.launch {
            try {
                val disciple = getDiscipleById(discipleId) ?: return@launch
                gameEngine.usePill(discipleId, pillId)
                showSuccess("${disciple.name}尝试突破")
            } catch (e: Exception) {
                showError(e.message ?: "突破失败")
            }
        }
    }
    
    fun resetAllDisciplesStatus() {
        viewModelScope.launch {
            gameEngine.resetAllDisciplesStatus()
        }
    }
    
    fun syncAllDiscipleStatuses() {
        gameEngine.syncAllDiscipleStatuses()
    }
    
    fun getEquipmentById(id: String): EquipmentInstance? {
        return gameEngine.equipmentInstances.value.find { it.id == id }
    }
    
    fun getManualById(id: String): ManualInstance? {
        return gameEngine.manualInstances.value.find { it.id == id }
    }
    
    fun getPillById(id: String): Pill? {
        return gameEngine.pills.value.find { it.id == id }
    }
    
    fun getMaterialById(id: String): Material? {
        return gameEngine.materials.value.find { it.id == id }
    }
    
    fun getEquipmentByOwner(discipleId: String): List<EquipmentInstance> {
        return gameEngine.equipmentInstances.value.filter { it.ownerId == discipleId }
    }
    
    fun getManualsByOwner(discipleId: String): List<ManualInstance> {
        return gameEngine.manualInstances.value.filter { it.ownerId == discipleId }
    }
}
