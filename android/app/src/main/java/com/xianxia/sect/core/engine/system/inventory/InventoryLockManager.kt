package com.xianxia.sect.core.engine.system.inventory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Singleton
class InventoryLockManager @Inject constructor() {
    
    private val globalLock = ReentrantReadWriteLock()
    private val typeLocks = ItemType.values().associateWith { ReentrantReadWriteLock() }
    
    fun <T> withReadLock(action: () -> T): T {
        return globalLock.readLock().withLock { action() }
    }
    
    fun <T> withWriteLock(action: () -> T): T {
        return globalLock.writeLock().withLock { action() }
    }
    
    fun <T> withTypeReadLock(itemType: ItemType, action: () -> T): T {
        return typeLocks[itemType]?.readLock()?.withLock { action() } ?: action()
    }
    
    fun <T> withTypeWriteLock(itemType: ItemType, action: () -> T): T {
        return typeLocks[itemType]?.writeLock()?.withLock { action() } ?: action()
    }
    
    fun <T> withMultiTypeReadLock(vararg types: ItemType, action: () -> T): T {
        val sortedTypes = types.sortedBy { it.ordinal }.distinct()
        return withMultiTypeReadLockRecursive(sortedTypes.toList(), 0, action)
    }
    
    fun <T> withMultiTypeWriteLock(vararg types: ItemType, action: () -> T): T {
        val sortedTypes = types.sortedBy { it.ordinal }.distinct()
        return withMultiTypeWriteLockRecursive(sortedTypes.toList(), 0, action)
    }
    
    private fun <T> withMultiTypeReadLockRecursive(
        types: List<ItemType>,
        index: Int,
        action: () -> T
    ): T {
        if (index >= types.size) return action()
        return typeLocks[types[index]]?.readLock()?.withLock {
            withMultiTypeReadLockRecursive(types, index + 1, action)
        } ?: withMultiTypeReadLockRecursive(types, index + 1, action)
    }
    
    private fun <T> withMultiTypeWriteLockRecursive(
        types: List<ItemType>,
        index: Int,
        action: () -> T
    ): T {
        if (index >= types.size) return action()
        return typeLocks[types[index]]?.writeLock()?.withLock {
            withMultiTypeWriteLockRecursive(types, index + 1, action)
        } ?: withMultiTypeWriteLockRecursive(types, index + 1, action)
    }
    
    private val globalMutex = Mutex()
    private val typeMutexes = ItemType.values().associateWith { Mutex() }
    
    suspend fun <T> withReadLockSuspend(action: suspend () -> T): T {
        return globalMutex.withLock { action() }
    }
    
    suspend fun <T> withWriteLockSuspend(action: suspend () -> T): T {
        return globalMutex.withLock { action() }
    }
    
    suspend fun <T> withTypeLockSuspend(itemType: ItemType, action: suspend () -> T): T {
        return typeMutexes[itemType]?.withLock { action() } ?: action()
    }
}

@Singleton
class InventoryAsyncLockManager @Inject constructor() {
    
    private val globalMutex = Mutex()
    private val typeMutexes = ItemType.values().associateWith { Mutex() }
    
    suspend fun <T> withGlobalLock(action: suspend () -> T): T {
        return globalMutex.withLock { action() }
    }
    
    suspend fun <T> withTypeLock(itemType: ItemType, action: suspend () -> T): T {
        return typeMutexes[itemType]?.withLock { action() } ?: action()
    }
    
    suspend fun <T> withMultiTypeLock(vararg types: ItemType, action: suspend () -> T): T {
        val sortedTypes = types.sortedBy { it.ordinal }.distinct()
        return withTypeLockRecursive(sortedTypes.toList(), 0, action)
    }
    
    private suspend fun <T> withTypeLockRecursive(
        types: List<ItemType>,
        index: Int,
        action: suspend () -> T
    ): T {
        if (index >= types.size) return action()
        return typeMutexes[types[index]]?.withLock {
            withTypeLockRecursive(types, index + 1, action)
        } ?: throw IllegalStateException("No mutex registered for item type: ${types[index]}")
    }
    
    fun tryLock(itemType: ItemType): Boolean {
        return typeMutexes[itemType]?.tryLock() ?: false
    }
    
    fun unlock(itemType: ItemType) {
        typeMutexes[itemType]?.unlock()
    }
    
    fun isLocked(itemType: ItemType): Boolean {
        return typeMutexes[itemType]?.isLocked ?: false
    }
}
