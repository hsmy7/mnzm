package com.xianxia.sect.core.util

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

class ListenerManager<T>(private val tag: String = "ListenerManager") {
    private val _listeners = CopyOnWriteArrayList<T>()
    
    val listeners: List<T> get() = _listeners.toList()
    
    fun add(listener: T) {
        _listeners.add(listener)
    }
    
    fun remove(listener: T) {
        _listeners.remove(listener)
    }
    
    fun clear() {
        _listeners.clear()
    }
    
    fun size(): Int = _listeners.size
    
    fun isEmpty(): Boolean = _listeners.isEmpty()
    
    fun isNotEmpty(): Boolean = _listeners.isNotEmpty()
    
    fun notify(action: (T) -> Unit) {
        _listeners.forEach { listener ->
            try {
                action(listener)
            } catch (e: Exception) {
                Log.e(tag, "Error notifying listener", e)
            }
        }
    }
    
    fun notifySafe(action: (T) -> Unit): Int {
        var errorCount = 0
        _listeners.forEach { listener ->
            try {
                action(listener)
            } catch (e: Exception) {
                Log.e(tag, "Error notifying listener", e)
                errorCount++
            }
        }
        return errorCount
    }
    
    fun <R> mapNotNull(transform: (T) -> R?): List<R> {
        return _listeners.mapNotNull { listener ->
            try {
                transform(listener)
            } catch (e: Exception) {
                Log.e(tag, "Error in listener transform", e)
                null
            }
        }
    }
}
