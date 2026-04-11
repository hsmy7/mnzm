package com.xianxia.sect.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "save_slot_metadata",
    indices = [
        Index(value = ["slot_id"], unique = true),
        Index(value = ["last_save_time"]),
        Index(value = ["game_year", "game_month"])
    ]
)
data class SaveSlotMetadata(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "slot_id")
    val slotId: Int,

    @ColumnInfo(name = "sect_name")
    val sectName: String = "",

    @ColumnInfo(name = "game_year")
    val gameYear: Int = 1,

    @ColumnInfo(name = "game_month")
    val gameMonth: Int = 1,

    @ColumnInfo(name = "game_day")
    val gameDay: Int = 1,

    @ColumnInfo(name = "spirit_stones")
    val spiritStones: Long = 0,

    @ColumnInfo(name = "spirit_herbs")
    val spiritHerbs: Int = 0,

    @ColumnInfo(name = "sect_cultivation")
    val sectCultivation: Double = 0.0,

    @ColumnInfo(name = "is_game_started")
    val isGameStarted: Boolean = false,

    @ColumnInfo(name = "last_save_time")
    val lastSaveTime: Long = 0L,

    @ColumnInfo(name = "disciple_count")
    val discipleCount: Int = 0,

    @ColumnInfo(name = "data_version")
    val dataVersion: Int = 1
)
