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
    }
}
