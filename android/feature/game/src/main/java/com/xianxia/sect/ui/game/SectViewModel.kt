package com.xianxia.sect.ui.game

import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.assignDirectDisciple
import com.xianxia.sect.core.engine.getDiscipleAggregate
import com.xianxia.sect.core.engine.removeDirectDisciple
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import com.xianxia.sect.core.usecase.SectPolicyToggleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class SectViewModel @Inject constructor(
    /** internal：同包政策扩展（SectViewModelPolicy*）消费——TMF 收敛外移 */
    internal val gameEngine: GameEngine,
    /** internal：同包政策扩展消费 */
    internal val sectPolicyToggle: SectPolicyToggleUseCase,
    private val elderManagement: ElderManagementUseCase
) : BaseViewModel() {

    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value)

    /**
     * 转换后的弟子列表（使用新的 DiscipleAggregate 模型）
     * 用于 UI 层展示，避免使用废弃的 Disciple 类
     */
    val disciplesAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val disciples: StateFlow<List<DiscipleAggregate>> = disciplesAggregates
    
    fun getViceSectMaster(): DiscipleAggregate? {
        val viceSectMasterId = gameEngine.gameData.value?.elderSlots?.viceSectMaster
        return getElderDisciple(viceSectMasterId)
    }

    fun getElderDisciple(elderId: String?): DiscipleAggregate? {
        if (elderId == null) return null
        return gameEngine.getDiscipleAggregate(elderId)
    }
    
    fun setViceSectMaster(discipleId: String) =
        launchElderAction({ elderManagement.assignElder(ElderSlotType.VICE_SECT_MASTER, discipleId) }, "任命失败")

    fun removeViceSectMaster() =
        launchElderAction({ elderManagement.removeElder(ElderSlotType.VICE_SECT_MASTER) }, "卸任失败")

    fun assignElder(slotType: ElderSlotType, discipleId: String) =
        launchElderAction({ elderManagement.assignElder(slotType, discipleId) }, "任命失败")
    
    fun removeElder(slotType: ElderSlotType) =
        launchElderAction({ elderManagement.removeElder(slotType) }, "卸任失败")
    
    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) =
        launchElderAction({ elderManagement.assignDirectDisciple(elderSlotType, slotIndex, discipleId) }, "分配失败")
    
    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) =
        launchElderAction({ elderManagement.removeDirectDisciple(elderSlotType, slotIndex) }, "卸任失败")
    
    fun getOuterElder(): DiscipleAggregate? {
        val outerElderId = gameEngine.gameData.value?.elderSlots?.outerElder
        return getElderDisciple(outerElderId)
    }

    fun getPreachingElder(): DiscipleAggregate? {
        val preachingElderId = gameEngine.gameData.value?.elderSlots?.preachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.preachingMasters ?: emptyList()
    }

    fun getLawEnforcementElder(): DiscipleAggregate? {
        val elderId = gameEngine.gameData.value?.elderSlots?.lawEnforcementElder
        return getElderDisciple(elderId)
    }

    fun getLawEnforcementDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.lawEnforcementDisciples ?: emptyList()
    }

    fun getViceSectMasterIntelligenceBonus(): Double {
        val viceSectMaster = getViceSectMaster() ?: return 0.0
        return sectPolicyToggle.getViceSectMasterIntelligenceBonus(viceSectMaster)
    }

}
