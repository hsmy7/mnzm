package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem

data class CompactInventory(
    val itemIds: IntArray,
    val nameIds: IntArray,
    val types: ByteArray,
    val rarities: ByteArray,
    val quantities: IntArray,
    val size: Int,
    private val indexMap: Map<Int, Int> = emptyMap()
) {
    companion object {
        const val GROWTH_FACTOR = 2
        const val INITIAL_CAPACITY = 64
        const val TYPE_PILL: Byte = 1
        const val TYPE_EQUIPMENT: Byte = 2
        const val TYPE_MANUAL: Byte = 3
        const val TYPE_MATERIAL: Byte = 4
        const val TYPE_HERB: Byte = 5
        const val TYPE_SEED: Byte = 6
        
        private val TYPE_MAP = mapOf(
            "pill" to TYPE_PILL,
            "equipment" to TYPE_EQUIPMENT,
            "manual" to TYPE_MANUAL,
            "material" to TYPE_MATERIAL,
            "herb" to TYPE_HERB,
            "seed" to TYPE_SEED
        )
        
        private val REVERSE_TYPE_MAP = TYPE_MAP.entries.associate { it.value to it.key }
        
        fun empty(): CompactInventory = CompactInventory(
            itemIds = IntArray(INITIAL_CAPACITY),
            nameIds = IntArray(INITIAL_CAPACITY),
            types = ByteArray(INITIAL_CAPACITY),
            rarities = ByteArray(INITIAL_CAPACITY),
            quantities = IntArray(INITIAL_CAPACITY),
            size = 0,
            indexMap = emptyMap()
        )
        
        fun fromItems(items: List<WarehouseItem>, compressor: WarehouseCompressor): CompactInventory {
            val capacity = maxOf(INITIAL_CAPACITY, items.size * 2)
            val itemIds = IntArray(capacity)
            val nameIds = IntArray(capacity)
            val types = ByteArray(capacity)
            val rarities = ByteArray(capacity)
            val quantities = IntArray(capacity)
            val indexMap = mutableMapOf<Int, Int>()
            
            items.forEachIndexed { index, item ->
                val itemId = compressor.getStringId(item.itemId)
                itemIds[index] = itemId
                nameIds[index] = compressor.getNameId(item.itemName)
                types[index] = TYPE_MAP[item.itemType] ?: 0
                rarities[index] = item.rarity.toByte()
                quantities[index] = item.quantity
                indexMap[itemId] = index
            }
            
            return CompactInventory(
                itemIds = itemIds,
                nameIds = nameIds,
                types = types,
                rarities = rarities,
                quantities = quantities,
                size = items.size,
                indexMap = indexMap.toMap()
            )
        }
    }
    
    fun getItem(index: Int, compressor: WarehouseCompressor): WarehouseItem? {
        if (index < 0 || index >= size) return null
        return WarehouseItem(
            itemId = compressor.getString(itemIds[index]) ?: "",
            itemName = compressor.getName(nameIds[index]) ?: "",
            itemType = REVERSE_TYPE_MAP[types[index]] ?: "",
            rarity = rarities[index].toInt().coerceIn(1, 6),
            quantity = quantities[index].coerceAtLeast(0)
        )
    }
    
    fun getCompactItem(index: Int): CompactItem? {
        if (index < 0 || index >= size) return null
        return CompactItem(
            id = itemIds[index],
            nameId = nameIds[index],
            type = types[index],
            rarity = rarities[index],
            quantity = quantities[index]
        )
    }
    
    fun findByItemId(itemId: Int): Int {
        return indexMap[itemId] ?: -1
    }
    
    fun add(item: WarehouseItem, compressor: WarehouseCompressor): CompactInventory {
        if (size >= itemIds.size) {
            return grow().add(item, compressor)
        }
        
        val itemId = compressor.getStringId(item.itemId)
        val existingIndex = indexMap[itemId]
        
        if (existingIndex != null && existingIndex >= 0) {
            val newQuantities = quantities.copyOf()
            newQuantities[existingIndex] += item.quantity
            return copy(quantities = newQuantities)
        }
        
        val newItemIds = itemIds.copyOf()
        val newNameIds = nameIds.copyOf()
        val newTypes = types.copyOf()
        val newRarities = rarities.copyOf()
        val newQuantities = quantities.copyOf()
        val newIndexMap = indexMap.toMutableMap()
        
        newItemIds[size] = itemId
        newNameIds[size] = compressor.getNameId(item.itemName)
        newTypes[size] = TYPE_MAP[item.itemType] ?: 0
        newRarities[size] = item.rarity.toByte()
        newQuantities[size] = item.quantity
        newIndexMap[itemId] = size
        
        return copy(
            itemIds = newItemIds,
            nameIds = newNameIds,
            types = newTypes,
            rarities = newRarities,
            quantities = newQuantities,
            size = size + 1,
            indexMap = newIndexMap.toMap()
        )
    }
    
    fun remove(itemId: Int): CompactInventory {
        val index = indexMap[itemId] ?: -1
        if (index < 0) return this
        
        val newItemIds = IntArray(size - 1)
        val newNameIds = IntArray(size - 1)
        val newTypes = ByteArray(size - 1)
        val newRarities = ByteArray(size - 1)
        val newQuantities = IntArray(size - 1)
        val newIndexMap = mutableMapOf<Int, Int>()
        
        var destIndex = 0
        for (i in 0 until size) {
            if (i != index) {
                newItemIds[destIndex] = itemIds[i]
                newNameIds[destIndex] = nameIds[i]
                newTypes[destIndex] = types[i]
                newRarities[destIndex] = rarities[i]
                newQuantities[destIndex] = quantities[i]
                newIndexMap[itemIds[i]] = destIndex
                destIndex++
            }
        }
        
        return copy(
            itemIds = newItemIds,
            nameIds = newNameIds,
            types = newTypes,
            rarities = newRarities,
            quantities = newQuantities,
            size = size - 1,
            indexMap = newIndexMap.toMap()
        )
    }
    
    fun updateQuantity(index: Int, newQuantity: Int): CompactInventory {
        if (index < 0 || index >= size) return this
        val newQuantities = quantities.copyOf()
        newQuantities[index] = newQuantity.coerceAtLeast(0)
        return copy(quantities = newQuantities)
    }
    
    private fun grow(): CompactInventory {
        val newCapacity = itemIds.size * GROWTH_FACTOR
        return copy(
            itemIds = itemIds.copyOf(newCapacity),
            nameIds = nameIds.copyOf(newCapacity),
            types = types.copyOf(newCapacity),
            rarities = rarities.copyOf(newCapacity),
            quantities = quantities.copyOf(newCapacity)
        )
    }
    
    fun toItems(compressor: WarehouseCompressor): List<WarehouseItem> {
        return (0 until size).mapNotNull { index ->
            getItem(index, compressor)
        }
    }
    
    fun memorySize(): Int {
        return itemIds.size * 4 + 
               nameIds.size * 4 + 
               types.size + 
               rarities.size + 
               quantities.size * 4 +
               indexMap.size * 16
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as CompactInventory
        
        if (size != other.size) return false
        if (!itemIds.contentEquals(other.itemIds)) return false
        if (!nameIds.contentEquals(other.nameIds)) return false
        if (!types.contentEquals(other.types)) return false
        if (!rarities.contentEquals(other.rarities)) return false
        if (!quantities.contentEquals(other.quantities)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = itemIds.contentHashCode()
        result = 31 * result + nameIds.contentHashCode()
        result = 31 * result + types.contentHashCode()
        result = 31 * result + rarities.contentHashCode()
        result = 31 * result + quantities.contentHashCode()
        result = 31 * result + size
        return result
    }
}

data class CompactItem(
    val id: Int,
    val nameId: Int,
    val type: Byte,
    val rarity: Byte,
    val quantity: Int
) {
    val isValid: Boolean get() = quantity > 0 && id >= 0 && nameId >= 0
}
