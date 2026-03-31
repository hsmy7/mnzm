package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SlotType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuildingOperationUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    data class AssignParams(
        val buildingId: String,
        val slotIndex: Int,
        val discipleId: String,
        val disciples: List<Disciple>
    )
    
    sealed class AssignResult {
        data class Success(val slotIndex: Int) : AssignResult()
        data class Error(val message: String) : AssignResult()
    }
    
    companion object {
        private val BUILDING_ID_TO_SLOT_TYPE = mapOf(
            "mine" to SlotType.MINING,
            "mining" to SlotType.MINING,
            "alchemy" to SlotType.ALCHEMY,
            "alchemyroom" to SlotType.ALCHEMY,
            "forge" to SlotType.FORGING,
            "forging" to SlotType.FORGING,
            "herb" to SlotType.HERB_GARDEN,
            "herbgarden" to SlotType.HERB_GARDEN,
            "herb_garden" to SlotType.HERB_GARDEN
        )
    }
    
    fun assignDiscipleToBuilding(params: AssignParams): AssignResult {
        val disciple = params.disciples.find { it.id == params.discipleId }
            ?: return AssignResult.Error("弟子不存在")
        
        if (disciple.status != DiscipleStatus.IDLE) {
            return AssignResult.Error("该弟子当前忙碌")
        }
        
        val slotType = getSlotTypeForBuilding(params.buildingId)
        
        gameEngine.assignDiscipleToBuildingSlot(
            params.buildingId,
            params.slotIndex,
            params.discipleId,
            slotType
        )
        return AssignResult.Success(params.slotIndex)
    }
    
    fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int): AssignResult {
        gameEngine.removeDiscipleFromBuildingSlot(buildingId, slotIndex)
        return AssignResult.Success(slotIndex)
    }
    
    fun getBuildingSlots(buildingId: String): List<BuildingSlot> {
        return gameEngine.getBuildingSlots(buildingId)
    }
    
    fun startBuildingWork(buildingId: String, slotIndex: Int, recipeId: String, baseDuration: Int): AssignResult {
        gameEngine.startBuildingWork(buildingId, slotIndex, recipeId, baseDuration)
        return AssignResult.Success(slotIndex)
    }
    
    fun collectBuildingResult(buildingId: String, slotIndex: Int): AssignResult {
        gameEngine.collectBuildingResult("$buildingId-$slotIndex")
        return AssignResult.Success(slotIndex)
    }
    
    private fun getSlotTypeForBuilding(buildingId: String): SlotType {
        val normalizedId = buildingId.lowercase().replace("_", "").replace("-", "")
        
        BUILDING_ID_TO_SLOT_TYPE[normalizedId]?.let { return it }
        
        for ((key, slotType) in BUILDING_ID_TO_SLOT_TYPE) {
            if (normalizedId.contains(key)) {
                return slotType
            }
        }
        
        return SlotType.IDLE
    }
}
