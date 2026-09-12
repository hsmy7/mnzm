package com.xianxia.sect.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * MutableStateFlow 原子更新工具
 *
 * **重要约束**: 同一个 MutableStateFlow 实例不得同时使用协程方法（atomicUpdate/atomicUpdateWithResult）
 * 和同步方法（atomicUpdateSync/atomicRead），因为协程方法使用 Mutex 而同步方法使用 ReentrantLock，
 * 两种锁互不感知，混用无法保证原子性。
 *
 * 选择指南：
 * - 在协程上下文中 → 使用 atomicUpdate / atomicUpdateWithResult（非阻塞挂起）
 * - 在同步上下文中 → 使用 atomicUpdateSync / atomicRead（阻塞锁）
 */
object AtomicStateFlowUpdates {
    private val flowMutexes = ConcurrentHashMap<MutableStateFlow<*>, Mutex>()
    private val flowLocks = Collections.synchronizedMap(
        WeakHashMap<MutableStateFlow<*>, ReentrantLock>()
    )

    @PublishedApi
    internal fun getMutex(flow: MutableStateFlow<*>): Mutex =
        flowMutexes.getOrPut(flow) { Mutex() }

    @PublishedApi
    internal fun getLock(flow: MutableStateFlow<*>): ReentrantLock =
        flowLocks.getOrPut(flow) { ReentrantLock() }

    suspend inline fun <T> atomicUpdate(
        flow: MutableStateFlow<T>,
        crossinline transform: (T) -> T
    ): T {
        val mutex = getMutex(flow)
        return mutex.withLock {
            val newValue = transform(flow.value)
            flow.value = newValue
            newValue
        }
    }

    suspend inline fun <T> atomicUpdateWithResult(
        flow: MutableStateFlow<T>,
        crossinline transform: (T) -> Pair<T, Boolean>
    ): Pair<T, Boolean> {
        val mutex = getMutex(flow)
        return mutex.withLock {
            val (newValue, result) = transform(flow.value)
            flow.value = newValue
            newValue to result
        }
    }

    inline fun <T> atomicUpdateSync(
        flow: MutableStateFlow<T>,
        transform: (T) -> T
    ): T {
        val lock = getLock(flow)
        return lock.withLock {
            val newValue = transform(flow.value)
            flow.value = newValue
            newValue
        }
    }

    inline fun <T, R> atomicRead(
        flow: MutableStateFlow<T>,
        block: (T) -> R
    ): R {
        val lock = getLock(flow)
        return lock.withLock {
            block(flow.value)
        }
    }
}

suspend inline fun <T> MutableStateFlow<T>.atomicUpdate(
    crossinline transform: (T) -> T
): T = AtomicStateFlowUpdates.atomicUpdate(this, transform)

suspend inline fun <T> MutableStateFlow<T>.atomicUpdateWithResult(
    crossinline transform: (T) -> Pair<T, Boolean>
): Pair<T, Boolean> = AtomicStateFlowUpdates.atomicUpdateWithResult(this, transform)

inline fun <T> MutableStateFlow<T>.atomicUpdateSync(
    crossinline transform: (T) -> T
): T = AtomicStateFlowUpdates.atomicUpdateSync(this, transform)
