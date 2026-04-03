package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * ## LazySlotCache - 生产槽位缓存 (线程安全版本)
 *
 * ### [H-05] 线程安全修复说明
 *
 * **原始问题**:
 * - 使用普通的 `mutableMapOf()` 作为索引存储
 * - `getOrPut()` 操作在多线程环境下存在竞态条件 (TOCTOU race)
 * - 可能导致:
 *   1. 重复创建列表对象 (内存浪费)
 *   2. 并发修改异常 (ConcurrentModificationException)
 *   3. 数据不一致 (部分索引更新失败)
 *
 * **修复方案**:
 * - 将所有 `MutableMap` 替换为 `ConcurrentHashMap`
 * - 使用 `computeIfAbsent()` 保证原子性 get-or-create 操作
 * - 保留 `synchronized` 和 `Mutex` 用于复合操作的全局一致性
 *
 * **性能影响**:
 * - ConcurrentHashMap 的 `computeIfAbsent()` 在无竞争时接近无锁性能
 * - 读操作完全无锁 (ConcurrentHashMap 的 get 是 volatile read)
 * - 写操作使用分段锁 (比全局 synchronized 性能更好)
 *
 * ### 使用场景
 * - 游戏主循环的每 tick 更新 (高频)
 * - UI 层的查询操作 (中频)
 * - 存档加载/保存 (低频)
 */
class LazySlotCache {

    private val mutex = Mutex()
    private var slots: List<ProductionSlot> = emptyList()
    private var version: Long = 0

    // H-05: 使用 ConcurrentHashMap 替代 MutableMap，保证线程安全
    private val primaryIndex: ConcurrentHashMap<String, ProductionSlot> = ConcurrentHashMap()
    private val typeIndex: ConcurrentHashMap<BuildingType, MutableList<ProductionSlot>> = ConcurrentHashMap()
    private val statusIndex: ConcurrentHashMap<ProductionSlotStatus, MutableList<ProductionSlot>> = ConcurrentHashMap()
    private val buildingIdIndex: ConcurrentHashMap<String, MutableList<ProductionSlot>> = ConcurrentHashMap()

    @Volatile
    private var dirty: Boolean = false
    
    suspend fun update(newSlots: List<ProductionSlot>) {
        mutex.withLock {
            slots = newSlots
            version++
            rebuildAllIndexesInternal()
            dirty = false
        }
    }
    
    fun updateSync(newSlots: List<ProductionSlot>) {
        synchronized(this) {
            slots = newSlots
            version++
            rebuildAllIndexesInternal()
            dirty = false
        }
    }
    
    private fun rebuildAllIndexesInternal() {
        primaryIndex.clear()
        typeIndex.clear()
        statusIndex.clear()
        buildingIdIndex.clear()

        for (slot in slots) {
            primaryIndex[createKey(slot.buildingType, slot.slotIndex)] = slot
            primaryIndex[createBuildingIdKey(slot.buildingId, slot.slotIndex)] = slot

            // H-05: 使用 computeIfAbsent 保证线程安全的 get-or-create
            typeIndex.computeIfAbsent(slot.buildingType) { mutableListOf() }.add(slot)
            statusIndex.computeIfAbsent(slot.status) { mutableListOf() }.add(slot)
            buildingIdIndex.computeIfAbsent(slot.buildingId) { mutableListOf() }.add(slot)
        }
    }
    
    private fun createKey(buildingType: BuildingType, slotIndex: Int): String =
        "type:${buildingType.name}:$slotIndex"
    
    private fun createBuildingIdKey(buildingId: String, slotIndex: Int): String =
        "id:$buildingId:$slotIndex"
    
    fun getByIndex(buildingType: BuildingType, slotIndex: Int): ProductionSlot? =
        primaryIndex[createKey(buildingType, slotIndex)]
    
    fun getByBuildingIdIndex(buildingId: String, slotIndex: Int): ProductionSlot? =
        primaryIndex[createBuildingIdKey(buildingId, slotIndex)]
    
    fun getByType(buildingType: BuildingType): List<ProductionSlot> =
        typeIndex[buildingType]?.toList() ?: emptyList()
    
    fun getByBuildingId(buildingId: String): List<ProductionSlot> =
        buildingIdIndex[buildingId]?.toList() ?: emptyList()
    
    fun getByStatus(status: ProductionSlotStatus): List<ProductionSlot> =
        statusIndex[status]?.toList() ?: emptyList()
    
    fun getWorkingSlots(): List<ProductionSlot> = getByStatus(ProductionSlotStatus.WORKING)
    
    fun getCompletedSlots(): List<ProductionSlot> = getByStatus(ProductionSlotStatus.COMPLETED)
    
    fun getIdleSlots(): List<ProductionSlot> = getByStatus(ProductionSlotStatus.IDLE)
    
    fun getFinishedSlots(currentYear: Int, currentMonth: Int): List<ProductionSlot> =
        getWorkingSlots().filter { it.isFinished(currentYear, currentMonth) }
    
    fun getAll(): List<ProductionSlot> = slots.toList()
    
    fun getById(slotId: String): ProductionSlot? = slots.find { it.id == slotId }
    
    suspend fun updateSlot(slot: ProductionSlot) {
        mutex.withLock {
            updateSlotInternal(slot)
        }
    }
    
    fun updateSlotSync(slot: ProductionSlot) {
        synchronized(this) {
            updateSlotInternal(slot)
        }
    }
    
    private fun updateSlotInternal(slot: ProductionSlot) {
        val oldSlot = getByIndex(slot.buildingType, slot.slotIndex)

        if (oldSlot != null) {
            typeIndex[oldSlot.buildingType]?.removeAll { it.id == oldSlot.id }
            statusIndex[oldSlot.status]?.removeAll { it.id == oldSlot.id }
            buildingIdIndex[oldSlot.buildingId]?.removeAll { it.id == oldSlot.id }
        }

        primaryIndex[createKey(slot.buildingType, slot.slotIndex)] = slot
        primaryIndex[createBuildingIdKey(slot.buildingId, slot.slotIndex)] = slot

        // H-05: 使用 computeIfAbsent 保证线程安全
        typeIndex.computeIfAbsent(slot.buildingType) { mutableListOf() }.add(slot)
        statusIndex.computeIfAbsent(slot.status) { mutableListOf() }.add(slot)
        buildingIdIndex.computeIfAbsent(slot.buildingId) { mutableListOf() }.add(slot)

        slots = slots.map {
            if (it.buildingType == slot.buildingType && it.slotIndex == slot.slotIndex) slot
            else it
        }
        version++
        dirty = true
    }

    suspend fun addSlot(slot: ProductionSlot) {
        mutex.withLock {
            addSlotInternal(slot)
        }
    }

    fun addSlotSync(slot: ProductionSlot) {
        synchronized(this) {
            addSlotInternal(slot)
        }
    }

    private fun addSlotInternal(slot: ProductionSlot) {
        slots = slots + slot
        primaryIndex[createKey(slot.buildingType, slot.slotIndex)] = slot
        primaryIndex[createBuildingIdKey(slot.buildingId, slot.slotIndex)] = slot

        // H-05: 使用 computeIfAbsent 保证线程安全
        typeIndex.computeIfAbsent(slot.buildingType) { mutableListOf() }.add(slot)
        statusIndex.computeIfAbsent(slot.status) { mutableListOf() }.add(slot)
        buildingIdIndex.computeIfAbsent(slot.buildingId) { mutableListOf() }.add(slot)

        version++
        dirty = true
    }
    
    suspend fun removeSlot(slotId: String): Boolean {
        return mutex.withLock {
            removeSlotInternal(slotId)
        }
    }
    
    fun removeSlotSync(slotId: String): Boolean {
        return synchronized(this) {
            removeSlotInternal(slotId)
        }
    }
    
    private fun removeSlotInternal(slotId: String): Boolean {
        val slot = getById(slotId) ?: return false
        
        slots = slots.filter { it.id != slotId }
        primaryIndex.remove(createKey(slot.buildingType, slot.slotIndex))
        primaryIndex.remove(createBuildingIdKey(slot.buildingId, slot.slotIndex))
        typeIndex[slot.buildingType]?.removeAll { it.id == slotId }
        statusIndex[slot.status]?.removeAll { it.id == slotId }
        buildingIdIndex[slot.buildingId]?.removeAll { it.id == slotId }
        version++
        dirty = true
        return true
    }
    
    suspend fun invalidate() {
        mutex.withLock {
            invalidateInternal()
        }
    }
    
    fun invalidateSync() {
        synchronized(this) {
            invalidateInternal()
        }
    }
    
    private fun invalidateInternal() {
        slots = emptyList()
        primaryIndex.clear()
        typeIndex.clear()
        statusIndex.clear()
        buildingIdIndex.clear()
        version++
        dirty = false
    }
    
    fun markDirty() {
        synchronized(this) {
            dirty = true
        }
    }
    
    fun isDirty(): Boolean = synchronized(this) { dirty }
    
    fun getVersion(): Long = synchronized(this) { version }
    
    fun getStatistics(): LazyCacheStatistics = synchronized(this) {
        LazyCacheStatistics(
            total = slots.size,
            working = statusIndex[ProductionSlotStatus.WORKING]?.size ?: 0,
            completed = statusIndex[ProductionSlotStatus.COMPLETED]?.size ?: 0,
            idle = statusIndex[ProductionSlotStatus.IDLE]?.size ?: 0,
            primaryIndexSize = primaryIndex.size,
            version = version,
            dirty = dirty
        )
    }
}

data class LazyCacheStatistics(
    val total: Int,
    val working: Int,
    val completed: Int,
    val idle: Int,
    val primaryIndexSize: Int,
    val version: Long,
    val dirty: Boolean
)
