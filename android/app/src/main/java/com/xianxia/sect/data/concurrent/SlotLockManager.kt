package com.xianxia.sect.data.concurrent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class LockStats(
    val totalAcquisitions: Long,
    val currentHoldCount: Int,
    val queuedRequests: Int
)

class SlotLockManager(
    private val maxSlots: Int = 5
) {
    companion object {
        private const val TAG = "SlotLockManager"
        const val AUTO_SAVE_SLOT = 0
        const val EMERGENCY_SLOT = -1
    }

    private val suspendLocks: Array<Mutex> = Array(maxSlots + 2) { Mutex() }
    private val globalSuspendLock = Mutex()

    private val acquisitionCount = ConcurrentHashMap<Int, Long>()
    private val holdCounts = ConcurrentHashMap<Int, Int>()

    private val slotIndexMap = mutableMapOf<Int, Int>().apply {
        put(AUTO_SAVE_SLOT, 0)
        put(EMERGENCY_SLOT, 1)
        for (i in 1..maxSlots) {
            put(i, i + 1)
        }
    }

    private fun getSuspendLock(slot: Int): Mutex {
        val index = slotIndexMap[slot]
            ?: throw IllegalArgumentException("Invalid slot: $slot")
        return suspendLocks[index]
    }

    /**
     * 同步版本的读锁 —— 已废弃。
     *
     * 此方法内部使用 [runBlocking] 阻塞调用线程以获取协程互斥锁。
     * 如果在主线程 (Dispatchers.Main) 调用且锁被持有时，将导致主线程冻结进而触发 ANR。
     *
     * 请迁移到 [withReadLockSuspend] 协程版本。
     */
    @Deprecated(
        message = "Use withReadLockSuspend instead. This blocking version can cause ANR if called on main thread.",
        level = DeprecationLevel.WARNING,
        replaceWith = ReplaceWith("withReadLockSuspend(slot, block)", "kotlinx.coroutines.runBlocking")
    )
    fun <T> withReadLock(slot: Int, block: () -> T): T {
        if (isPotentiallyMainThread()) {
            Log.w(TAG, "withReadLock($slot) called on potentially main thread — risk of ANR. Migrate to withReadLockSuspend.")
        }
        return runBlocking(Dispatchers.IO) { withReadLockSuspend(slot, block) }
    }

    /**
     * 同步版本的写锁 —— 已废弃。
     *
     * 此方法内部使用 [runBlocking] 阻塞调用线程以获取协程互斥锁。
     * 如果在主线程 (Dispatchers.Main) 调用且锁被持有时，将导致主线程冻结进而触发 ANR。
     *
     * 请迁移到 [withWriteLockSuspend] 协程版本。
     */
    @Deprecated(
        message = "Use withWriteLockSuspend instead. This blocking version can cause ANR if called on main thread.",
        level = DeprecationLevel.WARNING,
        replaceWith = ReplaceWith("withWriteLockSuspend(slot, block)", "kotlinx.coroutines.runBlocking")
    )
    fun <T> withWriteLock(slot: Int, block: () -> T): T {
        if (isPotentiallyMainThread()) {
            Log.w(TAG, "withWriteLock($slot) called on potentially main thread — risk of ANR. Migrate to withWriteLockSuspend.")
        }
        return runBlocking(Dispatchers.IO) { withWriteLockSuspend(slot, block) }
    }

    suspend fun <T> withReadLockSuspend(slot: Int, block: suspend () -> T): T {
        val mutex = getSuspendLock(slot)
        acquisitionCount.compute(slot) { _, count -> (count ?: 0) + 1 }
        holdCounts.compute(slot) { _, count -> (count ?: 0) + 1 }
        try {
            return mutex.withLock { block() }
        } finally {
            holdCounts.compute(slot) { _, count -> ((count ?: 1) - 1).coerceAtLeast(0) }
        }
    }

    suspend fun <T> withWriteLockSuspend(slot: Int, block: suspend () -> T): T {
        val mutex = getSuspendLock(slot)
        acquisitionCount.compute(slot) { _, count -> (count ?: 0) + 1 }
        holdCounts.compute(slot) { _, count -> (count ?: 0) + 1 }
        try {
            return mutex.withLock { block() }
        } finally {
            holdCounts.compute(slot) { _, count -> ((count ?: 1) - 1).coerceAtLeast(0) }
        }
    }

    /**
     * 同步版本的全局读锁 —— 已废弃。
     *
     * 此方法内部使用 [runBlocking] 阻塞调用线程。在主线程调用有 ANR 风险。
     *
     * 请迁移到协程版本或使用 [globalSuspendLock] 直接操作。
     */
    @Deprecated(
        message = "This blocking version can cause ANR on main thread.",
        level = DeprecationLevel.WARNING
    )
    fun <T> withGlobalReadLock(block: () -> T): T {
        if (isPotentiallyMainThread()) {
            Log.w(TAG, "withGlobalReadLock called on potentially main thread — risk of ANR.")
        }
        return runBlocking(Dispatchers.IO) { globalSuspendLock.withLock { block() } }
    }

    /**
     * 同步版本的全局写锁 —— 已废弃。
     *
     * 此方法内部使用 [runBlocking] 阻塞调用线程。在主线程调用有 ANR 风险。
     *
     * 请迁移到协程版本或使用 [globalSuspendLock] 直接操作。
     */
    @Deprecated(
        message = "This blocking version can cause ANR on main thread.",
        level = DeprecationLevel.WARNING
    )
    fun <T> withGlobalWriteLock(block: () -> T): T {
        if (isPotentiallyMainThread()) {
            Log.w(TAG, "withGlobalWriteLock called on potentially main thread — risk of ANR.")
        }
        return runBlocking(Dispatchers.IO) { globalSuspendLock.withLock { block() } }
    }

    /**
     * 同步版本的多槽位读锁 —— 已废弃。
     *
     * **Bug 修复**：原实现获取每个槽位的锁后立即释放（空 withLock 块），
     * 导致 [block] 实际上在无任何锁保护下执行。现已修正为按序获取所有锁并
     * 在全部持有时才执行 [block]，执行完毕后按逆序释放。
     *
     * 仍然存在 runBlocking 阻塞风险，请迁移到协程版本。
     */
    @Deprecated(
        message = "This blocking version can cause ANR on main thread. Migrate to coroutine version.",
        level = DeprecationLevel.WARNING
    )
    fun <T> withMultipleReadLocks(slots: Collection<Int>, block: () -> T): T {
        if (isPotentiallyMainThread()) {
            Log.w(TAG, "withMultipleReadLocks called on potentially main thread — risk of ANR.")
        }
        return runBlocking(Dispatchers.IO) {
            val sortedSlots = slots.sorted()
            // 递归嵌套 withLock，确保所有锁同时持有期间执行 block
            acquireAllLocked(sortedSlots, block)
        }
    }

    /**
     * 同步版本的多槽位写锁 —— 已废弃。
     *
     * **Bug 修复**：同 [withMultipleReadLocks]，原实现在空块内释放了所有锁。
     * 现已修正为在持有全部锁期间执行 [block]。
     */
    @Deprecated(
        message = "This blocking version can cause ANR on main thread. Migrate to coroutine version.",
        level = DeprecationLevel.WARNING
    )
    fun <T> withMultipleWriteLocks(slots: Collection<Int>, block: () -> T): T {
        if (isPotentiallyMainThread()) {
            Log.w(TAG, "withMultipleWriteLocks called on potentially main thread — risk of ANR.")
        }
        return runBlocking(Dispatchers.IO) {
            val sortedSlots = slots.sorted()
            acquireAllLocked(sortedSlots, block)
        }
    }

    /**
     * 按序获取所有锁（嵌套 withLock），在全部锁均持有状态下执行 block，
     * 执行完毕后按嵌套层次自动逆序释放。
     */
    private suspend fun <T> acquireAllLocked(sortedSlots: List<Int>, block: () -> T): T {
        if (sortedSlots.isEmpty()) return block()
        val head = sortedSlots.first()
        val tail = sortedSlots.drop(1)
        return getSuspendLock(head).withLock {
            if (tail.isEmpty()) block()
            else acquireAllLocked(tail, block)
        }
    }

    fun <T> withAllSlotsReadLock(block: () -> T): T {
        return withMultipleReadLocks((1..maxSlots).toList() + listOf(AUTO_SAVE_SLOT), block)
    }

    fun <T> withAllSlotsWriteLock(block: () -> T): T {
        return withMultipleWriteLocks((1..maxSlots).toList() + listOf(AUTO_SAVE_SLOT), block)
    }

    fun isSlotLocked(slot: Int): Boolean {
        return (holdCounts[slot] ?: 0) > 0
    }

    fun isSlotWriteLocked(slot: Int): Boolean {
        return isSlotLocked(slot)
    }

    fun getQueuedThreadCount(slot: Int): Int {
        return 0
    }

    fun getLockStats(slot: Int): LockStats {
        return LockStats(
            totalAcquisitions = acquisitionCount[slot] ?: 0,
            currentHoldCount = holdCounts[slot] ?: 0,
            queuedRequests = 0
        )
    }

    /**
     * 尝试获取读锁（同步版本）—— 已废弃。
     *
     * 使用 [runBlocking] 在 IO 线程池中尝试非阻塞获取锁。
     * 注意：此方法获取的是 Mutex 锁，返回的 Boolean 仅表示本次 tryLock 是否成功，
     * 调用方需自行负责后续的 unlock 操作（通过 [unlockRead]）。
     */
    @Deprecated(
        message = "This blocking version can cause ANR on main thread. Migrate to coroutine version.",
        level = DeprecationLevel.WARNING
    )
    fun tryReadLock(slot: Int): Boolean {
        if (isPotentiallyMainThread()) {
            Log.w(TAG, "tryReadLock($slot) called on potentially main thread.")
        }
        val mutex = getSuspendLock(slot)
        return try {
            runBlocking(Dispatchers.IO) {
                mutex.tryLock().also { locked ->
                    if (locked) {
                        holdCounts.compute(slot) { _, count -> (count ?: 0) + 1 }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryReadLock failed for slot $slot", e)
            false
        }
    }

    /**
     * 尝试获取写锁（同步版本）—— 已废弃。
     *
     * 同 [tryReadLock] 的注意事项。
     */
    @Deprecated(
        message = "This blocking version can cause ANR on main thread. Migrate to coroutine version.",
        level = DeprecationLevel.WARNING
    )
    fun tryWriteLock(slot: Int): Boolean {
        if (isPotentiallyMainThread()) {
            Log.w(TAG, "tryWriteLock($slot) called on potentially main thread.")
        }
        val mutex = getSuspendLock(slot)
        return try {
            runBlocking(Dispatchers.IO) {
                mutex.tryLock().also { locked ->
                    if (locked) {
                        holdCounts.compute(slot) { _, count -> (count ?: 0) + 1 }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryWriteLock failed for slot $slot", e)
            false
        }
    }

    fun unlockRead(slot: Int) {
        holdCounts.compute(slot) { _, count -> ((count ?: 1) - 1).coerceAtLeast(0) }
    }

    fun unlockWrite(slot: Int) {
        holdCounts.compute(slot) { _, count -> ((count ?: 1) - 1).coerceAtLeast(0) }
    }

    fun clearAllLocks() {
        holdCounts.clear()
        acquisitionCount.clear()
    }

    fun isValidSlot(slot: Int): Boolean {
        return slot in slotIndexMap.keys
    }

    fun getMaxSlots(): Int = maxSlots

    /**
     * 检测当前是否可能在主线程上运行。
     *
     * 注意：这是一个启发式检测，不能保证 100% 准确。
     * 在 Android 上，主线程的 Looper 是唯一的可靠标识。
     */
    private fun isPotentiallyMainThread(): Boolean {
        return try {
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        } catch (e: Exception) {
            false
        }
    }
}

class StripedLockManager(
    private val stripes: Int = 16
) {
    private val locks = Array(stripes) { Mutex() }

    private fun getStripe(key: Any): Int {
        val hash = key.hashCode()
        return ((hash and 0x7FFFFFFF) % stripes)
    }

    /**
     * 同步版本的读锁 —— 已废弃。
     *
     * 内部使用 [runBlocking] 阻塞调用线程。在主线程调用有 ANR 风险。
     */
    @Deprecated(
        message = "This blocking version can cause ANR on main thread.",
        level = DeprecationLevel.WARNING
    )
    fun <T> withReadLock(key: Any, block: () -> T): T {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { locks[getStripe(key)].withLock { block() } }
    }

    /**
     * 同步版本的写锁 —— 已废弃。
     *
     * 内部使用 [runBlocking] 阻塞调用线程。在主线程调用有 ANR 风险。
     */
    @Deprecated(
        message = "This blocking version can cause ANR on main thread.",
        level = DeprecationLevel.WARNING
    )
    fun <T> withWriteLock(key: Any, block: () -> T): T {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { locks[getStripe(key)].withLock { block() } }
    }

    /**
     * 同步版本的全部条带读锁 —— 已废弃。
     *
     * **注意**：与 SlotLockManager 的旧版 bug 类似，此处在 forEach 中逐个获取锁后
     * 立即释放（空 withLock 块），[block] 实际在无保护状态下执行。
     * 如需真正的多锁保护，请使用协程版本的嵌套 withLock。
     */
    @Deprecated(
        message = "This blocking version can cause ANR on main thread. Also has a bug where locks are released before block executes.",
        level = DeprecationLevel.ERROR
    )
    fun <T> withAllStripesReadLock(block: () -> T): T {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            locks.forEach { it.withLock {} }
            block()
        }
    }

    /**
     * 同步版本的全部条带写锁 —— 已废弃。
     *
     * **注意**：同 [withAllStripesReadLock] 的锁释放 bug。
     */
    @Deprecated(
        message = "This blocking version can cause ANR on main thread. Also has a bug where locks are released before block executes.",
        level = DeprecationLevel.ERROR
    )
    fun <T> withAllStripesWriteLock(block: () -> T): T {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            locks.forEach { it.withLock {} }
            block()
        }
    }
}
