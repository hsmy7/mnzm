package com.xianxia.sect.core.repository

import com.xianxia.sect.core.engine.system.inventory.AddResult
import com.xianxia.sect.core.model.GameItem
import kotlinx.coroutines.flow.Flow

interface ItemRepository<T : GameItem> {
    val items: Flow<List<T>>
    val count: Flow<Int>
    
    suspend fun add(item: T, merge: Boolean = true): AddResult
    suspend fun addAll(items: List<T>, merge: Boolean = true): AddResult
    suspend fun remove(id: String, quantity: Int = 1): Boolean
    suspend fun update(id: String, transform: (T) -> T): Boolean
    suspend fun getById(id: String): T?
    suspend fun getByName(name: String, rarity: Int): List<T>
    suspend fun hasItem(id: String): Boolean
    suspend fun getQuantity(id: String): Int
    suspend fun clear()
    
    fun observeById(id: String): Flow<T?>
    fun observeByRarity(rarity: Int): Flow<List<T>>
}
