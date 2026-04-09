package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
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
        val recipeId: String,
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
    
    suspend fun startAlchemy(params: StartAlchemyParams): AlchemyResult {
        val slots = gameEngine.getAlchemySlots()
        if (params.slotIndex < 0 || params.slotIndex >= slots.size) {
            return AlchemyResult.Error("无效的炼丹槽位")
        }
        
        val slot = slots[params.slotIndex]
        if (slot.status != AlchemySlotStatus.IDLE) {
            return AlchemyResult.Error("该槽位正在使用中")
        }
        
        val success = gameEngine.startAlchemy(params.slotIndex, params.recipeId)
        return if (success) {
            AlchemyResult.Success(params.slotIndex)
        } else {
            AlchemyResult.Error("炼丹启动失败")
        }
    }
    
    fun collectAlchemyResult(slotIndex: Int, currentYear: Int, currentMonth: Int): CollectResult {
        return CollectResult(false, message = "炼丹产物自动收取，无需手动操作")
    }
}
