package com.xianxia.sect.data

import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.engine.UnifiedStorageEngine
import com.xianxia.sect.data.model.SaveData
import org.junit.Assert.*
import org.junit.Test

class StorageConstantsTest {

    // ==================== Slot constants ====================

    @Test
    fun `DEFAULT_MAX_SLOTS is 6`() {
        assertEquals(6, StorageConstants.DEFAULT_MAX_SLOTS)
    }

    @Test
    fun `AUTO_SAVE_SLOT is 0`() {
        assertEquals(0, StorageConstants.AUTO_SAVE_SLOT)
    }

    @Test
    fun `EMERGENCY_SLOT is -1`() {
        assertEquals(-1, StorageConstants.EMERGENCY_SLOT)
    }

    // ==================== Cache constants ====================

    @Test
    fun `DEFAULT_MEMORY_CACHE_SIZE_MB is 8`() {
        assertEquals(8L, StorageConstants.DEFAULT_MEMORY_CACHE_SIZE_MB)
    }

    @Test
    fun `DEFAULT_DISK_CACHE_SIZE_MB is 32`() {
        assertEquals(32L, StorageConstants.DEFAULT_DISK_CACHE_SIZE_MB)
    }

    @Test
    fun `DEFAULT_MAX_CACHE_ENTRIES is 1000`() {
        assertEquals(1000, StorageConstants.DEFAULT_MAX_CACHE_ENTRIES)
    }

    @Test
    fun `SWR_TTL_MS is 1 hour`() {
        assertEquals(3600_000L, StorageConstants.SWR_TTL_MS)
    }

    @Test
    fun `SWR_STALE_WHILE_REVALIDATE_MS is 5 minutes`() {
        assertEquals(300_000L, StorageConstants.SWR_STALE_WHILE_REVALIDATE_MS)
    }

    @Test
    fun `CACHE_CLEANUP_INTERVAL_MS is 30 seconds`() {
        assertEquals(30_000L, StorageConstants.CACHE_CLEANUP_INTERVAL_MS)
    }

    // ==================== DB constants ====================

    @Test
    fun `DEFAULT_BATCH_SIZE is 200`() {
        assertEquals(200, StorageConstants.DEFAULT_BATCH_SIZE)
    }

    @Test
    fun `INCREMENTAL_THRESHOLD_BYTES is 50KB`() {
        assertEquals(50 * 1024, StorageConstants.INCREMENTAL_THRESHOLD_BYTES)
    }

    // ==================== WAL constants ====================

    @Test
    fun `WAL_DIR_NAME is wal_v4`() {
        assertEquals("wal_v4", StorageConstants.WAL_DIR_NAME)
    }

    @Test
    fun `MAX_WAL_SIZE_BYTES is 10MB`() {
        assertEquals(10L * 1024 * 1024, StorageConstants.MAX_WAL_SIZE_BYTES)
    }

    @Test
    fun `MAX_SNAPSHOT_SIZE_BYTES is 50MB`() {
        assertEquals(50L * 1024 * 1024, StorageConstants.MAX_SNAPSHOT_SIZE_BYTES)
    }

    @Test
    fun `MAX_TOTAL_SNAPSHOTS_SIZE_BYTES is 200MB`() {
        assertEquals(200L * 1024 * 1024, StorageConstants.MAX_TOTAL_SNAPSHOTS_SIZE_BYTES)
    }

    @Test
    fun `MAX_SNAPSHOT_AGE_MS is 12 hours`() {
        assertEquals(12 * 60 * 60 * 1000L, StorageConstants.MAX_SNAPSHOT_AGE_MS)
    }

    @Test
    fun `MIN_SNAPSHOTS_TO_KEEP is 3`() {
        assertEquals(3, StorageConstants.MIN_SNAPSHOTS_TO_KEEP)
    }

    @Test
    fun `CHECKPOINT_INTERVAL is 100`() {
        assertEquals(100, StorageConstants.CHECKPOINT_INTERVAL)
    }

    @Test
    fun `WAL_BUFFER_SIZE_BYTES is 64KB`() {
        assertEquals(64 * 1024, StorageConstants.WAL_BUFFER_SIZE_BYTES)
    }

    // ==================== Retry constants ====================

    @Test
    fun `SAVE_RETRY_MAX_RETRIES is 3`() {
        assertEquals(3, StorageConstants.SAVE_RETRY_MAX_RETRIES)
    }

    @Test
    fun `SAVE_RETRY_INITIAL_DELAY_MS is 100`() {
        assertEquals(100L, StorageConstants.SAVE_RETRY_INITIAL_DELAY_MS)
    }

    @Test
    fun `SAVE_RETRY_MAX_DELAY_MS is 5000`() {
        assertEquals(5000L, StorageConstants.SAVE_RETRY_MAX_DELAY_MS)
    }

    @Test
    fun `LOAD_RETRY_MAX_RETRIES is 2`() {
        assertEquals(2, StorageConstants.LOAD_RETRY_MAX_RETRIES)
    }

    @Test
    fun `RETRY_JITTER_FACTOR is 0_2`() {
        assertEquals(0.2, StorageConstants.RETRY_JITTER_FACTOR, 0.001)
    }

    // ==================== Quota constants ====================

    @Test
    fun `TOTAL_STORAGE_BUDGET_MB is 200`() {
        assertEquals(200L, StorageConstants.TOTAL_STORAGE_BUDGET_MB)
    }

    @Test
    fun `SHARDING_THRESHOLD_MB is 80`() {
        assertEquals(80L, StorageConstants.SHARDING_THRESHOLD_MB)
    }

    @Test
    fun `SHARD_MAX_SIZE_MB is 40`() {
        assertEquals(40L, StorageConstants.SHARD_MAX_SIZE_MB)
    }

    // ==================== Archive constants ====================

    @Test
    fun `DEFAULT_MAX_BATTLE_LOGS is 500`() {
        assertEquals(500, StorageConstants.DEFAULT_MAX_BATTLE_LOGS)
    }

    @Test
    fun `DEFAULT_MAX_GAME_EVENTS is 1000`() {
        assertEquals(1000, StorageConstants.DEFAULT_MAX_GAME_EVENTS)
    }

    // ==================== EntitySize constants ====================

    @Test
    fun `EntitySize constants are all positive`() {
        assertTrue(StorageConstants.EntitySize.DISCIPLE > 0)
        assertTrue(StorageConstants.EntitySize.EQUIPMENT > 0)
        assertTrue(StorageConstants.EntitySize.MANUAL > 0)
        assertTrue(StorageConstants.EntitySize.PILL > 0)
        assertTrue(StorageConstants.EntitySize.MATERIAL > 0)
        assertTrue(StorageConstants.EntitySize.HERB > 0)
        assertTrue(StorageConstants.EntitySize.SEED > 0)
        assertTrue(StorageConstants.EntitySize.BATTLE_LOG > 0)
        assertTrue(StorageConstants.EntitySize.GAME_EVENT > 0)
        assertTrue(StorageConstants.EntitySize.TEAM > 0)
        assertTrue(StorageConstants.EntitySize.ALLIANCE > 0)
        assertTrue(StorageConstants.EntitySize.PRODUCTION_SLOT > 0)
    }

    @Test
    fun `EntitySize DISCIPLE is largest entity`() {
        assertTrue(StorageConstants.EntitySize.DISCIPLE >= StorageConstants.EntitySize.EQUIPMENT)
        assertTrue(StorageConstants.EntitySize.DISCIPLE >= StorageConstants.EntitySize.MANUAL)
        assertTrue(StorageConstants.EntitySize.DISCIPLE >= StorageConstants.EntitySize.BATTLE_LOG)
    }

    // ==================== TrimMemoryPressure constants ====================

    @Test
    fun `TrimMemoryPressure values are in ascending order`() {
        assertTrue(StorageConstants.TrimMemoryPressure.RUNNING_LOW < StorageConstants.TrimMemoryPressure.RUNNING_MODERATE)
        assertTrue(StorageConstants.TrimMemoryPressure.RUNNING_MODERATE < StorageConstants.TrimMemoryPressure.BACKGROUND)
        assertTrue(StorageConstants.TrimMemoryPressure.BACKGROUND < StorageConstants.TrimMemoryPressure.COMPLETE)
    }

    @Test
    fun `TrimMemoryPressure values are between 0 and 1`() {
        assertTrue(StorageConstants.TrimMemoryPressure.RUNNING_LOW in 0f..1f)
        assertTrue(StorageConstants.TrimMemoryPressure.RUNNING_MODERATE in 0f..1f)
        assertTrue(StorageConstants.TrimMemoryPressure.BACKGROUND in 0f..1f)
        assertTrue(StorageConstants.TrimMemoryPressure.COMPLETE in 0f..1f)
    }

    // ==================== File format constants ====================

    @Test
    fun `MAGIC_NEW_FORMAT is XSAV`() {
        assertEquals("XSAV", StorageConstants.MAGIC_NEW_FORMAT)
    }

    @Test
    fun `SHARD_MAGIC is SHRD`() {
        assertEquals("SHRD", StorageConstants.SHARD_MAGIC)
    }

    // ==================== estimateSaveSize ====================

    @Test
    fun `estimateSaveSize - empty SaveData returns base overhead plus serialization overhead`() {
        val data = SaveData(
            version = "1.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(),
            disciples = emptyList(),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
        val size = UnifiedStorageEngine.estimateSaveSize(data)
        val expectedMin = StorageConstants.ESTIMATE_BASE_OVERHEAD
        assertTrue("Empty save should be at least base overhead ($expectedMin), got $size", size >= expectedMin)
    }

    @Test
    fun `estimateSaveSize - increases with disciples`() {
        val base = SaveData(
            version = "1.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(),
            disciples = emptyList(),
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
        val withDisciples = base.copy(disciples = List(10) { Disciple() })
        val baseSize = UnifiedStorageEngine.estimateSaveSize(base)
        val withSize = UnifiedStorageEngine.estimateSaveSize(withDisciples)
        assertTrue("Save with 10 disciples ($withSize) should be larger than empty ($baseSize)", withSize > baseSize)
    }

    @Test
    fun `estimateSaveSize - scales linearly with entity count`() {
        val data10 = SaveData(
            version = "1.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(),
            disciples = List(10) { Disciple() },
            equipment = emptyList(),
            manuals = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
        val data100 = data10.copy(disciples = List(100) { Disciple() })
        val size10 = UnifiedStorageEngine.estimateSaveSize(data10)
        val size100 = UnifiedStorageEngine.estimateSaveSize(data100)
        assertTrue("100 disciples ($size100) should be larger than 10 ($size10)", size100 > size10)
    }

    @Test
    fun `estimateSaveSize - accounts for all entity types`() {
        val data = SaveData(
            version = "1.0",
            timestamp = System.currentTimeMillis(),
            gameData = GameData(),
            disciples = List(5) { Disciple() },
            equipment = List(5) { Equipment() },
            manuals = List(5) { Manual() },
            pills = List(5) { Pill() },
            materials = List(5) { Material() },
            herbs = List(5) { Herb() },
            seeds = List(5) { Seed() },
            teams = emptyList(),
            events = emptyList()
        )
        val size = UnifiedStorageEngine.estimateSaveSize(data)
        val baseOverhead = StorageConstants.ESTIMATE_BASE_OVERHEAD
        assertTrue("Multi-entity save should exceed base overhead ($baseOverhead), got $size", size > baseOverhead)
    }
}
