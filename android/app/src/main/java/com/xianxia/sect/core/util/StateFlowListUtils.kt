package com.xianxia.sect.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object StateFlowListUtils {
    
    val globalLock = ReentrantLock()
    
    inline fun <T> addItem(
        flow: MutableStateFlow<List<T>>,
        item: T
    ) {
        globalLock.withLock {
            flow.value = flow.value + item
        }
    }
    
    inline fun <T> addAll(
        flow: MutableStateFlow<List<T>>,
        items: List<T>
    ) {
        globalLock.withLock {
            flow.value = flow.value + items
        }
    }
    
    inline fun <T> removeItem(
        flow: MutableStateFlow<List<T>>,
        predicate: (T) -> Boolean,
        quantity: Int = 1
    ): Boolean {
        globalLock.withLock {
            var removed = 0
            val newList = flow.value.filterNot { item ->
                if (predicate(item) && removed < quantity) {
                    removed++
                    true
                } else {
                    false
                }
            }
            flow.value = newList
            return removed > 0
        }
    }
    
    inline fun <T> removeItemById(
        flow: MutableStateFlow<List<T>>,
        id: String,
        crossinline getId: (T) -> String,
        quantity: Int = 1
    ): Boolean {
        return removeItem(flow, { getId(it) == id }, quantity)
    }
    
    inline fun <T> removeWhere(
        flow: MutableStateFlow<List<T>>,
        predicate: (T) -> Boolean
    ): Int {
        globalLock.withLock {
            val initialSize = flow.value.size
            flow.value = flow.value.filterNot(predicate)
            return initialSize - flow.value.size
        }
    }
    
    inline fun <T> updateItem(
        flow: MutableStateFlow<List<T>>,
        predicate: (T) -> Boolean,
        transform: (T) -> T
    ): Boolean {
        globalLock.withLock {
            var found = false
            flow.value = flow.value.map { 
                if (predicate(it)) {
                    found = true
                    transform(it)
                } else it
            }
            return found
        }
    }
    
    inline fun <T> updateItemById(
        flow: MutableStateFlow<List<T>>,
        id: String,
        crossinline getId: (T) -> String,
        transform: (T) -> T
    ): Boolean {
        return updateItem(flow, { getId(it) == id }, transform)
    }
    
    inline fun <T> findItem(
        flow: MutableStateFlow<List<T>>,
        predicate: (T) -> Boolean
    ): T? = flow.value.find(predicate)
    
    inline fun <T> findItemById(
        flow: MutableStateFlow<List<T>>,
        id: String,
        crossinline getId: (T) -> String
    ): T? = flow.value.find { getId(it) == id }
    
    inline fun <T> hasItem(
        flow: MutableStateFlow<List<T>>,
        predicate: (T) -> Boolean
    ): Boolean = flow.value.any(predicate)
    
    inline fun <T> hasItemById(
        flow: MutableStateFlow<List<T>>,
        id: String,
        crossinline getId: (T) -> String
    ): Boolean = flow.value.any { getId(it) == id }
    
    fun <T> clearList(
        flow: MutableStateFlow<List<T>>
    ) {
        globalLock.withLock {
            flow.value = emptyList()
        }
    }
    
    fun <T> setList(
        flow: MutableStateFlow<List<T>>,
        items: List<T>
    ) {
        globalLock.withLock {
            flow.value = items
        }
    }
}

interface StackableItem {
    val id: String
    val name: String
    val rarity: Int
    val quantity: Int
    fun withQuantity(newQuantity: Int): StackableItem
}

object StackableItemUtils {
    
    inline fun <T> addStackable(
        flow: MutableStateFlow<List<T>>,
        item: T,
        merge: Boolean = true,
        noinline matchPredicate: ((T, T) -> Boolean)? = null,
        crossinline getName: (T) -> String,
        crossinline getRarity: (T) -> Int,
        crossinline getQuantity: (T) -> Int,
        crossinline withQuantity: (T, Int) -> T
    ) where T : StackableItem {
        if (getQuantity(item) <= 0) return
        
        StateFlowListUtils.globalLock.withLock {
            if (merge) {
                val existingIndex = if (matchPredicate != null) {
                    flow.value.indexOfFirst { matchPredicate(it, item) }
                } else {
                    flow.value.indexOfFirst { 
                        getName(it) == getName(item) && getRarity(it) == getRarity(item)
                    }
                }
                if (existingIndex >= 0) {
                    val existing = flow.value[existingIndex]
                    val newQty = getQuantity(existing) + getQuantity(item)
                    flow.value = flow.value.mapIndexed { index, listItem ->
                        if (index == existingIndex) withQuantity(listItem, newQty) else listItem
                    }
                    return
                }
            }
            flow.value = flow.value + item
        }
    }
    
    inline fun <T> addStackableBatch(
        flow: MutableStateFlow<List<T>>,
        items: List<T>,
        merge: Boolean = true,
        noinline matchPredicate: ((T, T) -> Boolean)? = null,
        crossinline getName: (T) -> String,
        crossinline getRarity: (T) -> Int,
        crossinline getQuantity: (T) -> Int,
        crossinline withQuantity: (T, Int) -> T
    ) where T : StackableItem {
        if (items.isEmpty()) return
        val validItems = items.filter { getQuantity(it) > 0 }
        if (validItems.isEmpty()) return
        
        StateFlowListUtils.globalLock.withLock {
            var currentList = flow.value
            for (item in validItems) {
                if (merge) {
                    val existingIndex = if (matchPredicate != null) {
                        currentList.indexOfFirst { matchPredicate(it, item) }
                    } else {
                        currentList.indexOfFirst { 
                            getName(it) == getName(item) && getRarity(it) == getRarity(item)
                        }
                    }
                    if (existingIndex >= 0) {
                        val existing = currentList[existingIndex]
                        val newQty = getQuantity(existing) + getQuantity(item)
                        currentList = currentList.mapIndexed { index, listItem ->
                            if (index == existingIndex) withQuantity(listItem, newQty) else listItem
                        }
                    } else {
                        currentList = currentList + item
                    }
                } else {
                    currentList = currentList + item
                }
            }
            flow.value = currentList
        }
    }
    
    fun <T> removeStackable(
        flow: MutableStateFlow<List<T>>,
        id: String,
        quantity: Int,
        getId: (T) -> String,
        getQuantity: (T) -> Int,
        withQuantity: (T, Int) -> T,
        logWarning: ((String) -> Unit)? = null
    ): Boolean where T : StackableItem {
        StateFlowListUtils.globalLock.withLock {
            var removed = false
            flow.value = flow.value.mapNotNull { item ->
                if (getId(item) == id && !removed) {
                    val newQty = getQuantity(item) - quantity
                    when {
                        newQty < 0 -> {
                            logWarning?.invoke("Cannot remove $quantity items, only ${getQuantity(item)} available")
                            item
                        }
                        newQty == 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            withQuantity(item, newQty)
                        }
                    }
                } else item
            }
            return removed
        }
    }
    
    fun <T> removeStackableByName(
        flow: MutableStateFlow<List<T>>,
        name: String,
        rarity: Int,
        quantity: Int,
        getName: (T) -> String,
        getRarity: (T) -> Int,
        getId: (T) -> String,
        getQuantity: (T) -> Int,
        withQuantity: (T, Int) -> T,
        logWarning: ((String) -> Unit)? = null
    ): Boolean where T : StackableItem {
        StateFlowListUtils.globalLock.withLock {
            val item = flow.value.find { getName(it) == name && getRarity(it) == rarity } ?: return false
            if (getQuantity(item) < quantity) {
                logWarning?.invoke("Cannot remove $quantity items '$name', only ${getQuantity(item)} available")
                return false
            }
            var removed = false
            flow.value = flow.value.mapNotNull { listItem ->
                if (getId(listItem) == getId(item) && !removed) {
                    val newQty = getQuantity(listItem) - quantity
                    when {
                        newQty <= 0 -> {
                            removed = true
                            null
                        }
                        else -> {
                            removed = true
                            withQuantity(listItem, newQty)
                        }
                    }
                } else listItem
            }
            return removed
        }
    }
    
    inline fun <T> hasStackable(
        flow: MutableStateFlow<List<T>>,
        name: String,
        rarity: Int,
        quantity: Int,
        crossinline getName: (T) -> String,
        crossinline getRarity: (T) -> Int,
        crossinline getQuantity: (T) -> Int
    ): Boolean where T : StackableItem {
        val item = flow.value.find { getName(it) == name && getRarity(it) == rarity } ?: return false
        return getQuantity(item) >= quantity
    }
    
    inline fun <T> getStackableQuantity(
        flow: MutableStateFlow<List<T>>,
        id: String,
        crossinline getId: (T) -> String,
        crossinline getQuantity: (T) -> Int
    ): Int where T : StackableItem {
        return flow.value.find { getId(it) == id }?.let { getQuantity(it) } ?: 0
    }
}
