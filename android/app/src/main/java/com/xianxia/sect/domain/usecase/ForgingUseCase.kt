package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.SlotStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForgingUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    data class StartForgingParams(
        val slotIndex: Int,
        val recipe: ForgeRecipe,
        val assignedDiscipleId: String?
    )
    
    sealed class ForgingResult {
        data class Success(val slotIndex: Int) : ForgingResult()
        data class Error(val message: String) : ForgingResult()
    }
    
    data class CollectResult(
        val success: Boolean,
        val itemName: String? = null,
        val message: String? = null
    )
    
    fun startForging(params: StartForgingParams): ForgingResult {
        val slots = gameEngine.getBuildingSlots("forge")
        if (params.slotIndex < 0 || params.slotIndex >= slots.size) {
            return ForgingResult.Error("无效的锻造槽位")
        }
        
        val slot = slots[params.slotIndex]
        if (slot.status != SlotStatus.IDLE) {
            return ForgingResult.Error("该槽位正在使用中")
        }
        
        gameEngine.startForging(params.slotIndex, params.recipe.id)
        return ForgingResult.Success(params.slotIndex)
    }
    
    fun collectForgeResult(slotIndex: Int): CollectResult {
        val slots = gameEngine.getBuildingSlots("forge")
        if (slotIndex < 0 || slotIndex >= slots.size) {
            return CollectResult(false, message = "无效的锻造槽位")
        }
        
        val slot = slots[slotIndex]
        if (slot.status != SlotStatus.COMPLETED) {
            return CollectResult(false, message = "锻造尚未完成")
        }
        
        val result = gameEngine.collectForgeResult(slotIndex)
        return CollectResult(true, itemName = result?.equipment?.name)
    }
    
    fun clearForgeSlot(slotIndex: Int): CollectResult {
        gameEngine.clearForgeSlot(slotIndex)
        return CollectResult(true)
    }
    
    fun getForgeSlots(): List<BuildingSlot> = gameEngine.getBuildingSlots("forge")
}
