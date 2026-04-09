package com.xianxia.sect.data.concurrent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class LockStats(
    val totalAcquisitions: Long,
    val currentHoldCount: Int,
    val queuedRequests: Int
)

/**
 * 槽位锁管理器（v3 重构版）
 *
 * 【P1#4 修复】已删除所有 @Deprecated 的 runBlocking 方法，消除 ANR 风险。
 *
 * 设计原则：
 * 1. 所有公共 API 均为 suspend 函数，必须在协程上下文中调用
 * 2. 不再提供同步（阻塞）版本的锁操作
 * 3. 调用方必须使用协程（如 viewModelScope、lifecycleScope 等）
 *
 * 迁移指南：
 * - 旧代码：lockManager.withReadLock(slot) { ... }
 * - 新代码：suspend fun myFunc() = lockManager.withReadLockSuspend(slot) { ... }
 */
class SlotLockManager(
    private val maxSlots: Int = 6
) {
    companion object {
        private const val TAG = "SlotLockManager"
        const val AUTO_SAVE_SLOT = 0
        const val EMERGENCY_SLOT = -1
    }

    private val mutexes: Array<Mutex> =
        Array(maxSlots + 2) { Mutex() }
    
    private val globalMutex = Mutex()

    /** 内部使用的 IO 调度器（仅用于旧版 withContext 切换） */
    internal val lockDispatcher: ExecutorCoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(maxSlots) { r ->  // 优化：从 maxSlots+2 缩减为 maxSlots
            Thread(r, "SlotLock-Worker-${System.nanoTime()}").also { it.isDaemon = true }
        }.asCoroutineDispatcher()
    }

    private val acquisitionCount = ConcurrentHashMap<Int, Long>()
    private val holdCounts = ConcurrentHashMap<Int, Int>()

    private val slotIndexMap: Map<Int, Int> = mapOf(
        AUTO_SAVE_SLOT to 0,
        EMERGENCY_SLOT to 1,
        *(1..maxSlots).map { it to it + 1 }.toTypedArray()
    )

    private fun getMutex(slot: Int): Mutex {
        val index = slotIndexMap[slot]
            ?: throw IllegalArgumentException("Invalid slot: $slot")
        return mutexes[index]
    }

    // ==================== 协程版本的锁操作（唯一合法的公共API）====================

    /**
     * 轻量级读锁（无额外 Dispatcher 切换）
     *
     * 与 [withReadLockSuspend] 的区别：
     * - 不在锁内执行 `withContext(Dispatchers.IO)` 切换
     * - 调用方若已在 IO 调度器上，可避免不必要的线程跳转开销
     * - 推荐在新代码中使用此方法
     *
     * @param slot 槽位编号
     * @param block 在持有读锁期间执行的挂起代码块
     * @return 代码块的返回值
     */
    suspend fun <T> withReadLockLight(slot: Int, block: suspend () -> T): T {
        val mutex = getMutex(slot)
        acquisitionCount.compute(slot) { _, count -> (count ?: 0) + 1 }
        holdCounts.compute(slot) { _, count -> (count ?: 0) + 1 }
        try {
            return mutex.withLock { block() }
        } finally {
            holdCounts.compute(slot) { _, count -> ((count ?: 1) - 1).coerceAtLeast(0) }
        }
    }

    /**
     * 轻量级写锁（无额外 Dispatcher 切换）
     *
     * 与 [withWriteLockSuspend] 的区别：
     * - 不在锁内执行 `withContext(Dispatchers.IO)` 切换
     * - 调用方若已在 IO 调度器上，可避免不必要的线程跳转开销
     * - 推荐在新代码中使用此方法
     *
     * @param slot 槽位编号
     * @param block 在持有写锁期间执行的挂起代码块
     * @return 代码块的返回值
     */
    suspend fun <T> withWriteLockLight(slot: Int, block: suspend () -> T): T {
        val mutex = getMutex(slot)
        acquisitionCount.compute(slot) { _, count -> (count ?: 0) + 1 }
        holdCounts.compute(slot) { _, count -> (count ?: 0) + 1 }
        try {
            return mutex.withLock { block() }
        } finally {
            holdCounts.compute(slot) { _, count -> ((count ?: 1) - 1).coerceAtLeast(0) }
        }
    }

    /**
     * 读锁（协程版本）- 已废弃
     *
     * **问题**：锁内存在双重线程跳转（mutex.withLock + withContext(IO)），
     * 当调用方已在 IO 调度器时造成不必要的开销。
     *
     * 请改用 [withReadLockLight] 以避免额外的 Dispatcher 切换。
     */
    @Deprecated(
        message = "Use withReadLockLight() to avoid unnecessary dispatcher switch inside lock",
        replaceWith = ReplaceWith("withReadLockLight(slot, block)", imports = emptyArray()),
        level = DeprecationLevel.WARNING
    )
    suspend fun <T> withReadLockSuspend(slot: Int, block: suspend () -> T): T {
        val mutex = getMutex(slot)
        acquisitionCount.compute(slot) { _, count -> (count ?: 0) + 1 }
        holdCounts.compute(slot) { _, count -> (count ?: 0) + 1 }
        try {
            return mutex.withLock {
                withContext(Dispatchers.IO) { block() }
            }
        } finally {
            holdCounts.compute(slot) { _, count -> ((count ?: 1) - 1).coerceAtLeast(0) }
        }
    }

    /**
     * 写锁（协程版本）- 已废弃
     *
     * **问题**：锁内存在双重线程跳转（mutex.withLock + withContext(IO)），
     * 当调用方已在 IO 调度器时造成不必要的开销。
     *
     * 请改用 [withWriteLockLight] 以避免额外的 Dispatcher 切换。
     */
    @Deprecated(
        message = "Use withWriteLockLight() to avoid unnecessary dispatcher switch inside lock",
        replaceWith = ReplaceWith("withWriteLockLight(slot, block)", imports = emptyArray()),
        level = DeprecationLevel.WARNING
    )
    suspend fun <T> withWriteLockSuspend(slot: Int, block: suspend () -> T): T {
        val mutex = getMutex(slot)
        acquisitionCount.compute(slot) { _, count -> (count ?: 0) + 1 }
        holdCounts.compute(slot) { _, count -> (count ?: 0) + 1 }
        try {
            return mutex.withLock {
                withContext(Dispatchers.IO) { block() }
            }
        } finally {
            holdCounts.compute(slot) { _, count -> ((count ?: 1) - 1).coerceAtLeast(0) }
        }
    }

    /**
     * 全局轻量级读锁（无额外 Dispatcher 切换）
     */
    suspend fun <T> withGlobalReadLockLight(block: suspend () -> T): T {
        return globalMutex.withLock { block() }
    }

    /**
     * 全局轻量级写锁（无额外 Dispatcher 切换）
     */
    suspend fun <T> withGlobalWriteLockLight(block: suspend () -> T): T {
        return globalMutex.withLock { block() }
    }

    /**
     * 全局读锁（协程版本）- 已废弃
     *
     * 请改用 [withGlobalReadLockLight]
     */
    @Deprecated(
        message = "Use withGlobalReadLockLight() to avoid unnecessary dispatcher switch",
        replaceWith = ReplaceWith("withGlobalReadLockLight(block)")
    )
    suspend fun <T> withGlobalReadLockSuspend(block: suspend () -> T): T {
        return globalMutex.withLock {
            withContext(Dispatchers.IO) { block() }
        }
    }

    /**
     * 全局写锁（协程版本）- 已废弃
     *
     * 请改用 [withGlobalWriteLockLight]
     */
    @Deprecated(
        message = "Use withGlobalWriteLockLight() to avoid unnecessary dispatcher switch",
        replaceWith = ReplaceWith("withGlobalWriteLockLight(block)")
    )
    suspend fun <T> withGlobalWriteLockSuspend(block: suspend () -> T): T {
        return globalMutex.withLock {
            withContext(Dispatchers.IO) { block() }
        }
    }

    /**
     * 多槽位读锁（协程版本）
     *
     * 按序获取所有指定槽位的读锁，在全部持有时执行 [block]，
     * 执行完毕后按逆序释放。避免死锁。
     */
    suspend fun <T> withMultipleReadLocksSuspend(slots: Collection<Int>, block: suspend () -> T): T {
        val sortedSlots = slots.sorted()
        return acquireAllLocked(sortedSlots, block)
    }

    /**
     * 多槽位写锁（协程版本）
     *
     * 按序获取所有指定槽位的写锁，在全部持有时执行 [block]，
     * 执行完毕后按逆序释放。避免死锁。
     */
    suspend fun <T> withMultipleWriteLocksSuspend(slots: Collection<Int>, block: suspend () -> T): T {
        val sortedSlots = slots.sorted()
        return acquireAllLocked(sortedSlots, block)
    }

    /**
     * 按序获取所有写锁（嵌套 lock/unlock），在全部锁均持有状态下执行 block，
     * 执行完毕后按嵌套层次自动逆序释放。
     */
    private suspend fun <T> acquireAllLocked(sortedSlots: List<Int>, block: suspend () -> T): T {
        if (sortedSlots.isEmpty()) return block()
        val head = sortedSlots.first()
        val tail = sortedSlots.drop(1)
        val mutex = getMutex(head)
        return mutex.withLock {
            if (tail.isEmpty()) block()
            else acquireAllLocked(tail, block)
        }
    }

    // ==================== 状态查询方法 ====================

    fun isSlotLocked(slot: Int): Boolean {
        return (holdCounts[slot] ?: 0) > 0
    }

    fun isSlotWriteLocked(slot: Int): Boolean {
        return isSlotLocked(slot)
    }

    fun isValidSlot(slot: Int): Boolean {
        return slot in slotIndexMap.keys
    }

    fun getMaxSlots(): Int = maxSlots

    fun getLockStats(slot: Int): LockStats {
        return LockStats(
            totalAcquisitions = acquisitionCount[slot] ?: 0,
            currentHoldCount = holdCounts[slot] ?: 0,
            queuedRequests = 0
        )
    }

    fun clearAllLocks() {
        holdCounts.clear()
        acquisitionCount.clear()
    }

    fun shutdown() {
        lockDispatcher.close()
    }
}

/**
 * 条纹锁管理器（v3 重构版）
 *
 * 【P1#4 修复】同样删除了所有 runBlocking 方法。
 */
class StripedLockManager(
    private val stripes: Int = 16
) {
    private val mutexes: Array<Mutex> =
        Array(stripes) { Mutex() }

    internal val lockDispatcher: ExecutorCoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(stripes) { r ->
            Thread(r, "StripedLock-Worker-${System.nanoTime()}").also { it.isDaemon = true }
        }.asCoroutineDispatcher()
    }

    private fun getStripe(key: Any): Int {
        val hash = key.hashCode()
        return ((hash and 0x7FFFFFFF) % stripes)
    }

    suspend fun <T> withReadLockSuspend(key: Any, block: suspend () -> T): T {
        return withContext(lockDispatcher) {
            mutexes[getStripe(key)].withLock {
                withContext(Dispatchers.IO) { block() }
            }
        }
    }

    suspend fun <T> withWriteLockSuspend(key: Any, block: suspend () -> T): T {
        return withContext(lockDispatcher) {
            mutexes[getStripe(key)].withLock {
                withContext(Dispatchers.IO) { block() }
            }
        }
    }

    suspend fun <T> withAllStripesReadLockSuspend(block: suspend () -> T): T {
        return acquireAllStripesLocked(0, block)
    }

    suspend fun <T> withAllStripesWriteLockSuspend(block: suspend () -> T): T {
        return acquireAllStripesLocked(0, block)
    }

    private suspend fun <T> acquireAllStripesLocked(index: Int, block: suspend () -> T): T {
        if (index >= mutexes.size) return block()
        return mutexes[index].withLock { acquireAllStripesLocked(index + 1, block) }
    }

    fun shutdown() {
        lockDispatcher.close()
    }
}
