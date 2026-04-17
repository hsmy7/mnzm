package com.xianxia.sect.data.concurrent

import android.util.Log
import com.xianxia.sect.data.StorageConstants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class LockStats(
    val totalAcquisitions: Long,
    val currentHoldCount: Int,
    val queuedRequests: Int
)

/**
 * 槽位锁管理器
 *
 * 设计原则：
 * 1. 所有公共 API 均为 suspend 函数，必须在协程上下文中调用
 * 2. 不再提供同步（阻塞）版本的锁操作
 * 3. 调用方必须使用协程（如 viewModelScope、lifecycleScope 等）
 *
 * 迁移指南：
 * - 所有锁操作均使用 withXxxLockLight() 系列方法
 * - 调用方必须在协程上下文中调用（viewModelScope、lifecycleScope 等）
 */
class SlotLockManager(
    private val maxSlots: Int = 6
) {
    companion object {
        private const val TAG = "SlotLockManager"
    }

    private val mutexes: Array<Mutex> =
        Array(maxSlots + 2) { Mutex() }
    
    private val globalMutex = Mutex()

    private val acquisitionCount = ConcurrentHashMap<Int, Long>()
    private val holdCounts = ConcurrentHashMap<Int, Int>()

    private val slotIndexMap: Map<Int, Int> = mapOf(
        StorageConstants.AUTO_SAVE_SLOT to 0,
        StorageConstants.EMERGENCY_SLOT to 1,
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
     * 不在锁内执行 `withContext(Dispatchers.IO)` 切换，
     * 调用方若已在 IO 调度器上，可避免不必要的线程跳转开销。
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
     * 不在锁内执行 `withContext(Dispatchers.IO)` 切换，
     * 调用方若已在 IO 调度器上，可避免不必要的线程跳转开销。
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
        holdCounts.clear()
        acquisitionCount.clear()
    }
}
