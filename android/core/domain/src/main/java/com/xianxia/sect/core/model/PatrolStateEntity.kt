package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Keep
@Serializable
@Entity(
    tableName = "patrol_state",
    primaryKeys = ["slot_id"],
    indices = [Index(value = ["slot_id"], unique = true)]
)
data class PatrolStateEntity(
    @ColumnInfo(name = "slot_id")
    var slotId: Int = 1,
    var patrolSlots: List<PatrolSlot> = emptyList(),
    var patrolConfig: PatrolConfig = PatrolConfig(),
    var patrolConfigs: List<PatrolConfig> = emptyList(),
    var patrolBattleResultPopup: Boolean = false
)
