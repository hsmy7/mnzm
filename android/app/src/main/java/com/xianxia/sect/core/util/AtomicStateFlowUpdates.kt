package com.xianxia.sect.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object AtomicStateFlowUpdates {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    
    @PublishedApi
    internal fun getMutex(key: String): Mutex = 
        mutexes.getOrPut(key) { Mutex() }
    
    suspend inline fun <T> atomicUpdate(
        flow: MutableStateFlow<T>,
        crossinline transform: (T) -> T
    ): T {
        val mutex = getMutex(flow.hashCode().toString())
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
        val mutex = getMutex(flow.hashCode().toString())
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
        synchronized(flow) {
            val newValue = transform(flow.value)
            flow.value = newValue
            return newValue
        }
    }
    
    inline fun <T, R> atomicRead(
        flow: MutableStateFlow<T>,
        block: (T) -> R
    ): R {
        synchronized(flow) {
            return block(flow.value)
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
