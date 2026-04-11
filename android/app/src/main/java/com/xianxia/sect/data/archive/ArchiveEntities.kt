package com.xianxia.sect.data.archive

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.EventType

@Entity(
    tableName = "archived_battle_logs",
    indices = [
        Index(value = ["slot_id", "timestamp"]),
        Index(value = ["archived_at"])
    ]
)
data class ArchivedBattleLog(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "slot_id")
    val slotId: Int = 0,

    @ColumnInfo(name = "original_id")
    val originalId: String = "",

    @ColumnInfo(name = "battle_type")
    val battleType: String = BattleType.PVE.name,

    @ColumnInfo(name = "result")
    val result: String = BattleResult.DRAW.name,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = 0L,

    @ColumnInfo(name = "attacker_name")
    val attackerName: String = "",

    @ColumnInfo(name = "defender_name")
    val defenderName: String = "",

    @ColumnInfo(name = "data_blob")
    val dataBlob: String = "",

    @ColumnInfo(name = "archived_at")
    val archivedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "archived_game_events",
    indices = [
        Index(value = ["slot_id", "timestamp"]),
        Index(value = ["archived_at"])
    ]
)
data class ArchivedGameEvent(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "slot_id")
    val slotId: Int = 0,

    @ColumnInfo(name = "original_id")
    val originalId: String = "",

    @ColumnInfo(name = "event_type")
    val eventType: String = EventType.INFO.name,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = 0L,

    @ColumnInfo(name = "message")
    val message: String = "",

    @ColumnInfo(name = "data_blob")
    val dataBlob: String = "",

    @ColumnInfo(name = "archived_at")
    val archivedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "archived_disciples",
    indices = [
        Index(value = ["slot_id", "archived_at"]),
        Index(value = ["archived_at"])
    ]
)
data class ArchivedDisciple(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "slot_id")
    val slotId: Int = 0,

    @ColumnInfo(name = "original_id")
    val originalId: String = "",

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "realm")
    val realm: Int = 9,

    @ColumnInfo(name = "data_blob")
    val dataBlob: String = "",

    @ColumnInfo(name = "archived_at")
    val archivedAt: Long = System.currentTimeMillis()
)
