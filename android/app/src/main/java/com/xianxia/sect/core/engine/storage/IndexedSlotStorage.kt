package com.xianxia.sect.core.engine.storage

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IndexedSlotStorage @Inject constructor() {
    
    private val allSlots = mutableListOf<ProductionSlot>()
    private val byTypeIndex = mutableMapOf<BuildingType, MutableList<ProductionSlot>>()
    private val byStatusIndex = mutableMapOf<ProductionSlotStatus, MutableList<ProductionSlot>>()
    private val byIdIndex = mutableMapOf<String, ProductionSlot>()
    private val byBuildingIdIndex = mutableMapOf<String, MutableList<ProductionSlot>>()
    
    private val lock = Any()
    
    val size: Int get() = synchronized(lock) { allSlots.size }
    
    fun load(slots: List<ProductionSlot>) {
        synchronized(lock) {
            clearInternal()
            slots.forEach { addInternal(it) }
        }
    }
    
    fun add(slot: ProductionSlot) {
        synchronized(lock) {
            addInternal(slot)
        }
    }
    
    fun update(oldSlot: ProductionSlot, newSlot: ProductionSlot) {
        synchronized(lock) {
            updateInternal(oldSlot, newSlot)
        }
    }
    
    fun remove(slotId: String): Boolean {
        return synchronized(lock) {
            removeInternal(slotId)
        }
    }
    
    fun clear() {
        synchronized(lock) {
            clearInternal()
        }
    }
    
    fun getAll(): List<ProductionSlot> {
        return synchronized(lock) {
            allSlots.toList()
        }
    }
    
    fun getByBuildingId(buildingId: String): List<ProductionSlot> {
        return synchronized(lock) {
            byBuildingIdIndex[buildingId]?.toList() ?: emptyList()
        }
    }
    
    fun getById(id: String): ProductionSlot? {
        return synchronized(lock) {
            byIdIndex[id]
        }
    }
    
    fun getByType(buildingType: BuildingType): List<ProductionSlot> {
        return synchronized(lock) {
            byTypeIndex[buildingType]?.toList() ?: emptyList()
        }
    }
    
    fun getByStatus(status: ProductionSlotStatus): List<ProductionSlot> {
        return synchronized(lock) {
            byStatusIndex[status]?.toList() ?: emptyList()
        }
    }
    
    fun getWorkingSlots(): List<ProductionSlot> = getByStatus(ProductionSlotStatus.WORKING)
    
    fun getCompletedSlots(): List<ProductionSlot> = getByStatus(ProductionSlotStatus.COMPLETED)
    
    fun getIdleSlots(): List<ProductionSlot> = getByStatus(ProductionSlotStatus.IDLE)
    
    fun getFinishedSlots(currentYear: Int, currentMonth: Int): List<ProductionSlot> {
        return synchronized(lock) {
            getWorkingSlots().filter { it.isFinished(currentYear, currentMonth) }
        }
    }
    
    fun getSlotByIndex(buildingType: BuildingType, slotIndex: Int): ProductionSlot? {
        return synchronized(lock) {
            byTypeIndex[buildingType]?.find { it.slotIndex == slotIndex }
        }
    }
    
    fun getSlotByBuildingIdIndex(buildingId: String, slotIndex: Int): ProductionSlot? {
        return synchronized(lock) {
            byBuildingIdIndex[buildingId]?.find { it.slotIndex == slotIndex }
        }
    }
    
    fun getStatistics(): SlotStatistics {
        return synchronized(lock) {
            SlotStatistics(
                total = allSlots.size,
                idle = byStatusIndex[ProductionSlotStatus.IDLE]?.size ?: 0,
                working = byStatusIndex[ProductionSlotStatus.WORKING]?.size ?: 0,
                completed = byStatusIndex[ProductionSlotStatus.COMPLETED]?.size ?: 0,
                byType = byTypeIndex.mapValues { it.value.size }
            )
        }
    }
    
    private fun addInternal(slot: ProductionSlot) {
        allSlots.add(slot)
        byTypeIndex.getOrPut(slot.buildingType) { mutableListOf() }.add(slot)
        byStatusIndex.getOrPut(slot.status) { mutableListOf() }.add(slot)
        byIdIndex[slot.id] = slot
        byBuildingIdIndex.getOrPut(slot.buildingId) { mutableListOf() }.add(slot)
    }
    
    private fun updateInternal(oldSlot: ProductionSlot, newSlot: ProductionSlot) {
        val existingSlot = byIdIndex[oldSlot.id] ?: return
        
        val mainIndex = allSlots.indexOfFirst { it === existingSlot }
        if (mainIndex >= 0) {
            allSlots[mainIndex] = newSlot
        }
        
        if (oldSlot.buildingType != newSlot.buildingType) {
            byTypeIndex[oldSlot.buildingType]?.removeAll { it.id == oldSlot.id }
            byTypeIndex.getOrPut(newSlot.buildingType) { mutableListOf() }.add(newSlot)
        } else {
            byTypeIndex[newSlot.buildingType]?.let { list ->
                val idx = list.indexOfFirst { it.id == oldSlot.id }
                if (idx >= 0) list[idx] = newSlot
            }
        }
        
        if (oldSlot.status != newSlot.status) {
            byStatusIndex[oldSlot.status]?.removeAll { it.id == oldSlot.id }
            byStatusIndex.getOrPut(newSlot.status) { mutableListOf() }.add(newSlot)
        } else {
            byStatusIndex[newSlot.status]?.let { list ->
                val idx = list.indexOfFirst { it.id == oldSlot.id }
                if (idx >= 0) list[idx] = newSlot
            }
        }
        
        if (oldSlot.buildingId != newSlot.buildingId) {
            byBuildingIdIndex[oldSlot.buildingId]?.removeAll { it.id == oldSlot.id }
            byBuildingIdIndex.getOrPut(newSlot.buildingId) { mutableListOf() }.add(newSlot)
        } else {
            byBuildingIdIndex[newSlot.buildingId]?.let { list ->
                val idx = list.indexOfFirst { it.id == oldSlot.id }
                if (idx >= 0) list[idx] = newSlot
            }
        }
        
        byIdIndex[newSlot.id] = newSlot
    }
    
    private fun removeInternal(slotId: String): Boolean {
        val slot = byIdIndex[slotId] ?: return false
        
        allSlots.removeAll { it.id == slotId }
        byTypeIndex[slot.buildingType]?.removeAll { it.id == slotId }
        byStatusIndex[slot.status]?.removeAll { it.id == slotId }
        byIdIndex.remove(slotId)
        byBuildingIdIndex[slot.buildingId]?.removeAll { it.id == slotId }
        
        return true
    }
    
    private fun clearInternal() {
        allSlots.clear()
        byTypeIndex.clear()
        byStatusIndex.clear()
        byIdIndex.clear()
        byBuildingIdIndex.clear()
    }
}

data class SlotStatistics(
    val total: Int,
    val idle: Int,
    val working: Int,
    val completed: Int,
    val byType: Map<BuildingType, Int>
)
