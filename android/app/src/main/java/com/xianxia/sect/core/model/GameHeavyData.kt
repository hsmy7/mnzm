package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "game_heavy_data",
    primaryKeys = ["slot_id", "data_key"],
    indices = [
        Index(value = ["slot_id", "data_key"], unique = true),
        Index(value = ["slot_id"])
    ]
)
class GameHeavyData(
    @ColumnInfo(name = "slot_id")
    val slotId: Int = 0,

    @ColumnInfo(name = "data_key")
    val dataKey: String = "",

    @ColumnInfo(name = "data_value", typeAffinity = ColumnInfo.BLOB)
    val dataValue: ByteArray = byteArrayOf(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameHeavyData) return false
        return slotId == other.slotId
                && dataKey == other.dataKey
                && dataValue.contentEquals(other.dataValue)
                && updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = slotId
        result = 31 * result + dataKey.hashCode()
        result = 31 * result + dataValue.contentHashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }

    companion object {
        // ── Key 常量 ──
        const val KEY_AI_SECT_DISCIPLES = "aiSectDisciples"
        const val KEY_SECT_DETAILS = "sectDetails"
        const val KEY_EXPLORED_SECTS = "exploredSects"
        const val KEY_SCOUT_INFO = "scoutInfo"
        const val KEY_MANUAL_PROFICIENCIES = "manualProficiencies"
        const val KEY_RECRUIT_LIST = "recruitList"
        const val KEY_WORLD_MAP_SECTS = "worldMapSects"

        val ALL_KEYS = listOf(
            KEY_AI_SECT_DISCIPLES, KEY_SECT_DETAILS, KEY_EXPLORED_SECTS,
            KEY_SCOUT_INFO, KEY_MANUAL_PROFICIENCIES,
            KEY_RECRUIT_LIST, KEY_WORLD_MAP_SECTS
        )

        // ── 分块参数 ──
        /** 单行最大字节数（远低于 CursorWindow 2MB 限制）*/
        const val MAX_CHUNK_BYTES = 900_000

        // ── Key 工具 ──
        private const val SEPARATOR = "/"
        private const val OVERFLOW_SUFFIX = "_overflow_"

        /** 构造分块 key：`aiSectDisciples/青云宗` */
        fun chunkKey(prefix: String, id: String): String = "$prefix$SEPARATOR$id"

        /** 从分块 key 中提取 id。`aiSectDisciples/青云宗` → `青云宗`，不匹配返回 null */
        fun parseChunkKey(key: String, prefix: String): String? {
            val needle = "$prefix$SEPARATOR"
            if (!key.startsWith(needle)) return null
            return key.removePrefix(needle)
        }

        /** 检查 key 是否为溢出分块 */
        fun isOverflowKey(key: String): Boolean = key.contains(OVERFLOW_SUFFIX)

        /** 从溢出 key 提取原始 key */
        fun baseOverflowKey(overflowKey: String): String =
            overflowKey.substringBeforeLast(OVERFLOW_SUFFIX)

        // ── 分块 / 重组 ──

        /**
         * 将大 BLOB 拆分为多条行。
         * 正常情况（< MAX_CHUNK_BYTES）：返回 1 行。
         * 极端情况（单个条目超限）：拆分为多行，key 加 _overflow_N 后缀。
         */
        fun chunk(slotId: Int, key: String, value: ByteArray, updatedAt: Long = System.currentTimeMillis()): List<GameHeavyData> {
            if (value.size <= MAX_CHUNK_BYTES) {
                return listOf(GameHeavyData(slotId, key, value, updatedAt))
            }
            val result = mutableListOf<GameHeavyData>()
            var offset = 0
            while (offset < value.size) {
                val end = minOf(offset + MAX_CHUNK_BYTES, value.size)
                result.add(GameHeavyData(slotId,
                    "${key}${OVERFLOW_SUFFIX}${offset / MAX_CHUNK_BYTES}",
                    value.copyOfRange(offset, end), updatedAt))
                offset = end
            }
            return result
        }

        /**
         * 从行列表重组为 key → ByteArray 映射。
         * 自动合并溢出分块。
         */
        fun reassemble(rows: List<GameHeavyData>): Map<String, ByteArray> {
            val overflowGroups = mutableMapOf<String, MutableList<Pair<Int, ByteArray>>>()
            val normal = mutableMapOf<String, ByteArray>()

            for (row in rows) {
                if (isOverflowKey(row.dataKey)) {
                    val base = baseOverflowKey(row.dataKey)
                    val index = row.dataKey.substringAfterLast("_").toIntOrNull() ?: continue
                    overflowGroups.getOrPut(base) { mutableListOf() }.add(index to row.dataValue)
                } else {
                    normal[row.dataKey] = row.dataValue
                }
            }

            for ((base, parts) in overflowGroups) {
                parts.sortBy { it.first }
                val totalSize = parts.sumOf { it.second.size }
                val combined = ByteArray(totalSize)
                var offset = 0
                for ((_, bytes) in parts) {
                    bytes.copyInto(combined, offset)
                    offset += bytes.size
                }
                normal[base] = combined
            }
            return normal
        }
    }
}
