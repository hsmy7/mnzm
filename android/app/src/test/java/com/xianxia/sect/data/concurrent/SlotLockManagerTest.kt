package com.xianxia.sect.data.concurrent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SlotLockManagerTest {

    private lateinit var lockManager: SlotLockManager

    @Before
    fun setUp() {
        lockManager = SlotLockManager(maxSlots = 5)
    }

    // ==================== isValidSlot ====================

    @Test
    fun `isValidSlot - slot 0 is valid (AUTO_SAVE_SLOT)`() {
        assertTrue(lockManager.isValidSlot(SlotLockManager.AUTO_SAVE_SLOT))
    }

    @Test
    fun `isValidSlot - slot -1 is valid (EMERGENCY_SLOT)`() {
        assertTrue(lockManager.isValidSlot(SlotLockManager.EMERGENCY_SLOT))
    }

    @Test
    fun `isValidSlot - slots 1 to maxSlots are valid`() {
        for (slot in 1..5) {
            assertTrue("Slot $slot should be valid", lockManager.isValidSlot(slot))
        }
    }

    @Test
    fun `isValidSlot - slot exceeding maxSlots is invalid`() {
        assertFalse(lockManager.isValidSlot(6))
        assertFalse(lockManager.isValidSlot(100))
    }

    @Test
    fun `isValidSlot - slot -2 is invalid (not EMERGENCY_SLOT)`() {
        assertFalse(lockManager.isValidSlot(-2))
    }

    @Test
    fun `isValidSlot - slot -10 is invalid`() {
        assertFalse(lockManager.isValidSlot(-10))
    }

    // ==================== getMaxSlots ====================

    @Test
    fun `getMaxSlots returns configured value`() {
        assertEquals(5, lockManager.getMaxSlots())
    }

    @Test
    fun `getMaxSlots with custom value`() {
        val custom = SlotLockManager(maxSlots = 10)
        assertEquals(10, custom.getMaxSlots())
    }

    // ==================== withReadLockLight ====================

    @Test
    fun `withReadLockLight - executes block and returns result`() = runTest {
        val result = lockManager.withReadLockLight(1) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `withReadLockLight - executes block for slot 0`() = runTest {
        val result = lockManager.withReadLockLight(0) { "auto_save" }
        assertEquals("auto_save", result)
    }

    @Test
    fun `withReadLockLight - executes block for EMERGENCY_SLOT`() = runTest {
        val result = lockManager.withReadLockLight(-1) { "emergency" }
        assertEquals("emergency", result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `withReadLockLight - throws for invalid slot`() = runTest {
        lockManager.withReadLockLight(99) { "invalid" }
    }

    // ==================== withWriteLockLight ====================

    @Test
    fun `withWriteLockLight - executes block and returns result`() = runTest {
        val result = lockManager.withWriteLockLight(1) { 100 }
        assertEquals(100, result)
    }

    @Test
    fun `withWriteLockLight - executes block for all valid slots`() = runTest {
        for (slot in 1..5) {
            val result = lockManager.withWriteLockLight(slot) { slot * 10 }
            assertEquals(slot * 10, result)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `withWriteLockLight - throws for invalid slot`() = runTest {
        lockManager.withWriteLockLight(-5) { "invalid" }
    }

    // ==================== Lock Statistics ====================

    @Test
    fun `getLockStats - initial stats have zero acquisitions`() {
        val stats = lockManager.getLockStats(1)
        assertEquals(0L, stats.totalAcquisitions)
        assertEquals(0, stats.currentHoldCount)
    }

    @Test
    fun `getLockStats - increments acquisition count after lock`() = runTest {
        lockManager.withReadLockLight(1) { /* no-op */ }
        val stats = lockManager.getLockStats(1)
        assertEquals(1L, stats.totalAcquisitions)
    }

    @Test
    fun `getLockStats - increments acquisition count for each lock operation`() = runTest {
        lockManager.withReadLockLight(2) { /* no-op */ }
        lockManager.withWriteLockLight(2) { /* no-op */ }
        lockManager.withReadLockLight(2) { /* no-op */ }
        val stats = lockManager.getLockStats(2)
        assertEquals(3L, stats.totalAcquisitions)
    }

    @Test
    fun `getLockStats - currentHoldCount is 0 after lock released`() = runTest {
        lockManager.withWriteLockLight(3) { /* no-op */ }
        assertEquals(0, lockManager.getLockStats(3).currentHoldCount)
    }

    // ==================== isSlotLocked ====================

    @Test
    fun `isSlotLocked - returns false when no lock held`() {
        assertFalse(lockManager.isSlotLocked(1))
    }

    @Test
    fun `isSlotLocked - returns false after lock released`() = runTest {
        lockManager.withReadLockLight(1) { /* no-op */ }
        assertFalse(lockManager.isSlotLocked(1))
    }

    // ==================== clearAllLocks ====================

    @Test
    fun `clearAllLocks - resets all statistics`() = runTest {
        lockManager.withReadLockLight(1) { /* no-op */ }
        lockManager.withWriteLockLight(2) { /* no-op */ }
        lockManager.clearAllLocks()
        assertEquals(0L, lockManager.getLockStats(1).totalAcquisitions)
        assertEquals(0L, lockManager.getLockStats(2).totalAcquisitions)
    }

    // ==================== withMultipleReadLocksSuspend ====================

    @Test
    fun `withMultipleReadLocksSuspend - acquires multiple slots and executes block`() = runTest {
        val result = lockManager.withMultipleReadLocksSuspend(listOf(1, 2, 3)) {
            "multi_read"
        }
        assertEquals("multi_read", result)
    }

    @Test
    fun `withMultipleReadLocksSuspend - works with empty list`() = runTest {
        val result = lockManager.withMultipleReadLocksSuspend(emptyList()) {
            "empty"
        }
        assertEquals("empty", result)
    }

    @Test
    fun `withMultipleReadLocksSuspend - works with single slot`() = runTest {
        val result = lockManager.withMultipleReadLocksSuspend(listOf(1)) {
            "single"
        }
        assertEquals("single", result)
    }

    // ==================== withMultipleWriteLocksSuspend ====================

    @Test
    fun `withMultipleWriteLocksSuspend - acquires multiple slots and executes block`() = runTest {
        val result = lockManager.withMultipleWriteLocksSuspend(listOf(1, 2)) {
            "multi_write"
        }
        assertEquals("multi_write", result)
    }

    // ==================== Global Lock ====================

    @Test
    fun `withGlobalReadLockLight - executes block`() = runTest {
        val result = lockManager.withGlobalReadLockLight { "global_read" }
        assertEquals("global_read", result)
    }

    @Test
    fun `withGlobalWriteLockLight - executes block`() = runTest {
        val result = lockManager.withGlobalWriteLockLight { "global_write" }
        assertEquals("global_write", result)
    }

    // ==================== Companion constants ====================

    @Test
    fun `AUTO_SAVE_SLOT is 0`() {
        assertEquals(0, SlotLockManager.AUTO_SAVE_SLOT)
    }

    @Test
    fun `EMERGENCY_SLOT is -1`() {
        assertEquals(-1, SlotLockManager.EMERGENCY_SLOT)
    }

    // ==================== LockStats data class ====================

    @Test
    fun `LockStats - constructor sets fields correctly`() {
        val stats = LockStats(totalAcquisitions = 10L, currentHoldCount = 2, queuedRequests = 3)
        assertEquals(10L, stats.totalAcquisitions)
        assertEquals(2, stats.currentHoldCount)
        assertEquals(3, stats.queuedRequests)
    }

    // ==================== StripedLockManager ====================

    @Test
    fun `StripedLockManager - withReadLockSuspend executes block`() = runTest {
        val striped = StripedLockManager(stripes = 4)
        val result = striped.withReadLockSuspend("key1") { "striped_read" }
        assertEquals("striped_read", result)
    }

    @Test
    fun `StripedLockManager - withWriteLockSuspend executes block`() = runTest {
        val striped = StripedLockManager(stripes = 4)
        val result = striped.withWriteLockSuspend("key2") { "striped_write" }
        assertEquals("striped_write", result)
    }

    @Test
    fun `StripedLockManager - different keys can be locked concurrently`() = runTest {
        val striped = StripedLockManager(stripes = 16)
        val result1 = striped.withReadLockSuspend("abc") { 1 }
        val result2 = striped.withWriteLockSuspend("xyz") { 2 }
        assertEquals(1, result1)
        assertEquals(2, result2)
    }

    @Test
    fun `StripedLockManager - withAllStripesReadLockSuspend executes block`() = runTest {
        val striped = StripedLockManager(stripes = 4)
        val result = striped.withAllStripesReadLockSuspend { "all_stripes" }
        assertEquals("all_stripes", result)
    }

    @Test
    fun `StripedLockManager - withAllStripesWriteLockSuspend executes block`() = runTest {
        val striped = StripedLockManager(stripes = 4)
        val result = striped.withAllStripesWriteLockSuspend { "all_stripes_write" }
        assertEquals("all_stripes_write", result)
    }
}
