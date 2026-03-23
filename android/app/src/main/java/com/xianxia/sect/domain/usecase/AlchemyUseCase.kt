package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.AlchemyRecipe
import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.model.AlchemySlotStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlchemyUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    data class StartAlchemyParams(
        val slotIndex: Int,
        val recipe: AlchemyRecipe,
        val assignedDiscipleId: String?
    )
    
    sealed class AlchemyResult {
        data class Success(val slotIndex: Int) : AlchemyResult()
        data class Error(val message: String) : AlchemyResult()
    }
    
    data class CollectResult(
        val success: Boolean,
        val itemName: String? = null,
        val message: String? = null
    )
    
    fun startAlchemy(params: StartAlchemyParams): AlchemyResult {
        val slots = gameEngine.getAlchemySlots()
        if (params.slotIndex < 0 || params.slotIndex >= slots.size) {
            return AlchemyResult.Error("无效的炼丹槽位")
        }
        
        val slot = slots[params.slotIndex]
        if (slot.status != AlchemySlotStatus.IDLE) {
            return AlchemyResult.Error("该槽位正在使用中")
        }
        
        gameEngine.startAlchemy(params.slotIndex, params.recipe)
        return AlchemyResult.Success(params.slotIndex)
    }
    
    fun collectAlchemyResult(slotIndex: Int, currentYear: Int, currentMonth: Int): CollectResult {
        val slots = gameEngine.getAlchemySlots()
        if (slotIndex < 0 || slotIndex >= slots.size) {
            return CollectResult(false, message = "无效的炼丹槽位")
        }
        
        val slot = slots[slotIndex]
        if (slot.status != AlchemySlotStatus.FINISHED) {
            return CollectResult(false, message = "炼丹尚未完成")
        }
        
        val result = gameEngine.collectAlchemyResult(slotIndex)
        return CollectResult(true, itemName = result?.pill?.name)
    }
    
    fun clearAlchemySlot(slotIndex: Int): CollectResult {
        gameEngine.clearAlchemySlot(slotIndex)
        return CollectResult(true)
    }
    
    fun getAlchemySlots(): List<AlchemySlot> = gameEngine.getAlchemySlots()
}
