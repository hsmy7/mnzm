package com.xianxia.sect.data.result

import org.junit.Assert.*
import org.junit.Test

class StorageResultTest {

    @Test
    fun `Success - isSuccess returns true`() {
        val result: StorageResult<String> = StorageResult.success("data")
        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
    }

    @Test
    fun `Failure - isFailure returns true`() {
        val result: StorageResult<String> = StorageResult.failure(StorageError.SAVE_FAILED, "error")
        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `Success - getOrNull returns data`() {
        val result: StorageResult<Int> = StorageResult.success(42)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `Failure - getOrNull returns null`() {
        val result: StorageResult<Int> = StorageResult.failure(StorageError.LOAD_FAILED, "error")
        assertNull(result.getOrNull())
    }

    @Test
    fun `Success - getOrThrow returns data`() {
        val result: StorageResult<String> = StorageResult.success("hello")
        assertEquals("hello", result.getOrThrow())
    }

    @Test(expected = StorageException::class)
    fun `Failure - getOrThrow throws StorageException`() {
        val result: StorageResult<String> = StorageResult.failure(StorageError.SAVE_FAILED, "save failed")
        result.getOrThrow()
    }

    @Test
    fun `Success - getOrDefault returns data`() {
        val result: StorageResult<Int> = StorageResult.success(42)
        assertEquals(42, result.getOrDefault(0))
    }

    @Test
    fun `Failure - getOrDefault returns default`() {
        val result: StorageResult<Int> = StorageResult.failure(StorageError.SAVE_FAILED, "error")
        assertEquals(0, result.getOrDefault(0))
    }

    @Test
    fun `onSuccess - calls action for Success`() {
        var captured = ""
        val result: StorageResult<String> = StorageResult.success("data")
        result.onSuccess { captured = it }
        assertEquals("data", captured)
    }

    @Test
    fun `onFailure - calls action for Failure`() {
        var capturedError: StorageError? = null
        var capturedMessage = ""
        val result: StorageResult<String> = StorageResult.failure(StorageError.IO_ERROR, "msg")
        result.onFailure { error, message -> capturedError = error; capturedMessage = message }
        assertEquals(StorageError.IO_ERROR, capturedError)
        assertEquals("msg", capturedMessage)
    }

    @Test
    fun `StorageError isRecoverable - returns true for recoverable errors`() {
        assertTrue(StorageError.SLOT_CORRUPTED.isRecoverable())
        assertTrue(StorageError.IO_ERROR.isRecoverable())
        assertTrue(StorageError.TIMEOUT.isRecoverable())
        assertTrue(StorageError.OUT_OF_MEMORY.isRecoverable())
        assertTrue(StorageError.WAL_ERROR.isRecoverable())
    }

    @Test
    fun `StorageError isRecoverable - returns false for non-recoverable errors`() {
        assertFalse(StorageError.INVALID_SLOT.isRecoverable())
        assertFalse(StorageError.SAVE_FAILED.isRecoverable())
        assertFalse(StorageError.ENCRYPTION_ERROR.isRecoverable())
        assertFalse(StorageError.CHECKSUM_MISMATCH.isRecoverable())
    }

    @Test
    fun `StorageError requiresUserAction - returns true for encryption errors`() {
        assertTrue(StorageError.ENCRYPTION_ERROR.requiresUserAction())
        assertTrue(StorageError.DECRYPTION_ERROR.requiresUserAction())
        assertTrue(StorageError.KEY_DERIVATION_ERROR.requiresUserAction())
    }

    @Test
    fun `StorageError getSeverity - returns correct severity levels`() {
        assertEquals(ErrorSeverity.INFO, StorageError.INVALID_SLOT.getSeverity())
        assertEquals(ErrorSeverity.INFO, StorageError.SLOT_EMPTY.getSeverity())
        assertEquals(ErrorSeverity.WARNING, StorageError.SAVE_FAILED.getSeverity())
        assertEquals(ErrorSeverity.WARNING, StorageError.LOAD_FAILED.getSeverity())
        assertEquals(ErrorSeverity.ERROR, StorageError.SLOT_CORRUPTED.getSeverity())
        assertEquals(ErrorSeverity.ERROR, StorageError.ENCRYPTION_ERROR.getSeverity())
        assertEquals(ErrorSeverity.CRITICAL, StorageError.IO_ERROR.getSeverity())
        assertEquals(ErrorSeverity.CRITICAL, StorageError.OUT_OF_MEMORY.getSeverity())
    }

    @Test
    fun `StorageException - preserves error type and message`() {
        val exception = StorageException(StorageError.IO_ERROR, "io error msg")
        assertEquals(StorageError.IO_ERROR, exception.error)
        assertTrue(exception.toString().contains("IO_ERROR"))
    }

    @Test
    fun `StorageOperationStats - default values are correct`() {
        val stats = StorageOperationStats()
        assertEquals(0L, stats.bytesProcessed)
        assertEquals(0L, stats.timeMs)
        assertEquals(0, stats.itemsProcessed)
        assertFalse(stats.wasEncrypted)
        assertFalse(stats.wasCompressed)
        assertEquals("", stats.checksum)
    }

    @Test
    fun `BatchOperationResult - allSuccess reflects failure count`() {
        val allSuccess = BatchOperationResult(totalCount = 5, successCount = 5, failureCount = 0, errors = emptyMap())
        val partialSuccess = BatchOperationResult(totalCount = 5, successCount = 3, failureCount = 2, errors = mapOf(1 to StorageError.IO_ERROR, 3 to StorageError.TIMEOUT))
        val allFailed = BatchOperationResult(totalCount = 3, successCount = 0, failureCount = 3, errors = mapOf(0 to StorageError.SAVE_FAILED, 1 to StorageError.SAVE_FAILED, 2 to StorageError.SAVE_FAILED))

        assertTrue(allSuccess.allSuccess)
        assertFalse(partialSuccess.allSuccess)
        assertFalse(allFailed.allSuccess)
    }

    @Test
    fun `BatchOperationResult - successRate is calculated correctly`() {
        val result = BatchOperationResult(totalCount = 10, successCount = 7, failureCount = 3, errors = emptyMap())
        assertEquals(0.7f, result.successRate, 0.001f)
    }

    @Test
    fun `BatchOperationResult - successRate is 0 when totalCount is 0`() {
        val result = BatchOperationResult(totalCount = 0, successCount = 0, failureCount = 0, errors = emptyMap())
        assertEquals(0f, result.successRate, 0.001f)
    }

    @Test
    fun `ErrorSeverity - has correct ordinal order`() {
        assertTrue(ErrorSeverity.INFO.ordinal < ErrorSeverity.WARNING.ordinal)
        assertTrue(ErrorSeverity.WARNING.ordinal < ErrorSeverity.ERROR.ordinal)
        assertTrue(ErrorSeverity.ERROR.ordinal < ErrorSeverity.CRITICAL.ordinal)
    }
}
