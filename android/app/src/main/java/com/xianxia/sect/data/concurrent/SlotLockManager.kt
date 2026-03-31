package com.xianxia.sect.data.concurrent

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class LockStats(
    val totalAcquisitions: Long,
    val readLockAcquisitions: Long,
    val writeLockAcquisitions: Long,
    val currentReadLockHolders: Int,
    val currentWriteLockHolder: Boolean,
    val queuedThreads: Int
)

class SlotLockManager(
    private val maxSlots: Int = 5
) {
    companion object {
        private const val TAG = "SlotLockManager"
        const val AUTO_SAVE_SLOT = 0
        const val EMERGENCY_SLOT = -1
    }
    
    private val slotLocks: Array<ReentrantReadWriteLock> = Array(maxSlots + 2) { 
        ReentrantReadWriteLock(true) 
    }
    
    private val globalLock = ReentrantReadWriteLock(true)
    
    private val acquisitionCount = ConcurrentHashMap<Int, Long>()
    
    private val slotIndexMap = mutableMapOf<Int, Int>().apply {
        put(AUTO_SAVE_SLOT, 0)
        put(EMERGENCY_SLOT, 1)
        for (i in 1..maxSlots) {
            put(i, i + 1)
        }
    }
    
    private fun getSlotLock(slot: Int): ReentrantReadWriteLock {
        val index = slotIndexMap[slot] 
            ?: throw IllegalArgumentException("Invalid slot: $slot")
        return slotLocks[index]
    }
    
    fun <T> withReadLock(slot: Int, block: () -> T): T {
        val lock = getSlotLock(slot)
        acquisitionCount.compute(slot) { _, count -> (count ?: 0) + 1 }
        return lock.read { block() }
    }
    
    fun <T> withWriteLock(slot: Int, block: () -> T): T {
        val lock = getSlotLock(slot)
        acquisitionCount.compute(slot) { _, count -> (count ?: 0) + 1 }
        return lock.write { block() }
    }
    
    suspend fun <T> withReadLockSuspend(slot: Int, block: suspend () -> T): T {
        val lock = getSlotLock(slot)
        acquisitionCount.compute(slot) { _, count -> (count ?: 0) + 1 }
        return lock.read { kotlinx.coroutines.runBlocking { block() } }
    }
    
    suspend fun <T> withWriteLockSuspend(slot: Int, block: suspend () -> T): T {
        val lock = getSlotLock(slot)
        acquisitionCount.compute(slot) { _, count -> (count ?: 0) + 1 }
        return lock.write { kotlinx.coroutines.runBlocking { block() } }
    }
    
    fun <T> withGlobalReadLock(block: () -> T): T {
        return globalLock.read { block() }
    }
    
    fun <T> withGlobalWriteLock(block: () -> T): T {
        return globalLock.write { block() }
    }
    
    fun <T> withMultipleReadLocks(slots: Collection<Int>, block: () -> T): T {
        val sortedSlots = slots.sorted()
        val locks = sortedSlots.map { getSlotLock(it) }
        
        locks.forEach { it.readLock().lock() }
        try {
            return block()
        } finally {
            locks.reversed().forEach { it.readLock().unlock() }
        }
    }
    
    fun <T> withMultipleWriteLocks(slots: Collection<Int>, block: () -> T): T {
        val sortedSlots = slots.sorted()
        val locks = sortedSlots.map { getSlotLock(it) }
        
        locks.forEach { it.writeLock().lock() }
        try {
            return block()
        } finally {
            locks.reversed().forEach { it.writeLock().unlock() }
        }
    }
    
    fun <T> withAllSlotsReadLock(block: () -> T): T {
        return withMultipleReadLocks((1..maxSlots).toList() + listOf(AUTO_SAVE_SLOT), block)
    }
    
    fun <T> withAllSlotsWriteLock(block: () -> T): T {
        return withMultipleWriteLocks((1..maxSlots).toList() + listOf(AUTO_SAVE_SLOT), block)
    }
    
    fun isSlotLocked(slot: Int): Boolean {
        val lock = getSlotLock(slot)
        return lock.isWriteLocked || lock.readLockCount > 0
    }
    
    fun isSlotWriteLocked(slot: Int): Boolean {
        return getSlotLock(slot).isWriteLocked
    }
    
    fun getQueuedThreadCount(slot: Int): Int {
        val lock = getSlotLock(slot)
        return lock.queueLength
    }
    
    fun getLockStats(slot: Int): LockStats {
        val lock = getSlotLock(slot)
        return LockStats(
            totalAcquisitions = acquisitionCount[slot] ?: 0,
            readLockAcquisitions = lock.readLockCount.toLong(),
            writeLockAcquisitions = if (lock.isWriteLocked) 1L else 0L,
            currentReadLockHolders = lock.readLockCount,
            currentWriteLockHolder = lock.isWriteLocked,
            queuedThreads = lock.queueLength
        )
    }
    
    fun tryReadLock(slot: Int): Boolean {
        return getSlotLock(slot).readLock().tryLock()
    }
    
    fun tryWriteLock(slot: Int): Boolean {
        return getSlotLock(slot).writeLock().tryLock()
    }
    
    fun unlockRead(slot: Int) {
        try {
            getSlotLock(slot).readLock().unlock()
        } catch (e: IllegalMonitorStateException) {
            Log.w(TAG, "Attempted to unlock read lock without holding it for slot $slot")
        }
    }
    
    fun unlockWrite(slot: Int) {
        try {
            getSlotLock(slot).writeLock().unlock()
        } catch (e: IllegalMonitorStateException) {
            Log.w(TAG, "Attempted to unlock write lock without holding it for slot $slot")
        }
    }
    
    fun clearAllLocks() {
        slotLocks.forEach { lock ->
            while (lock.isWriteLocked) {
                try {
                    lock.writeLock().unlock()
                } catch (e: Exception) {
                    break
                }
            }
            while (lock.readLockCount > 0) {
                try {
                    lock.readLock().unlock()
                } catch (e: Exception) {
                    break
                }
            }
        }
        acquisitionCount.clear()
    }
    
    fun isValidSlot(slot: Int): Boolean {
        return slot in slotIndexMap.keys
    }
    
    fun getMaxSlots(): Int = maxSlots
}

class StripedLockManager(
    private val stripes: Int = 16
) {
    private val locks = Array(stripes) { ReentrantReadWriteLock(true) }
    
    private fun getStripe(key: Any): Int {
        val hash = key.hashCode()
        return ((hash and 0x7FFFFFFF) % stripes)
    }
    
    fun <T> withReadLock(key: Any, block: () -> T): T {
        return locks[getStripe(key)].read { block() }
    }
    
    fun <T> withWriteLock(key: Any, block: () -> T): T {
        return locks[getStripe(key)].write { block() }
    }
    
    fun <T> withAllStripesReadLock(block: () -> T): T {
        locks.forEach { it.readLock().lock() }
        try {
            return block()
        } finally {
            locks.reversed().forEach { it.readLock().unlock() }
        }
    }
    
    fun <T> withAllStripesWriteLock(block: () -> T): T {
        locks.forEach { it.writeLock().lock() }
        try {
            return block()
        } finally {
            locks.reversed().forEach { it.writeLock().unlock() }
        }
    }
}
