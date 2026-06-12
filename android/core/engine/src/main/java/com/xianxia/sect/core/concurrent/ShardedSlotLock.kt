package com.xianxia.sect.core.concurrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ShardedSlotLock(
    private val shardCount: Int = DEFAULT_SHARD_COUNT
) {
    companion object {
        const val DEFAULT_SHARD_COUNT = 16
        const val MAX_BATCH_LOCK_SIZE = 100
        private const val MAX_TRY_LOCK_RETRIES = 50
        private const val TRY_LOCK_BACKOFF_MS = 5L
    }

    private val shards = Array(shardCount) { Mutex() }
    private val lockHolders = ConcurrentHashMap<String, AtomicInteger>()

    private fun getShardIndex(key: String): Int {
        var hash = 0
        for (char in key) {
            hash = 31 * hash + char.code
        }
        return (hash and 0x7FFFFFFF) % shardCount
    }

    private fun createKey(buildingType: String, slotIndex: Int): String =
        "$buildingType:$slotIndex"

    suspend fun <T> withLock(
        buildingType: String,
        slotIndex: Int,
        block: suspend () -> T
    ): T {
        val key = createKey(buildingType, slotIndex)
        val shardIndex = getShardIndex(key)
        val holder = lockHolders.computeIfAbsent(key) { AtomicInteger(0) }
        
        holder.incrementAndGet()
        try {
            return shards[shardIndex].withLock {
                block()
            }
        } finally {
            if (holder.decrementAndGet() == 0) {
                lockHolders.remove(key)
            }
        }
    }

    suspend fun <T> withLock(
        buildingType: Enum<*>,
        slotIndex: Int,
        block: suspend () -> T
    ): T = withLock(buildingType.name, slotIndex, block)

    suspend fun <T> withBatchLock(
        keys: List<Pair<String, Int>>,
        block: suspend () -> T
    ): T {
        if (keys.isEmpty()) return block()
        
        val sortedKeys = keys
            .distinctBy { createKey(it.first, it.second) }
            .sortedBy { createKey(it.first, it.second) }
        
        val lockIndices = sortedKeys
            .map { getShardIndex(createKey(it.first, it.second)) }
            .distinct()
            .sorted()
        
        return acquireLocksWithTryLock(lockIndices, block)
    }
    
    private suspend fun <T> acquireLocksWithTryLock(
        lockIndices: List<Int>,
        block: suspend () -> T
    ): T {
        if (lockIndices.isEmpty()) return block()
        if (lockIndices.size == 1) {
            return shards[lockIndices[0]].withLock { block() }
        }
        
        var lastBackoff = TRY_LOCK_BACKOFF_MS.milliseconds
        
        repeat(MAX_TRY_LOCK_RETRIES) { attempt ->
            val acquired = mutableListOf<Int>()
            try {
                for (index in lockIndices) {
                    val locked = shards[index].tryLock()
                    if (!locked) {
                        break
                    }
                    acquired.add(index)
                }
                
                if (acquired.size == lockIndices.size) {
                    try {
                        return block()
                    } finally {
                        acquired.reversed().forEach { shards[it].unlock() }
                    }
                }
                
                acquired.reversed().forEach { shards[it].unlock() }
                
                if (attempt < MAX_TRY_LOCK_RETRIES - 1) {
                    lastBackoff = (lastBackoff * 1.5).coerceAtMost(100.milliseconds)
                    kotlinx.coroutines.delay(lastBackoff)
                }
            } catch (e: Throwable) {
                acquired.reversed().forEach { 
                    try { shards[it].unlock() } catch (_: Exception) {}
                }
                throw e
            }
        }
        
        return fallbackToSequential(lockIndices, block)
    }
    
    private suspend fun <T> fallbackToSequential(
        lockIndices: List<Int>,
        block: suspend () -> T
    ): T {
        val sortedUnique = lockIndices.distinct().sorted()
        return sortedUnique.first().let { firstIndex ->
            shards[firstIndex].withLock {
                val remaining = sortedUnique.drop(1)
                if (remaining.isEmpty()) {
                    block()
                } else {
                    fallbackToSequential(remaining, block)
                }
            }
        }
    }

    fun getLockStatistics(): ShardedLockStatistics {
        var totalWaiters = 0
        var activeShards = 0
        
        for (i in 0 until shardCount) {
            if (shards[i].isLocked) {
                activeShards++
            }
        }
        
        lockHolders.values.forEach { totalWaiters += it.get() }
        
        return ShardedLockStatistics(
            shardCount = shardCount,
            activeShards = activeShards,
            totalWaiters = totalWaiters,
            activeKeys = lockHolders.size
        )
    }
}

data class ShardedLockStatistics(
    val shardCount: Int,
    val activeShards: Int,
    val totalWaiters: Int,
    val activeKeys: Int
)
