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
class TradeUseCase @Inject constructor(
    private val gameEngine: GameEngine,
    private val stateManager: UnifiedGameStateManager,
    private val eventBus: EventBus
) {
    data class TradeResult(
        val success: Boolean,
        val message: String = "",
        val spiritStonesSpent: Long = 0,
        val spiritStonesEarned: Long = 0
    )
    
    data class MerchantItemInfo(
        val id: String,
        val name: String,
        val price: Long,
        val type: String,
        val quantity: Int = 1
    )
    
    suspend fun buyMerchantItem(
        itemId: String,
        quantity: Int
    ): TradeResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.buyMerchantItem(itemId, quantity)
                
                eventBus.emit(NotificationEvent(
                    title = "购买成功",
                    message = "成功购买物品",
                    severity = NotificationSeverity.SUCCESS
                ))
                
                TradeResult(success = true, message = "购买成功")
            } catch (e: Exception) {
                TradeResult(false, e.message ?: "购买失败")
            }
        }
    }
    
    suspend fun listItemsToMerchant(
        items: List<Pair<String, Int>>
    ): TradeResult {
        return withContext(Dispatchers.Default) {
            try {
                if (items.isEmpty()) {
                    return@withContext TradeResult(false, "没有选择要出售的物品")
                }
                
                gameEngine.listItemsToMerchant(items)
                
                eventBus.emit(NotificationEvent(
                    title = "上架成功",
                    message = "成功上架${items.size}件物品",
                    severity = NotificationSeverity.SUCCESS
                ))
                
                TradeResult(success = true, message = "上架成功")
            } catch (e: Exception) {
                TradeResult(false, e.message ?: "上架失败")
            }
        }
    }
    
    suspend fun removePlayerListedItem(itemId: String): TradeResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.removePlayerListedItem(itemId)
                
                eventBus.emit(NotificationEvent(
                    title = "下架成功",
                    message = "物品已下架",
                    severity = NotificationSeverity.SUCCESS
                ))
                
                TradeResult(success = true, message = "下架成功")
            } catch (e: Exception) {
                TradeResult(false, e.message ?: "下架失败")
            }
        }
    }
    
    suspend fun sellEquipment(equipmentId: String): TradeResult {
        return withContext(Dispatchers.Default) {
            try {
                val state = stateManager.currentState
                val equipment = state.equipment.find { it.id == equipmentId }
                    ?: return@withContext TradeResult(false, "装备不存在")
                
                if (equipment.ownerId != null) {
                    return@withContext TradeResult(false, "装备已被装备，请先卸下")
                }
                
                val price = (equipment.basePrice * 0.8).toLong()
                gameEngine.sellEquipment(equipmentId)
                
                eventBus.emit(NotificationEvent(
                    title = "出售成功",
                    message = "出售${equipment.name}获得${price}灵石",
                    severity = NotificationSeverity.SUCCESS
                ))
                
                TradeResult(success = true, spiritStonesEarned = price)
            } catch (e: Exception) {
                TradeResult(false, e.message ?: "出售失败")
            }
        }
    }
    
    suspend fun sellManual(manualId: String, quantity: Int): TradeResult {
        return withContext(Dispatchers.Default) {
            try {
                val state = stateManager.currentState
                val manual = state.manuals.find { it.id == manualId }
                    ?: return@withContext TradeResult(false, "功法不存在")
                
                if (manual.quantity < quantity) {
                    return@withContext TradeResult(false, "功法数量不足")
                }
                
                val price = (manual.basePrice * quantity * 0.8).toLong()
                gameEngine.sellManual(manualId, quantity)
                
                eventBus.emit(NotificationEvent(
                    title = "出售成功",
                    message = "出售${manual.name}x${quantity}获得${price}灵石",
                    severity = NotificationSeverity.SUCCESS
                ))
                
                TradeResult(success = true, spiritStonesEarned = price)
            } catch (e: Exception) {
                TradeResult(false, e.message ?: "出售失败")
            }
        }
    }
    
    suspend fun sellPill(pillId: String, quantity: Int): TradeResult {
        return withContext(Dispatchers.Default) {
            try {
                val state = stateManager.currentState
                val pill = state.pills.find { it.id == pillId }
                    ?: return@withContext TradeResult(false, "丹药不存在")
                
                if (pill.quantity < quantity) {
                    return@withContext TradeResult(false, "丹药数量不足")
                }
                
                val price = (pill.basePrice * quantity * 0.8).toLong()
                gameEngine.sellPill(pillId, quantity)
                
                eventBus.emit(NotificationEvent(
                    title = "出售成功",
                    message = "出售${pill.name}x${quantity}获得${price}灵石",
                    severity = NotificationSeverity.SUCCESS
                ))
                
                TradeResult(success = true, spiritStonesEarned = price)
            } catch (e: Exception) {
                TradeResult(false, e.message ?: "出售失败")
            }
        }
    }
    
    suspend fun sellMaterial(materialId: String, quantity: Int): TradeResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.sellMaterial(materialId, quantity)
                TradeResult(success = true, message = "出售成功")
            } catch (e: Exception) {
                TradeResult(false, e.message ?: "出售失败")
            }
        }
    }
    
    suspend fun sellHerb(herbId: String, quantity: Int): TradeResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.sellHerb(herbId, quantity)
                TradeResult(success = true, message = "出售成功")
            } catch (e: Exception) {
                TradeResult(false, e.message ?: "出售失败")
            }
        }
    }
    
    suspend fun sellSeed(seedId: String, quantity: Int): TradeResult {
        return withContext(Dispatchers.Default) {
            try {
                gameEngine.sellSeed(seedId, quantity)
                TradeResult(success = true, message = "出售成功")
            } catch (e: Exception) {
                TradeResult(false, e.message ?: "出售失败")
            }
        }
    }
    
    fun getSpiritStones(): Long {
        return stateManager.currentState.gameData.spiritStones
    }
    
    fun canAfford(price: Long): Boolean {
        return getSpiritStones() >= price
    }
    
}
