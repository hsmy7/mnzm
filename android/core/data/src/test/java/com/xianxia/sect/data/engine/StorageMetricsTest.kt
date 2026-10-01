package com.xianxia.sect.data.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * StorageMetrics 单测：计数器记录与快照读数、快照字段齐全、
 * 账本漂移计数（SS3 对 SS9 登记消费的落地）。
 */
class StorageMetricsTest {

    @Test
    fun `计数器记录与快照读数一致`() {
        val metrics = StorageMetrics()

        metrics.recordSave()
        metrics.recordSave()
        metrics.recordLoad()
        metrics.recordCacheHit()
        metrics.recordCacheMiss()
        metrics.recordBackupSuccess()
        metrics.recordBackupFailure()
        metrics.recordBackupRestore()
        metrics.recordBackupSkippedOversize()

        val snapshot = metrics.snapshot()
        assertEquals(2L, snapshot.saveCount)
        assertEquals(1L, snapshot.loadCount)
        assertEquals(1L, snapshot.cacheHitCount)
        assertEquals(1L, snapshot.cacheMissCount)
        assertEquals(1L, snapshot.backupSuccessCount)
        assertEquals(1L, snapshot.backupFailureCount)
        assertEquals(1L, snapshot.backupRestoreCount)
        assertEquals(1L, snapshot.backupSkippedOversizeCount)
        assertEquals(0L, snapshot.incrementalSaveCount)
        assertEquals(0L, snapshot.fullSaveCount)
        assertEquals(0L, snapshot.dirtyFallbackCount)
        assertNull(snapshot.lastFullSaveReason)
    }

    @Test
    fun `增量全量路径分布与脏集回退计数（SS5）`() {
        val metrics = StorageMetrics()

        metrics.recordIncrementalSave()
        metrics.recordIncrementalSave()
        metrics.recordFullSave()
        metrics.recordDirtyFallback()
        metrics.setLastFullSaveReason(FullSaveReason.DIRTY_OUT_OF_SNAPSHOT.name)

        val snapshot = metrics.snapshot()
        assertEquals(2L, snapshot.incrementalSaveCount)
        assertEquals(1L, snapshot.fullSaveCount)
        assertEquals(1L, snapshot.dirtyFallbackCount)
        assertEquals("DIRTY_OUT_OF_SNAPSHOT", snapshot.lastFullSaveReason)
    }

    @Test
    fun `快照覆盖全部计数器且与 getter 一致`() {
        val metrics = StorageMetrics()
        metrics.recordSave()
        metrics.recordBackupFailure()

        val snapshot = metrics.snapshot()

        assertEquals(1L, snapshot.saveCount)
        assertEquals(0L, snapshot.loadCount)
        assertEquals(0L, snapshot.cacheHitCount)
        assertEquals(0L, snapshot.cacheMissCount)
        assertEquals(0L, snapshot.backupSuccessCount)
        assertEquals(1L, snapshot.backupFailureCount)
        assertEquals(0L, snapshot.backupRestoreCount)
        assertEquals(0L, snapshot.backupSkippedOversizeCount)
        assertEquals(0L, snapshot.jadeLedgerDriftCount)
        assertNull(snapshot.lastJadeLedgerDriftOp)
    }

    @Test
    fun `账本漂移计数累计且保留最近操作名`() {
        val telemetry: com.xianxia.sect.core.util.PersistenceTelemetryPort = StorageMetrics()

        telemetry.recordJadeLedgerDrift("consume")
        telemetry.recordJadeLedgerDrift("grant")

        val snapshot = (telemetry as StorageMetrics).snapshot()
        assertEquals(2L, snapshot.jadeLedgerDriftCount)
        assertEquals("grant", snapshot.lastJadeLedgerDriftOp)
    }
}
