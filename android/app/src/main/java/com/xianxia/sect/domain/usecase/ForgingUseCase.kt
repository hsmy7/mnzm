package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.SlotStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * ## ForgingUseCase - 锻造用例
 *
 * ### [H-09] 过度工程化评估
 *
 * **总体评价**: 部分有价值，部分为纯代理
 *
 * **保留的方法** (有验证逻辑):
 * - `startForging()`: 槽位有效性检查 + 状态检查
 * - `collectForgeResult()`: 槽位检查 + 完成状态验证
 *
 * **@Deprecated 的方法** (纯代理):
 * - `clearForgeSlot()`: 直接转发
 * - `getForgeSlots()`: 直接转发
 */
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
    
    suspend fun startForging(params: StartForgingParams): ForgingResult {
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

        gameEngine.collectForgeResult(slotIndex)
        return CollectResult(true, itemName = slot.recipeName.ifEmpty { null })
    }

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.clearForgeSlot() directly.",
        ReplaceWith("gameEngine.clearForgeSlot(slotIndex)", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun clearForgeSlot(slotIndex: Int): CollectResult {
        gameEngine.clearForgeSlot(slotIndex)
        return CollectResult(true)
    }

    // H-09: 纯代理方法
    @Deprecated(
        "Pure proxy with no business logic. Use gameEngine.getBuildingSlots(\"forge\") directly.",
        ReplaceWith("gameEngine.getBuildingSlots(\"forge\")", "com.xianxia.sect.core.engine.GameEngine")
    )
    fun getForgeSlots(): List<BuildingSlot> = gameEngine.getBuildingSlots("forge")
}
