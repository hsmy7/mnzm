package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.model.AlchemySlotStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * ## AlchemyUseCase - 炼丹用例
 *
 * ### [H-09] 过度工程化评估
 *
 * **总体评价**: 部分有价值，部分为纯代理
 *
 * **保留的方法** (有验证逻辑):
 * - `startAlchemy()`: 槽位有效性检查 + 状态检查 + 结果封装
 * - `collectAlchemyResult()`: 槽位检查 + 完成状态验证
 *
 * **@Deprecated 的方法** (纯代理):
 * - `clearAlchemySlot()`: 直接转发
 * - `getAlchemySlots()`: 直接转发
 */
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

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.clearAlchemySlot() directly.",
        ReplaceWith("gameEngine.clearAlchemySlot(slotIndex)", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun clearAlchemySlot(slotIndex: Int): CollectResult {
        gameEngine.clearAlchemySlot(slotIndex)
        return CollectResult(true)
    }

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.getAlchemySlots() directly.",
        ReplaceWith("gameEngine.getAlchemySlots()", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun getAlchemySlots(): List<AlchemySlot> = gameEngine.getAlchemySlots()
}
