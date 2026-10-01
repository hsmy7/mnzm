package com.xianxia.sect.data.concurrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 存档锁管理器（单档单锁）
 *
 * 设计原则：
 * 1. 所有公共 API 均为 suspend 函数，必须在协程上下文中调用
 * 2. 不再提供同步（阻塞）版本的锁操作
 * 3. 调用方必须使用协程（如 viewModelScope、lifecycleScope 等）
 *
 * 使用约定：
 * - 所有锁操作均使用 withXxxLockLight() 系列方法
 * - "读锁"与"写锁"实际是**同一把排他 Mutex**——"读锁"命名仅表达调用语义，
 *   存读档操作之间互相串行。StorageEngine.load 依赖此排他语义才可在
 *   "读锁"内执行 performFullTransactionSave 写库
 */
class SlotLockManager {

    /** 存档互斥锁：save/load/delete 全部串行 */
    private val mutex = Mutex()

    /** 全局互斥锁：跨存档的维护类操作 */
    private val globalMutex = Mutex()

    /**
     * 轻量级读锁（无额外 Dispatcher 切换）
     *
     * 不在锁内执行 `withContext(Dispatchers.IO)` 切换，
     * 调用方若已在 IO 调度器上，可避免不必要的线程跳转开销。
     */
    suspend fun <T> withReadLockLight(block: suspend () -> T): T {
        return mutex.withLock { block() }
    }

    /**
     * 轻量级写锁（无额外 Dispatcher 切换）
     *
     * 不在锁内执行 `withContext(Dispatchers.IO)` 切换，
     * 调用方若已在 IO 调度器上，可避免不必要的线程跳转开销。
     */
    suspend fun <T> withWriteLockLight(block: suspend () -> T): T {
        return mutex.withLock { block() }
    }

    /** 全局轻量级读锁（无额外 Dispatcher 切换） */
    suspend fun <T> withGlobalReadLockLight(block: suspend () -> T): T {
        return globalMutex.withLock { block() }
    }

    /** 全局轻量级写锁（无额外 Dispatcher 切换） */
    suspend fun <T> withGlobalWriteLockLight(block: suspend () -> T): T {
        return globalMutex.withLock { block() }
    }

    fun shutdown() = Unit
}
