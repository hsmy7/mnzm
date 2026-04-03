package com.xianxia.sect.core.warehouse

import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.Deflater
import java.util.zip.Inflater

object InventorySerializer {
    private const val MAGIC_NUMBER = 0x58494E49
    private const val VERSION = 2
    private const val BUFFER_SIZE = 4096
    private const val POOL_MAX_SIZE = 4
    
    private val deflaterPool = ConcurrentLinkedQueue<Deflater>()
    private val inflaterPool = ConcurrentLinkedQueue<Inflater>()
    
    private fun acquireDeflater(): Deflater {
        return deflaterPool.poll() ?: Deflater(Deflater.BEST_SPEED)
    }
    
    private fun releaseDeflater(deflater: Deflater) {
        if (deflaterPool.size < POOL_MAX_SIZE) {
            deflater.reset()
            deflaterPool.offer(deflater)
        } else {
            deflater.end()
        }
    }
    
    private fun acquireInflater(): Inflater {
        return inflaterPool.poll() ?: Inflater()
    }
    
    private fun releaseInflater(inflater: Inflater) {
        if (inflaterPool.size < POOL_MAX_SIZE) {
            inflater.reset()
            inflaterPool.offer(inflater)
        } else {
            inflater.end()
        }
    }
    
    fun serialize(warehouse: SectWarehouse): ByteArray {
        val buffer = ByteArrayOutputStream(BUFFER_SIZE)
        val data = DataOutputStream(buffer)
        
        data.writeInt(MAGIC_NUMBER)
        data.writeInt(VERSION)
        data.writeLong(warehouse.spiritStones)
        data.writeInt(warehouse.items.size)
        
        val compressor = WarehouseCompressor
        val compactItems = warehouse.items.map { compressor.compress(it) }
        
        compactItems.forEach { item ->
            data.writeInt(item.id)
            data.writeInt(item.nameId)
            data.writeByte(item.type.toInt())
            data.writeByte(item.rarity.toInt())
            data.writeShort(item.quantity.toInt())
        }
        
        return compress(buffer.toByteArray())
    }
    
    fun deserialize(bytes: ByteArray): SectWarehouse {
        val decompressed = decompress(bytes)
        val buffer = ByteArrayInputStream(decompressed)
        val data = DataInputStream(buffer)
        
        val magic = data.readInt()
        require(magic == MAGIC_NUMBER) { "Invalid inventory file format" }
        
        val version = data.readInt()
        require(version <= VERSION) { "Unsupported inventory version: $version" }
        
        val spiritStones = data.readLong()
        val itemCount = data.readInt()
        
        val items = mutableListOf<WarehouseItem>()
        val compressor = WarehouseCompressor
        
        repeat(itemCount) {
            val item = CompactWarehouseItem(
                id = data.readInt(),
                nameId = data.readInt(),
                type = data.readByte(),
                rarity = data.readByte(),
                quantity = data.readShort()
            )
            items.add(compressor.decompress(item))
        }
        
        return SectWarehouse(items = items, spiritStones = spiritStones)
    }
    
    fun serializeCompact(inventory: CompactInventory, spiritStones: Long): ByteArray {
        val buffer = ByteArrayOutputStream(BUFFER_SIZE)
        val data = DataOutputStream(buffer)
        
        data.writeInt(MAGIC_NUMBER)
        data.writeInt(VERSION)
        data.writeLong(spiritStones)
        data.writeInt(inventory.size)
        
        for (i in 0 until inventory.size) {
            data.writeInt(inventory.itemIds[i])
            data.writeInt(inventory.nameIds[i])
            data.writeByte(inventory.types[i].toInt())
            data.writeByte(inventory.rarities[i].toInt())
            data.writeInt(inventory.quantities[i])
        }
        
        return compress(buffer.toByteArray())
    }
    
    fun deserializeCompact(bytes: ByteArray): Pair<CompactInventory, Long> {
        val decompressed = decompress(bytes)
        val buffer = ByteArrayInputStream(decompressed)
        val data = DataInputStream(buffer)
        
        val magic = data.readInt()
        require(magic == MAGIC_NUMBER) { "Invalid inventory file format" }
        
        val version = data.readInt()
        require(version <= VERSION) { "Unsupported inventory version: $version" }
        
        val spiritStones = data.readLong()
        val itemCount = data.readInt()
        
        val capacity = maxOf(CompactInventory.INITIAL_CAPACITY, itemCount * 2)
        val itemIds = IntArray(capacity)
        val nameIds = IntArray(capacity)
        val types = ByteArray(capacity)
        val rarities = ByteArray(capacity)
        val quantities = IntArray(capacity)
        
        repeat(itemCount) { i ->
            itemIds[i] = data.readInt()
            nameIds[i] = data.readInt()
            types[i] = data.readByte()
            rarities[i] = data.readByte()
            quantities[i] = data.readInt()
        }
        
        return Pair(
            CompactInventory(
                itemIds = itemIds,
                nameIds = nameIds,
                types = types,
                rarities = rarities,
                quantities = quantities,
                size = itemCount
            ),
            spiritStones
        )
    }
    
    fun serializeToByteBuffer(warehouse: SectWarehouse): ByteBuffer {
        val bytes = serialize(warehouse)
        return ByteBuffer.wrap(bytes)
    }
    
    fun deserializeFromByteBuffer(buffer: ByteBuffer): SectWarehouse {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return deserialize(bytes)
    }
    
    private fun compress(data: ByteArray): ByteArray {
        val deflater = acquireDeflater()
        try {
            deflater.setInput(data)
            deflater.finish()
            
            val output = ByteArrayOutputStream(data.size)
            val buffer = ByteArray(BUFFER_SIZE)
            
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            
            return output.toByteArray()
        } finally {
            releaseDeflater(deflater)
        }
    }
    
    private fun decompress(data: ByteArray): ByteArray {
        val inflater = acquireInflater()
        try {
            inflater.setInput(data)
            
            val output = ByteArrayOutputStream(data.size * 2)
            val buffer = ByteArray(BUFFER_SIZE)
            
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                output.write(buffer, 0, count)
            }
            
            return output.toByteArray()
        } finally {
            releaseInflater(inflater)
        }
    }
    
    fun estimateSerializedSize(warehouse: SectWarehouse): Int {
        return 4 + 4 + 8 + 4 + warehouse.items.size * 14
    }
    
    fun calculateCompressionRatio(warehouse: SectWarehouse): Float {
        val original = warehouse.items.sumOf { 
            it.itemId.length * 2 + it.itemName.length * 2 + 20 
        }
        val compressed = serialize(warehouse).size
        return if (original == 0) 1f else compressed.toFloat() / original
    }
    
    fun clearPools() {
        deflaterPool.forEach { it.end() }
        deflaterPool.clear()
        inflaterPool.forEach { it.end() }
        inflaterPool.clear()
    }
}
