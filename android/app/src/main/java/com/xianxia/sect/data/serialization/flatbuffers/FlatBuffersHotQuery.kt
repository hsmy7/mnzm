package com.xianxia.sect.data.serialization.flatbuffers

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ## FlatBuffersHotQuery - 存档热查询路径
 *
 * 利用 FlatBuffers 的 zero-copy random access 特性实现存档选择界面的快速预览加载。
 * 无需完整反序列化整个存档对象，仅读取构建 SlotPreview 所需的少量字段。
 *
 * ### 设计目标
 * - 单个槽位预览 < 5ms（对比 Protobuf 全量反序列化 ~200ms）
 * - 5 个槽位批量预览 < 20ms
 * - 内存分配最小化（避免创建完整 SaveData 对象）
 * - 双格式兼容：FlatBuffers / Protobuf 自动检测与回退
 *
 * ### 双格式兼容策略
 * 1. **FlatBuffers 路径**（优先）：
 *    - 检测 magic number / buffer identifier 确认 FB 格式
 *    - 利用 Table offset 直接访问 vtable，时间复杂度 O(1)
 *
 * 2. **Protobuf 回退路径**：
 *    - 非 FB 格式时，使用手动 wire format 解析
 *    - 仅解析 SaveDataProto 的前几个关键字段，跳过后续数据
 *    - 避免完整的 ProtoBuf 反序列化开销
 *
 * ### 架构位置
 * 此工具类位于序列化层与 UI 层之间，
 * 供存档选择界面（SaveSlotListScreen）直接调用获取预览信息。
 *
 * ### 使用示例
 * ```kotlin
 * // 单个槽位快速预览
 * val preview = FlatBuffersHotQuery.getSlotPreview(saveBytes)
 * preview?.let {
 *     println("门派: ${it.sectName}, 年月: ${it.gameYear}/${it.gameMonth}")
 * }
 *
 * // 批量预览所有槽位
 * val previews = FlatBuffersHotQuery.getAllSlotPreviews(slotFileMap)
 * previews.forEach { (slot, info) ->
 *     println("Slot $slot: ${info.sectName} - ${info.discipleCount} 弟子")
 * }
 * ```
 */
object FlatBuffersHotQuery {

    private const val TAG = "FBHotQuery"

    // ==================== 性能基准常量 ====================

    /** 单个槽位预览的目标耗时上限（毫秒） */
    private const val TARGET_SINGLE_SLOT_MS = 5L

    /** 批量槽位预览的目标耗时上限（毫秒，5 个槽位） */
    private const val TARGET_BATCH_SLOTS_MS = 20L

    // ==================== Protobuf Wire Format 常量 ====================
    // 参考 save_data.proto 中的字段编号定义

    /** SaveDataProto.version 字段编号 = 1 (string) */
    private const val PB_SAVE_VERSION_FIELD = 1

    /** SaveDataProto.timestamp 字段编号 = 2 (int64) */
    private const val PB_SAVE_TIMESTAMP_FIELD = 2

    /** SaveDataProto.core 字段编号 = 4 (CoreModuleProto message) */
    private const val PB_SAVE_CORE_FIELD = 4

    /** SaveDataProto.disciples 字段编号 = 5 (DiscipleModuleProto message) */
    private const val PB_SAVE_DISCIPLES_FIELD = 5

    /** CoreModuleProto.sect_name 字段编号 = 2 (string) */
    private const val PB_CORE_SECT_NAME_FIELD = 2

    /** CoreModuleProto.game_year 字段编号 = 4 (int32) */
    private const val PB_CORE_GAME_YEAR_FIELD = 4

    /** CoreModuleProto.game_month 字段编号 = 5 (int32) */
    private const val PB_CORE_GAME_MONTH_FIELD = 5

    /** CoreModuleProto.spirit_stones 字段编号 = 7 (int64) */
    private const val PB_CORE_SPIRIT_STONES_FIELD = 7

    /** DiscipleModuleProto.disciples 字段编号 = 4 (repeated DiscipleProto) */
    private const val PB_DISCIPLES_LIST_FIELD = 4

    // ==================== Wire Format 类型常量 ====================

    /** Protobuf varint 类型 (field number << 3 | 0) */
    private const val WIRE_TYPE_VARINT = 0

    /** Protobuf length-delimited 类型 (field number << 3 | 2)，用于 string/sub-message */
    private const val WIRE_TYPE_LENGTH_DELIMITED = 2

    /**
     * 快速读取存档预览信息，无需完整反序列化
     *
     * 利用 FlatBuffers 的 table offset 直接访问（O(1)），
     * 或对 Protobuf 格式使用手动 wire format 解析（仅读前几个字段）。
     *
     * @param saveFileBytes 存档文件的原始字节数组（可能经过压缩层解压后的裸数据）
     * @return SlotPreview 预览信息，解析失败返回 null
     */
    fun getSlotPreview(saveFileBytes: ByteArray): SlotPreview? {
        if (saveFileBytes.isEmpty()) return null

        val startTime = System.nanoTime()

        return try {
            when {
                isValidFlatBufferFormat(saveFileBytes) -> {
                    parseFlatBuffersPreview(saveFileBytes)
                }
                else -> {
                    parseProtobufPreview(saveFileBytes)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse slot preview", e)
            null
        } finally {
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            if (elapsedMs > TARGET_SINGLE_SLOT_MS) {
                Log.w(
                    TAG,
                    "getSlotPreview took ${elapsedMs}ms (target < ${TARGET_SINGLE_SLOT_MS}ms)"
                )
            }
        }
    }

    /**
     * 批量获取所有槽位的预览信息
     *
     * 内部优化：对每个槽位独立解析，避免相互阻塞。
     * 未来可考虑并行化（Coroutine dispatchers.IO）进一步优化。
     *
     * @param slotFiles 槽位编号 -> 存档文件字节数组的映射
     * @return 槽位编号 -> 预览信息的映射（解析失败的槽位不包含在结果中）
     */
    fun getAllSlotPreviews(slotFiles: Map<Int, ByteArray>): Map<Int, SlotPreview> {
        if (slotFiles.isEmpty()) return emptyMap()

        val startTime = System.nanoTime()
        val results = mutableMapOf<Int, SlotPreview>()

        for ((slot, bytes) in slotFiles) {
            getSlotPreview(bytes)?.let { preview ->
                results[slot] = preview
            }
        }

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        if (elapsedMs > TARGET_BATCH_SLOTS_MS) {
            Log.w(
                TAG,
                "getAllSlotPreviews(${results.size} slots) took ${elapsedMs}ms (target < ${TARGET_BATCH_SLOTS_MS}ms)"
            )
        }

        return results
    }

    /**
     * 验证字节数组是否为有效的 FlatBuffers 格式存档
     *
     * 检测策略：
     * 1. 文件大小 >= 8 bytes（最小 FB buffer 需要 root_offset 4B + file_identifier 4B）
     * 2. 根偏移量指向有效位置（root_offset < bufferSize - 4）
     * 3. 可选：检查 buffer identifier 是否匹配预期值
     *
     * 注意：当前项目 FlatBuffers schema (save_data.fbs) 未定义 file_identifier，
     * 因此主要依赖结构合法性判断。
     *
     * @param data 待检测的字节数组
     * @return true 表示可能是有效的 FlatBuffers 格式
     */
    fun isValidFlatBufferFormat(data: ByteArray): Boolean {
        if (data.size < 8) return false

        return try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            // FlatBuffers 前 4 bytes 是 root table 的偏移量
            val rootOffset = buf.getInt(0)
            // rootOffset 必须指向 buffer 内的有效位置，且至少有 4 bytes 的 vtable 空间
            rootOffset >= 0 && rootOffset < data.size - 4 && rootOffset % 4 == 0
        } catch (e: Exception) {
            false
        }
    }

    // ==================== FlatBuffers 解析路径 ====================

    /**
     * 使用 FlatBuffers zero-copy 方式解析预览信息
     *
     * 基于 save_data.fbs schema 的字段布局：
     * - SaveDataFlat (root): version(f0), timestamp(f1), gameData(f2)
     * - GameDataFlat: sectName(f0), gameYear(f2), gameMonth(f3), spiritStones(f5)
     * - disciples vector length 用于计算 discipleCount
     *
     * 由于当前项目未生成 FlatBuffers Java 类（generateFlatBuffers task SKIPPED），
     * 这里使用 flatbuffers-java 库的低级 Table/ByteBuffer API 手动访问字段。
     *
     * FlatBuffers 二进制布局参考：
     * ```
     * [root_offset: uoffset_t] [file_ident: 4B] ... [table_data] ... [vtable] ... [strings/vectors]
     * ```
     *
     * @param data FlatBuffers 格式的字节数组
     * @return SlotPreview 或 null
     */
    private fun parseFlatBuffersPreview(data: ByteArray): SlotPreview? {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // 获取根表偏移
        val rootOffset = bb.getInt(0)
        val tableStart = rootOffset

        // 读取 SaveDataFlat 表
        // soffset_t (vtable offset) 在 table 起始位置
        val vtableOffset = tableStart - bb.getInt(tableStart)
        val vtableSize = bb.getShort(vtableOffset).toInt() and 0xFFFF
        val fieldCount = (vtableSize - 4) / 2

        // SaveDataFlat 字段索引（基于 fbs schema 中声明的顺序）：
        //   f0: version (string, offset)
        //   f1: timestamp (long, inline)
        //   f2: gameData (GameDataFlat table, offset)
        //   f3: disciples (vector of DiscipleFlat, offset)
        //   ...
        //   f14: checksum (string, offset)
        //   f15: schemaVersion (int, inline)

        if (fieldCount < 3) {
            Log.w(TAG, "FlatBuffers vtable too small: $fieldCount fields")
            return null
        }

        // --- 读取 version (f0: string offset) ---
        val version = readFlatBuffersString(bb, tableStart, vtableOffset, fieldIndex = 0)

        // --- 读取 timestamp (f1: int64 inline) ---
        val timestamp = readFlatBuffersLong(bb, tableStart, vtableOffset, fieldIndex = 1, default = 0L)

        // --- 读取 gameData (f2: table offset) -> GameDataFlat ---
        val gameDataRelOffset = readFlatBuffersUOffset(bb, tableStart, vtableOffset, fieldIndex = 2)
        var sectName = ""
        var gameYear = 0
        var gameMonth = 0
        var spiritStones = 0L

        if (gameDataRelOffset > 0) {
            val gameDataTablePos = gameDataRelOffset + gameDataRelOffset  // uoffset 相对于自身位置
            val gdVtableOffset = gameDataTablePos - bb.getInt(gameDataTablePos)
            val gdVtableSize = bb.getShort(gdVtableOffset).toInt() and 0xFFFF
            val gdFieldCount = (gdVtableSize - 4) / 2

            // GameDataFlat 字段索引：
            //   f0: sectName (string)
            //   f1: currentSlot (int)
            //   f2: gameYear (int)
            //   f3: gameMonth (int)
            //   f4: gameDay (int)
            //   f5: spiritStones (long)
            if (gdFieldCount >= 6) {
                sectName = readFlatBuffersString(
                    bb, gameDataTablePos, gdVtableOffset, fieldIndex = 0
                ) ?: ""
                gameYear = readFlatBuffersInt(
                    bb, gameDataTablePos, gdVtableOffset, fieldIndex = 2, default = 1
                )
                gameMonth = readFlatBuffersInt(
                    bb, gameDataTablePos, gdVtableOffset, fieldIndex = 3, default = 1
                )
                spiritStones = readFlatBuffersLong(
                    bb, gameDataTablePos, gdVtableOffset, fieldIndex = 5, default = 1000L
                )
            }
        }

        // --- 读取 disciples (f3: vector offset) -> 计算弟子数量 ---
        val discipleCount = readFlatBuffersVectorSize(bb, tableStart, vtableOffset, fieldIndex = 3)

        return SlotPreview(
            sectName = sectName,
            gameYear = gameYear,
            gameMonth = gameMonth,
            discipleCount = discipleCount,
            spiritStones = spiritStones,
            timestamp = timestamp,
            saveSizeBytes = data.size,
            isValid = true,
            formatVersion = version ?: "unknown"
        )
    }

    // ==================== FlatBuffers 低级读取辅助方法 ====================

    /**
     * 从 FlatBuffers 表中读取 String 字段
     *
     * @param bb 字节缓冲区
     * @param tablePos 表的起始位置
     * @param vtablePos vtable 的起始位置
     * @param fieldIndex 字段在 vtable 中的索引（从 0 开始）
     * @return 字符串值，字段不存在或为默认值时返回 null
     */
    private fun readFlatBuffersString(
        bb: ByteBuffer,
        tablePos: Int,
        vtablePos: Int,
        fieldIndex: Int
    ): String? {
        val fieldOffset = getFieldOffset(bb, tablePos, vtablePos, fieldIndex)
        if (fieldOffset == 0) return null

        val strOffsetPos = tablePos + fieldOffset
        val strOffset = bb.getInt(strOffsetPos)
        val strStart = strOffsetPos + strOffset
        val length = bb.getInt(strStart)

        if (strStart + 4 + length > bb.capacity()) return null

        val bytes = ByteArray(length)
            bb.position(strStart + 4)
            bb.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 从 FlatBuffers 表中读取 Int 字段（inline 存储）
     */
    private fun readFlatBuffersInt(
        bb: ByteBuffer,
        tablePos: Int,
        vtablePos: Int,
        fieldIndex: Int,
        default: Int
    ): Int {
        val fieldOffset = getFieldOffset(bb, tablePos, vtablePos, fieldIndex)
        return if (fieldOffset == 0) default else bb.getInt(tablePos + fieldOffset)
    }

    /**
     * 从 FlatBuffers 表中读取 Long 字段（inline 存储）
     */
    private fun readFlatBuffersLong(
        bb: ByteBuffer,
        tablePos: Int,
        vtablePos: Int,
        fieldIndex: Int,
        default: Long
    ): Long {
        val fieldOffset = getFieldOffset(bb, tablePos, vtablePos, fieldIndex)
        return if (fieldOffset == 0) default else bb.getLong(tablePos + fieldOffset)
    }

    /**
     * 从 FlatBuffers 表中读取 uoffset_t（相对偏移量，用于嵌套 table/vector）
     */
    private fun readFlatBuffersUOffset(
        bb: ByteBuffer,
        tablePos: Int,
        vtablePos: Int,
        fieldIndex: Int
    ): Int {
        val fieldOffset = getFieldOffset(bb, tablePos, vtablePos, fieldIndex)
        return if (fieldOffset == 0) 0 else bb.getInt(tablePos + fieldOffset)
    }

    /**
     * 读取 FlatBuffers vector 的元素数量
     */
    private fun readFlatBuffersVectorSize(
        bb: ByteBuffer,
        tablePos: Int,
        vtablePos: Int,
        fieldIndex: Int
    ): Int {
        val fieldOffset = getFieldOffset(bb, tablePos, vtablePos, fieldIndex)
        if (fieldOffset == 0) return 0

        val vecPos = tablePos + fieldOffset
        val vecRelativeOffset = bb.getInt(vecPos)
        val vecStart = vecPos + vecRelativeOffset
        return bb.getInt(vecStart)
    }

    /**
     * 获取 FlatBuffers 字段在 table 中的相对偏移量
     *
     * FlatBuffers vtable 布局：
     * - vtable[0..1]: vtable size (u16)
     * - vtable[2..3]: table inline data size (u16)
     * - vtable[4+2*i .. 5+2*i]: field i 的 offset (s16, 0 表示不存在/默认值)
     */
    private fun getFieldOffset(
        bb: ByteBuffer,
        tablePos: Int,
        vtablePos: Int,
        fieldIndex: Int
    ): Int {
        val entryPos = vtablePos + 4 + fieldIndex * 2
        if (entryPos + 2 > bb.capacity()) return 0
        return bb.getShort(entryPos).toInt()
    }

    // ==================== Protobuf 手动偏移解析路径（Fallback）====================

    /**
     * 使用 Protobuf wire format 手动解析预览信息
     *
     * 仅读取构建 SlotPreview 所需的最小字段集，跳过其他所有数据。
     * 这避免了完整的 ProtoBuf 反序列化开销（无需创建完整对象图）。
     *
     * 解析策略（按 SaveDataProto 的字段顺序扫描）：
     * 1. field 1 (version): string — 跳过（仅需长度）
     * 2. field 2 (timestamp): int64 — 记录
     * 3. field 3 (meta): MetaDataProto — 跳过
     * 4. field 4 (core): CoreModuleProto — 进入子消息解析 sect_name, game_year, game_month, spirit_stones
     * 5. field 5 (disciples): DiscipleModuleProto — 进入子消息统计 disciples repeated 数量
     * 6. 其余字段：跳过
     *
     * Protobuf wire format 参考：
     * - tag = (field_number << 3) | wire_type
     * - varint: 每字节高 bit 为续传标志
     * - length-delimited: tag + length(varint) + data[length]
     *
     * @param data Protobuf 格式的序列化字节数组
     * @return SlotPreview 或 null
     */
    private fun parseProtobufPreview(data: ByteArray): SlotPreview? {
        var pos = 0
        var version = "protobuf"
        var timestamp = 0L
        var sectName = ""
        var gameYear = 1
        var gameMonth = 1
        var spiritStones = 1000L
        var discipleCount = 0

        while (pos < data.size) {
            val tag = readVarint(data, pos)
            pos += varintSize(tag)
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            when (fieldNumber) {
                PB_SAVE_VERSION_FIELD -> {
                    // version (string) — 读取作为 formatVersion
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        val len = readVarint(data, pos).toInt()
                        pos += varintSize(len.toLong())
                        if (len > 0 && len < 256 && pos + len <= data.size) {
                            version = String(data, pos, len, Charsets.UTF_8)
                        }
                        pos += len
                    } else {
                        pos = skipWireType(data, pos, wireType)
                    }
                }

                PB_SAVE_TIMESTAMP_FIELD -> {
                    // timestamp (int64 varint)
                    if (wireType == WIRE_TYPE_VARINT) {
                        timestamp = readVarint(data, pos)
                        pos += varintSize(timestamp)
                    } else {
                        pos = skipWireType(data, pos, wireType)
                    }
                }

                PB_SAVE_CORE_FIELD -> {
                    // core (CoreModuleProto sub-message)
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        val len = readVarint(data, pos).toInt()
                        pos += varintSize(len.toLong())
                        if (len > 0 && pos + len <= data.size) {
                            val coreResult = parseCoreModuleFields(data, pos, len)
                            sectName = coreResult.sectName
                            gameYear = coreResult.gameYear
                            gameMonth = coreResult.gameMonth
                            spiritStones = coreResult.spiritStones
                        }
                        pos += len
                    } else {
                        pos = skipWireType(data, pos, wireType)
                    }
                }

                PB_SAVE_DISCIPLES_FIELD -> {
                    // disciples (DiscipleModuleProto sub-message)
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        val len = readVarint(data, pos).toInt()
                        pos += varintSize(len.toLong())
                        if (len > 0 && pos + len <= data.size) {
                            discipleCount = countDisciplesInModule(data, pos, len)
                        }
                        pos += len
                    } else {
                        pos = skipWireType(data, pos, wireType)
                    }
                }

                else -> {
                    // 跳过未知字段
                    pos = skipWireType(data, pos, wireType)
                }
            }

            // 安全保护：防止死循环（理论上不会触发，但作为防御性编程）
            if (pos <= 0 || pos > data.size) break
        }

        return SlotPreview(
            sectName = sectName.ifEmpty { "Unknown Sect" },
            gameYear = gameYear.coerceAtLeast(1),
            gameMonth = gameMonth.coerceIn(1, 12),
            discipleCount = discipleCount.coerceAtLeast(0),
            spiritStones = spiritStones.coerceAtLeast(0),
            timestamp = timestamp,
            saveSizeBytes = data.size,
            isValid = true,
            formatVersion = version
        )
    }

    /**
     * 解析 CoreModuleProto 子消息中的关键字段
     *
     * 目标字段：
     * - field 2: sect_name (string)
     * - field 4: game_year (int32 varint)
     * - field 5: game_month (int32 varint)
     * - field 7: spirit_stones (int64 varint)
     *
     * @param data 完整的字节数组
     * @param start CoreModuleProto 子消息在 data 中的起始偏移
     * @param length 子消息长度
     * @return 解析出的核心字段
     */
    private fun parseCoreModuleFields(
        data: ByteArray,
        start: Int,
        length: Int
    ): CoreFieldsResult {
        var sectName = ""
        var gameYear = 1
        var gameMonth = 1
        var spiritStones = 1000L
        var pos = start
        val end = start + length

        while (pos < end) {
            val tag = readVarint(data, pos)
            pos += varintSize(tag)
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            when (fieldNumber) {
                PB_CORE_SECT_NAME_FIELD -> {
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED && pos < end) {
                        val len = readVarint(data, pos).toInt()
                        pos += varintSize(len.toLong())
                        if (len > 0 && pos + len <= end) {
                            sectName = String(data, pos, len, Charsets.UTF_8)
                        }
                        pos += len
                    } else {
                        pos = skipWireType(data, pos, wireType, end)
                    }
                }

                PB_CORE_GAME_YEAR_FIELD -> {
                    if (wireType == WIRE_TYPE_VARINT && pos < end) {
                        gameYear = readVarint(data, pos).toInt()
                        pos += varintSize(readVarint(data, pos))
                    } else {
                        pos = skipWireType(data, pos, wireType, end)
                    }
                }

                PB_CORE_GAME_MONTH_FIELD -> {
                    if (wireType == WIRE_TYPE_VARINT && pos < end) {
                        gameMonth = readVarint(data, pos).toInt()
                        pos += varintSize(readVarint(data, pos))
                    } else {
                        pos = skipWireType(data, pos, wireType, end)
                    }
                }

                PB_CORE_SPIRIT_STONES_FIELD -> {
                    if (wireType == WIRE_TYPE_VARINT && pos < end) {
                        spiritStones = readVarint(data, pos)
                        pos += varintSize(spiritStones)
                    } else {
                        pos = skipWireType(data, pos, wireType, end)
                    }
                }

                else -> {
                    pos = skipWireType(data, pos, wireType, end)
                }
            }

            if (pos <= start || pos > end) break
        }

        return CoreFieldsResult(sectName, gameYear, gameMonth, spiritStones)
    }

    /**
     * 统计 DiscipleModuleProto 中的弟子数量
     *
     * 通过计数 field 4 (disciples, repeated DiscipleProto) 的出现次数。
     * 每个 DiscipleProto 子消息算作一个弟子。
     *
     * @param data 完整的字节数组
     * @param start DiscipleModuleProto 子消息起始偏移
     * @param length 子消息长度
     * @return 弟子数量
     */
    private fun countDisciplesInModule(data: ByteArray, start: Int, length: Int): Int {
        var count = 0
        var pos = start
        val end = start + length

        while (pos < end) {
            val tag = readVarint(data, pos)
            pos += varintSize(tag)
            val fieldNumber = (tag shr 3).toInt()
            val wireType = (tag and 0x07).toInt()

            if (fieldNumber == PB_DISCIPLES_LIST_FIELD && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                count++
                // 跳过这个 DiscipleProto 子消息
                val len = readVarint(data, pos)
                pos += varintSize(len) + len.toInt()
            } else {
                pos = skipWireType(data, pos, wireType, end)
            }

            if (pos <= start || pos > end) break
        }

        return count
    }

    // ==================== Protobuf Wire Format 底层工具方法 ====================

    /**
     * 读取 protobuf varint 编码的无符号长整型值
     *
     * Varint 编码规则：
     * - 每个字节的最高位 (MSB) 是续传标志：1=后续还有字节，0=这是最后一个字节
     * - 有效数据使用低 7 位
     * - 小端序存储（最低有效组在前）
     *
     * @param data 字节数组
     * @param offset 起始偏移量
     * @return 解码后的 long 值
     */
    private fun readVarint(data: ByteArray, offset: Int): Long {
        var result = 0L
        var shift = 0
        var pos = offset

        while (pos < data.size) {
            val b = data[pos].toLong() and 0x7FL
            result = result or (b shl shift)
            shift += 7
            pos++
            if ((data[pos - 1].toInt() and 0x80) == 0) break
            if (shift >= 64) break  // 防止溢出
        }

        return result
    }

    /**
     * 计算 varint 值占用的字节数
     */
    private fun varintSize(value: Long): Int {
        if (value == 0L) return 1
        var remaining = value
        var size = 0
        do {
            remaining = remaining ushr 7
            size++
        } while (remaining != 0L)
        return size
    }

    /**
     * 根据 wire type 跳过当前字段的数据部分
     *
     * @param data 字节数组
     * @param pos 当前位置（tag 之后）
     * @param wireType wire type (0=varint, 1=64bit, 2=length-delimited, 5=32bit)
     * @param boundary 可选的上界限制（子消息内解析时使用）
     * @return 跳过后新的位置
     */
    private fun skipWireType(
        data: ByteArray,
        pos: Int,
        wireType: Int,
        boundary: Int = data.size
    ): Int {
        return when (wireType) {
            WIRE_TYPE_VARINT -> {
                // 跳过 varint：读到 MSB=0 为止
                var p = pos
                while (p < boundary && p < data.size) {
                    p++
                    if ((data[p - 1].toInt() and 0x80) == 0) break
                }
                p
            }

            WIRE_TYPE_LENGTH_DELIMITED -> {
                // 跳过 length-delimited：读取长度，跳过数据
                if (pos >= boundary) return boundary
                val len = readVarint(data, pos)
                (pos + varintSize(len) + len.toInt()).coerceAtMost(boundary)
            }

            // wire type 1: 64-bit fixed (跳过 8 bytes)
            1 -> (pos + 8).coerceAtMost(boundary)

            // wire type 5: 32-bit fixed (跳过 4 bytes)
            5 -> (pos + 4).coerceAtMost(boundary)

            // 其他未知 wire type：无法安全跳过，停止解析
            else -> boundary
        }
    }

    // ==================== 内部数据类 ====================

    /**
     * CoreModuleProto 关键字段的解析结果
     */
    private data class CoreFieldsResult(
        val sectName: String,
        val gameYear: Int,
        val gameMonth: Int,
        val spiritStones: Long
    )
}

/**
 * ## SlotPreview - 存档槽位预览信息
 *
 * 用于存档选择界面展示的轻量摘要数据。
 * 不包含完整存档内容，仅包含用户选择所需的关键信息。
 *
 * ### 字段说明
 * - **sectName**: 门派名称（用户识别存档的主要标识）
 * - **gameYear / gameMonth**: 游戏进度时间
 * - **discipleCount**: 弟子数量（反映发展程度）
 * - **spiritStones**: 灵石资源（反映经济状况）
 * - **timestamp**: 存档保存时间戳
 * - **saveSizeBytes**: 存档文件大小（调试用）
 * - **isValid**: 数据是否合法
 * - **formatVersion**: 序列化格式版本号
 */
data class SlotPreview(
    val sectName: String,
    val gameYear: Int,
    val gameMonth: Int,
    val discipleCount: Int,
    val spiritStones: Long,
    val timestamp: Long,
    val saveSizeBytes: Int,
    val isValid: Boolean,
    val formatVersion: String
) {
    /**
     * 生成用于显示的游戏时间字符串
     *
     * @return 如 "第42年 3月" 格式的字符串
     */
    fun displayGameTime(): String = "第${gameYear}年 ${gameMonth}月"

    /**
     * 生成简短的摘要描述
     *
     * @return 一行摘要文本
     */
    fun summary(): String =
        "$sectName | ${displayGameTime()} | $discipleCount 弟子 | $spiritStones 灵石"
}
