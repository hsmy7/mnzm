package com.xianxia.sect.data.unified

import org.junit.Assert.*
import org.junit.Test

class SaveResultTest {

    @Test
    fun `Success - isSuccess returns true`() {
        val result: SaveResult<String> = SaveResult.success("data")
        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
    }

    @Test
    fun `Failure - isFailure returns true`() {
        val result: SaveResult<String> = SaveResult.failure(SaveError.SAVE_FAILED, "error")
        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `Success - getOrNull returns data`() {
        val result: SaveResult<Int> = SaveResult.success(42)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `Failure - getOrNull returns null`() {
        val result: SaveResult<Int> = SaveResult.failure(SaveError.LOAD_FAILED, "error")
        assertNull(result.getOrNull())
    }

    @Test
    fun `Success - getOrThrow returns data`() {
        val result: SaveResult<String> = SaveResult.success("hello")
        assertEquals("hello", result.getOrThrow())
    }

    @Test(expected = SaveException::class)
    fun `Failure - getOrThrow throws SaveException`() {
        val result: SaveResult<String> = SaveResult.failure(SaveError.SAVE_FAILED, "save failed")
        result.getOrThrow()
    }

    @Test
    fun `Success - getOrDefault returns data`() {
        val result: SaveResult<Int> = SaveResult.success(42)
        assertEquals(42, result.getOrDefault(0))
    }

    @Test
    fun `Failure - getOrDefault returns default`() {
        val result: SaveResult<Int> = SaveResult.failure(SaveError.SAVE_FAILED, "error")
        assertEquals(0, result.getOrDefault(0))
    }

    @Test
    fun `map - transforms Success data`() {
        val result: SaveResult<Int> = SaveResult.success(10)
        val mapped = result.map { it * 2 }
        assertEquals(20, mapped.getOrNull())
    }

    @Test
    fun `map - preserves Failure`() {
        val result: SaveResult<Int> = SaveResult.failure(SaveError.IO_ERROR, "io error")
        val mapped = result.map { it * 2 }
        assertTrue(mapped.isFailure)
    }

    @Test
    fun `onSuccess - calls action for Success`() {
        var captured = ""
        val result: SaveResult<String> = SaveResult.success("data")
        result.onSuccess { captured = it }
        assertEquals("data", captured)
    }

    @Test
    fun `onSuccess - does not call action for Failure`() {
        var called = false
        val result: SaveResult<String> = SaveResult.failure(SaveError.SAVE_FAILED, "error")
        result.onSuccess { called = true }
        assertFalse(called)
    }

    @Test
    fun `onFailure - calls action for Failure`() {
        var capturedError: SaveError? = null
        var capturedMessage = ""
        val result: SaveResult<String> = SaveResult.failure(SaveError.SAVE_FAILED, "msg")
        result.onFailure { error, message -> capturedError = error; capturedMessage = message }
        assertEquals(SaveError.SAVE_FAILED, capturedError)
        assertEquals("msg", capturedMessage)
    }

    @Test
    fun `onFailure - does not call action for Success`() {
        var called = false
        val result: SaveResult<String> = SaveResult.success("data")
        result.onFailure { _, _ -> called = true }
        assertFalse(called)
    }

    @Test
    fun `Success - data is accessible`() {
        val result = SaveResult.success("test") as SaveResult.Success
        assertEquals("test", result.data)
    }

    @Test
    fun `Failure - error and message are accessible`() {
        val result = SaveResult.failure(SaveError.CHECKSUM_MISMATCH, "hash mismatch", RuntimeException("cause")) as SaveResult.Failure
        assertEquals(SaveError.CHECKSUM_MISMATCH, result.error)
        assertEquals("hash mismatch", result.message)
        assertNotNull(result.cause)
    }

    @Test
    fun `SaveError isRecoverable - returns true for recoverable errors`() {
        assertTrue(SaveError.SLOT_CORRUPTED.isRecoverable())
        assertTrue(SaveError.IO_ERROR.isRecoverable())
        assertTrue(SaveError.TIMEOUT.isRecoverable())
        assertTrue(SaveError.OUT_OF_MEMORY.isRecoverable())
        assertTrue(SaveError.WAL_ERROR.isRecoverable())
    }

    @Test
    fun `SaveError isRecoverable - returns false for non-recoverable errors`() {
        assertFalse(SaveError.INVALID_SLOT.isRecoverable())
        assertFalse(SaveError.SAVE_FAILED.isRecoverable())
        assertFalse(SaveError.LOAD_FAILED.isRecoverable())
        assertFalse(SaveError.ENCRYPTION_ERROR.isRecoverable())
        assertFalse(SaveError.DECRYPTION_ERROR.isRecoverable())
        assertFalse(SaveError.CHECKSUM_MISMATCH.isRecoverable())
    }

    @Test
    fun `SaveError requiresUserAction - returns true for encryption errors`() {
        assertTrue(SaveError.ENCRYPTION_ERROR.requiresUserAction())
        assertTrue(SaveError.DECRYPTION_ERROR.requiresUserAction())
        assertTrue(SaveError.KEY_DERIVATION_ERROR.requiresUserAction())
    }

    @Test
    fun `SaveError requiresUserAction - returns false for other errors`() {
        assertFalse(SaveError.IO_ERROR.requiresUserAction())
        assertFalse(SaveError.SAVE_FAILED.requiresUserAction())
        assertFalse(SaveError.INVALID_SLOT.requiresUserAction())
    }

    @Test
    fun `SaveException - preserves error type and message`() {
        val exception = SaveException(SaveError.SAVE_FAILED, "save error msg")
        assertEquals(SaveError.SAVE_FAILED, exception.error)
        assertTrue(exception.toString().contains("SAVE_FAILED"))
        assertTrue(exception.toString().contains("save error msg"))
    }

    @Test
    fun `SaveException - preserves cause`() {
        val cause = RuntimeException("root cause")
        val exception = SaveException(SaveError.IO_ERROR, "io error", cause)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun `SaveOperationStats - default values are correct`() {
        val stats = SaveOperationStats()
        assertEquals(0L, stats.bytesWritten)
        assertEquals(0L, stats.timeMs)
        assertEquals(1.0, stats.compressionRatio, 0.001)
        assertFalse(stats.wasEncrypted)
        assertFalse(stats.wasIncremental)
        assertEquals("", stats.checksum)
    }

    @Test
    fun `LoadOperationStats - default values are correct`() {
        val stats = LoadOperationStats()
        assertEquals(0L, stats.bytesRead)
        assertEquals(0L, stats.timeMs)
        assertFalse(stats.wasDecrypted)
        assertTrue(stats.checksumValid)
        assertEquals(0, stats.appliedDeltas)
    }

    @Test
    fun `SaveResult companion factory methods produce correct types`() {
        val success: SaveResult<Int> = SaveResult.success(42)
        assertTrue(success is SaveResult.Success)
        assertEquals(42, (success as SaveResult.Success).data)

        val failure: SaveResult<Int> = SaveResult.failure(SaveError.UNKNOWN, "unknown")
        assertTrue(failure is SaveResult.Failure)
    }

    @Test
    fun `SaveResult - can hold complex types`() {
        val stats = SaveOperationStats(bytesWritten = 1024, timeMs = 50, compressionRatio = 0.75)
        val result: SaveResult<SaveOperationStats> = SaveResult.success(stats)
        assertEquals(1024L, result.getOrNull()!!.bytesWritten)
        assertEquals(0.75, result.getOrNull()!!.compressionRatio, 0.001)
    }
}
