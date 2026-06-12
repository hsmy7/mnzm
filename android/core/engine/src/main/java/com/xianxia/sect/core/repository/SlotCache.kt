package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import java.util.concurrent.ConcurrentHashMap

class SlotCache {

    private val lock = Any()

    @Volatile
    private var dirty: Boolean = true

    @Volatile
    private var slots: List<ProductionSlot> = emptyList()

    private val typeIndex: ConcurrentHashMap<BuildingType, MutableList<ProductionSlot>> = ConcurrentHashMap()
    private val statusIndex: ConcurrentHashMap<ProductionSlotStatus, MutableList<ProductionSlot>> = ConcurrentHashMap()
    private val buildingIdIndex: ConcurrentHashMap<String, MutableList<ProductionSlot>> = ConcurrentHashMap()
    private val primaryIndex: ConcurrentHashMap<String, ProductionSlot> = ConcurrentHashMap()
    private val idIndex: ConcurrentHashMap<String, ProductionSlot> = ConcurrentHashMap()

    fun updateCache(slots: List<ProductionSlot>) {
        synchronized(lock) {
            if (!dirty && this.slots === slots) return
            this.slots = slots
            rebuildIndexes()
            dirty = false
        }
    }

    fun markDirty() {
        synchronized(lock) {
            dirty = true
        }
    }

    fun invalidate() {
        synchronized(lock) {
            slots = emptyList()
            typeIndex.clear()
            statusIndex.clear()
            buildingIdIndex.clear()
            primaryIndex.clear()
            idIndex.clear()
            dirty = false
        }
    }

    fun isDirty(): Boolean = synchronized(lock) { dirty }

    private fun ensureIndexes() {
        if (!dirty) return
        synchronized(lock) {
            if (!dirty) return
            rebuildIndexes()
            dirty = false
        }
    }

    private fun rebuildIndexes() {
        typeIndex.clear()
        statusIndex.clear()
        buildingIdIndex.clear()
        primaryIndex.clear()
        idIndex.clear()

        for (slot in slots) {
            primaryIndex[typeKey(slot.buildingType, slot.slotIndex)] = slot
            primaryIndex[buildingIdKey(slot.buildingId, slot.slotIndex)] = slot
            idIndex[slot.id] = slot

            typeIndex.computeIfAbsent(slot.buildingType) { mutableListOf() }.add(slot)
            statusIndex.computeIfAbsent(slot.status) { mutableListOf() }.add(slot)
            buildingIdIndex.computeIfAbsent(slot.buildingId) { mutableListOf() }.add(slot)
        }
    }

    private fun typeKey(buildingType: BuildingType, slotIndex: Int): String =
        "type:${buildingType.name}:$slotIndex"

    private fun buildingIdKey(buildingId: String, slotIndex: Int): String =
        "id:$buildingId:$slotIndex"

    fun getWorkingSlots(): List<ProductionSlot> {
        ensureIndexes()
        return statusIndex[ProductionSlotStatus.WORKING]?.toList() ?: emptyList()
    }

    fun getCompletedSlots(): List<ProductionSlot> {
        ensureIndexes()
        return statusIndex[ProductionSlotStatus.COMPLETED]?.toList() ?: emptyList()
    }

    fun getIdleSlots(): List<ProductionSlot> {
        ensureIndexes()
        return statusIndex[ProductionSlotStatus.IDLE]?.toList() ?: emptyList()
    }

    fun getByType(buildingType: BuildingType): List<ProductionSlot> {
        ensureIndexes()
        return typeIndex[buildingType]?.toList() ?: emptyList()
    }

    fun getByBuildingId(buildingId: String): List<ProductionSlot> {
        ensureIndexes()
        return buildingIdIndex[buildingId]?.toList() ?: emptyList()
    }

    fun getByIndex(buildingType: BuildingType, slotIndex: Int): ProductionSlot? {
        ensureIndexes()
        return primaryIndex[typeKey(buildingType, slotIndex)]
    }

    fun getByBuildingIdIndex(buildingId: String, slotIndex: Int): ProductionSlot? {
        ensureIndexes()
        return primaryIndex[buildingIdKey(buildingId, slotIndex)]
    }

    fun getFinishedSlots(currentYear: Int, currentMonth: Int): List<ProductionSlot> =
        getWorkingSlots().filter { it.isFinished(currentYear, currentMonth) }

    fun getAll(): List<ProductionSlot> = synchronized(lock) { slots.toList() }

    fun getById(slotId: String): ProductionSlot? {
        ensureIndexes()
        return idIndex[slotId]
    }

    fun getStatistics(): SlotCacheStatistics {
        ensureIndexes()
        return synchronized(lock) {
            SlotCacheStatistics(
                total = slots.size,
                working = statusIndex[ProductionSlotStatus.WORKING]?.size ?: 0,
                completed = statusIndex[ProductionSlotStatus.COMPLETED]?.size ?: 0,
                idle = statusIndex[ProductionSlotStatus.IDLE]?.size ?: 0,
                byTypeCount = typeIndex.mapValues { it.value.size }
            )
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
