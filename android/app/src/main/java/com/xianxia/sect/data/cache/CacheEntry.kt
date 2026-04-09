package com.xianxia.sect.data.cache

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// ==================== Cache Serializer Interface ====================

interface CacheSerializer<T : Any> {
    fun serialize(value: T): ByteArray
    fun deserialize(data: ByteArray): T?
}

// ==================== SWR Policy ====================

data class SwrPolicy(
    val ttlMs: Long,
    val staleWhileRevalidateMs: Long = (ttlMs * 0.25).toLong()
) {
    val totalWindowMs: Long get() = ttlMs + staleWhileRevalidateMs

    fun isStale(createdAt: Long): Boolean {
        return System.currentTimeMillis() - createdAt > ttlMs
    }

    fun isExpired(createdAt: Long): Boolean {
        return System.currentTimeMillis() - createdAt > totalWindowMs
    }

    /**
     * 获取 SWR 状态
     *
     * 根据条目的创建时间和当前时间判断数据的新鲜度状态：
     * - FRESH: 数据在 TTL 内，完全新鲜
     * - STALE: 数据超过 TTL 但在宽限期内（stale-while-revalidate 窗口）
     * - EXPIRED: 数据完全过期，超过 TTL + 宽限期
     */
    fun getState(createdAt: Long): SwrState {
        val age = System.currentTimeMillis() - createdAt
        return when {
            age > totalWindowMs -> SwrState.EXPIRED
            age > ttlMs -> SwrState.STALE
            else -> SwrState.FRESH
        }
    }
}

/**
 * SWR 状态枚举
 */
enum class SwrState {
    FRESH,      // 数据新鲜，直接返回
    STALE,      // 数据过期但在宽限期内，返回旧值 + 异步刷新
    EXPIRED     // 完全过期，需要删除
}

// ==================== CacheEntry (v2) ====================

const val CACHE_ENTRY_MAGIC = 0xCA53E01
const val CACHE_ENTRY_VERSION = 2

val PROTOBUF_TYPE_TOKENS = setOf(
    "GameData", "Disciple", "Equipment", "Manual", "Pill",
    "Material", "Herb", "Seed", "BattleLog", "GameEvent",
    "ExplorationTeam", "BuildingSlot", "Building"
)

data class CacheEntry(
    val value: Any?,
    val size: Int,
    val typeToken: String = value?.let { it::class.qualifiedName ?: "unknown" } ?: "null",
    val version: Int = CACHE_ENTRY_VERSION,
    val swrPolicy: SwrPolicy = SwrPolicy(ttlMs = 3600_000L),
    val createdAt: Long = System.currentTimeMillis(),
    val ttlMs: Long = swrPolicy.ttlMs
) {
    // 注意: isDirty 字段已删除，脏数据管理由 DirtyTracker 独立承担

    private val _accessCount = AtomicLong(0)
    private val _lastAccessedAt = AtomicLong(createdAt)

    val accessCount: Long get() = _accessCount.get()
    val lastAccessedAt: Long get() = _lastAccessedAt.get()

    fun isExpired(): Boolean {
        return swrPolicy.isExpired(createdAt)
    }

    fun isStale(): Boolean {
        return swrPolicy.isStale(createdAt)
    }

    fun touch(): CacheEntry {
        _accessCount.incrementAndGet()
        _lastAccessedAt.set(System.currentTimeMillis())
        return this
    }

    fun idleTime(): Long = System.currentTimeMillis() - lastAccessedAt

    // ========== New serialize (v2) ==========

    internal fun serializeV2(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Magic header (4 bytes int)
        dos.writeInt(CACHE_ENTRY_MAGIC)
        // Version (4 bytes int)
        dos.writeInt(version)
        // typeToken (UTF string, length-prefixed)
        val typeBytes = typeToken.toByteArray(StandardCharsets.UTF_8)
        dos.writeInt(typeBytes.size)
        dos.write(typeBytes)
        // createdAt (8 bytes long)
        dos.writeLong(createdAt)
        // ttlMs (8 bytes long)
        dos.writeLong(ttlMs)
        // swrPolicy.staleWhileRevalidateMs (8 bytes long)
        dos.writeLong(swrPolicy.staleWhileRevalidateMs)
        // size (4 bytes int)
        dos.writeInt(size)
        // null flag (1 byte): 0x00=null, 0x01=non-null
        if (value == null) {
            dos.writeByte(0x00)
        } else {
            dos.writeByte(0x01)
            // payload length (4 bytes int) + payload bytes
            val payload = encodePayload(value, typeToken)
            dos.writeInt(payload.size)
            dos.write(payload)
        }

        dos.flush()
        return baos.toByteArray()
    }

    companion object {

        // ========== New create (v2) ==========

        fun create(
            value: Any?,
            ttlMs: Long = 3600_000L,
            staleWhileRevalidateMs: Long? = null
        ): CacheEntry {
            val swr = SwrPolicy(
                ttlMs = ttlMs,
                staleWhileRevalidateMs = staleWhileRevalidateMs ?: (ttlMs * 0.25).toLong()
            )
            val size = estimateSize(value)
            return CacheEntry(
                value = value,
                size = size,
                typeToken = value?.let { it::class.qualifiedName ?: "unknown" } ?: "null",
                version = CACHE_ENTRY_VERSION,
                swrPolicy = swr,
                ttlMs = ttlMs
            )
        }

        // ========== New deserialize (v2) ==========

        internal fun deserializeV2(data: ByteArray): CacheEntry? {
            return try {
                val bais = ByteArrayInputStream(data)
                val dis = DataInputStream(bais)

                // Validate magic header
                val magic = dis.readInt()
                if (magic != CACHE_ENTRY_MAGIC) {
                    // Not a v2 format, fall back to legacy deserialization for backward compat
                    return deserializeLegacy(data)
                }

                // Version
                val ver = dis.readInt()
                // typeToken
                val typeLen = dis.readInt()
                val typeBytes = ByteArray(typeLen)
                dis.readFully(typeBytes)
                val typeToken = String(typeBytes, StandardCharsets.UTF_8)
                // createdAt
                val createdAt = dis.readLong()
                // ttlMs
                val ttlMs = dis.readLong()
                // staleWhileRevalidateMs
                val swrMs = dis.readLong()
                // size
                val size = dis.readInt()
                // null flag
                val nullFlag = dis.readByte()

                val swrPolicy = SwrPolicy(ttlMs = ttlMs, staleWhileRevalidateMs = swrMs)

                val value: Any? = if (nullFlag == 0x00.toByte()) {
                    null
                } else {
                    val payloadLen = dis.readInt()
                    val payload = ByteArray(payloadLen)
                    dis.readFully(payload)
                    decodePayload(payload, typeToken)
                }

                dis.close()

                CacheEntry(
                    value = value,
                    size = size,
                    typeToken = typeToken,
                    version = ver,
                    swrPolicy = swrPolicy,
                    createdAt = createdAt,
                    ttlMs = ttlMs
                )
            } catch (e: Exception) {
                // If v2 parsing fails entirely, try legacy as last resort
                try {
                    deserializeLegacy(data)
                } catch (_: Exception) {
                    null
                }
            }
        }

        // ---------- Internal helpers ----------

        /**
         * Legacy deserialization for pre-v2 format (no magic header).
         * Wraps raw bytes as the value.
         */
        internal fun deserializeLegacy(data: ByteArray): CacheEntry? {
            return try {
                CacheEntry(
                    value = data,
                    size = data.size,
                    typeToken = "[kotlin.ByteArray]",
                    version = 1,
                    swrPolicy = SwrPolicy(ttlMs = 3600_000L),
                    ttlMs = 3600_000L
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Encode a value to byte payload based on its type.
         */
        internal fun encodePayload(value: Any, typeToken: String): ByteArray {
            return when (value) {
                is ByteArray -> value
                is String -> value.toByteArray(StandardCharsets.UTF_8)
                is CompressedData -> {
                    // CompressedData: [algorithm_len(4)] + algorithm + [originalSize(4)] + data
                    val baos = ByteArrayOutputStream()
                    val dos = DataOutputStream(baos)
                    val algoBytes = value.algorithm.toByteArray(StandardCharsets.UTF_8)
                    dos.writeInt(algoBytes.size)
                    dos.write(algoBytes)
                    dos.writeInt(value.originalSize)
                    dos.write(value.data)
                    dos.flush()
                    baos.toByteArray()
                }
                else -> {
                    // For protobuf-eligible types or unknown types, use toString as fallback
                    // Real protobuf serialization would require the actual serializer instance;
                    // here we preserve the string representation with a type marker so that
                    // decodePayload can at least return something meaningful.
                    val strValue = value.toString()
                    strValue.toByteArray(StandardCharsets.UTF_8)
                }
            }
        }

        /**
         * Decode a byte payload back to an object based on typeToken.
         */
        @Suppress("UNCHECKED_CAST")
        internal fun <T> decodePayload(payload: ByteArray, typeToken: String): T {
            return when {
                typeToken == "[kotlin.ByteArray]" || typeToken == "kotlin.ByteArray" -> payload as T
                typeToken == "kotlin.String" || typeToken == "java.lang.String" ->
                    String(payload, StandardCharsets.UTF_8) as T
                typeToken.endsWith(".CompressedData") || typeToken == "CompressedData" -> {
                    val bais = ByteArrayInputStream(payload)
                    val dis = DataInputStream(bais)
                    val algoLen = dis.readInt()
                    val algoBytes = ByteArray(algoLen)
                    dis.readFully(algoBytes)
                    val algorithm = String(algoBytes, StandardCharsets.UTF_8)
                    val originalSize = dis.readInt()
                    val dataBytes = ByteArray(payload.size - 4 - algoLen - 4)
                    dis.readFully(dataBytes)
                    dis.close()
                    CompressedData(
                        data = dataBytes,
                        originalSize = originalSize,
                        algorithm = algorithm
                    ) as T
                }
                PROTOBUF_TYPE_TOKENS.any { typeToken.contains(it) } -> {
                    // ProtoBuf-eligible types: attempt UTF-8 decode as string representation.
                    // In production, this would delegate to the actual ProtoBuf serializer
                    // registered for the domain model class. The typeToken preserves enough
                    // information to route to the correct deserializer.
                    String(payload, StandardCharsets.UTF_8) as T
                }
                else -> {
                    // Fallback: UTF-8 decode as String
                    String(payload, StandardCharsets.UTF_8) as T
                }
            }
        }

        /**
         * More precise size estimation per type.
         */
        internal fun estimateSize(value: Any?): Int {
            if (value == null) return 0
            return when (value) {
                is ByteArray -> value.size
                is String -> value.toByteArray(StandardCharsets.UTF_8).size
                is CompressedData -> value.data.size + value.algorithm.length + 12
                is List<*> -> {
                    var total = 16 // list overhead
                    value.forEach { item ->
                        total += estimateSize(item) + 8 // element ref overhead
                    }
                    total
                }
                is Map<*, *> -> {
                    var total = 32 // map overhead
                    value.entries.forEach { (k, v) ->
                        total += estimateSize(k) + estimateSize(v) + 16
                    }
                    total
                }
                is Int -> 4
                is Long -> 8
                is Float -> 4
                is Double -> 8
                is Boolean -> 1
                is Char -> 2
                is Short -> 2
                is Byte -> 1
                else -> {
                    // For complex objects, use className hash + toString size heuristic
                    val className = value::class.qualifiedName ?: "unknown"
                    val strRep = value.toString()
                    (className.length + strRep.length) * 2 + 64
                }
            }
        }
    }

    // ========== Instance-level estimateSize (delegates to companion) ==========

    fun estimateActualSize(): Int = estimateSize(value)
}
// ==================== CompressedData ====================

data class CompressedData(
    val data: ByteArray,
    val originalSize: Int,
    val algorithm: String = "GZIP"
) {
    fun toStorageFormat(): ByteArray = data

    /**
     * Compress raw bytes using GZIP.
     */
    fun compress(raw: ByteArray): CompressedData {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzos ->
            gzos.write(raw)
        }
        return CompressedData(
            data = baos.toByteArray(),
            originalSize = raw.size,
            algorithm = "GZIP"
        )
    }

    /**
     * Decompress back to raw bytes.
     */
    fun decompress(): ByteArray {
        return if (algorithm == "GZIP") {
            ByteArrayInputStream(data).use { bais ->
                GZIPInputStream(bais).use { gzis ->
                    gzis.readBytes()
                }
            }
        } else {
            data
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CompressedData
        return data.contentEquals(other.data) && originalSize == other.originalSize && algorithm == other.algorithm
    }

    override fun hashCode(): Int = 31 * data.contentHashCode() + originalSize.hashCode() + algorithm.hashCode()
}
