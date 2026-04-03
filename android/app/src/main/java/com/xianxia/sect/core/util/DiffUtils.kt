package com.xianxia.sect.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DiffResult<T>(
    val added: List<T> = emptyList(),
    val removed: List<T> = emptyList(),
    val updated: List<T> = emptyList(),
    val unchanged: List<T> = emptyList()
)

data class ListDiff<T>(
    val oldList: List<T>,
    val newList: List<T>,
    val added: List<T>,
    val removed: List<T>,
    val updated: List<Pair<T, T>>,
    val unchanged: List<T>
) {
    val hasChanges: Boolean get() = added.isNotEmpty() || removed.isNotEmpty() || updated.isNotEmpty()
    
    fun <R> map(transform: (T) -> R): ListDiff<R> = ListDiff(
        oldList = oldList.map(transform),
        newList = newList.map(transform),
        added = added.map(transform),
        removed = removed.map(transform),
        updated = updated.map { (old, new) -> transform(old) to transform(new) },
        unchanged = unchanged.map(transform)
    )
}

object DiffUtils {
    
    fun <T, K> computeDiff(
        oldList: List<T>,
        newList: List<T>,
        keySelector: (T) -> K,
        areContentsSame: (T, T) -> Boolean = { old, new -> old == new }
    ): ListDiff<T> {
        val oldMap = oldList.associateBy(keySelector)
        val newMap = newList.associateBy(keySelector)
        
        val oldKeys = oldMap.keys
        val newKeys = newMap.keys
        
        val addedKeys = newKeys - oldKeys
        val removedKeys = oldKeys - newKeys
        val commonKeys = oldKeys.intersect(newKeys)
        
        val added = addedKeys.mapNotNull { newMap[it] }
        val removed = removedKeys.mapNotNull { oldMap[it] }
        
        val updated = mutableListOf<Pair<T, T>>()
        val unchanged = mutableListOf<T>()
        
        for (key in commonKeys) {
            val oldItem = oldMap[key] ?: continue
            val newItem = newMap[key] ?: continue
            if (areContentsSame(oldItem, newItem)) {
                unchanged.add(oldItem)
            } else {
                updated.add(oldItem to newItem)
            }
        }
        
        return ListDiff(
            oldList = oldList,
            newList = newList,
            added = added,
            removed = removed,
            updated = updated,
            unchanged = unchanged
        )
    }
    
    fun <T, K> applyDiff(
        currentList: List<T>,
        diff: ListDiff<T>,
        keySelector: (T) -> K
    ): List<T> {
        val currentMap = currentList.associateBy(keySelector).toMutableMap()
        
        diff.removed.forEach { item ->
            currentMap.remove(keySelector(item))
        }
        
        diff.added.forEach { item ->
            currentMap[keySelector(item)] = item
        }
        
        diff.updated.forEach { (_, newItem) ->
            currentMap[keySelector(newItem)] = newItem
        }
        
        return currentMap.values.toList()
    }
}

class DiffAwareStateFlow<T>(
    initialValue: List<T>,
    private val keySelector: (T) -> String,
    private val areContentsSame: (T, T) -> Boolean = { old, new -> old == new }
) {
    private val _value = MutableStateFlow(initialValue)
    val value: StateFlow<List<T>> = _value.asStateFlow()
    
    private val mutex = Mutex()
    private var lastList: List<T> = initialValue
    
    suspend fun updateWithDiff(newList: List<T>): ListDiff<T> {
        return mutex.withLock {
            val diff = DiffUtils.computeDiff(
                oldList = lastList,
                newList = newList,
                keySelector = keySelector,
                areContentsSame = areContentsSame
            )
            
            if (diff.hasChanges) {
                _value.value = newList
                lastList = newList
            }
            
            diff
        }
    }
    
    suspend fun updateItem(itemId: String, transform: (T) -> T): Boolean {
        return mutex.withLock {
            val currentList = _value.value
            val index = currentList.indexOfFirst { keySelector(it) == itemId }
            
            if (index == -1) return false
            
            val oldItem = currentList[index]
            val newItem = transform(oldItem)
            
            if (areContentsSame(oldItem, newItem)) return true
            
            val newList = currentList.toMutableList()
            newList[index] = newItem
            _value.value = newList
            lastList = newList
            true
        }
    }
    
    suspend fun addItem(item: T) {
        mutex.withLock {
            val newList = _value.value + item
            _value.value = newList
            lastList = newList
        }
    }
    
    suspend fun removeItem(itemId: String): Boolean {
        return mutex.withLock {
            val currentList = _value.value
            val index = currentList.indexOfFirst { keySelector(it) == itemId }
            
            if (index == -1) return false
            
            val newList = currentList.toMutableList()
            newList.removeAt(index)
            _value.value = newList
            lastList = newList
            true
        }
    }
    
    fun getCurrentValue(): List<T> = _value.value
    
    fun getItemById(itemId: String): T? = _value.value.find { keySelector(it) == itemId }
}

class BatchUpdateBuilder<T>(
    private val currentList: List<T>,
    private val keySelector: (T) -> String
) {
    private val additions = mutableListOf<T>()
    private val removals = mutableSetOf<String>()
    private val updates = mutableMapOf<String, (T) -> T>()
    
    fun add(item: T): BatchUpdateBuilder<T> {
        additions.add(item)
        return this
    }
    
    fun addAll(items: List<T>): BatchUpdateBuilder<T> {
        additions.addAll(items)
        return this
    }
    
    fun remove(itemId: String): BatchUpdateBuilder<T> {
        removals.add(itemId)
        return this
    }
    
    fun update(itemId: String, transform: (T) -> T): BatchUpdateBuilder<T> {
        updates[itemId] = transform
        return this
    }
    
    fun build(): List<T> {
        val currentMap = currentList.associateBy(keySelector).toMutableMap()
        
        removals.forEach { id ->
            currentMap.remove(id)
        }
        
        updates.forEach { (id, transform) ->
            currentMap[id]?.let { item ->
                currentMap[id] = transform(item)
            }
        }
        
        additions.forEach { item ->
            currentMap[keySelector(item)] = item
        }
        
        return currentMap.values.toList()
    }
}

object ListUpdateUtils {
    
    fun <T> batchUpdate(
        currentList: List<T>,
        keySelector: (T) -> String,
        block: BatchUpdateBuilder<T>.() -> Unit
    ): List<T> {
        val builder = BatchUpdateBuilder(currentList, keySelector)
        builder.block()
        return builder.build()
    }
    
    inline fun <T, reified R : Comparable<R>> sortBy(
        list: List<T>,
        crossinline selector: (T) -> R
    ): List<T> = list.sortedBy(selector)
    
    inline fun <T> filterBy(
        list: List<T>,
        crossinline predicate: (T) -> Boolean
    ): List<T> = list.filter(predicate)
    
    inline fun <T, K> groupBy(
        list: List<T>,
        crossinline keySelector: (T) -> K
    ): Map<K, List<T>> = list.groupBy(keySelector)
}
