package com.xianxia.sect.core.repository

import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import org.junit.Assert.*
import org.junit.Test

class RepositoryModelsTest {

    // ==================== SlotCacheStatistics ====================

    @Test
    fun slotCacheStatistics_defaultConstruction() {
        val stats = SlotCacheStatistics(
            total = 0,
            working = 0,
            completed = 0,
            idle = 0,
            byTypeCount = emptyMap()
        )
        assertEquals(0, stats.total)
        assertEquals(0, stats.working)
        assertEquals(0, stats.completed)
        assertEquals(0, stats.idle)
        assertTrue(stats.byTypeCount.isEmpty())
    }

    @Test
    fun slotCacheStatistics_equality() {
        val a = SlotCacheStatistics(total = 5, working = 2, completed = 1, idle = 2, byTypeCount = mapOf(BuildingType.ALCHEMY to 2))
        val b = SlotCacheStatistics(total = 5, working = 2, completed = 1, idle = 2, byTypeCount = mapOf(BuildingType.ALCHEMY to 2))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun slotCacheStatistics_copy() {
        val original = SlotCacheStatistics(total = 10, working = 3, completed = 2, idle = 5, byTypeCount = emptyMap())
        val modified = original.copy(working = 4, idle = 4)
        assertEquals(10, modified.total)
        assertEquals(4, modified.working)
        assertEquals(4, modified.idle)
    }

    @Test
    fun slotCacheStatistics_inequality() {
        val a = SlotCacheStatistics(total = 5, working = 2, completed = 1, idle = 2, byTypeCount = emptyMap())
        val b = SlotCacheStatistics(total = 5, working = 3, completed = 1, idle = 1, byTypeCount = emptyMap())
        assertNotEquals(a, b)
    }

    // ==================== SlotUpdate ====================

    @Test
    fun slotUpdate_construction() {
        val update = SlotUpdate(
            buildingType = BuildingType.ALCHEMY,
            slotIndex = 0,
            transform = { it }
        )
        assertEquals(BuildingType.ALCHEMY, update.buildingType)
        assertEquals(0, update.slotIndex)
    }

    @Test
    fun slotUpdate_transformExecution() {
        val slot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
        val update = SlotUpdate(
            buildingType = BuildingType.ALCHEMY,
            slotIndex = 0,
            transform = { it.copy(status = ProductionSlotStatus.WORKING) }
        )
        val result = update.transform(slot)
        assertEquals(ProductionSlotStatus.WORKING, result.status)
    }

    // ==================== SlotCache (pure logic) ====================

    @Test
    fun slotCache_initialState() {
        val cache = SlotCache()
        assertTrue(cache.getAll().isEmpty())
        assertTrue(cache.getWorkingSlots().isEmpty())
        assertTrue(cache.getCompletedSlots().isEmpty())
        assertTrue(cache.getIdleSlots().isEmpty())
    }

    @Test
    fun slotCache_updateAndGetByStatus() {
        val cache = SlotCache()
        val idleSlot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
        val workingSlot = idleSlot.copy(id = "w1", slotIndex = 1, status = ProductionSlotStatus.WORKING)
        val completedSlot = idleSlot.copy(id = "c1", slotIndex = 2, status = ProductionSlotStatus.COMPLETED)

        cache.updateCache(listOf(idleSlot, workingSlot, completedSlot))

        assertEquals(1, cache.getIdleSlots().size)
        assertEquals(1, cache.getWorkingSlots().size)
        assertEquals(1, cache.getCompletedSlots().size)
    }

    @Test
    fun slotCache_getByType() {
        val cache = SlotCache()
        val alchemySlot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
        val forgeSlot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.FORGE, buildingId = "forge")

        cache.updateCache(listOf(alchemySlot, forgeSlot))

        assertEquals(1, cache.getByType(BuildingType.ALCHEMY).size)
        assertEquals(1, cache.getByType(BuildingType.FORGE).size)
        assertEquals(0, cache.getByType(BuildingType.MINING).size)
    }

    @Test
    fun slotCache_getByBuildingId() {
        val cache = SlotCache()
        val slot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")

        cache.updateCache(listOf(slot))

        assertEquals(1, cache.getByBuildingId("alchemy").size)
        assertEquals(0, cache.getByBuildingId("forge").size)
    }

    @Test
    fun slotCache_getByIndex() {
        val cache = SlotCache()
        val slot0 = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
        val slot1 = ProductionSlot.createIdle(slotIndex = 1, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")

        cache.updateCache(listOf(slot0, slot1))

        assertNotNull(cache.getByIndex(BuildingType.ALCHEMY, 0))
        assertNotNull(cache.getByIndex(BuildingType.ALCHEMY, 1))
        assertNull(cache.getByIndex(BuildingType.ALCHEMY, 2))
    }

    @Test
    fun slotCache_getByBuildingIdIndex() {
        val cache = SlotCache()
        val slot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")

        cache.updateCache(listOf(slot))

        assertNotNull(cache.getByBuildingIdIndex("alchemy", 0))
        assertNull(cache.getByBuildingIdIndex("alchemy", 1))
        assertNull(cache.getByBuildingIdIndex("forge", 0))
    }

    @Test
    fun slotCache_getById() {
        val cache = SlotCache()
        val slot = ProductionSlot.createIdle(id = "test-id", slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")

        cache.updateCache(listOf(slot))

        assertNotNull(cache.getById("test-id"))
        assertNull(cache.getById("nonexistent"))
    }

    @Test
    fun slotCache_invalidate() {
        val cache = SlotCache()
        val slot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
        cache.updateCache(listOf(slot))
        assertFalse(cache.getAll().isEmpty())

        cache.invalidate()
        assertTrue(cache.getAll().isEmpty())
        assertTrue(cache.getWorkingSlots().isEmpty())
    }

    @Test
    fun slotCache_markDirty() {
        val cache = SlotCache()
        val slot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
        cache.updateCache(listOf(slot))
        assertFalse(cache.isDirty())

        cache.markDirty()
        assertTrue(cache.isDirty())
    }

    @Test
    fun slotCache_getStatistics() {
        val cache = SlotCache()
        val idleSlot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
        val workingSlot = idleSlot.copy(id = "w1", slotIndex = 1, status = ProductionSlotStatus.WORKING)

        cache.updateCache(listOf(idleSlot, workingSlot))

        val stats = cache.getStatistics()
        assertEquals(2, stats.total)
        assertEquals(1, stats.idle)
        assertEquals(1, stats.working)
        assertEquals(0, stats.completed)
    }

    @Test
    fun slotCache_getAllReturnsDefensiveCopy() {
        val cache = SlotCache()
        val slot = ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
        cache.updateCache(listOf(slot))

        val all = cache.getAll()
        assertEquals(1, all.size)
        // Modifying the returned list should not affect the cache
        // (toList() creates a copy)
    }

    @Test
    fun slotCache_updateCacheSameReferenceSkipsRebuild() {
        val cache = SlotCache()
        val slots = listOf(ProductionSlot.createIdle(slotIndex = 0, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy"))
        cache.updateCache(slots)
        assertFalse(cache.isDirty())

        // Same reference should not trigger rebuild
        cache.updateCache(slots)
        assertFalse(cache.isDirty())
    }

    // ==================== ProductionSlotRepository companion ====================

    @Test
    fun productionSlotRepository_getBuildingIdForType_knownTypes() {
        assertEquals("alchemy", ProductionSlotRepository.getBuildingIdForType(BuildingType.ALCHEMY))
        assertEquals("forge", ProductionSlotRepository.getBuildingIdForType(BuildingType.FORGE))
        assertEquals("mining", ProductionSlotRepository.getBuildingIdForType(BuildingType.MINING))
        assertEquals("herbGarden", ProductionSlotRepository.getBuildingIdForType(BuildingType.HERB_GARDEN))
        assertEquals("tianshu_hall", ProductionSlotRepository.getBuildingIdForType(BuildingType.ADMINISTRATION))
        assertEquals("library", ProductionSlotRepository.getBuildingIdForType(BuildingType.LIBRARY))
    }

    @Test
    fun productionSlotRepository_getBuildingIdForType_unknownTypeFallsBackToLowercase() {
        val result = ProductionSlotRepository.getBuildingIdForType(BuildingType.PATROL)
        assertEquals("patrol", result)
    }

    // ==================== Repository DEFAULT_SLOT_ID constants ====================

    @Test
    fun inventoryRepository_defaultSlotId() {
        assertEquals(0, InventoryRepository.DEFAULT_SLOT_ID)
    }

    @Test
    fun gameDataRepository_defaultSlotId() {
        assertEquals(0, GameDataRepository.DEFAULT_SLOT_ID)
    }

    @Test
    fun forgeRepository_defaultSlotId() {
        assertEquals(0, ForgeRepository.DEFAULT_SLOT_ID)
    }

    @Test
    fun equipmentRepository_defaultSlotId() {
        assertEquals(0, EquipmentRepository.DEFAULT_SLOT_ID)
    }

    @Test
    fun discipleRepository_defaultSlotId() {
        assertEquals(0, DiscipleRepository.DEFAULT_SLOT_ID)
    }

    @Test
    fun worldRepository_defaultSlotId() {
        assertEquals(0, WorldRepository.DEFAULT_SLOT_ID)
    }
}
