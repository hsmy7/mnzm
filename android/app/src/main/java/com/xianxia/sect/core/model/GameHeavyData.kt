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
data class GameHeavyData(
    @ColumnInfo(name = "slot_id")
    val slotId: Int = 0,

    @ColumnInfo(name = "data_key")
    val dataKey: String = "",

    @ColumnInfo(name = "data_value")
    val dataValue: String = "",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KEY_AI_SECT_DISCIPLES = "aiSectDisciples"
        const val KEY_SECT_DETAILS = "sectDetails"
        const val KEY_EXPLORED_SECTS = "exploredSects"
        const val KEY_SCOUT_INFO = "scoutInfo"
        const val KEY_MANUAL_PROFICIENCIES = "manualProficiencies"

        val ALL_KEYS = listOf(
            KEY_AI_SECT_DISCIPLES,
            KEY_SECT_DETAILS,
            KEY_EXPLORED_SECTS,
            KEY_SCOUT_INFO,
            KEY_MANUAL_PROFICIENCIES
        )

        /** 单行 data_value 最大字符数 (~900KB，远低于 CursorWindow 2MB 限制) */
        const val MAX_CHUNK_SIZE = 900_000

        /** 分块 key 后缀格式 */
        private const val CHUNK_SUFFIX = "_chunk_"

        /** 检查 key 是否为分块 key */
        fun isChunkKey(key: String): Boolean = key.contains(CHUNK_SUFFIX)

        /** 从分块 key 提取原始 key（如 "aiSectDisciples_chunk_0" → "aiSectDisciples"） */
        fun baseKey(chunkKey: String): String =
            chunkKey.substringBeforeLast(CHUNK_SUFFIX)

        /**
         * 将大字符串拆分为多个 GameHeavyData 条目。
         * 每条目 ≤ MAX_CHUNK_SIZE 字符，确保单行不超过 CursorWindow 限制。
         */
        fun chunk(slotId: Int, key: String, value: String, updatedAt: Long = System.currentTimeMillis()): List<GameHeavyData> {
            if (value.length <= MAX_CHUNK_SIZE) {
                return listOf(GameHeavyData(slotId, key, value, updatedAt))
            }
            return value.chunked(MAX_CHUNK_SIZE).mapIndexed { index, chunk ->
                GameHeavyData(slotId, "${key}_chunk_$index", chunk, updatedAt)
            }
        }

        /**
         * 从原始行列表重组数据 map。
         * 自动检测并合并分块 key（如 aiSectDisciples_chunk_0 + _chunk_1 → aiSectDisciples）。
         */
        fun reassemble(rows: List<GameHeavyData>): Map<String, String> {
            val chunks = mutableMapOf<String, MutableList<Pair<Int, String>>>()
            val normal = mutableMapOf<String, String>()

            for (row in rows) {
                if (isChunkKey(row.dataKey)) {
                    val base = baseKey(row.dataKey)
                    val index = row.dataKey.substringAfterLast("_").toIntOrNull() ?: continue
                    chunks.getOrPut(base) { mutableListOf() }.add(index to row.dataValue)
                } else {
                    normal[row.dataKey] = row.dataValue
                }
            }

            val result = normal.toMutableMap()
            for ((base, parts) in chunks) {
                parts.sortBy { it.first }
                result[base] = parts.joinToString("") { it.second }
            }
            return result
        }
    }
}
