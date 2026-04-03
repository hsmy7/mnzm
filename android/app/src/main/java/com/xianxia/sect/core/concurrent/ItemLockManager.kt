package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.engine.system.inventory.ItemType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Singleton

@Singleton
class ItemLockManager {
    private val itemLocks = ConcurrentHashMap<ItemType, ReentrantReadWriteLock>()
    private val itemMutexes = ConcurrentHashMap<ItemType, Mutex>()
    private val globalMutex = Mutex()
    private val globalRWLock = ReentrantReadWriteLock()
    
    init {
        ItemType.values().forEach { type ->
            itemLocks[type] = ReentrantReadWriteLock()
            itemMutexes[type] = Mutex()
        }
    }
    
    fun <T> withReadLock(itemType: ItemType, block: () -> T): T {
        val lock = itemLocks[itemType] ?: return block()
        return lock.readLock().withLock(block)
    }
    
    fun <T> withWriteLock(itemType: ItemType, block: () -> T): T {
        val lock = itemLocks[itemType] ?: return block()
        return lock.writeLock().withLock(block)
    }
    
    suspend fun <T> withMutex(itemType: ItemType, block: suspend () -> T): T {
        val mutex = itemMutexes[itemType] ?: return block()
        return mutex.withLock { block() }
    }
    
    fun <T> withMultiReadLock(itemTypes: Collection<ItemType>, block: () -> T): T {
        val sortedTypes = itemTypes.sortedBy { it.ordinal }
        val acquiredLocks = mutableListOf<ReentrantReadWriteLock.ReadLock>()
        try {
            sortedTypes.forEach { type ->
                val lock = itemLocks[type]?.readLock()
                lock?.lock()
                if (lock != null) acquiredLocks.add(lock)
            }
            return block()
        } finally {
            acquiredLocks.reversed().forEach { it.unlock() }
        }
    }
    
    fun <T> withMultiWriteLock(itemTypes: Collection<ItemType>, block: () -> T): T {
        val sortedTypes = itemTypes.sortedBy { it.ordinal }
        val acquiredLocks = mutableListOf<ReentrantReadWriteLock.WriteLock>()
        try {
            sortedTypes.forEach { type ->
                val lock = itemLocks[type]?.writeLock()
                lock?.lock()
                if (lock != null) acquiredLocks.add(lock)
            }
            return block()
        } finally {
            acquiredLocks.reversed().forEach { it.unlock() }
        }
    }
    
    suspend fun <T> withGlobalLock(block: suspend () -> T): T {
        return globalMutex.withLock { block() }
    }
    
    fun <T> withGlobalReadLock(block: () -> T): T {
        return globalRWLock.readLock().withLock(block)
    }
    
    fun <T> withGlobalWriteLock(block: () -> T): T {
        return globalRWLock.writeLock().withLock(block)
    }
    
    fun getLockStats(): Map<ItemType, LockStats> {
        return itemLocks.mapValues { (_, lock) ->
            LockStats(
                readLockCount = lock.readHoldCount,
                writeLockCount = lock.writeHoldCount,
                isWriteLocked = lock.isWriteLocked,
                queueLength = lock.queueLength
            )
        }
    }
    
    fun getMutexStats(): Map<ItemType, MutexStats> {
        return itemMutexes.mapValues { (_, mutex) ->
            MutexStats(
                isLocked = mutex.isLocked
            )
        }
    }
    
    data class LockStats(
        val readLockCount: Int,
        val writeLockCount: Int,
        val isWriteLocked: Boolean,
        val queueLength: Int
    )
    
    data class MutexStats(
        val isLocked: Boolean
    )
}

private inline fun <T> java.util.concurrent.locks.Lock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
