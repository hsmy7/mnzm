package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.UnifiedGameStateManager
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.event.NotificationEvent
import com.xianxia.sect.core.event.NotificationSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductionUseCase @Inject constructor(
    private val gameEngine: GameEngine,
    private val stateManager: UnifiedGameStateManager,
    private val eventBus: EventBus
) {
    data class ProductionResult(
        val success: Boolean,
        val message: String = "",
        val itemId: String? = null
    )
    
    data class SlotInfo(
        val slotIndex: Int,
        val status: SlotStatus,
        val recipeId: String?,
        val recipeName: String?,
        val progress: Float = 0f,
        val remainingTime: Int = 0
    )
    
    suspend fun startAlchemy(
        slotIndex: Int,
        recipeId: String
    ): ProductionResult {
        return withContext(Dispatchers.Default) {
            try {
                val state = stateManager.currentState
                val slot = state.gameData.alchemySlots.getOrNull(slotIndex)
                
                if (slot == null) {
                    return@withContext ProductionResult(false, "炼丹槽位不存在")
                }
                
                if (slot.status != AlchemySlotStatus.IDLE) {
                    return@withContext ProductionResult(false, "炼丹槽位正在使用中")
                }
                
                gameEngine.startAlchemy(slotIndex, recipeId)
                
                eventBus.emit(NotificationEvent(
                    title = "炼丹开始",
                    message = "开始在槽位${slotIndex + 1}炼丹",
                    severity = NotificationSeverity.SUCCESS
                ))
                
                ProductionResult(success = true)
            } catch (e: Exception) {
                ProductionResult(false, e.message ?: "炼丹启动失败")
            }
        }
    }
    
    suspend fun collectAlchemyResult(slotIndex: Int): ProductionResult {
        return withContext(Dispatchers.Default) {
            ProductionResult(false, "炼丹产物自动收取，无需手动操作")
        }
    }
    
    suspend fun clearAlchemySlot(slotIndex: Int): ProductionResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.clearAlchemySlot(slotIndex)
                ProductionResult(success = true, message = "炼丹槽位已清空")
            } catch (e: Exception) {
                ProductionResult(false, e.message ?: "清空失败")
            }
        }
    }
    
    suspend fun startForging(
        slotIndex: Int,
        recipeId: String
    ): ProductionResult {
        return withContext(Dispatchers.Default) {
            try {
                val state = stateManager.currentState
                val forgeSlots = state.gameData.forgeSlots.filter { 
                    it.buildingId == "forge" 
                }
                val slot = forgeSlots.getOrNull(slotIndex)
                
                if (slot == null) {
                    return@withContext ProductionResult(false, "锻造槽位不存在")
                }
                
                if (slot.status != SlotStatus.IDLE) {
                    return@withContext ProductionResult(false, "锻造槽位正在使用中")
                }
                
                gameEngine.startForging(slotIndex, recipeId)
                
                eventBus.emit(NotificationEvent(
                    title = "锻造开始",
                    message = "开始在槽位${slotIndex + 1}锻造",
                    severity = NotificationSeverity.SUCCESS
                ))
                
                ProductionResult(success = true)
            } catch (e: Exception) {
                ProductionResult(false, e.message ?: "锻造启动失败")
            }
        }
    }
    
    suspend fun collectForgeResult(slotIndex: Int): ProductionResult {
        return withContext(Dispatchers.Default) {
            ProductionResult(false, "锻造产物自动收取，无需手动操作")
        }
    }
    
    suspend fun clearForgeSlot(slotIndex: Int): ProductionResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.clearForgeSlot(slotIndex)
                ProductionResult(success = true, message = "锻造槽位已清空")
            } catch (e: Exception) {
                ProductionResult(false, e.message ?: "清空失败")
            }
        }
    }
    
    suspend fun startManualPlanting(
        slotIndex: Int,
        seedId: String
    ): ProductionResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.startManualPlanting(slotIndex, seedId)
                
                eventBus.emit(NotificationEvent(
                    title = "种植开始",
                    message = "开始在槽位${slotIndex + 1}种植",
                    severity = NotificationSeverity.SUCCESS
                ))
                
                ProductionResult(success = true)
            } catch (e: Exception) {
                ProductionResult(false, e.message ?: "种植启动失败")
            }
        }
    }
    
    suspend fun harvestHerb(slotIndex: Int): ProductionResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.harvestHerb(slotIndex)
                
                eventBus.emit(NotificationEvent(
                    title = "收获完成",
                    message = "成功收获灵草",
                    severity = NotificationSeverity.SUCCESS
                ))
                
                ProductionResult(success = true)
            } catch (e: Exception) {
                ProductionResult(false, e.message ?: "收获失败")
            }
        }
    }
    
    fun getAlchemySlots(): List<AlchemySlot> {
        return stateManager.currentState.gameData.alchemySlots
    }
    
    fun getForgeSlots(): List<BuildingSlot> {
        return stateManager.currentState.gameData.forgeSlots.filter { 
            it.buildingId == "forge" 
        }
    }
    
    fun getIdleAlchemySlots(): List<AlchemySlot> {
        return stateManager.currentState.gameData.alchemySlots.filter { 
            it.status == AlchemySlotStatus.IDLE 
        }
    }
    
    fun getIdleForgeSlots(): List<BuildingSlot> {
        return stateManager.currentState.gameData.forgeSlots.filter { 
            it.buildingId == "forge" && it.status == SlotStatus.IDLE 
        }
    }
}
