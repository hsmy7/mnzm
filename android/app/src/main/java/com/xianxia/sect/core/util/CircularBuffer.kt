package com.xianxia.sect.core.util

class CircularBuffer<T : Number>(private val capacity: Int) {
    private val buffer = ArrayList<T>(capacity)
    private val lock = Any()
    
    fun add(item: T) {
        synchronized(lock) {
            buffer.add(item)
            if (buffer.size > capacity) {
                buffer.removeAt(0)
            }
        }
    }
    
    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }
    
    fun average(): Double {
        synchronized(lock) {
            if (buffer.isEmpty()) return 0.0
            return buffer.map { it.toDouble() }.average()
        }
    }
    
    fun size(): Int {
        synchronized(lock) {
            return buffer.size
        }
    }
    
    fun isEmpty(): Boolean {
        synchronized(lock) {
            return buffer.isEmpty()
        }
    }
    
    fun isNotEmpty(): Boolean {
        synchronized(lock) {
            return buffer.isNotEmpty()
        }
    }
    
    fun toList(): List<T> {
        synchronized(lock) {
            return buffer.toList()
        }
    }
    
    fun sum(): Double {
        synchronized(lock) {
            return buffer.sumOf { it.toDouble() }
        }
    }
    
    fun max(): Double {
        synchronized(lock) {
            if (buffer.isEmpty()) return 0.0
            return buffer.maxOf { it.toDouble() }
        }
    }
    
    fun min(): Double {
        synchronized(lock) {
            if (buffer.isEmpty()) return 0.0
            return buffer.minOf { it.toDouble() }
        }
    }
}
