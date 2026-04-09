package com.xianxia.sect.data.wal

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class WALDataClassesTest {

    // ==================== TransactionStatus ====================

    @Test
    fun `TransactionStatus has 5 values`() {
        assertEquals(5, TransactionStatus.values().size)
        assertTrue(TransactionStatus.values().contains(TransactionStatus.ACTIVE))
        assertTrue(TransactionStatus.values().contains(TransactionStatus.COMMITTING))
        assertTrue(TransactionStatus.values().contains(TransactionStatus.ABORTING))
        assertTrue(TransactionStatus.values().contains(TransactionStatus.COMMITTED))
        assertTrue(TransactionStatus.values().contains(TransactionStatus.ABORTED))
    }

    // ==================== WALEntryType ====================

    @Test
    fun `WALEntryType has 5 values`() {
        assertEquals(5, WALEntryType.values().size)
        assertTrue(WALEntryType.values().contains(WALEntryType.BEGIN))
        assertTrue(WALEntryType.values().contains(WALEntryType.COMMIT))
        assertTrue(WALEntryType.values().contains(WALEntryType.ABORT))
        assertTrue(WALEntryType.values().contains(WALEntryType.SNAPSHOT))
        assertTrue(WALEntryType.values().contains(WALEntryType.DATA))
    }

    // ==================== RecoveryResult ====================

    @Test
    fun `RecoveryResult - success with empty sets`() {
        val result = RecoveryResult(true, emptySet(), emptySet(), emptyList())
        assertTrue(result.success)
        assertTrue(result.recoveredSlots.isEmpty())
        assertTrue(result.failedSlots.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `RecoveryResult - success with recovered slots`() {
        val result = RecoveryResult(
            success = true,
            recoveredSlots = setOf(1, 2, 3),
            failedSlots = emptySet(),
            errors = emptyList()
        )
        assertTrue(result.success)
        assertEquals(setOf(1, 2, 3), result.recoveredSlots)
    }

    @Test
    fun `RecoveryResult - partial failure`() {
        val result = RecoveryResult(
            success = false,
            recoveredSlots = setOf(1, 2),
            failedSlots = setOf(3),
            errors = listOf("Slot 3 corrupted")
        )
        assertFalse(result.success)
        assertEquals(setOf(3), result.failedSlots)
        assertEquals(listOf("Slot 3 corrupted"), result.errors)
    }

    // ==================== EnhancedWALStats ====================

    @Test
    fun `EnhancedWALStats - default values`() {
        val stats = EnhancedWALStats()
        assertEquals(0, stats.activeTransactions)
        assertEquals(0L, stats.totalTransactions)
        assertEquals(0L, stats.committedCount)
        assertEquals(0L, stats.abortedCount)
        assertEquals(0L, stats.walFileSize)
        assertEquals(0L, stats.lastCheckpointTime)
    }

    @Test
    fun `EnhancedWALStats - custom values`() {
        val stats = EnhancedWALStats(
            activeTransactions = 3,
            totalTransactions = 100L,
            committedCount = 95L,
            abortedCount = 5L,
            walFileSize = 1024 * 1024L,
            lastCheckpointTime = System.currentTimeMillis()
        )
        assertEquals(3, stats.activeTransactions)
        assertEquals(100L, stats.totalTransactions)
        assertEquals(95L, stats.committedCount)
        assertEquals(5L, stats.abortedCount)
    }

    // ==================== TransactionRecord ====================

    @Test
    fun `TransactionRecord - constructor sets fields`() {
        val record = TransactionRecord(
            txnId = 1L,
            slot = 3,
            operation = WALEntryType.BEGIN,
            startTime = 1000L,
            snapshotFile = null
        )
        assertEquals(1L, record.txnId)
        assertEquals(3, record.slot)
        assertEquals(WALEntryType.BEGIN, record.operation)
        assertEquals(1000L, record.startTime)
        assertNull(record.snapshotFile)
        assertEquals(TransactionStatus.ACTIVE, record.status)
        assertEquals("", record.checksum)
        assertNull(record.gameEvent)
        assertEquals(0, record.currentGameYear)
    }

    @Test
    fun `TransactionRecord - status transitions via compareAndSetStatus`() {
        val record = TransactionRecord(
            txnId = 1L,
            slot = 1,
            operation = WALEntryType.BEGIN,
            startTime = 0L,
            snapshotFile = null
        )
        assertEquals(TransactionStatus.ACTIVE, record.status)

        val success = record.compareAndSetStatus(TransactionStatus.ACTIVE, TransactionStatus.COMMITTING)
        assertTrue(success)
        assertEquals(TransactionStatus.COMMITTING, record.status)

        val fail = record.compareAndSetStatus(TransactionStatus.ACTIVE, TransactionStatus.ABORTING)
        assertFalse(fail)
        assertEquals(TransactionStatus.COMMITTING, record.status)
    }

    @Test
    fun `TransactionRecord - mutable checksum and gameEvent`() {
        val record = TransactionRecord(
            txnId = 5L,
            slot = 2,
            operation = WALEntryType.COMMIT,
            startTime = 0L,
            snapshotFile = null
        )
        record.checksum = "abc123"
        record.gameEvent = "CRITICAL_SAVE"
        record.currentGameYear = 10
        assertEquals("abc123", record.checksum)
        assertEquals("CRITICAL_SAVE", record.gameEvent)
        assertEquals(10, record.currentGameYear)
    }

    @Test
    fun `TransactionRecord - statusRef is independent per record`() {
        val record1 = TransactionRecord(1L, 1, WALEntryType.BEGIN, 0L, null)
        val record2 = TransactionRecord(2L, 2, WALEntryType.BEGIN, 0L, null)

        record1.compareAndSetStatus(TransactionStatus.ACTIVE, TransactionStatus.COMMITTED)
        assertEquals(TransactionStatus.COMMITTED, record1.status)
        assertEquals(TransactionStatus.ACTIVE, record2.status)
    }
}
