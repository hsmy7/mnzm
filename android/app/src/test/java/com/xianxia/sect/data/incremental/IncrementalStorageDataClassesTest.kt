package com.xianxia.sect.data.incremental

import org.junit.Assert.*
import org.junit.Test

class IncrementalStorageDataClassesTest {

    // ==================== DeltaMetadata ====================

    @Test
    fun `DeltaMetadata - constructor sets all fields`() {
        val meta = DeltaMetadata(
            version = 1L,
            timestamp = 1000L,
            size = 2048L,
            operationCount = 5,
            checksum = "abc123"
        )
        assertEquals(1L, meta.version)
        assertEquals(1000L, meta.timestamp)
        assertEquals(2048L, meta.size)
        assertEquals(5, meta.operationCount)
        assertEquals("abc123", meta.checksum)
    }

    @Test
    fun `DeltaMetadata - default-like values`() {
        val meta = DeltaMetadata(0L, 0L, 0L, 0, "")
        assertEquals(0L, meta.version)
        assertEquals("", meta.checksum)
    }

    // ==================== IncrementalStorageConfig ====================

    @Test
    fun `MAX_SLOTS is 5`() {
        assertEquals(5, IncrementalStorageConfig.MAX_SLOTS)
    }

    @Test
    fun `MAX_DELTA_CHAIN_LENGTH is 15`() {
        assertEquals(15, IncrementalStorageConfig.MAX_DELTA_CHAIN_LENGTH)
    }

    @Test
    fun `SOFT_CHAIN_LIMIT is 8`() {
        assertEquals(8, IncrementalStorageConfig.SOFT_CHAIN_LIMIT)
    }

    @Test
    fun `COMPACTION_THRESHOLD is 5`() {
        assertEquals(5, IncrementalStorageConfig.COMPACTION_THRESHOLD)
    }

    @Test
    fun `AUTO_COMPACT_INTERVAL_MS is 3 minutes`() {
        assertEquals(180_000L, IncrementalStorageConfig.AUTO_COMPACT_INTERVAL_MS)
    }

    @Test
    fun `MAX_DELTA_SIZE_MB is 10`() {
        assertEquals(10, IncrementalStorageConfig.MAX_DELTA_SIZE_MB)
    }

    @Test
    fun `DELTA_DIR is deltas`() {
        assertEquals("deltas", IncrementalStorageConfig.DELTA_DIR)
    }

    @Test
    fun `SNAPSHOT_DIR is snapshots`() {
        assertEquals("snapshots", IncrementalStorageConfig.SNAPSHOT_DIR)
    }

    @Test
    fun `CHAIN_HEALTH_CHECK_INTERVAL_MS is 60 seconds`() {
        assertEquals(60_000L, IncrementalStorageConfig.CHAIN_HEALTH_CHECK_INTERVAL_MS)
    }

    @Test
    fun `URGENT_COMPACT_THRESHOLD is 12`() {
        assertEquals(12, IncrementalStorageConfig.URGENT_COMPACT_THRESHOLD)
    }

    // ==================== SnapshotInfo ====================

    @Test
    fun `SnapshotInfo - constructor sets all fields`() {
        val info = SnapshotInfo(
            version = 10L,
            timestamp = 2000L,
            size = 4096L,
            checksum = "def456",
            isFull = true
        )
        assertEquals(10L, info.version)
        assertEquals(2000L, info.timestamp)
        assertEquals(4096L, info.size)
        assertEquals("def456", info.checksum)
        assertTrue(info.isFull)
    }

    @Test
    fun `SnapshotInfo - incremental snapshot`() {
        val info = SnapshotInfo(1L, 0L, 0L, "", false)
        assertFalse(info.isFull)
    }

    // ==================== IncrementalSaveResult ====================

    @Test
    fun `IncrementalSaveResult - success`() {
        val result = IncrementalSaveResult(
            success = true,
            savedBytes = 1024L,
            elapsedMs = 50L,
            isIncremental = true,
            deltaCount = 3
        )
        assertTrue(result.success)
        assertEquals(1024L, result.savedBytes)
        assertEquals(50L, result.elapsedMs)
        assertTrue(result.isIncremental)
        assertEquals(3, result.deltaCount)
        assertNull(result.error)
    }

    @Test
    fun `IncrementalSaveResult - failure`() {
        val result = IncrementalSaveResult(
            success = false,
            savedBytes = 0L,
            elapsedMs = 10L,
            isIncremental = false,
            deltaCount = 0,
            error = "write failed"
        )
        assertFalse(result.success)
        assertEquals("write failed", result.error)
    }

    // ==================== IncrementalLoadResult ====================

    @Test
    fun `IncrementalLoadResult - success`() {
        val result = IncrementalLoadResult(
            data = null,
            elapsedMs = 30L,
            appliedDeltas = 2,
            wasFromSnapshot = true
        )
        assertEquals(30L, result.elapsedMs)
        assertEquals(2, result.appliedDeltas)
        assertTrue(result.wasFromSnapshot)
        assertNull(result.error)
    }

    @Test
    fun `IncrementalLoadResult - failure`() {
        val result = IncrementalLoadResult(
            data = null,
            elapsedMs = 0L,
            appliedDeltas = 0,
            wasFromSnapshot = false,
            error = "corrupted"
        )
        assertNull(result.data)
        assertEquals("corrupted", result.error)
    }

    // ==================== StorageConfig (from IncrementalStorageCoordinator) ====================

    @Test
    fun `StorageConfig - default values`() {
        val config = StorageConfig()
        assertTrue(config.enableIncrementalSave)
        assertEquals(60_000L, config.autoSaveIntervalMs)
        assertEquals(50, config.maxDeltaChainLength)
        assertEquals(10, config.compactionThreshold)
        assertEquals(20, config.forceFullSaveInterval)
    }

    @Test
    fun `StorageConfig - custom values`() {
        val config = StorageConfig(
            enableIncrementalSave = false,
            autoSaveIntervalMs = 120_000L,
            maxDeltaChainLength = 100,
            compactionThreshold = 20,
            forceFullSaveInterval = 50
        )
        assertFalse(config.enableIncrementalSave)
        assertEquals(120_000L, config.autoSaveIntervalMs)
        assertEquals(100, config.maxDeltaChainLength)
        assertEquals(20, config.compactionThreshold)
        assertEquals(50, config.forceFullSaveInterval)
    }

    // ==================== StorageSaveResult ====================

    @Test
    fun `StorageSaveResult - success`() {
        val result = StorageSaveResult(
            success = true,
            savedBytes = 2048L,
            elapsedMs = 100L,
            isIncremental = false,
            deltaCount = 0
        )
        assertTrue(result.success)
        assertEquals(2048L, result.savedBytes)
        assertNull(result.error)
    }

    @Test
    fun `StorageSaveResult - failure with error`() {
        val result = StorageSaveResult(
            success = false,
            error = "disk full"
        )
        assertFalse(result.success)
        assertEquals("disk full", result.error)
    }

    // ==================== StorageLoadResult ====================

    @Test
    fun `StorageLoadResult - success with data`() {
        val result = StorageLoadResult(
            data = null,
            elapsedMs = 50L,
            appliedDeltas = 0,
            wasFromSnapshot = false
        )
        assertNull(result.data)
        assertEquals(50L, result.elapsedMs)
        assertNull(result.error)
    }

    // ==================== StorageStats ====================

    @Test
    fun `StorageStats - default values`() {
        val stats = StorageStats()
        assertEquals(0L, stats.totalSaves)
        assertEquals(0L, stats.totalLoads)
        assertEquals(0L, stats.incrementalSaves)
        assertEquals(0L, stats.fullSaves)
        assertEquals(0L, stats.totalBytesSaved)
        assertEquals(0L, stats.totalBytesLoaded)
        assertEquals(0L, stats.avgSaveTimeMs)
        assertEquals(0L, stats.avgLoadTimeMs)
        assertEquals(0, stats.currentDeltaChainLength)
        assertEquals(0, stats.pendingChanges)
    }

    @Test
    fun `StorageStats - custom values`() {
        val stats = StorageStats(
            totalSaves = 100L,
            totalLoads = 200L,
            incrementalSaves = 80L,
            fullSaves = 20L,
            totalBytesSaved = 1024 * 1024L,
            totalBytesLoaded = 2048 * 1024L,
            avgSaveTimeMs = 150L,
            avgLoadTimeMs = 80L,
            currentDeltaChainLength = 5,
            pendingChanges = 3
        )
        assertEquals(100L, stats.totalSaves)
        assertEquals(200L, stats.totalLoads)
        assertEquals(80L, stats.incrementalSaves)
        assertEquals(20L, stats.fullSaves)
        assertEquals(5, stats.currentDeltaChainLength)
        assertEquals(3, stats.pendingChanges)
    }
}
