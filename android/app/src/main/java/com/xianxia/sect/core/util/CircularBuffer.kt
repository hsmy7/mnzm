package com.xianxia.sect.core.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * 环形缓冲区 — O(1) 添加/移除。
 * 基于 [Array] + [head]/[tail] 索引，替代原 ArrayList.removeAt(0) O(n) 实现。
 */
class CircularBuffer<T : Number>(private val capacity: Int) {
    private val buffer = arrayOfNulls<Any?>(capacity) as Array<T?>
    @Volatile private var head = 0
    @Volatile private var tail = 0
    private val lock = Any()
    private val _size = AtomicInteger(0)

    fun add(item: T) {
        synchronized(lock) {
            buffer[tail] = item
            tail = (tail + 1) % capacity
            if (_size.get() < capacity) {
                _size.incrementAndGet()
            } else {
                // 缓冲区已满，覆盖最旧元素
                head = (head + 1) % capacity
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            for (i in 0 until capacity) buffer[i] = null
            head = 0; tail = 0; _size.set(0)
        }
    }

    fun average(): Double {
        synchronized(lock) {
            val s = _size.get()
            if (s == 0) return 0.0
            var sum = 0.0
            var idx = head
            for (i in 0 until s) {
                buffer[idx]?.let { sum += it.toDouble() }
                idx = (idx + 1) % capacity
            }
            return sum / s
        }
    }

    fun size(): Int = _size.get()
    fun isEmpty(): Boolean = _size.get() == 0
    fun isNotEmpty(): Boolean = _size.get() > 0

    fun toList(): List<T> {
        synchronized(lock) {
            val s = _size.get()
            val result = mutableListOf<T>()
            var idx = head
            for (i in 0 until s) {
                buffer[idx]?.let { result.add(it) }
                idx = (idx + 1) % capacity
            }
            return result
        }
    }

    fun sum(): Double {
        synchronized(lock) {
            val s = _size.get()
            if (s == 0) return 0.0
            var sum = 0.0
            var idx = head
            for (i in 0 until s) {
                buffer[idx]?.let { sum += it.toDouble() }
                idx = (idx + 1) % capacity
            }
            return sum
        }
    }

    fun max(): Double {
        synchronized(lock) {
            val s = _size.get()
            if (s == 0) return 0.0
            var maxVal = Double.MIN_VALUE
            var idx = head
            for (i in 0 until s) {
                buffer[idx]?.let { maxVal = maxOf(maxVal, it.toDouble()) }
                idx = (idx + 1) % capacity
            }
            return maxVal
        }
    }

    fun min(): Double {
        synchronized(lock) {
            val s = _size.get()
            if (s == 0) return 0.0
            var minVal = Double.MAX_VALUE
            var idx = head
            for (i in 0 until s) {
                buffer[idx]?.let { minVal = minOf(minVal, it.toDouble()) }
                idx = (idx + 1) % capacity
            }
            return minVal
        }
    }
}
