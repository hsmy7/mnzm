package com.xianxia.sect.data.cache

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

interface Poolable {
    fun reset()
}

class ObjectPool<T : Poolable>(
    private val factory: () -> T,
    private val maxSize: Int = 50
) {
    companion object {
        private const val TAG = "ObjectPool"
    }

    private val pool = ConcurrentLinkedQueue<T>()
    private val createdCount = AtomicInteger(0)
    private val reusedCount = AtomicInteger(0)
    private val returnedCount = AtomicInteger(0)

    fun acquire(): T {
        val obj = pool.poll()
        return if (obj != null) {
            reusedCount.incrementAndGet()
            obj
        } else {
            createdCount.incrementAndGet()
            factory()
        }
    }

    fun release(obj: T) {
        if (pool.size < maxSize) {
            obj.reset()
            pool.offer(obj)
            returnedCount.incrementAndGet()
        }
    }

    fun clear() {
        pool.clear()
        Log.d(TAG, "Pool cleared")
    }

    fun size(): Int = pool.size

    fun stats(): PoolStats {
        return PoolStats(
            poolSize = pool.size,
            createdCount = createdCount.get(),
            reusedCount = reusedCount.get(),
            returnedCount = returnedCount.get()
        )
    }
}

data class PoolStats(
    val poolSize: Int,
    val createdCount: Int,
    val reusedCount: Int,
    val returnedCount: Int
) {
    val reuseRate: Double
        get() = if (createdCount + reusedCount > 0) {
            reusedCount.toDouble() / (createdCount + reusedCount)
        } else 0.0
}

class PooledStringBuilder : Poolable {
    val builder = StringBuilder()

    fun clear(): PooledStringBuilder {
        builder.clear()
        return this
    }

    fun append(str: String): PooledStringBuilder {
        builder.append(str)
        return this
    }

    fun append(char: Char): PooledStringBuilder {
        builder.append(char)
        return this
    }

    fun append(num: Int): PooledStringBuilder {
        builder.append(num)
        return this
    }

    fun append(num: Long): PooledStringBuilder {
        builder.append(num)
        return this
    }

    override fun reset() {
        builder.clear()
    }

    override fun toString(): String = builder.toString()

    companion object {
        val POOL = ObjectPool(::PooledStringBuilder, 20)
    }
}

class PooledByteArray(private val size: Int) : Poolable {
    val array = ByteArray(size)
    var length = 0

    fun clear(): PooledByteArray {
        length = 0
        return this
    }

    override fun reset() {
        length = 0
    }

    companion object {
        fun createPool(arraySize: Int, poolSize: Int = 10): ObjectPool<PooledByteArray> {
            return ObjectPool({ PooledByteArray(arraySize) }, poolSize)
        }
    }
}
