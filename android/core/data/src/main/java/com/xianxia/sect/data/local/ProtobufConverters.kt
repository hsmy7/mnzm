package com.xianxia.sect.data.local

import androidx.room.TypeConverter
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FastDecompressor

/**
 * ## ProtobufConverters - 纯 Protobuf 的 Room TypeConverter
 *
 * ### 设计目标
 * 1. 完全替代 [ModelConverters] 中的 Gson JSON 字符串序列化
 * 2. 使用 **kotlinx.serialization.protobuf.ProtoBuf** 进行二进制序列化
 * 3. 通过 **Base64 编码** 将 ByteArray 转换为 String 存储（Room TEXT 列兼容）
 * 4. **KSP 安全**：无匿名内部类，避免 KSP NPE 问题
 * 5. **无 Gson 依赖**：不需要向后兼容
 *
 * ### 性能优势
 * - 二进制体积比 JSON 小 30-50%
 * - 序列化/反序列化速度提升 2-5x
 * - 类型安全：编译时检查 schema 一致性
 */
object ProtobufConverters {

    private const val TAG = "ProtobufConverters"

    /**
     * Room TypeConverter 用 ProtoBuf 实例 — encodeDefaults=false
     * 直接序列化领域对象（含 String?/Int? 可空字段），null 字段自动省略
     */
    internal val protoBuf = NullSafeProtoBuf.roomProtoBuf

    // ==================== Serializer 实例（避免重复创建）====================

    private val stringListSerializer: KSerializer<List<String>> = ListSerializer(String.serializer())
    private val stringStringMapSerializer: KSerializer<Map<String, String>> =
        MapSerializer(String.serializer(), String.serializer())
    private val stringIntMapSerializer: KSerializer<Map<String, Int>> =
        MapSerializer(String.serializer(), Int.serializer())
    private val intIntMapSerializer: KSerializer<Map<Int, Int>> =
        MapSerializer(Int.serializer(), Int.serializer())
    private val stringDoubleMapSerializer: KSerializer<Map<String, Double>> =
        MapSerializer(String.serializer(), Double.serializer())
    private val intBooleanMapSerializer: KSerializer<Map<Int, Boolean>> =
        MapSerializer(Int.serializer(), Boolean.serializer())
    private val intSetSerializer: KSerializer<Set<Int>> =
        SetSerializer(Int.serializer())
    private val stringSetSerializer: KSerializer<Set<String>> =
        SetSerializer(String.serializer())
    private val attackWarningListSerializer: KSerializer<List<AttackWarning>> =
        ListSerializer(AttackWarning.serializer())
    private val aiSectPersonalityMapSerializer: KSerializer<Map<String, AISectPersonality>> =
        MapSerializer(String.serializer(), AISectPersonality.serializer())

    // LZ4 BLOB 压缩（写入前压缩，读出时解压）
    private const val LZ4_COMPRESSION_THRESHOLD = 1024
    private const val BLOB_COMPRESSED_MARKER: Byte = 0x01

    private val lz4Compressor: LZ4Compressor by lazy {
        LZ4Factory.fastestInstance().fastCompressor()
    }

    private val lz4Decompressor: LZ4FastDecompressor by lazy {
        LZ4Factory.fastestInstance().fastDecompressor()
    }

    // ==================== 工具方法 ====================

    private fun bytesToBase64(data: ByteArray): String =
        android.util.Base64.encodeToString(data, android.util.Base64.DEFAULT)

    private fun base64ToBytes(encoded: String): ByteArray =
        try {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            ByteArray(0)
        }

    // 预检查：Collection/Map 超过此大小拒绝序列化，防止 protoBuf 分配 GB 级 byte array
    internal const val MAX_COLLECTION_SIZE = 100_000

    /**
     * 通用序列化方法：将任意 @Serializable 对象编码为 Base64 字符串
     * encodeDefaults=false 使可空字段为 null 时自动省略，符合 ProtoBuf proto3 语义
     */
    internal fun <T : Any> encodeToBase64(serializer: KSerializer<T>, value: T): String {
        if (isTooLarge(value, serializer.descriptor.serialName)) return ""
        try {
            val bytes = protoBuf.encodeToByteArray(serializer, value)
            return bytesToBase64(bytes)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during serialization of ${serializer.descriptor.serialName}!")
            return ""
        } catch (e: Throwable) {
            Log.e(TAG, "Protobuf serialization FAILED for ${serializer.descriptor.serialName}, data will be lost!", e)
            return ""
        }
    }

    /**
     * 可空类型序列化方法
     */
    internal fun <T : Any> encodeNullableToBase64(serializer: KSerializer<T>, value: T?): String {
        if (value == null) return ""
        if (isTooLarge(value, serializer.descriptor.serialName)) return ""
        try {
            val bytes = protoBuf.encodeToByteArray(serializer, value)
            return bytesToBase64(bytes)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during nullable serialization of ${serializer.descriptor.serialName}!")
            return ""
        } catch (e: Throwable) {
            Log.e(TAG, "Protobuf nullable serialization FAILED for ${serializer.descriptor.serialName}, data will be lost!", e)
            return ""
        }
    }

    /** 检查集合/Map 大小是否异常，防止序列化分配 GB 级内存 */
    private fun isTooLarge(value: Any, name: String): Boolean {
        val size = when (value) {
            is Collection<*> -> value.size
            is Map<*, *> -> value.size
            else -> -1
        }
        if (size > MAX_COLLECTION_SIZE) {
            Log.e(TAG, "CRITICAL: $name has $size entries (>$MAX_COLLECTION_SIZE), refusing to serialize to prevent OOM!")
            return true
        }
        return false
    }

    // 超过 50MB 打印警告（正常值远小于此），超过 500MB 硬拒绝防 OOM
    private const val DECODE_WARN_LENGTH =  50_000_000  // 50MB Base64 — 异常偏大，记录警告
    private const val DECODE_HARD_LIMIT   = 500_000_000  // 500MB Base64 — 必然异常，拒绝解码

    // BLOB 解压上限 500MB（对齐 DECODE_HARD_LIMIT）
    // 单个 chunk 正常 < 1MB（每 chunk 50~100 条实体），500MB 已有充足安全边际
    private const val MAX_DECOMPRESS_SIZE = 500_000_000
    // LZ4 最大解压比 25x（对齐 Tor Project compress.c 标准）
    // LZ4 实际压缩比 ~2.1x，25x 覆盖所有合法场景，超出必为损坏/炸弹
    private const val MAX_LZ4_DECOMPRESS_RATIO = 25

    internal fun <T> decodeFromBase64(serializer: KSerializer<T>, encoded: String, default: () -> T): T {
        if (encoded.isEmpty()) return default()
        if (encoded.length > DECODE_HARD_LIMIT) {
            Log.e(TAG, "Deserialization REJECTED for ${serializer.descriptor.serialName}: encoded length ${encoded.length} exceeds hard limit $DECODE_HARD_LIMIT, returning default to prevent OOM")
            return default()
        }
        if (encoded.length > DECODE_WARN_LENGTH) {
            Log.w(TAG, "Deserialization WARNING for ${serializer.descriptor.serialName}: encoded length ${encoded.length} exceeds warn threshold $DECODE_WARN_LENGTH, attempting decode")
        }
        try {
            val bytes = base64ToBytes(encoded)
            if (bytes.isEmpty()) return default()
            return protoBuf.decodeFromByteArray(serializer, bytes)
        } catch (e: Throwable) {
            Log.e(TAG, "Deserialization FAILED for ${serializer.descriptor.serialName}, returning default! Encoded length: ${encoded.length}", e)
            return default()
        }
    }

    // ==================== 基本类型转换器 ====================

    // --- List<String> ---

    @TypeConverter
    @JvmStatic
    fun fromStringList(value: List<String>): String =
        encodeToBase64(stringListSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toStringList(value: String): List<String> =
        decodeFromBase64(stringListSerializer, value) { emptyList() }

    // --- Map<String, String> ---

    @TypeConverter
    @JvmStatic
    fun fromStringMap(value: Map<String, String>): String =
        encodeToBase64(stringStringMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toStringMap(value: String): Map<String, String> =
        decodeFromBase64(stringStringMapSerializer, value) { emptyMap() }

    // --- Map<String, Int> ---

    @TypeConverter
    @JvmStatic
    fun fromIntMap(value: Map<String, Int>): String =
        encodeToBase64(stringIntMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toIntMap(value: String): Map<String, Int> =
        decodeFromBase64(stringIntMapSerializer, value) { emptyMap() }

    // --- Map<Int, Int> ---

    @TypeConverter
    @JvmStatic
    fun fromIntIntMap(value: Map<Int, Int>): String =
        encodeToBase64(intIntMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toIntIntMap(value: String): Map<Int, Int> =
        decodeFromBase64(intIntMapSerializer, value) { emptyMap() }

    // --- Map<String, Double> ---

    @TypeConverter
    @JvmStatic
    fun fromDoubleMap(value: Map<String, Double>): String =
        encodeToBase64(stringDoubleMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toDoubleMap(value: String): Map<String, Double> =
        decodeFromBase64(stringDoubleMapSerializer, value) { emptyMap() }

    // --- Map<Int, Boolean> ---

    @TypeConverter
    @JvmStatic
    fun fromIntBooleanMap(value: Map<Int, Boolean>): String =
        encodeToBase64(intBooleanMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toIntBooleanMap(value: String): Map<Int, Boolean> =
        decodeFromBase64(intBooleanMapSerializer, value) { emptyMap() }

    // --- Set<Int> ---

    @TypeConverter
    @JvmStatic
    fun fromIntSet(value: Set<Int>): String =
        encodeToBase64(intSetSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toIntSet(value: String): Set<Int> =
        decodeFromBase64(intSetSerializer, value) { emptySet() }

    // ═══════════════════════════════════════════════════════════
    // 增量编码 API（无 Base64，逐项/分批序列化）
    // ═══════════════════════════════════════════════════════════

    /**
     * Map<String, List<Disciple>> — 每个宗门独立一行
     * 用例：aiSectDisciples
     */
    suspend fun encodeDiscipleListMapIncremental(
        value: Map<String, List<Disciple>>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = ListSerializer(Disciple.serializer())
        for ((sectName, disciples) in value) {
            val bytes = encodeToBlobInternal(serializer, disciples)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, sectName), bytes))
        }
    }

    fun decodeDiscipleListMapFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): Map<String, List<Disciple>> {
        val serializer = ListSerializer(Disciple.serializer())
        val result = mutableMapOf<String, List<Disciple>>()
        for (row in rows) {
            val sectName = GameHeavyData.parseChunkKey(row.dataKey, keyPrefix) ?: continue
            result[sectName] = decodeFromBlobInternal(serializer, row.dataValue) { emptyList() }
        }
        return result
    }

    /**
     * Map<String, SectDetail> — 每个宗门独立一行
     * 用例：sectDetails
     */
    suspend fun encodeSectDetailMapIncremental(
        value: Map<String, SectDetail>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = SectDetail.serializer()
        for ((sectName, detail) in value) {
            val bytes = encodeToBlobInternal(serializer, detail)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, sectName), bytes))
        }
    }

    fun decodeSectDetailMapFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): Map<String, SectDetail> {
        val serializer = SectDetail.serializer()
        val result = mutableMapOf<String, SectDetail>()
        for (row in rows) {
            val sectName = GameHeavyData.parseChunkKey(row.dataKey, keyPrefix) ?: continue
            result[sectName] = decodeFromBlobInternal(serializer, row.dataValue) { SectDetail() }
        }
        return result
    }

    /**
     * Map<String, ExploredSectInfo> — 逐项
     * 用例：exploredSects
     */
    suspend fun encodeExploredSectInfoMapIncremental(
        value: Map<String, ExploredSectInfo>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = ExploredSectInfo.serializer()
        for ((sectName, info) in value) {
            val bytes = encodeToBlobInternal(serializer, info)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, sectName), bytes))
        }
    }

    fun decodeExploredSectInfoMapFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): Map<String, ExploredSectInfo> {
        val serializer = ExploredSectInfo.serializer()
        val result = mutableMapOf<String, ExploredSectInfo>()
        for (row in rows) {
            val sectName = GameHeavyData.parseChunkKey(row.dataKey, keyPrefix) ?: continue
            result[sectName] = decodeFromBlobInternal(serializer, row.dataValue) { ExploredSectInfo() }
        }
        return result
    }

    /**
     * Map<String, SectScoutInfo> — 逐项
     * 用例：scoutInfo
     */
    suspend fun encodeSectScoutInfoMapIncremental(
        value: Map<String, SectScoutInfo>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = SectScoutInfo.serializer()
        for ((sectName, info) in value) {
            val bytes = encodeToBlobInternal(serializer, info)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, sectName), bytes))
        }
    }

    fun decodeSectScoutInfoMapFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): Map<String, SectScoutInfo> {
        val serializer = SectScoutInfo.serializer()
        val result = mutableMapOf<String, SectScoutInfo>()
        for (row in rows) {
            val sectName = GameHeavyData.parseChunkKey(row.dataKey, keyPrefix) ?: continue
            result[sectName] = decodeFromBlobInternal(serializer, row.dataValue) { SectScoutInfo() }
        }
        return result
    }

    /**
     * Map<String, List<ManualProficiencyData>> — 逐项
     * 用例：manualProficiencies
     */
    suspend fun encodeManualProficiencyMapIncremental(
        value: Map<String, List<ManualProficiencyData>>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = ListSerializer(ManualProficiencyData.serializer())
        for ((key, proficiencies) in value) {
            val bytes = encodeToBlobInternal(serializer, proficiencies)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, key), bytes))
        }
    }

    fun decodeManualProficiencyMapFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): Map<String, List<ManualProficiencyData>> {
        val serializer = ListSerializer(ManualProficiencyData.serializer())
        val result = mutableMapOf<String, List<ManualProficiencyData>>()
        for (row in rows) {
            val key = GameHeavyData.parseChunkKey(row.dataKey, keyPrefix) ?: continue
            result[key] = decodeFromBlobInternal(serializer, row.dataValue) { emptyList() }
        }
        return result
    }

    /**
     * List<Disciple> — 每 100 条一批
     * 用例：recruitList
     */
    suspend fun encodeDiscipleListIncremental(
        value: List<Disciple>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = ListSerializer(Disciple.serializer())
        value.chunked(100).forEachIndexed { index, batch ->
            val bytes = encodeToBlobInternal(serializer, batch)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, index.toString()), bytes))
        }
    }

    fun decodeDiscipleListFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): List<Disciple> {
        val serializer = ListSerializer(Disciple.serializer())
        val result = mutableListOf<Disciple>()
        val relevant = rows.filter { it.dataKey.startsWith("$keyPrefix/") }
            .sortedBy { it.dataKey }
        for (row in relevant) {
            result.addAll(decodeFromBlobInternal(serializer, row.dataValue) { emptyList() })
        }
        return result
    }

    /**
     * List<WorldSect> — 每 50 条一批
     * 用例：worldMapSects
     */
    suspend fun encodeWorldSectListIncremental(
        value: List<WorldSect>,
        slotId: Int,
        keyPrefix: String,
        writer: suspend (List<GameHeavyData>) -> Unit
    ) {
        val serializer = ListSerializer(WorldSect.serializer())
        value.chunked(50).forEachIndexed { index, batch ->
            val bytes = encodeToBlobInternal(serializer, batch)
            writer(GameHeavyData.chunk(slotId, GameHeavyData.chunkKey(keyPrefix, index.toString()), bytes))
        }
    }

    fun decodeWorldSectListFromRows(
        rows: List<GameHeavyData>,
        keyPrefix: String
    ): List<WorldSect> {
        val serializer = ListSerializer(WorldSect.serializer())
        val result = mutableListOf<WorldSect>()
        val relevant = rows.filter { it.dataKey.startsWith("$keyPrefix/") }
            .sortedBy { it.dataKey }
        for (row in relevant) {
            result.addAll(decodeFromBlobInternal(serializer, row.dataValue) { emptyList() })
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════
    // 底层 BLOB 编解码（无 Base64）
    // ═══════════════════════════════════════════════════════════

    private fun <T : Any> encodeToBlobInternal(serializer: KSerializer<T>, value: T): ByteArray {
        try {
            val sizeCheck = when (value) {
                is Collection<*> -> value.size
                is Map<*, *> -> value.size
                else -> -1
            }
            if (sizeCheck > MAX_COLLECTION_SIZE) {
                Log.e(TAG, "CRITICAL: BLOB encode ${serializer.descriptor.serialName} has $sizeCheck entries, refusing to prevent OOM!")
                return ByteArray(0)
            }
            val bytes = protoBuf.encodeToByteArray(serializer, value)
            if (bytes.size >= LZ4_COMPRESSION_THRESHOLD) {
                val maxCompressedLength = lz4Compressor.maxCompressedLength(bytes.size)
                val compressed = ByteArray(maxCompressedLength)
                val compressedLength = lz4Compressor.compress(bytes, 0, bytes.size, compressed, 0)
                if (compressedLength < bytes.size) {
                    // 0x01 marker + 4 bytes original size + LZ4 compressed data
                    val result = ByteArray(1 + 4 + compressedLength)
                    result[0] = BLOB_COMPRESSED_MARKER
                    result[1] = (bytes.size shr 24).toByte()
                    result[2] = (bytes.size shr 16).toByte()
                    result[3] = (bytes.size shr 8).toByte()
                    result[4] = bytes.size.toByte()
                    System.arraycopy(compressed, 0, result, 5, compressedLength)
                    return result
                }
            }
            return bytes
        } catch (e: Throwable) {
            Log.e(TAG, "Protobuf BLOB encode FAILED for ${serializer.descriptor.serialName}", e)
            return ByteArray(0)
        }
    }

    private fun <T> decodeFromBlobInternal(
        serializer: KSerializer<T>,
        data: ByteArray,
        default: () -> T
    ): T {
        if (data.isEmpty()) return default()

        // LZ4 压缩数据：0x01 标记 + 4 字节原始大小 + 压缩数据
        if (data.size > 5 && data[0] == BLOB_COMPRESSED_MARKER) {
            try {
                val originalSize = ((data[1].toInt() and 0xFF) shl 24) or
                                  ((data[2].toInt() and 0xFF) shl 16) or
                                  ((data[3].toInt() and 0xFF) shl 8) or
                                  (data[4].toInt() and 0xFF)
                val compressedSize = data.size - 5

                // L1: 分配前校验 — 防止损坏 header 导致 GB 级分配
                if (originalSize <= 0 ||
                    originalSize > MAX_DECOMPRESS_SIZE ||
                    (compressedSize > 0 &&
                     originalSize / compressedSize > MAX_LZ4_DECOMPRESS_RATIO)) {
                    Log.e(TAG,
                        "LZ4 header REJECTED for ${serializer.descriptor.serialName}: " +
                        "originalSize=$originalSize, compressedSize=$compressedSize, " +
                        "max=$MAX_DECOMPRESS_SIZE, maxRatio=$MAX_LZ4_DECOMPRESS_RATIO. " +
                        "Data may be corrupted, returning default"
                    )
                    return default()
                }

                val compressedData = data.copyOfRange(5, data.size)
                val decompressed = ByteArray(originalSize)
                lz4Decompressor.decompress(compressedData, 0, decompressed, 0, originalSize)
                return protoBuf.decodeFromByteArray(serializer, decompressed)
            } catch (e: OutOfMemoryError) {
                // L2: OOM 兜底 — 极端情况下的最后防线
                Log.e(TAG,
                    "OOM during LZ4 decompress for ${serializer.descriptor.serialName}, " +
                    "returning default"
                )
                return default()
            } catch (e: Exception) {
                Log.w(TAG, "LZ4 decompression failed for ${serializer.descriptor.serialName}, falling back to direct decode", e)
            }
        }

        // 直接 protobuf 解码
        try {
            return protoBuf.decodeFromByteArray(serializer, data)
        } catch (e: OutOfMemoryError) {
            // L2: OOM 兜底 — 直接解码路径的异常保护
            Log.e(TAG,
                "OOM during BLOB decode for ${serializer.descriptor.serialName}, " +
                "dataSize=${data.size}, returning default"
            )
            return default()
        } catch (e: Exception) {
            Log.e(TAG,
                "BLOB decode FAILED for ${serializer.descriptor.serialName}, " +
                "data.size=${data.size}, firstByte=${data.getOrNull(0)}, " +
                "err=${e.message?.take(80)}"
            )
            return default()
        }
    }

    // ==================== v4.0.20: AI宗门进攻系统新增类型 ====================

    @TypeConverter
    @JvmStatic
    fun fromAttackWarningList(value: List<AttackWarning>): String =
        encodeToBase64(attackWarningListSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toAttackWarningList(value: String): List<AttackWarning> =
        decodeFromBase64(attackWarningListSerializer, value) { emptyList() }

    @TypeConverter
    @JvmStatic
    fun fromAISectPersonalityMap(value: Map<String, AISectPersonality>): String =
        encodeToBase64(aiSectPersonalityMapSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toAISectPersonalityMap(value: String): Map<String, AISectPersonality> =
        decodeFromBase64(aiSectPersonalityMapSerializer, value) { emptyMap() }

    @TypeConverter
    @JvmStatic
    fun fromStringSet(value: Set<String>): String =
        encodeToBase64(stringSetSerializer, value)

    @TypeConverter
    @JvmStatic
    fun toStringSet(value: String): Set<String> =
        decodeFromBase64(stringSetSerializer, value) { emptySet() }
}
