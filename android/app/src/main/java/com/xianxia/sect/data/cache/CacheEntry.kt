package com.xianxia.sect.data.cache

import android.util.Log
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

@Serializable
data class CacheEntry(
    @SerialName("value")
    val value: String? = null,
    @SerialName("createdAt")
    val createdAt: Long,
    @SerialName("lastAccessedAt")
    val lastAccessedAt: Long = createdAt,
    @SerialName("ttl")
    val ttl: Long,
    @SerialName("size")
    val size: Int = 0,
    @SerialName("valueType")
    val valueType: String? = null,
    @SerialName("accessCount")
    val accessCount: Int = 0,
    @SerialName("valueJson")
    val valueJson: String? = null
) {
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - createdAt > ttl
    }

    fun remainingTtl(): Long {
        return (ttl - (System.currentTimeMillis() - createdAt)).coerceAtLeast(0)
    }

    fun age(): Long {
        return System.currentTimeMillis() - createdAt
    }

    fun idleTime(): Long {
        return System.currentTimeMillis() - lastAccessedAt
    }

    fun touch(): CacheEntry {
        return copy(
            lastAccessedAt = System.currentTimeMillis(),
            accessCount = accessCount + 1
        )
    }

    fun serialize(): ByteArray {
        return CacheEntrySerializer.serialize(this)
    }
    
    fun <T : Any> getTypedValue(clazz: Class<T>, json: Json = Json): T? {
        if (valueJson == null || valueType == null) return null
        return try {
            @OptIn(InternalSerializationApi::class)
            @Suppress("UNCHECKED_CAST")
            val serializer = clazz.kotlin.serializer() as KSerializer<T>
            json.decodeFromString(serializer, valueJson)
        } catch (e: Exception) {
            Log.w("CacheEntry", "Failed to deserialize typed value", e)
            null
        }
    }

    companion object {
        private const val TAG = "CacheEntry"

        fun deserialize(bytes: ByteArray): CacheEntry? {
            return CacheEntrySerializer.deserialize(bytes)
        }

        fun create(value: Any?, ttl: Long = CacheKey.DEFAULT_TTL, json: Json = Json): CacheEntry {
            val now = System.currentTimeMillis()
            val (valueJson, valueType) = if (value != null) {
                try {
                    @OptIn(InternalSerializationApi::class)
                    @Suppress("UNCHECKED_CAST")
                    val serializer = value::class.serializer() as KSerializer<Any>
                    json.encodeToString(serializer, value) to value::class.qualifiedName
                } catch (e: Exception) {
                    value.toString() to value::class.qualifiedName
                }
            } else {
                null to null
            }
            return CacheEntry(
                value = value?.toString(),
                createdAt = now,
                lastAccessedAt = now,
                ttl = ttl,
                size = estimateSize(value),
                valueType = valueType,
                accessCount = 0,
                valueJson = valueJson
            )
        }

        private fun estimateSize(value: Any?): Int {
            if (value == null) return 0
            return when (value) {
                is String -> value.length * 2
                is ByteArray -> value.size
                is List<*> -> value.size * 100
                is Map<*, *> -> value.size * 100
                else -> 100
            }
        }
    }
}

object CacheEntrySerializer {
    private const val TAG = "CacheEntrySerializer"
    private const val VERSION: Byte = 2
    private const val MAGIC_HEADER: Short = 0x4345
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val allowedTypes: Set<String> = setOf(
        "java.lang.String",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Double",
        "java.lang.Float",
        "java.lang.Boolean",
        "java.util.ArrayList",
        "java.util.LinkedHashMap",
        "java.util.HashMap",
        "kotlin.collections.ArrayList",
        "kotlin.collections.LinkedHashMap",
        "kotlin.collections.HashMap",
        "com.xianxia.sect.data.model.SaveData",
        "com.xianxia.sect.core.model.GameData",
        "com.xianxia.sect.core.model.Disciple",
        "com.xianxia.sect.core.model.Equipment",
        "com.xianxia.sect.core.model.Manual",
        "com.xianxia.sect.core.model.Pill",
        "com.xianxia.sect.core.model.Material",
        "com.xianxia.sect.core.model.Herb",
        "com.xianxia.sect.core.model.Seed",
        "com.xianxia.sect.core.model.GameEvent",
        "com.xianxia.sect.core.model.BattleLog",
        "com.xianxia.sect.core.model.WorldSect",
        "com.xianxia.sect.core.model.Alliance"
    )

    fun serialize(entry: CacheEntry): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        dos.writeShort(MAGIC_HEADER.toInt())
        dos.writeByte(VERSION.toInt())
        dos.writeLong(entry.createdAt)
        dos.writeLong(entry.lastAccessedAt)
        dos.writeLong(entry.ttl)
        dos.writeInt(entry.size)
        dos.writeInt(entry.accessCount)
        
        val valueTypeBytes = (entry.valueType ?: "").toByteArray(Charsets.UTF_8)
        dos.writeInt(valueTypeBytes.size)
        dos.write(valueTypeBytes)
        
        val valueJsonBytes = (entry.valueJson ?: "").toByteArray(Charsets.UTF_8)
        dos.writeInt(valueJsonBytes.size)
        dos.write(valueJsonBytes)
        
        dos.flush()
        return baos.toByteArray()
    }

    fun deserialize(bytes: ByteArray): CacheEntry? {
        if (bytes.size < 30) {
            Log.w(TAG, "Invalid cache entry: too short")
            return null
        }
        
        return try {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            
            val magic = dis.readShort()
            if (magic != MAGIC_HEADER) {
                Log.w(TAG, "Invalid cache entry: wrong magic header")
                return tryDeserializeLegacy(bytes)
            }
            
            val version = dis.readByte()
            if (version > VERSION) {
                Log.w(TAG, "Cache entry version $version is newer than supported $VERSION")
                return null
            }
            
            val createdAt = dis.readLong()
            val lastAccessedAt = dis.readLong()
            val ttl = dis.readLong()
            val size = dis.readInt()
            val accessCount = dis.readInt()
            
            val valueTypeLen = dis.readInt()
            val valueType = if (valueTypeLen > 0) {
                val typeBytes = ByteArray(valueTypeLen)
                dis.readFully(typeBytes)
                String(typeBytes, Charsets.UTF_8)
            } else null
            
            val valueJsonLen = dis.readInt()
            val valueJson = if (valueJsonLen > 0) {
                val jsonBytes = ByteArray(valueJsonLen)
                dis.readFully(jsonBytes)
                String(jsonBytes, Charsets.UTF_8)
            } else null
            
            if (valueType != null && !isTypeAllowed(valueType)) {
                Log.w(TAG, "Blocked deserialization of disallowed type: $valueType")
                return CacheEntry(null, createdAt, lastAccessedAt, ttl, size, valueType, accessCount)
            }
            
            CacheEntry(
                value = null,
                createdAt = createdAt,
                lastAccessedAt = lastAccessedAt,
                ttl = ttl,
                size = size,
                valueType = valueType,
                accessCount = accessCount,
                valueJson = valueJson
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize cache entry", e)
            null
        }
    }
    
    private fun isTypeAllowed(typeName: String): Boolean {
        if (allowedTypes.contains(typeName)) return true
        if (typeName.startsWith("kotlin.") || typeName.startsWith("java.lang.")) return true
        if (typeName.startsWith("com.xianxia.sect.core.model.")) return true
        if (typeName.startsWith("com.xianxia.sect.data.model.")) return true
        if (typeName.startsWith("[")) return isTypeAllowed(typeName.substring(1))
        return false
    }
    
    private fun tryDeserializeLegacy(bytes: ByteArray): CacheEntry? {
        Log.w(TAG, "Legacy format detected, returning null to trigger cache miss")
        return null
    }
}
