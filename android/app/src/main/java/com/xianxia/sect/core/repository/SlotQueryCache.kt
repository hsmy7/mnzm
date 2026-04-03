package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus

class SlotQueryCache {
    
    private var dirty: Boolean = true
    
    private var cachedSlots: List<ProductionSlot> = emptyList()
    private var workingSlotsCache: List<ProductionSlot> = emptyList()
    private var completedSlotsCache: List<ProductionSlot> = emptyList()
    private var idleSlotsCache: List<ProductionSlot> = emptyList()
    private var byTypeCache: Map<BuildingType, List<ProductionSlot>> = emptyMap()
    private var byIndexCache: Map<Pair<BuildingType, Int>, ProductionSlot> = emptyMap()
    private var byBuildingIdIndexCache: Map<Pair<String, Int>, ProductionSlot> = emptyMap()
    private var byBuildingIdCache: Map<String, List<ProductionSlot>> = emptyMap()
    
    private val updateLock = Any()
    
    fun markDirty() {
        synchronized(updateLock) {
            dirty = true
        }
    }
    
    fun invalidate() {
        synchronized(updateLock) {
            dirty = true
            cachedSlots = emptyList()
            workingSlotsCache = emptyList()
            completedSlotsCache = emptyList()
            idleSlotsCache = emptyList()
            byTypeCache = emptyMap()
            byIndexCache = emptyMap()
            byBuildingIdIndexCache = emptyMap()
            byBuildingIdCache = emptyMap()
        }
    }
    
    fun updateCache(slots: List<ProductionSlot>) {
        synchronized(updateLock) {
            cachedSlots = slots
            rebuildIndexes()
            dirty = false
        }
    }
    
    private fun ensureCache(slots: List<ProductionSlot>) {
        synchronized(updateLock) {
            if (!dirty && cachedSlots === slots) return
            cachedSlots = slots
            rebuildIndexes()
            dirty = false
        }
    }
    
    private fun rebuildIndexes() {
        workingSlotsCache = cachedSlots.filter { it.status == ProductionSlotStatus.WORKING }
        completedSlotsCache = cachedSlots.filter { it.status == ProductionSlotStatus.COMPLETED }
        idleSlotsCache = cachedSlots.filter { it.status == ProductionSlotStatus.IDLE }
        
        byTypeCache = cachedSlots.groupBy { it.buildingType }
        byBuildingIdCache = cachedSlots.groupBy { it.buildingId }
        
        byIndexCache = cachedSlots.associateBy { Pair(it.buildingType, it.slotIndex) }
        byBuildingIdIndexCache = cachedSlots.associateBy { Pair(it.buildingId, it.slotIndex) }
    }
    
    fun getWorkingSlots(slots: List<ProductionSlot>): List<ProductionSlot> {
        ensureCache(slots)
        return workingSlotsCache
    }
    
    fun getCompletedSlots(slots: List<ProductionSlot>): List<ProductionSlot> {
        ensureCache(slots)
        return completedSlotsCache
    }
    
    fun getIdleSlots(slots: List<ProductionSlot>): List<ProductionSlot> {
        ensureCache(slots)
        return idleSlotsCache
    }
    
    fun getByType(slots: List<ProductionSlot>, buildingType: BuildingType): List<ProductionSlot> {
        ensureCache(slots)
        return byTypeCache[buildingType] ?: emptyList()
    }
    
    fun getByBuildingId(slots: List<ProductionSlot>, buildingId: String): List<ProductionSlot> {
        ensureCache(slots)
        return byBuildingIdCache[buildingId] ?: emptyList()
    }
    
    fun getByIndex(slots: List<ProductionSlot>, buildingType: BuildingType, slotIndex: Int): ProductionSlot? {
        ensureCache(slots)
        return byIndexCache[Pair(buildingType, slotIndex)]
    }
    
    fun getByBuildingIdIndex(slots: List<ProductionSlot>, buildingId: String, slotIndex: Int): ProductionSlot? {
        ensureCache(slots)
        return byBuildingIdIndexCache[Pair(buildingId, slotIndex)]
    }
    
    fun getFinishedSlots(slots: List<ProductionSlot>, currentYear: Int, currentMonth: Int): List<ProductionSlot> {
        return getWorkingSlots(slots).filter { it.isFinished(currentYear, currentMonth) }
    }
    
    fun getStatistics(slots: List<ProductionSlot>): SlotCacheStatistics {
        ensureCache(slots)
        return SlotCacheStatistics(
            total = slots.size,
            working = workingSlotsCache.size,
            completed = completedSlotsCache.size,
            idle = idleSlotsCache.size,
            byTypeCount = byTypeCache.mapValues { it.value.size }
        )
    }
    
    fun isDirty(): Boolean {
        synchronized(updateLock) {
            return dirty
        }
    }
}

data class SlotCacheStatistics(
    val total: Int,
    val working: Int,
    val completed: Int,
    val idle: Int,
    val byTypeCount: Map<BuildingType, Int>
)
