package com.xianxia.sect.core.engine.system.inventory

sealed class InventoryEvent {
    abstract val timestamp: Long
    
    data class ItemAdded(
        val item: InventoryItem,
        val quantity: Int,
        val merged: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InventoryEvent()
    
    data class ItemRemoved(
        val item: InventoryItem,
        val quantity: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InventoryEvent()
    
    data class ItemUpdated(
        val item: InventoryItem,
        val previousQuantity: Int,
        val newQuantity: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InventoryEvent()
    
    data class ItemsAdded(
        val items: List<InventoryItem>,
        val mergedCount: Int,
        val addedCount: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InventoryEvent()
    
    data class ItemsRemoved(
        val items: List<InventoryItem>,
        val totalQuantity: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InventoryEvent()
    
    data class CapacityChanged(
        val usedSlots: Int,
        val maxSlots: Int,
        val previousUsedSlots: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InventoryEvent()
    
    data class InventoryCleared(
        val itemCount: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InventoryEvent()
    
    data class InventorySorted(
        val sortBy: SortBy,
        val order: SortOrder,
        override val timestamp: Long = System.currentTimeMillis()
    ) : InventoryEvent()
}

enum class SortBy {
    RARITY, NAME, QUANTITY, VALUE, TYPE
}

enum class SortOrder {
    ASC, DESC
}

interface InventoryEventListener {
    suspend fun onEvent(event: InventoryEvent)
}

class InventoryEventBus {
    private val listeners = mutableListOf<InventoryEventListener>()
    private val eventHistory = mutableListOf<InventoryEvent>()
    private val maxHistorySize = 100
    private val listenersLock = Any()
    private val historyLock = Any()
    
    fun addListener(listener: InventoryEventListener) {
        synchronized(listenersLock) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }
    
    fun removeListener(listener: InventoryEventListener) {
        synchronized(listenersLock) {
            listeners.remove(listener)
        }
    }
    
    fun clearListeners() {
        synchronized(listenersLock) {
            listeners.clear()
        }
    }
    
    suspend fun emit(event: InventoryEvent) {
        val currentListeners: List<InventoryEventListener>
        
        synchronized(listenersLock) {
            currentListeners = listeners.toList()
        }
        
        synchronized(historyLock) {
            eventHistory.add(event)
            if (eventHistory.size > maxHistorySize) {
                eventHistory.removeAt(0)
            }
        }
        
        currentListeners.forEach { listener ->
            try {
                listener.onEvent(event)
            } catch (e: Exception) {
                android.util.Log.e("InventoryEventBus", "Error notifying listener", e)
            }
        }
    }
    
    fun getHistory(): List<InventoryEvent> = synchronized(historyLock) {
        eventHistory.toList()
    }
    
    fun getHistorySince(timestamp: Long): List<InventoryEvent> = synchronized(historyLock) {
        eventHistory.filter { it.timestamp >= timestamp }
    }
}
