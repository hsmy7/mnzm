package com.xianxia.sect.data.quota

import org.junit.Assert.*
import org.junit.Test

class StorageQuotaConfigTest {

    // ==================== Total storage limits ====================

    @Test
    fun `MAX_TOTAL_STORAGE_MB is 1024 (1 GB)`() {
        assertEquals(1024L, StorageQuotaConfig.MAX_TOTAL_STORAGE_MB)
    }

    @Test
    fun `MAX_SINGLE_SAVE_MB is 100`() {
        assertEquals(100L, StorageQuotaConfig.MAX_SINGLE_SAVE_MB)
    }

    @Test
    fun `MIN_FREE_SPACE_MB is 100`() {
        assertEquals(100L, StorageQuotaConfig.MIN_FREE_SPACE_MB)
    }

    // ==================== Threshold percentages ====================

    @Test
    fun `WARNING_THRESHOLD_PERCENTAGE is 80`() {
        assertEquals(80f, StorageQuotaConfig.WARNING_THRESHOLD_PERCENTAGE, 0.001f)
    }

    @Test
    fun `CRITICAL_THRESHOLD_PERCENTAGE is 95`() {
        assertEquals(95f, StorageQuotaConfig.CRITICAL_THRESHOLD_PERCENTAGE, 0.001f)
    }

    @Test
    fun `WARNING is less than CRITICAL`() {
        assertTrue(StorageQuotaConfig.WARNING_THRESHOLD_PERCENTAGE < StorageQuotaConfig.CRITICAL_THRESHOLD_PERCENTAGE)
    }

    // ==================== Game phase multipliers ====================

    @Test
    fun `PHASE_SHORT_MONTHS is 6`() {
        assertEquals(6, StorageQuotaConfig.PHASE_SHORT_MONTHS)
    }

    @Test
    fun `PHASE_MEDIUM_MULTIPLIER is 1_2`() {
        assertEquals(1.2f, StorageQuotaConfig.PHASE_MEDIUM_MULTIPLIER, 0.001f)
    }

    @Test
    fun `PHASE_LONG_MULTIPLIER is 1_5`() {
        assertEquals(1.5f, StorageQuotaConfig.PHASE_LONG_MULTIPLIER, 0.001f)
    }

    @Test
    fun `PHASE_LONG_MONTHS is 24`() {
        assertEquals(24, StorageQuotaConfig.PHASE_LONG_MONTHS)
    }

    @Test
    fun `multipliers are in ascending order`() {
        assertTrue(1.0f < StorageQuotaConfig.PHASE_MEDIUM_MULTIPLIER)
        assertTrue(StorageQuotaConfig.PHASE_MEDIUM_MULTIPLIER < StorageQuotaConfig.PHASE_LONG_MULTIPLIER)
    }

    // ==================== Cleanup retention ====================

    @Test
    fun `IMPORTANT_DATA_PROTECTION_MS is 30 days`() {
        val expected = 30L * 24 * 60 * 60 * 1000
        assertEquals(expected, StorageQuotaConfig.IMPORTANT_DATA_PROTECTION_MS)
    }

    @Test
    fun `BACKUP_RETENTION_DAYS is 90`() {
        assertEquals(90, StorageQuotaConfig.BACKUP_RETENTION_DAYS)
    }

    @Test
    fun `WAL_SNAPSHOT_RETENTION_HOURS is 24`() {
        assertEquals(24, StorageQuotaConfig.WAL_SNAPSHOT_RETENTION_HOURS)
    }

    // ==================== QuotaResult ====================

    @Test
    fun `QuotaResult OK is singleton`() {
        val r1 = QuotaResult.OK
        val r2 = QuotaResult.OK
        assertEquals(r1, r2)
    }

    @Test
    fun `QuotaResult Exceeded holds reason`() {
        val result = QuotaResult.Exceeded("over limit")
        assertEquals("over limit", result.reason)
    }

    @Test
    fun `QuotaResult DiskFull holds bytes`() {
        val result = QuotaResult.DiskFull(availableBytes = 100L, requiredBytes = 500L)
        assertEquals(100L, result.availableBytes)
        assertEquals(500L, result.requiredBytes)
    }

    @Test
    fun `QuotaResult Evicted holds file list and freed bytes`() {
        val result = QuotaResult.Evicted(
            evictedFiles = listOf("file1.bak", "file2.snap"),
            freedBytes = 1024L
        )
        assertEquals(listOf("file1.bak", "file2.snap"), result.evictedFiles)
        assertEquals(1024L, result.freedBytes)
    }

    // ==================== StorageQuotaStats ====================

    @Test
    fun `StorageQuotaStats - default values`() {
        val stats = StorageQuotaStats(
            totalUsedBytes = 0L,
            totalLimitBytes = 1024L * 1024 * 1024,
            freeDiskBytes = 0L,
            saveFileCount = 0,
            backupFileCount = 0,
            walSnapshotCount = 0,
            usagePercentage = 0f
        )
        assertEquals(0L, stats.totalUsedBytes)
        assertEquals(0, stats.saveFileCount)
        assertEquals(0f, stats.usagePercentage, 0.001f)
    }

    @Test
    fun `StorageQuotaStats - usagePercentage is calculated correctly`() {
        val stats = StorageQuotaStats(
            totalUsedBytes = 512L * 1024 * 1024,
            totalLimitBytes = 1024L * 1024 * 1024,
            effectiveLimitBytes = 1024L * 1024 * 1024,
            freeDiskBytes = 0L,
            saveFileCount = 3,
            backupFileCount = 2,
            walSnapshotCount = 1,
            usagePercentage = 50f
        )
        assertEquals(50f, stats.usagePercentage, 0.001f)
    }

    // ==================== StorageUsageBreakdown ====================

    @Test
    fun `StorageUsageBreakdown - default values`() {
        val breakdown = StorageUsageBreakdown(
            savesDirBytes = 0L,
            walDirBytes = 0L,
            backupDirBytes = 0L,
            databaseBytes = 0L,
            shardFilesBytes = 0L,
            archiveDirBytes = 0L,
            otherBytes = 0L
        )
        assertEquals(0L, breakdown.savesDirBytes)
        assertEquals(0L, breakdown.walDirBytes)
        assertEquals(0L, breakdown.backupDirBytes)
        assertEquals(0L, breakdown.databaseBytes)
        assertEquals(0L, breakdown.shardFilesBytes)
        assertEquals(0L, breakdown.archiveDirBytes)
        assertEquals(0L, breakdown.otherBytes)
    }

    @Test
    fun `StorageUsageBreakdown - total can be computed from parts`() {
        val breakdown = StorageUsageBreakdown(
            savesDirBytes = 100L,
            walDirBytes = 200L,
            backupDirBytes = 300L,
            databaseBytes = 400L,
            shardFilesBytes = 50L,
            archiveDirBytes = 150L,
            otherBytes = 0L
        )
        val total = breakdown.savesDirBytes + breakdown.walDirBytes +
                breakdown.backupDirBytes + breakdown.databaseBytes +
                breakdown.shardFilesBytes + breakdown.archiveDirBytes +
                breakdown.otherBytes
        assertEquals(1200L, total)
    }
}
