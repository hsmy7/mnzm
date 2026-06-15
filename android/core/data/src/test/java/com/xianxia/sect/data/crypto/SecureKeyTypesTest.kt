package com.xianxia.sect.data.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SecureKeyTypesTest {

    @Before
    fun setUp() {
        KeyManagerMetrics.reset()
    }

    // ==================== KeyManagerMetrics ====================

    @Test
    fun `KeyManagerMetrics - initial values are zero`() {
        assertEquals(0, KeyManagerMetrics.permissionFixAttempts.get())
        assertEquals(0, KeyManagerMetrics.permissionFixSuccesses.get())
        assertEquals(0, KeyManagerMetrics.backupRecoveries.get())
        assertEquals(0, KeyManagerMetrics.keyRegenerations.get())
        assertEquals(0, KeyManagerMetrics.diskFullErrors.get())
        assertEquals(0, KeyManagerMetrics.fileSystemErrors.get())
    }

    @Test
    fun `KeyManagerMetrics - increment and reset`() {
        KeyManagerMetrics.permissionFixAttempts.incrementAndGet()
        KeyManagerMetrics.permissionFixAttempts.incrementAndGet()
        KeyManagerMetrics.backupRecoveries.incrementAndGet()
        assertEquals(2, KeyManagerMetrics.permissionFixAttempts.get())
        assertEquals(1, KeyManagerMetrics.backupRecoveries.get())

        KeyManagerMetrics.reset()
        assertEquals(0, KeyManagerMetrics.permissionFixAttempts.get())
        assertEquals(0, KeyManagerMetrics.backupRecoveries.get())
    }

    @Test
    fun `KeyManagerMetrics - thread safety`() {
        val threadCount = 10
        val incrementsPerThread = 100
        val threads = (1..threadCount).map {
            Thread {
                repeat(incrementsPerThread) {
                    KeyManagerMetrics.keyRegenerations.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(threadCount * incrementsPerThread.toLong(), KeyManagerMetrics.keyRegenerations.get())
    }

    // ==================== KeyRecoveryDecision ====================

    @Test
    fun `KeyRecoveryDecision - all values present`() {
        val values = KeyRecoveryDecision.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(KeyRecoveryDecision.IMPORT_TOKEN))
        assertTrue(values.contains(KeyRecoveryDecision.GENERATE_NEW_KEY))
        assertTrue(values.contains(KeyRecoveryDecision.RETRY))
        assertTrue(values.contains(KeyRecoveryDecision.CANCEL))
    }

    // ==================== KeyError ====================

    @Test
    fun `KeyError - all values present`() {
        val values = KeyError.entries
        assertEquals(7, values.size)
        assertTrue(values.contains(KeyError.FILE_NOT_FOUND))
        assertTrue(values.contains(KeyError.PERMISSION_DENIED))
        assertTrue(values.contains(KeyError.CORRUPTED_DATA))
        assertTrue(values.contains(KeyError.BACKUP_RECOVERY_FAILED))
        assertTrue(values.contains(KeyError.DISK_FULL))
        assertTrue(values.contains(KeyError.FILE_SYSTEM_ERROR))
        assertTrue(values.contains(KeyError.UNKNOWN_ERROR))
    }

    // ==================== KeyRecoveryResult ====================

    @Test
    fun `KeyRecoveryResult - Success contains key and message`() {
        val key = byteArrayOf(1, 2, 3)
        val result = KeyRecoveryResult.Success(key, "ok")
        assertEquals("ok", result.message)
        assertArrayEquals(key, result.key)
    }

    @Test
    fun `KeyRecoveryResult - Failure contains error`() {
        val result = KeyRecoveryResult.Failure("something went wrong")
        assertEquals("something went wrong", result.error)
    }

    @Test
    fun `KeyRecoveryResult - Success is Success, not Failure`() {
        val result = KeyRecoveryResult.Success(ByteArray(0), "")
        assertTrue(result is KeyRecoveryResult.Success)
        assertFalse(result is KeyRecoveryResult.Failure)
    }

    // ==================== KeyReadResult ====================

    @Test
    fun `KeyReadResult - Success contains data`() {
        val data = byteArrayOf(10, 20, 30)
        val result = KeyReadResult.Success(data)
        assertArrayEquals(data, result.data)
    }

    @Test
    fun `KeyReadResult - FileNotFound is singleton`() {
        assertSame(KeyReadResult.FileNotFound, KeyReadResult.FileNotFound)
    }

    @Test
    fun `KeyReadResult - Error contains exception`() {
        val ex = RuntimeException("test")
        val result = KeyReadResult.Error(ex)
        assertSame(ex, result.exception)
    }

    // ==================== Exception classes ====================

    @Test
    fun `KeyIntegrityException - message and cause`() {
        val cause = RuntimeException("root")
        val ex = KeyIntegrityException("integrity check failed", cause)
        assertEquals("integrity check failed", ex.message)
        assertSame(cause, ex.cause)
    }

    @Test
    fun `KeyPermissionException - message without cause`() {
        val ex = KeyPermissionException("no permission")
        assertEquals("no permission", ex.message)
        assertNull(ex.cause)
    }

    @Test
    fun `KeyFileSystemException - is Exception`() {
        val ex = KeyFileSystemException("io error")
        assertTrue(ex is Exception)
    }
}
