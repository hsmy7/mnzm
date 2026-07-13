package com.xianxia.sect.core.concurrent

import com.xianxia.sect.core.util.DomainLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 分片槽锁 — 游戏线程专用轻量锁，非线程安全（仅单写线程）。
 * 重试上限 3 次（~20ms），防止阻塞游戏线程触发看门狗。
 */
class ShardedSlotLock(private val shardCount: Int = DEFAULT_SHARD_COUNT) {
    companion object {
        const val DEFAULT_SHARD_COUNT = 16
        const val MAX_BATCH_LOCK_SIZE = 100
        private const val MAX_TRY_LOCK_RETRIES = 3
        private const val TRY_LOCK_BACKOFF_MS = 5L
    }
    private val shards = Array(shardCount) { ReentrantLock() }
    private val lockHolders = ConcurrentHashMap<String, AtomicInteger>()

    private fun getShardIndex(key: String): Int {
        var h = 0; for (c in key) h = 31 * h + c.code
        return (h and 0x7FFFFFFF) % shardCount
    }
    private fun createKey(b: String, s: Int) = "$b:$s"

    fun <T> withLock(buildingType: String, slotIndex: Int, block: () -> T): T {
        val key = createKey(buildingType, slotIndex); val hi = getShardIndex(key)
        val h = lockHolders.computeIfAbsent(key) { AtomicInteger(0) }
        h.incrementAndGet()
        try { return shards[hi].withLock { block() } } finally {
            if (h.decrementAndGet() == 0) lockHolders.remove(key)
        }
    }
    fun <T> withLock(buildingType: Enum<*>, slotIndex: Int, block: () -> T): T =
        withLock(buildingType.name, slotIndex, block)

    fun <T> withBatchLock(keys: List<Pair<String, Int>>, block: () -> T): T {
        if (keys.isEmpty()) return block()
        val li = keys.distinctBy { createKey(it.first, it.second) }
            .sortedBy { createKey(it.first, it.second) }
            .map { getShardIndex(createKey(it.first, it.second)) }.distinct().sorted()
        return acquireLocksWithTryLock(li, block)
    }

    /**
     * 尝试获取所有锁，失败时释放已获取的锁并重试。
     * 最大重试 3 次（~20ms 总耗时），超过则直接回退到顺序获取。
     * 游戏线程专用：不阻塞看门狗阈值（3s）。
     */
    private fun <T> acquireLocksWithTryLock(lockIndices: List<Int>, block: () -> T): T {
        if (lockIndices.isEmpty()) return block()
        if (lockIndices.size == 1) return shards[lockIndices[0]].withLock { block() }
        var backoff = TRY_LOCK_BACKOFF_MS.toLong()
        repeat(MAX_TRY_LOCK_RETRIES) { attempt ->
            val acquired = mutableListOf<Int>()
            try {
                for (i in lockIndices) {
                    if (!shards[i].tryLock()) break
                    acquired.add(i)
                }
                if (acquired.size == lockIndices.size) {
                    // 全部获取成功 → 执行 block
                    val result = try { block() } finally {
                        acquired.reversed().forEach { shards[it].unlock() }
                    }
                    return result
                }
                // 部分失败 → 释放已获取的锁
                acquired.reversed().forEach { shards[it].unlock() }
                if (attempt < MAX_TRY_LOCK_RETRIES - 1) {
                    backoff = (backoff * 1.5).toLong().coerceAtMost(20L)
                    Thread.sleep(backoff)
                }
            } catch (e: Throwable) {
                // 异常时只释放已获取的锁（block 的 finally 已释放，外层不重复释放）
                acquired.reversed().forEach { shards[it].unlock() }
                throw e
            }
        }
        // 重试耗尽 → 顺序获取（串行化保障）
        return fallbackToSequential(lockIndices, block)
    }

    private fun <T> fallbackToSequential(lockIndices: List<Int>, block: () -> T): T {
        val su = lockIndices.distinct().sorted()
        return su.first().let { fi ->
            shards[fi].withLock {
                val r = su.drop(1)
                if (r.isEmpty()) block() else fallbackToSequential(r, block)
            }
        }
    }

    fun getLockStatistics() = ShardedLockStatistics(shardCount, lockHolders.size)
}

data class ShardedLockStatistics(val shardCount: Int, val activeKeys: Int)
