package com.xianxia.sect.core.service

import com.xianxia.sect.core.engine.system.inventory.AddResult
import com.xianxia.sect.core.engine.system.inventory.InventorySystemV2
import com.xianxia.sect.core.engine.system.inventory.ItemType
import com.xianxia.sect.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnifiedItemService @Inject constructor(
    private val inventorySystem: InventorySystemV2
) {
    suspend fun <T : GameItem> add(
        itemType: ItemType,
        item: T,
        merge: Boolean = true
    ): AddResult {
        return when (itemType) {
            ItemType.EQUIPMENT -> {
                @Suppress("UNCHECKED_CAST")
                inventorySystem.addEquipment(item as Equipment)
            }
            ItemType.MANUAL -> {
                @Suppress("UNCHECKED_CAST")
                inventorySystem.addManual(item as Manual, merge)
            }
            ItemType.PILL -> {
                @Suppress("UNCHECKED_CAST")
                inventorySystem.addPill(item as Pill, merge)
            }
            ItemType.MATERIAL -> {
                @Suppress("UNCHECKED_CAST")
                inventorySystem.addMaterial(item as Material, merge)
            }
            ItemType.HERB -> {
                @Suppress("UNCHECKED_CAST")
                inventorySystem.addHerb(item as Herb, merge)
            }
            ItemType.SEED -> {
                @Suppress("UNCHECKED_CAST")
                inventorySystem.addSeed(item as Seed, merge)
            }
        }
    }
    
    suspend fun <T : GameItem> remove(
        itemType: ItemType,
        id: String,
        quantity: Int = 1
    ): Boolean {
        return when (itemType) {
            ItemType.EQUIPMENT -> inventorySystem.removeEquipment(id)
            ItemType.MANUAL -> inventorySystem.removeManual(id, quantity)
            ItemType.PILL -> inventorySystem.removePill(id, quantity)
            ItemType.MATERIAL -> inventorySystem.removeMaterial(id, quantity)
            ItemType.HERB -> inventorySystem.removeHerb(id, quantity)
            ItemType.SEED -> inventorySystem.removeSeed(id, quantity)
        }
    }
    
    suspend fun <T : GameItem> getById(itemType: ItemType, id: String): T? {
        @Suppress("UNCHECKED_CAST")
        return when (itemType) {
            ItemType.EQUIPMENT -> inventorySystem.getEquipmentById(id) as T?
            ItemType.MANUAL -> inventorySystem.getManualById(id) as T?
            ItemType.PILL -> inventorySystem.getPillById(id) as T?
            ItemType.MATERIAL -> inventorySystem.getMaterialById(id) as T?
            ItemType.HERB -> inventorySystem.getHerbById(id) as T?
            ItemType.SEED -> inventorySystem.getSeedById(id) as T?
        }
    }
    
    suspend fun <T : GameItem> getQuantity(itemType: ItemType, id: String): Int {
        return when (itemType) {
            ItemType.EQUIPMENT -> if (inventorySystem.getEquipmentById(id) != null) 1 else 0
            ItemType.MANUAL -> inventorySystem.getManualById(id)?.quantity ?: 0
            ItemType.PILL -> inventorySystem.getPillById(id)?.quantity ?: 0
            ItemType.MATERIAL -> inventorySystem.getMaterialById(id)?.quantity ?: 0
            ItemType.HERB -> inventorySystem.getHerbById(id)?.quantity ?: 0
            ItemType.SEED -> inventorySystem.getSeedById(id)?.quantity ?: 0
        }
    }
    
    suspend fun <T : GameItem> hasItem(itemType: ItemType, id: String): Boolean {
        return when (itemType) {
            ItemType.EQUIPMENT -> inventorySystem.getEquipmentById(id) != null
            ItemType.MANUAL -> inventorySystem.getManualById(id) != null
            ItemType.PILL -> inventorySystem.getPillById(id) != null
            ItemType.MATERIAL -> inventorySystem.getMaterialById(id) != null
            ItemType.HERB -> inventorySystem.getHerbById(id) != null
            ItemType.SEED -> inventorySystem.getSeedById(id) != null
        }
    }
    
    fun <T : GameItem> observeById(itemType: ItemType, id: String): Flow<T?> {
        return flowOf(null)
    }
    
    fun <T : GameItem> observeByRarity(itemType: ItemType, rarity: Int): Flow<List<T>> {
        return flowOf(emptyList())
    }
    
    suspend fun getTotalItemCount(): Int {
        return inventorySystem.getTotalItemCount()
    }
    
    fun canAddItem(): Boolean = inventorySystem.canAddItem()
    
    fun canAddItems(count: Int): Boolean = inventorySystem.canAddItems(count)
    
    fun getCapacityInfo() = inventorySystem.getCapacityInfo()
    
    suspend fun clearAll() {
        inventorySystem.clear()
    }
    
    fun getList(itemType: ItemType): List<GameItem> {
        return when (itemType) {
            ItemType.EQUIPMENT -> inventorySystem.getEquipmentList()
            ItemType.MANUAL -> inventorySystem.getManualList()
            ItemType.PILL -> inventorySystem.getPillList()
            ItemType.MATERIAL -> inventorySystem.getMaterialList()
            ItemType.HERB -> inventorySystem.getHerbList()
            ItemType.SEED -> inventorySystem.getSeedList()
        }
    }
    
    suspend fun updateItem(itemType: ItemType, id: String, transform: (GameItem) -> GameItem): Boolean {
        return when (itemType) {
            ItemType.EQUIPMENT -> {
                val item = inventorySystem.getEquipmentById(id) ?: return false
                @Suppress("UNCHECKED_CAST")
                inventorySystem.updateEquipment(id) { transform(it) as Equipment }
            }
            ItemType.MANUAL -> {
                val item = inventorySystem.getManualById(id) ?: return false
                @Suppress("UNCHECKED_CAST")
                inventorySystem.updateManual(id) { transform(it) as Manual }
            }
            ItemType.PILL -> {
                val item = inventorySystem.getPillById(id) ?: return false
                @Suppress("UNCHECKED_CAST")
                inventorySystem.updatePill(id) { transform(it) as Pill }
            }
            ItemType.MATERIAL -> {
                val item = inventorySystem.getMaterialById(id) ?: return false
                @Suppress("UNCHECKED_CAST")
                inventorySystem.updateMaterial(id) { transform(it) as Material }
            }
            ItemType.HERB -> {
                val item = inventorySystem.getHerbById(id) ?: return false
                @Suppress("UNCHECKED_CAST")
                inventorySystem.updateHerb(id) { transform(it) as Herb }
            }
            ItemType.SEED -> {
                val item = inventorySystem.getSeedById(id) ?: return false
                @Suppress("UNCHECKED_CAST")
                inventorySystem.updateSeed(id) { transform(it) as Seed }
            }
        }
    }
    
    fun hasItemByType(itemType: String, itemId: String): Boolean {
        return inventorySystem.hasItem(itemType, itemId)
    }
    
    fun getItemQuantityByType(itemType: String, itemId: String): Int {
        return inventorySystem.getItemQuantity(itemType, itemId)
    }
}
