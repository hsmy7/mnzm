package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.WarehouseItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object WarehouseCompressor {
    private val stringPool = ConcurrentHashMap<String, Int>()
    private val reverseStringPool = ConcurrentHashMap<Int, String>()
    private val nextId = AtomicInteger(0)
    
    private val namePool = ConcurrentHashMap<String, Int>()
    private val reverseNamePool = ConcurrentHashMap<Int, String>()
    private val nextNameId = AtomicInteger(0)
    
    private val stringPoolLock = Any()
    private val namePoolLock = Any()
    
    private val typeMap = mapOf(
        "pill" to CompactWarehouseItem.TYPE_PILL,
        "equipment" to CompactWarehouseItem.TYPE_EQUIPMENT,
        "manual" to CompactWarehouseItem.TYPE_MANUAL,
        "material" to CompactWarehouseItem.TYPE_MATERIAL,
        "herb" to CompactWarehouseItem.TYPE_HERB,
        "seed" to CompactWarehouseItem.TYPE_SEED
    )
    
    private val reverseTypeMap = typeMap.entries.associate { it.value to it.key }
    
    fun compress(item: WarehouseItem): CompactWarehouseItem {
        val id = getStringId(item.itemId)
        val nameId = getNameId(item.itemName)
        val type = typeMap[item.itemType] ?: 0
        
        return CompactWarehouseItem(
            id = id,
            nameId = nameId,
            type = type,
            rarity = item.rarity.toByte(),
            quantity = item.quantity.coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
        )
    }
    
    fun compressAll(items: List<WarehouseItem>): List<CompactWarehouseItem> {
        return items.map { compress(it) }
    }
    
    fun decompress(compact: CompactWarehouseItem): WarehouseItem {
        return WarehouseItem(
            itemId = reverseStringPool[compact.id] ?: "",
            itemName = reverseNamePool[compact.nameId] ?: "",
            itemType = reverseTypeMap[compact.type] ?: "",
            rarity = compact.rarity.toInt().coerceIn(1, 4),
            quantity = compact.quantity.toInt().coerceAtLeast(0)
        )
    }
    
    fun decompressAll(compacts: List<CompactWarehouseItem>): List<WarehouseItem> {
        return compacts.map { decompress(it) }
    }
    
    fun compressToBytes(items: List<WarehouseItem>): ByteArray {
        val compacts = compressAll(items)
        val buffer = java.io.ByteArrayOutputStream()
        val data = java.io.DataOutputStream(buffer)
        
        data.writeInt(compacts.size)
        compacts.forEach { c ->
            data.writeInt(c.id)
            data.writeInt(c.nameId)
            data.writeByte(c.type.toInt())
            data.writeByte(c.rarity.toInt())
            data.writeShort(c.quantity.toInt())
        }
        
        return buffer.toByteArray()
    }
    
    fun decompressFromBytes(bytes: ByteArray): List<WarehouseItem> {
        val buffer = java.io.ByteArrayInputStream(bytes)
        val data = java.io.DataInputStream(buffer)
        
        val count = data.readInt()
        val compacts = mutableListOf<CompactWarehouseItem>()
        
        repeat(count) {
            compacts.add(CompactWarehouseItem(
                id = data.readInt(),
                nameId = data.readInt(),
                type = data.readByte(),
                rarity = data.readByte(),
                quantity = data.readShort()
            ))
        }
        
        return decompressAll(compacts)
    }
    
    fun getStringId(str: String): Int {
        val existing = stringPool[str]
        if (existing != null) return existing
        
        return synchronized(stringPoolLock) {
            stringPool.getOrPut(str) {
                val id = nextId.getAndIncrement()
                reverseStringPool[id] = str
                id
            }
        }
    }
    
    fun getNameId(name: String): Int {
        val existing = namePool[name]
        if (existing != null) return existing
        
        return synchronized(namePoolLock) {
            namePool.getOrPut(name) {
                val id = nextNameId.getAndIncrement()
                reverseNamePool[id] = name
                id
            }
        }
    }
    
    fun getString(id: Int): String? = reverseStringPool[id]
    fun getName(id: Int): String? = reverseNamePool[id]
    
    fun clearPools() {
        synchronized(stringPoolLock) {
            stringPool.clear()
            reverseStringPool.clear()
            nextId.set(0)
        }
        synchronized(namePoolLock) {
            namePool.clear()
            reverseNamePool.clear()
            nextNameId.set(0)
        }
    }
    
    fun getPoolStats(): CompressionStats {
        return CompressionStats(
            stringPoolSize = stringPool.size,
            namePoolSize = namePool.size,
            uniqueStrings = stringPool.size,
            uniqueNames = namePool.size
        )
    }
    
    fun estimateCompressionRatio(items: List<WarehouseItem>): Float {
        if (items.isEmpty()) return 1f
        
        val originalSize = items.sumOf { 
            it.itemId.length * 2 + it.itemName.length * 2 + 12 
        }
        
        val compressedSize = items.size * 10
        
        return if (originalSize == 0) 1f else compressedSize.toFloat() / originalSize
    }
}

data class CompressionStats(
    val stringPoolSize: Int,
    val namePoolSize: Int,
    val uniqueStrings: Int,
    val uniqueNames: Int
)
