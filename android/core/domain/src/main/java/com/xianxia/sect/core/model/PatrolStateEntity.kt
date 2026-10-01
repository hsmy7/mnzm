package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Keep
@Serializable
@Entity(tableName = "patrol_state")
data class PatrolStateEntity(
    /** 单档单行标识：本表恒一行，主键恒 1 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: Int = 1,
    var patrolSlots: List<PatrolSlot> = emptyList(),
    var patrolConfig: PatrolConfig = PatrolConfig(),
    var patrolConfigs: List<PatrolConfig> = emptyList(),
    var patrolBattleResultPopup: Boolean = false
)
