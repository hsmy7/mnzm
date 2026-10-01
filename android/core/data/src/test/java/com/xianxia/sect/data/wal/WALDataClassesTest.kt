package com.xianxia.sect.data.wal

import org.junit.Assert.*
import org.junit.Test

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
        val result = RecoveryResult(true, 0, 0, emptyList())
        assertTrue(result.success)
        assertEquals(0, result.recoveredCount)
        assertEquals(0, result.failedCount)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `RecoveryResult - success with recovered slots`() {
        val result = RecoveryResult(
            success = true,
            recoveredCount = 3,
            failedCount = 0,
            errors = emptyList()
        )
        assertTrue(result.success)
        assertEquals(3, result.recoveredCount)
    }

    @Test
    fun `RecoveryResult - partial failure`() {
        val result = RecoveryResult(
            success = false,
            recoveredCount = 2,
            failedCount = 1,
            errors = listOf("recovery corrupted")
        )
        assertFalse(result.success)
        assertEquals(1, result.failedCount)
        assertEquals(listOf("recovery corrupted"), result.errors)
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
            operation = WALEntryType.BEGIN,
            startTime = 1000L
        )
        assertEquals(1L, record.txnId)
                assertEquals(WALEntryType.BEGIN, record.operation)
        assertEquals(1000L, record.startTime)
        assertEquals(TransactionStatus.ACTIVE, record.status)
        assertEquals("", record.checksum)
        assertNull(record.gameEvent)
        assertEquals(0, record.currentGameYear)
    }

    @Test
    fun `TransactionRecord - status transitions via compareAndSetStatus`() {
        val record = TransactionRecord(
            txnId = 1L,
            operation = WALEntryType.BEGIN,
            startTime = 0L
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
            operation = WALEntryType.COMMIT,
            startTime = 0L
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
        val record1 = TransactionRecord(1L, WALEntryType.BEGIN, 0L)
        val record2 = TransactionRecord(2L, WALEntryType.BEGIN, 0L)

        record1.compareAndSetStatus(TransactionStatus.ACTIVE, TransactionStatus.COMMITTED)
        assertEquals(TransactionStatus.COMMITTED, record1.status)
        assertEquals(TransactionStatus.ACTIVE, record2.status)
    }
}
