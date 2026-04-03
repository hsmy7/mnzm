package com.xianxia.sect.data.cache

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.serialization.UnifiedSerializationConstants
import kotlinx.serialization.KSerializer
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@OptIn(InternalSerializationApi::class)
class DiskCacheLayer(
    private val context: Context,
    private val cacheDirName: String = "disk_cache"
) {
    private val cacheDir = File(context.cacheDir, cacheDirName)
    private val index = ConcurrentHashMap<String, DiskCacheEntry>()
    
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    private val lz4Compressor = net.jpountz.lz4.LZ4Factory.fastestInstance().fastCompressor()
    private val lz4Decompressor = net.jpountz.lz4.LZ4Factory.fastestInstance().fastDecompressor()
    
    data class DiskCacheEntry(
        val key: String,
        val file: File,
        val createdAt: Long,
        val ttl: Long,
        val size: Long,
        val typeName: String = ""
    )
    
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        loadIndex()
    }
    
    fun put(key: String, value: Any, ttl: Long) {
        try {
            val sanitizedKey = sanitizeKey(key)
            val file = File(cacheDir, sanitizedKey)
            val bytes = serialize(value)
            file.writeBytes(bytes)
            
            index[key] = DiskCacheEntry(
                key = key,
                file = file,
                createdAt = System.currentTimeMillis(),
                ttl = ttl,
                size = bytes.size.toLong(),
                typeName = value::class.qualifiedName ?: ""
            )
            
            saveIndex()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to put cache entry: $key", e)
        }
    }
    
    fun get(key: String): Any? {
        val entry = index[key] ?: return null
        
        if (System.currentTimeMillis() - entry.createdAt > entry.ttl) {
            remove(key)
            return null
        }
        
        return try {
            val bytes = entry.file.readBytes()
            deserialize(bytes, entry.typeName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get cache entry: $key", e)
            null
        }
    }
    
    fun remove(key: String) {
        val entry = index.remove(key)
        entry?.file?.delete()
    }
    
    fun contains(key: String): Boolean {
        val entry = index[key] ?: return false
        return System.currentTimeMillis() - entry.createdAt <= entry.ttl
    }
    
    fun entryCount(): Int = index.size
    
    fun clear() {
        index.values.forEach { it.file.delete() }
        index.clear()
        saveIndex()
    }
    
    fun invalidatePattern(pattern: String) {
        val sanitizedPattern = sanitizeKey(pattern)
        val toRemove = index.keys.filter { 
            it.contains(pattern) || it.contains(sanitizedPattern)
        }
        toRemove.forEach { remove(it) }
    }
    
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = index.entries.filter { now - it.value.createdAt > it.value.ttl }
        expired.forEach { remove(it.key) }
        
        if (expired.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expired.size} expired disk cache entries")
        }
    }
    
    fun flush() {
        saveIndex()
    }
    
    fun getStats(): DiskCacheStats {
        val totalSize = index.values.sumOf { it.size }
        return DiskCacheStats(
            entryCount = index.size,
            totalSize = totalSize,
            oldestEntry = index.values.minOfOrNull { it.createdAt } ?: 0L,
            newestEntry = index.values.maxOfOrNull { it.createdAt } ?: 0L
        )
    }
    
    data class DiskCacheStats(
        val entryCount: Int,
        val totalSize: Long,
        val oldestEntry: Long,
        val newestEntry: Long
    )
    
    private fun loadIndex() {
        val indexFile = File(cacheDir, "index")
        if (!indexFile.exists()) return
        
        try {
            indexFile.readLines().forEach { line ->
                val parts = line.split("|")
                when {
                    parts.size >= 6 -> {
                        val key = parts[0]
                        val file = File(cacheDir, parts[1])
                        if (file.exists()) {
                            index[key] = DiskCacheEntry(
                                key = key,
                                file = file,
                                createdAt = parts[2].toLongOrNull() ?: 0L,
                                ttl = parts[3].toLongOrNull() ?: 0L,
                                size = parts[4].toLongOrNull() ?: file.length(),
                                typeName = parts[5]
                            )
                        }
                    }
                    parts.size >= 5 -> {
                        val key = parts[0]
                        val file = File(cacheDir, parts[1])
                        if (file.exists()) {
                            index[key] = DiskCacheEntry(
                                key = key,
                                file = file,
                                createdAt = parts[2].toLongOrNull() ?: 0L,
                                ttl = parts[3].toLongOrNull() ?: 0L,
                                size = parts[4].toLongOrNull() ?: file.length(),
                                typeName = ""
                            )
                        }
                    }
                    parts.size >= 4 -> {
                        val key = parts[0]
                        val file = File(cacheDir, parts[1])
                        if (file.exists()) {
                            index[key] = DiskCacheEntry(
                                key = key,
                                file = file,
                                createdAt = parts[2].toLongOrNull() ?: 0L,
                                ttl = parts[3].toLongOrNull() ?: 0L,
                                size = file.length(),
                                typeName = ""
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disk cache index", e)
        }
    }
    
    private fun saveIndex() {
        try {
            val indexFile = File(cacheDir, "index")
            val lines = index.values.map { entry ->
                "${entry.key}|${entry.file.name}|${entry.createdAt}|${entry.ttl}|${entry.size}|${entry.typeName}"
            }
            indexFile.writeText(lines.joinToString("\n"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save disk cache index", e)
        }
    }
    
    private fun sanitizeKey(key: String): String {
        return key.replace(":", "_").replace("/", "_").replace("\\", "_")
    }
    
    private fun serialize(value: Any): ByteArray {
        val typeName = value::class.qualifiedName ?: "Unknown"
        val typeNameBytes = typeName.toByteArray(Charsets.UTF_8)
        
        val jsonStr = try {
            @Suppress("UNCHECKED_CAST")
            val serializer = value::class.serializer() as KSerializer<Any>
            json.encodeToString(serializer, value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serialize with Kotlinx, falling back to toString: ${e.message}")
            when (value) {
                is String -> "\"$value\""
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> {
                    Log.e(TAG, "Cannot serialize type: $typeName")
                    return ByteArray(0)
                }
            }
        }
        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
        
        val maxCompressed = lz4Compressor.maxCompressedLength(jsonBytes.size)
        val compressed = ByteArray(maxCompressed)
        val compressedSize = lz4Compressor.compress(jsonBytes, 0, jsonBytes.size, compressed, 0)
        
        val baos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(baos)
        
        dos.writeShort(UnifiedSerializationConstants.SmartCache.MAGIC_HEADER.toInt())
        dos.writeByte(UnifiedSerializationConstants.SmartCache.FORMAT_VERSION.toInt())
        dos.writeInt(typeNameBytes.size)
        dos.write(typeNameBytes)
        dos.writeInt(jsonBytes.size)
        dos.write(compressed, 0, compressedSize)
        
        return baos.toByteArray()
    }
    
    private fun deserialize(bytes: ByteArray, expectedTypeName: String): Any? {
        if (bytes.size < 10) return null
        
        return try {
            val dis = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
            
            val magic = dis.readShort()
            if (magic != UnifiedSerializationConstants.SmartCache.MAGIC_HEADER) {
                val jsonStr = String(bytes, Charsets.UTF_8)
                return deserializeByTypeName(jsonStr, expectedTypeName)
            }
            
            dis.readByte()
            val typeNameLen = dis.readInt()
            val typeNameBytes = ByteArray(typeNameLen)
            dis.readFully(typeNameBytes)
            val typeName = String(typeNameBytes, Charsets.UTF_8)
            
            val originalSize = dis.readInt()
            val compressedData = bytes.copyOfRange(10 + typeNameLen, bytes.size)
            
            val decompressed = ByteArray(originalSize)
            lz4Decompressor.decompress(compressedData, 0, decompressed, 0, originalSize)
            
            val jsonStr = String(decompressed, Charsets.UTF_8)
            
            deserializeByTypeName(jsonStr, typeName.ifEmpty { expectedTypeName })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize: ${e.message}")
            null
        }
    }
    
    private fun deserializeByTypeName(jsonStr: String, typeName: String): Any? {
        return try {
            when {
                typeName.contains("GameData") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<GameData>(),
                        jsonStr
                    )
                }
                typeName.contains("Disciple") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<Disciple>(),
                        jsonStr
                    )
                }
                typeName.contains("Equipment") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<Equipment>(),
                        jsonStr
                    )
                }
                typeName.contains("Manual") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<Manual>(),
                        jsonStr
                    )
                }
                typeName.contains("Pill") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<Pill>(),
                        jsonStr
                    )
                }
                typeName.contains("Material") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<Material>(),
                        jsonStr
                    )
                }
                typeName.contains("Herb") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<Herb>(),
                        jsonStr
                    )
                }
                typeName.contains("Seed") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<Seed>(),
                        jsonStr
                    )
                }
                typeName.contains("BattleLog") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<BattleLog>(),
                        jsonStr
                    )
                }
                typeName.contains("GameEvent") -> {
                    json.decodeFromString(
                        kotlinx.serialization.serializer<GameEvent>(),
                        jsonStr
                    )
                }
                typeName == "kotlin.String" -> jsonStr.trim('"')
                typeName == "kotlin.Int" -> jsonStr.toIntOrNull()
                typeName == "kotlin.Long" -> jsonStr.toLongOrNull()
                typeName == "kotlin.Double" -> jsonStr.toDoubleOrNull()
                typeName == "kotlin.Boolean" -> jsonStr.toBooleanStrictOrNull()
                else -> {
                    Log.d(TAG, "Unknown type: $typeName, returning raw string")
                    jsonStr
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize type $typeName: ${e.message}")
            null
        }
    }
    
    fun shutdown() {
        saveIndex()
        Log.i(TAG, "DiskCacheLayer shutdown completed")
    }
    
    companion object {
        private const val TAG = "DiskCacheLayer"
    }
}
