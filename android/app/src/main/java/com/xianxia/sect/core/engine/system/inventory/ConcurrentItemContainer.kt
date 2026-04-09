@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package com.xianxia.sect.core.engine.system.inventory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject

class ConcurrentItemContainer<T : InventoryItem> @Inject constructor(
    private val lockManager: InventoryLockManager,
    private val itemType: ItemType,
    private val maxStackSize: Int = InventoryItem.MAX_STACK_SIZE
) {
    private val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()
    
    /**
     * 线程安全的索引：id -> 列表位置。
     * 使用 ConcurrentHashMap 保证即使锁粒度未来变化或锁实现有缺陷，
     * 索引本身的读写操作也是线程安全的。
     */
    private val idIndex = ConcurrentHashMap<String, Int>()
    
    /**
     * 线程安全的索引：mergeKey -> 列表位置（仅可堆叠物品）。
     */
    private val mergeKeyIndex = ConcurrentHashMap<String, Int>()
    
    /**
     * 线程安全的索引：name -> 位置集合（一个名称可能对应多个物品）。
     * 值使用 CopyOnWriteArraySet，保证迭代时修改的安全性（写时复制）。
     */
    private val nameIndex = ConcurrentHashMap<String, CopyOnWriteArraySet<Int>>()
    
    val size: Int get() = lockManager.withTypeReadLock(itemType) { _items.value.size }
    val isEmpty: Boolean get() = lockManager.withTypeReadLock(itemType) { _items.value.isEmpty() }
    
    fun getAll(): List<T> = lockManager.withTypeReadLock(itemType) { _items.value.toList() }
    
    fun getById(id: String): T? = lockManager.withTypeReadLock(itemType) {
        val idx = idIndex[id] ?: return@withTypeReadLock null
        _items.value.getOrNull(idx)
    }
    
    fun getByMergeKey(mergeKey: String): T? = lockManager.withTypeReadLock(itemType) {
        val idx = mergeKeyIndex[mergeKey] ?: return@withTypeReadLock null
        _items.value.getOrNull(idx)
    }
    
    fun getByName(name: String): List<T> = lockManager.withTypeReadLock(itemType) {
        nameIndex[name]?.mapNotNull { idx -> _items.value.getOrNull(idx) } ?: emptyList()
    }
    
    fun getByNameAndRarity(name: String, rarity: Int): T? = lockManager.withTypeReadLock(itemType) {
        nameIndex[name]?.firstNotNullOfOrNull { idx -> 
            _items.value.getOrNull(idx)?.takeIf { it.rarity == rarity } 
        }
    }
    
    fun hasItem(id: String): Boolean = lockManager.withTypeReadLock(itemType) {
        idIndex.containsKey(id)
    }
    
    fun hasItemByMergeKey(mergeKey: String): Boolean = lockManager.withTypeReadLock(itemType) {
        mergeKeyIndex.containsKey(mergeKey)
    }
    
    fun hasItemByName(name: String, rarity: Int, quantity: Int = 1): Boolean = lockManager.withTypeReadLock(itemType) {
        val item = nameIndex[name]?.firstNotNullOfOrNull { idx -> 
            _items.value.getOrNull(idx)?.takeIf { it.rarity == rarity } 
        } ?: return@withTypeReadLock false
        item.quantity >= quantity
    }
    
    fun getQuantity(id: String): Int = lockManager.withTypeReadLock(itemType) {
        val idx = idIndex[id] ?: return@withTypeReadLock 0
        _items.value.getOrNull(idx)?.quantity ?: 0
    }
    
    fun add(item: T, merge: Boolean = true, maxSlots: Int = Int.MAX_VALUE): ContainerResult {
        return lockManager.withTypeWriteLock(itemType) {
            if (item.quantity <= 0) return@withTypeWriteLock ContainerResult.invalidQuantity()
            
            if (item.isStackable && merge) {
                val existingIdx = mergeKeyIndex[item.mergeKey]
                if (existingIdx != null && existingIdx < _items.value.size) {
                    val existing = _items.value[existingIdx]
                    val newQty = (existing.quantity + item.quantity).coerceAtMost(maxStackSize)
                    updateItemAt(existingIdx, existing.withQuantity(newQty) as T)
                    return@withTypeWriteLock ContainerResult.success(mergedCount = 1)
                }
            }
            
            if (_items.value.size >= maxSlots) {
                return@withTypeWriteLock ContainerResult.full()
            }
            
            val newList = _items.value + item
            _items.value = newList
            rebuildIndexes(newList)
            ContainerResult.success(addedCount = 1)
        }
    }
    
    fun addAll(items: List<T>, merge: Boolean = true, maxSlots: Int = Int.MAX_VALUE): ContainerResult {
        return lockManager.withTypeWriteLock(itemType) {
            if (items.isEmpty()) return@withTypeWriteLock ContainerResult.success()
            
            val validItems = items.filter { it.quantity > 0 }
            if (validItems.isEmpty()) return@withTypeWriteLock ContainerResult.invalidQuantity()
            
            var mergedCount = 0
            var addedCount = 0
            var currentList = _items.value.toMutableList()
            
            for (item in validItems) {
                if (item.isStackable && merge) {
                    val existingIdx = mergeKeyIndex[item.mergeKey]
                    if (existingIdx != null && existingIdx < currentList.size) {
                        val existing = currentList[existingIdx]
                        val newQty = (existing.quantity + item.quantity).coerceAtMost(maxStackSize)
                        currentList[existingIdx] = existing.withQuantity(newQty) as T
                        mergedCount++
                        continue
                    }
                }
                
                if (currentList.size >= maxSlots) {
                    _items.value = currentList
                    rebuildIndexes(currentList)
                    return@withTypeWriteLock ContainerResult.full().copy(
                        mergedCount = mergedCount,
                        addedCount = addedCount
                    )
                }
                
                currentList.add(item)
                addedCount++
            }
            
            _items.value = currentList
            rebuildIndexes(currentList)
            ContainerResult.success(mergedCount = mergedCount, addedCount = addedCount)
        }
    }
    
    fun remove(id: String, quantity: Int = 1): ContainerResult {
        return lockManager.withTypeWriteLock(itemType) {
            if (quantity <= 0) return@withTypeWriteLock ContainerResult.invalidQuantity()
            
            val idx = idIndex[id] ?: return@withTypeWriteLock ContainerResult.notFound()
            val item = _items.value[idx]
            
            if (item.isLocked) return@withTypeWriteLock ContainerResult.locked()
            if (item.quantity < quantity) return@withTypeWriteLock ContainerResult(
                false, error = ContainerError.INSUFFICIENT_QUANTITY
            )
            
            if (item.quantity == quantity) {
                val newList = _items.value.filterNot { it.id == id }
                _items.value = newList
                
                incrementalRemoveIndex(item, idx)
                shiftIndicesAfter(idx)
            } else {
                val newList = _items.value.map { 
                    if (it.id == id) it.withQuantity(it.quantity - quantity) as T else it 
                }
                _items.value = newList
            }
            
            ContainerResult.success(removedCount = quantity)
        }
    }
    
    fun removeByMergeKey(mergeKey: String, quantity: Int = 1): ContainerResult {
        return lockManager.withTypeWriteLock(itemType) {
            if (quantity <= 0) return@withTypeWriteLock ContainerResult.invalidQuantity()
            
            val idx = mergeKeyIndex[mergeKey] ?: return@withTypeWriteLock ContainerResult.notFound()
            val item = _items.value[idx]
            
            if (item.isLocked) return@withTypeWriteLock ContainerResult.locked()
            if (item.quantity < quantity) return@withTypeWriteLock ContainerResult(
                false, error = ContainerError.INSUFFICIENT_QUANTITY
            )
            
            if (item.quantity == quantity) {
                val newList = _items.value.filterNot { it.mergeKey == mergeKey }
                _items.value = newList
                
                incrementalRemoveIndex(item, idx)
                shiftIndicesAfter(idx)
            } else {
                val newList = _items.value.map { 
                    if (it.mergeKey == mergeKey) it.withQuantity(it.quantity - quantity) as T else it 
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
        val indices = nameIndex[item.name]
        if (indices != null) {
            indices.remove(removedIndex)
            if (indices.isEmpty()) {
                nameIndex.remove(item.name)
            }
        }
    }
    
    private fun shiftIndicesAfter(removedIndex: Int) {
        // idIndex：遍历并更新大于 removedIndex 的条目
        val idEntries = idIndex.entries.toList() // 快照避免 ConcurrentModificationException
        for ((key, value) in idEntries) {
            if (value > removedIndex) {
                idIndex[key] = value - 1
            }
        }
        
        // mergeKeyIndex：同上
        val mergeKeyEntries = mergeKeyIndex.entries.toList()
        for ((key, value) in mergeKeyEntries) {
            if (value > removedIndex) {
                mergeKeyIndex[key] = value - 1
            }
        }
        
        // nameIndex：对每个 CopyOnWriteArraySet 执行移除+添加
        // CopyOnWriteArraySet 的 remove/add 是线程安全的（写时复制）
        for ((_, indices) in nameIndex) {
            val toUpdate = indices.filter { it > removedIndex }.toList()
            for (oldIndex in toUpdate) {
                // CopyOnWriteArraySet.remove 创建新底层数组
                indices.remove(oldIndex)
                // CopyOnWriteArraySet.add 再次创建新底层数组
                indices.add(oldIndex - 1)
            }
        }
    }
    
    fun update(id: String, transform: (T) -> T): Boolean {
        return lockManager.withTypeWriteLock(itemType) {
            val idx = idIndex[id] ?: return@withTypeWriteLock false
            val item = _items.value[idx]
            val newItem = transform(item)
            updateItemAt(idx, newItem)
            
            if (item.mergeKey != newItem.mergeKey) {
                mergeKeyIndex.remove(item.mergeKey)
                mergeKeyIndex[newItem.mergeKey] = idx
            }
            if (item.id != newItem.id) {
                idIndex.remove(item.id)
                idIndex[newItem.id] = idx
            }
            if (item.name != newItem.name) {
                val oldIndices = nameIndex[item.name]
                if (oldIndices != null) {
                    oldIndices.remove(idx)
                    if (oldIndices.isEmpty()) {
                        nameIndex.remove(item.name)
                    }
                }
                nameIndex.computeIfAbsent(newItem.name) { CopyOnWriteArraySet() }.add(idx)
            }
            
            true
        }
    }
    
    fun clear() {
        lockManager.withTypeWriteLock(itemType) {
            _items.value = emptyList()
            idIndex.clear()
            mergeKeyIndex.clear()
            nameIndex.clear()
        }
    }
    
    fun loadItems(items: List<T>) {
        lockManager.withTypeWriteLock(itemType) {
            _items.value = items
            rebuildIndexes(items)
        }
    }
    
    fun totalItemCount(): Int = lockManager.withTypeReadLock(itemType) {
        _items.value.sumOf { it.quantity }
    }
    
    fun filter(predicate: (T) -> Boolean): List<T> = lockManager.withTypeReadLock(itemType) {
        _items.value.filter(predicate)
    }
    
    fun <R : Comparable<R>> sortedBy(selector: (T) -> R, descending: Boolean = true): List<T> {
        return lockManager.withTypeReadLock(itemType) {
            if (descending) {
                _items.value.sortedByDescending(selector)
            } else {
                _items.value.sortedBy(selector)
            }
        }
    }
    
    private fun updateItemAt(index: Int, item: T) {
        val newList = _items.value.toMutableList()
        if (index in newList.indices) {
            newList[index] = item
            _items.value = newList
        }
    }
    
    private fun rebuildIndexes(items: List<T>) {
        idIndex.clear()
        mergeKeyIndex.clear()
        nameIndex.clear()
        items.forEachIndexed { idx, item ->
            idIndex[item.id] = idx
            if (item.isStackable) {
                mergeKeyIndex[item.mergeKey] = idx
            }
            nameIndex.computeIfAbsent(item.name) { CopyOnWriteArraySet() }.add(idx)
        }
    }
    
    fun getIndexStats(): IndexStats = lockManager.withTypeReadLock(itemType) {
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

private inline fun <T> T.takeIf(condition: (T) -> Boolean): T? = if (condition(this)) this else null
