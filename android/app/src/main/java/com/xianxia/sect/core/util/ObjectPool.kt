package com.xianxia.sect.core.util

import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

// ==================== 具名 PoolFactory 实现（避免匿名内部类触发 KSP getSimpleName NPE）====================
private class SimplePoolFactory<T>(
    private val creator: () -> T,
    private val resetter: (T) -> Unit
) : ObjectPool.PoolFactory<T> {
    override fun create(): T = creator()
    override fun reset(instance: T) { resetter(instance) }
    override fun validate(instance: T): Boolean = true
}

private class StringBuilderPoolFactory : ObjectPool.PoolFactory<StringBuilder> {
    override fun create(): StringBuilder = StringBuilder(256)
    override fun reset(instance: StringBuilder) { instance.clear() }
    override fun validate(instance: StringBuilder): Boolean = instance.length < 10000
}

@Singleton
class ObjectPool @Inject constructor() {
    
    companion object {
        private const val TAG = "ObjectPool"
        private const val DEFAULT_MAX_POOL_SIZE = 64
        private const val DEFAULT_INITIAL_SIZE = 16
        private const val MAX_BORROW_RETRIES = 3
    }
    
    private val pools = ConcurrentHashMap<String, Pool<*>>()
    
    data class PoolStats(
        val objectType: String,
        val poolSize: Int,
        val borrowedCount: Int,
        val returnedCount: Int,
        val createdCount: Int,
        val hitCount: Int,
        val missCount: Int,
        val hitRate: Double
    )
    
    interface PoolFactory<T> {
        fun create(): T
        fun reset(instance: T)
        fun validate(instance: T): Boolean = true
    }
    
    inner class Pool<T>(
        private val key: String,
        private val factory: PoolFactory<T>,
        private val maxSize: Int = DEFAULT_MAX_POOL_SIZE
    ) {
        private val available = ConcurrentLinkedQueue<T>()
        private val currentSize = AtomicInteger(0)
        private val borrowed = AtomicInteger(0)
        private val returned = AtomicInteger(0)
        private val created = AtomicInteger(0)
        private val hitCount = AtomicInteger(0)
        private val missCount = AtomicInteger(0)
        
        init {
            repeat(DEFAULT_INITIAL_SIZE.coerceAtMost(maxSize)) {
                val instance = factory.create()
                available.offer(instance)
                created.incrementAndGet()
                currentSize.incrementAndGet()
            }
        }
        
        fun borrow(): T {
            val instance = available.poll()
            if (instance != null) {
                currentSize.decrementAndGet()
                if (factory.validate(instance)) {
                    borrowed.incrementAndGet()
                    hitCount.incrementAndGet()
                    return instance
                }
            }
            
            val newInstance = createNew()
            created.incrementAndGet()
            borrowed.incrementAndGet()
            missCount.incrementAndGet()
            return newInstance
        }
        
        fun release(instance: T) {
            while (true) {
                val current = currentSize.get()
                if (current >= maxSize) return
                if (currentSize.compareAndSet(current, current + 1)) {
                    try {
                        factory.reset(instance)
                        if (factory.validate(instance)) {
                            available.offer(instance)
                            returned.incrementAndGet()
                        } else {
                            currentSize.decrementAndGet()
                        }
                    } catch (e: Exception) {
                        currentSize.decrementAndGet()
                        Log.w(TAG, "Failed to release object to pool: $key", e)
                    }
                    return
                }
            }
        }
        
        private fun createNew(): T {
            while (true) {
                val current = currentSize.get()
                if (current >= maxSize) {
                    return factory.create()
                }
                if (currentSize.compareAndSet(current, current + 1)) {
                    return factory.create()
                }
            }
        }
        
        fun getStats(): PoolStats {
            val borrowedVal = borrowed.get()
            val returnedVal = returned.get()
            val hitVal = hitCount.get()
            val missVal = missCount.get()
            val totalRequests = hitVal + missVal
            val hitRate = if (totalRequests > 0) hitVal.toDouble() / totalRequests else 0.0
            
            return PoolStats(
                objectType = key,
                poolSize = currentSize.get(),
                borrowedCount = borrowedVal,
                returnedCount = returnedVal,
                createdCount = created.get(),
                hitCount = hitVal,
                missCount = missVal,
                hitRate = hitRate
            )
        }
        
        fun clear() {
            available.clear()
            currentSize.set(0)
        }
    }
    
    fun <T : Any> registerPool(
        key: String,
        factory: PoolFactory<T>,
        maxSize: Int = DEFAULT_MAX_POOL_SIZE
    ): Pool<T> {
        val pool = Pool(key, factory, maxSize)
        pools[key] = pool
        Log.i(TAG, "Registered pool for $key with max size $maxSize")
        return pool
    }
    
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getPool(key: String): Pool<T>? {
        return pools[key] as? Pool<T>
    }
    
    fun <T : Any> borrow(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return (pools[key] as? Pool<T>)?.borrow()
    }
    
    fun <T : Any> release(key: String, instance: T) {
        @Suppress("UNCHECKED_CAST")
        (pools[key] as? Pool<T>)?.release(instance)
    }
    
    fun getAllStats(): List<PoolStats> {
        return pools.map { (key, pool) ->
            @Suppress("UNCHECKED_CAST")
            (pool as Pool<Any>).getStats()
        }
    }
    
    fun logStats() {
        val stats = getAllStats()
        if (stats.isEmpty()) {
            Log.i(TAG, "No pools registered")
            return
        }
        
        val statsStr = stats.joinToString("\n") { stat ->
            "  ${stat.objectType}: size=${stat.poolSize}, borrowed=${stat.borrowedCount}, " +
            "returned=${stat.returnedCount}, created=${stat.createdCount}, " +
            "hits=${stat.hitCount}, misses=${stat.missCount}, hitRate=${String.format(Locale.ROOT, "%.2f", stat.hitRate)}"
        }
        Log.i(TAG, "ObjectPool Stats:\n$statsStr")
    }
    
    fun clearAll() {
        pools.values.forEach { it.clear() }
        Log.i(TAG, "All pools cleared")
    }
    
    fun cleanup() {
        clearAll()
        pools.clear()
        Log.i(TAG, "ObjectPool cleanup completed")
    }
}

class TypedMapPool<K, V>(
    private val pool: ObjectPool,
    private val key: String
) {
    private val factory = SimplePoolFactory({ mutableMapOf<K, V>() }, { it.clear() })

    private var registeredPool: ObjectPool.Pool<MutableMap<K, V>>? = null

    fun initialize(maxSize: Int = 32) {
        registeredPool = pool.registerPool(key, factory, maxSize)
    }

    fun borrow(): MutableMap<K, V> {
        return registeredPool?.borrow() ?: HashMap()
    }

    fun release(map: MutableMap<K, V>) {
        registeredPool?.release(map)
    }

    inline fun <T> use(block: (MutableMap<K, V>) -> T): T {
        val map = borrow()
        try {
            return block(map)
        } finally {
            release(map)
        }
    }
}

class TypedListPool<T>(
    private val pool: ObjectPool,
    private val key: String
) {
    private val factory = SimplePoolFactory({ mutableListOf<T>() }, { it.clear() })
    
    private var registeredPool: ObjectPool.Pool<MutableList<T>>? = null
    
    fun initialize(maxSize: Int = 32) {
        registeredPool = pool.registerPool(key, factory, maxSize)
    }
    
    fun borrow(): MutableList<T> {
        return registeredPool?.borrow() ?: ArrayList()
    }
    
    fun release(list: MutableList<T>) {
        registeredPool?.release(list)
    }
    
    inline fun <R> use(block: (MutableList<T>) -> R): R {
        val list = borrow()
        try {
            return block(list)
        } finally {
            release(list)
        }
    }
}

class StringBuilderPool(
    private val pool: ObjectPool
) {
    companion object {
        private const val KEY = "StringBuilder"
    }
    
    private val factory = StringBuilderPoolFactory()
    
    private var registeredPool: ObjectPool.Pool<StringBuilder>? = null
    
    fun initialize(maxSize: Int = 16) {
        registeredPool = pool.registerPool(KEY, factory, maxSize)
    }
    
    fun borrow(): StringBuilder {
        return registeredPool?.borrow() ?: StringBuilder(256)
    }
    
    fun release(sb: StringBuilder) {
        registeredPool?.release(sb)
    }
    
    inline fun <T> use(block: (StringBuilder) -> T): T {
        val sb = borrow()
        try {
            return block(sb)
        } finally {
            release(sb)
        }
    }
}
