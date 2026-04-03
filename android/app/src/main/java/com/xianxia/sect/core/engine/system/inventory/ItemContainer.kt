package com.xianxia.sect.core.engine.system.inventory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

data class ContainerResult(
    val success: Boolean,
    val mergedCount: Int = 0,
    val addedCount: Int = 0,
    val removedCount: Int = 0,
    val error: ContainerError? = null
) {
    companion object {
        fun success(mergedCount: Int = 0, addedCount: Int = 0, removedCount: Int = 0) = 
            ContainerResult(true, mergedCount, addedCount, removedCount)
        fun full() = ContainerResult(false, error = ContainerError.FULL)
        fun locked() = ContainerResult(false, error = ContainerError.LOCKED)
        fun notFound() = ContainerResult(false, error = ContainerError.NOT_FOUND)
        fun invalidQuantity() = ContainerResult(false, error = ContainerError.INVALID_QUANTITY)
        fun insufficientQuantity() = ContainerResult(false, error = ContainerError.INSUFFICIENT_QUANTITY)
        fun duplicateId() = ContainerResult(false, error = ContainerError.DUPLICATE_ID)
    }
}

enum class ContainerError {
    FULL, LOCKED, NOT_FOUND, INVALID_QUANTITY, INSUFFICIENT_QUANTITY, DUPLICATE_ID
}

class ItemContainer<T : InventoryItem>(
    private val maxStackSize: Int = InventoryItem.MAX_STACK_SIZE
) {
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()
    
    private val idIndex = mutableMapOf<String, Int>()
    private val mergeKeyIndex = mutableMapOf<String, Int>()
    private val nameIndex = mutableMapOf<String, MutableSet<Int>>()
    private val lock = ReentrantReadWriteLock()
    
    val size: Int get() = lock.readLock().withLock { _items.value.size }
    val isEmpty: Boolean get() = lock.readLock().withLock { _items.value.isEmpty() }
    
    fun getAll(): List<T> = lock.readLock().withLock { _items.value.toList() }
    
    fun getById(id: String): T? = lock.readLock().withLock {
        idIndex[id]?.let { idx -> _items.value.getOrNull(idx) }
    }
    
    fun getByMergeKey(mergeKey: String): T? = lock.readLock().withLock {
        mergeKeyIndex[mergeKey]?.let { idx -> _items.value.getOrNull(idx) }
    }
    
    fun getByName(name: String): List<T> = lock.readLock().withLock {
        nameIndex[name]?.mapNotNull { idx -> _items.value.getOrNull(idx) } ?: emptyList()
    }
    
    fun getByNameAndRarity(name: String, rarity: Int): T? = lock.readLock().withLock {
        nameIndex[name]?.firstNotNullOfOrNull { idx -> 
            _items.value.getOrNull(idx)?.takeIf { it.rarity == rarity } 
        }
    }
    
    fun hasItem(id: String): Boolean = lock.readLock().withLock { idIndex.containsKey(id) }
    
    fun hasItemByName(name: String, rarity: Int, quantity: Int = 1): Boolean = lock.readLock().withLock {
        val item = nameIndex[name]?.firstNotNullOfOrNull { idx -> 
            _items.value.getOrNull(idx)?.takeIf { it.rarity == rarity } 
        } ?: return false
        item.quantity >= quantity
    }
    
    fun hasItem(mergeKey: String, quantity: Int): Boolean = lock.readLock().withLock {
        val idx = mergeKeyIndex[mergeKey] ?: return false
        val item = _items.value.getOrNull(idx) ?: return false
        item.quantity >= quantity
    }
    
    fun getQuantity(id: String): Int = lock.readLock().withLock {
        val idx = idIndex[id] ?: return 0
        _items.value.getOrNull(idx)?.quantity ?: 0
    }
    
    fun add(item: T, merge: Boolean = true, maxSlots: Int = Int.MAX_VALUE): ContainerResult {
        if (item.quantity <= 0) return ContainerResult.invalidQuantity()
        
        return lock.writeLock().withLock {
            if (item.isStackable && merge) {
                val existingIndex = mergeKeyIndex[item.mergeKey]
                if (existingIndex != null && existingIndex >= 0 && existingIndex < _items.value.size) {
                    val existing = _items.value[existingIndex]
                    val newQty = (existing.quantity + item.quantity).coerceAtMost(maxStackSize)
                    @Suppress("UNCHECKED_CAST")
                    val newList = _items.value.mapIndexed { index, listItem ->
                        if (index == existingIndex) (listItem as T).withQuantity(newQty) as T else listItem
                    }
                    _items.value = newList
                    return ContainerResult.success(mergedCount = 1)
                }
            }
            
            if (idIndex.containsKey(item.id)) {
                return ContainerResult.duplicateId()
            }
            
            if (_items.value.size >= maxSlots) {
                return ContainerResult.full()
            }
            
            val newIndex = _items.value.size
            val newList = _items.value + item
            _items.value = newList
            idIndex[item.id] = newIndex
            if (item.isStackable) {
                mergeKeyIndex[item.mergeKey] = newIndex
            }
            nameIndex.getOrPut(item.name) { mutableSetOf() }.add(newIndex)
            
            ContainerResult.success(addedCount = 1)
        }
    }
    
    fun addAll(items: List<T>, merge: Boolean = true, maxSlots: Int = Int.MAX_VALUE): ContainerResult {
        if (items.isEmpty()) return ContainerResult.success()
        
        val validItems = items.filter { it.quantity > 0 }
        if (validItems.isEmpty()) return ContainerResult.invalidQuantity()
        
        return lock.writeLock().withLock {
            var mergedCount = 0
            var addedCount = 0
            val currentList = _items.value.toMutableList()
            val localIdIndex = idIndex.toMutableMap()
            val localMergeKeyIndex = mergeKeyIndex.toMutableMap()
            val localNameIndex = nameIndex.mapValues { it.value.toMutableSet() }.toMutableMap()
            
            for (item in validItems) {
                if (item.isStackable && merge) {
                    val existingIndex = localMergeKeyIndex[item.mergeKey]
                    if (existingIndex != null && existingIndex >= 0 && existingIndex < currentList.size) {
                        val existing = currentList[existingIndex]
                        val newQty = (existing.quantity + item.quantity).coerceAtMost(maxStackSize)
                        @Suppress("UNCHECKED_CAST")
                        currentList[existingIndex] = (existing as T).withQuantity(newQty) as T
                        mergedCount++
                        continue
                    }
                }
                
                if (localIdIndex.containsKey(item.id)) {
                    continue
                }
                
                if (currentList.size >= maxSlots) {
                    break
                }
                
                val newIndex = currentList.size
                currentList.add(item)
                localIdIndex[item.id] = newIndex
                if (item.isStackable) {
                    localMergeKeyIndex[item.mergeKey] = newIndex
                }
                localNameIndex.getOrPut(item.name) { mutableSetOf() }.add(newIndex)
                addedCount++
            }
            
            _items.value = currentList
            idIndex.clear()
            idIndex.putAll(localIdIndex)
            mergeKeyIndex.clear()
            mergeKeyIndex.putAll(localMergeKeyIndex)
            nameIndex.clear()
            nameIndex.putAll(localNameIndex)
            
            ContainerResult.success(mergedCount = mergedCount, addedCount = addedCount)
        }
    }
    
    fun remove(id: String, quantity: Int = 1): ContainerResult {
        if (quantity <= 0) return ContainerResult.invalidQuantity()
        
        return lock.writeLock().withLock {
            val existingIndex = idIndex[id] ?: return ContainerResult.notFound()
            val item = _items.value.getOrNull(existingIndex) ?: return ContainerResult.notFound()
            
            if (item.isLocked) return ContainerResult.locked()
            
            if (item.quantity < quantity) {
                return ContainerResult.insufficientQuantity()
            }
            
            if (item.quantity == quantity) {
                val newList = _items.value.filterNot { it.id == id }
                _items.value = newList
                
                incrementalRemoveIndex(item, existingIndex)
                shiftIndicesAfter(existingIndex)
            } else {
                @Suppress("UNCHECKED_CAST")
                val newList = _items.value.map { 
                    if (it.id == id) (it as T).withQuantity(it.quantity - quantity) as T else it 
                }
                _items.value = newList
            }
            
            ContainerResult.success(removedCount = quantity)
        }
    }
    
    fun removeByMergeKey(mergeKey: String, quantity: Int = 1): ContainerResult {
        if (quantity <= 0) return ContainerResult.invalidQuantity()
        
        return lock.writeLock().withLock {
            val existingIndex = mergeKeyIndex[mergeKey] ?: return ContainerResult.notFound()
            val item = _items.value.getOrNull(existingIndex) ?: return ContainerResult.notFound()
            
            if (item.isLocked) return ContainerResult.locked()
            
            if (item.quantity < quantity) {
                return ContainerResult.insufficientQuantity()
            }
            
            if (item.quantity == quantity) {
                val newList = _items.value.filterNot { it.mergeKey == mergeKey }
                _items.value = newList
                
                incrementalRemoveIndex(item, existingIndex)
                shiftIndicesAfter(existingIndex)
            } else {
                @Suppress("UNCHECKED_CAST")
                val newList = _items.value.map { 
                    if (it.mergeKey == mergeKey) (it as T).withQuantity(it.quantity - quantity) as T else it 
                }
                _items.value = newList
            }
            
            ContainerResult.success(removedCount = quantity)
        }
    }
    
    private fun incrementalRemoveIndex(item: T, removedIndex: Int) {
        idIndex.remove(item.id)
        if (item.isStackable) {
            mergeKeyIndex.remove(item.mergeKey)
        }
        nameIndex[item.name]?.remove(removedIndex)
        if (nameIndex[item.name]?.isEmpty() == true) {
            nameIndex.remove(item.name)
        }
    }
    
    private fun shiftIndicesAfter(removedIndex: Int) {
        val idUpdates = mutableListOf<Pair<String, Int>>()
        idIndex.entries.forEach { entry ->
            if (entry.value > removedIndex) {
                idUpdates.add(entry.key to entry.value - 1)
            }
        }
        idUpdates.forEach { (key, newValue) -> idIndex[key] = newValue }
        
        val mergeKeyUpdates = mutableListOf<Pair<String, Int>>()
        mergeKeyIndex.entries.forEach { entry ->
            if (entry.value > removedIndex) {
                mergeKeyUpdates.add(entry.key to entry.value - 1)
            }
        }
        mergeKeyUpdates.forEach { (key, newValue) -> mergeKeyIndex[key] = newValue }
        
        nameIndex.values.forEach { indices ->
            val toUpdate = indices.filter { it > removedIndex }.toList()
            toUpdate.forEach { oldIndex ->
                indices.remove(oldIndex)
                indices.add(oldIndex - 1)
            }
        }
    }
    
    fun update(id: String, transform: (T) -> T): Boolean {
        return lock.writeLock().withLock {
            val existingIndex = idIndex[id] ?: return false
            val item = _items.value.getOrNull(existingIndex) ?: return false
            
            @Suppress("UNCHECKED_CAST")
            val newItem = transform(item as T) as T
            val newList = _items.value.toMutableList()
            newList[existingIndex] = newItem
            _items.value = newList
            
            if (item.mergeKey != newItem.mergeKey) {
                mergeKeyIndex.remove(item.mergeKey)
                mergeKeyIndex[newItem.mergeKey] = existingIndex
            }
            if (item.id != newItem.id) {
                idIndex.remove(item.id)
                idIndex[newItem.id] = existingIndex
            }
            if (item.name != newItem.name) {
                nameIndex[item.name]?.remove(existingIndex)
                if (nameIndex[item.name]?.isEmpty() == true) {
                    nameIndex.remove(item.name)
                }
                nameIndex.getOrPut(newItem.name) { mutableSetOf() }.add(existingIndex)
            }
            
            true
        }
    }
    
    fun clear() {
        lock.writeLock().withLock {
            _items.value = emptyList()
            idIndex.clear()
            mergeKeyIndex.clear()
            nameIndex.clear()
        }
    }
    
    fun loadItems(items: List<T>) {
        lock.writeLock().withLock {
            _items.value = items
            rebuildIndexesInternal(items)
        }
    }
    
    fun totalItemCount(): Int = lock.readLock().withLock { _items.value.sumOf { it.quantity } }
    
    fun filter(predicate: (T) -> Boolean): List<T> = lock.readLock().withLock { _items.value.filter(predicate) }
    
    fun <R : Comparable<R>> sortedBy(selector: (T) -> R, descending: Boolean = true): List<T> = lock.readLock().withLock {
        if (descending) {
            _items.value.sortedByDescending(selector)
        } else {
            _items.value.sortedBy(selector)
        }
    }
    
    private fun rebuildIndexesInternal(items: List<T>) {
        idIndex.clear()
        mergeKeyIndex.clear()
        nameIndex.clear()
        items.forEachIndexed { index, item ->
            idIndex[item.id] = index
            if (item.isStackable) {
                mergeKeyIndex[item.mergeKey] = index
            }
            nameIndex.getOrPut(item.name) { mutableSetOf() }.add(index)
        }
    }
    
    fun getIndexStats(): IndexStats = lock.readLock().withLock {
        IndexStats(
            itemCount = _items.value.size,
            idIndexSize = idIndex.size,
            mergeKeyIndexSize = mergeKeyIndex.size,
            nameIndexSize = nameIndex.size,
            idIndexValid = idIndex.values.all { it in 0 until _items.value.size },
            mergeKeyIndexValid = mergeKeyIndex.values.all { it in 0 until _items.value.size },
            nameIndexValid = nameIndex.values.all { indices -> indices.all { it in 0 until _items.value.size } }
        )
    }
}

data class IndexStats(
    val itemCount: Int,
    val idIndexSize: Int,
    val mergeKeyIndexSize: Int,
    val nameIndexSize: Int,
    val idIndexValid: Boolean,
    val mergeKeyIndexValid: Boolean,
    val nameIndexValid: Boolean
) {
    val isHealthy: Boolean get() = idIndexValid && mergeKeyIndexValid && nameIndexValid && idIndexSize == itemCount
}
