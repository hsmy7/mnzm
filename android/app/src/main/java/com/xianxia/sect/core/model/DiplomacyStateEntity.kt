package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Keep
@Serializable
@Entity(
    tableName = "diplomacy_state",
    primaryKeys = ["slot_id"],
    indices = [Index(value = ["slot_id"], unique = true)]
)
data class DiplomacyState(
    @ColumnInfo(name = "slot_id")
    var slotId: Int = 1,
    var sectRelations: List<SectRelation> = emptyList(),
    var alliances: List<Alliance> = emptyList(),
    var playerAllianceSlots: Int = 3,
    var playerProtectionEnabled: Boolean = true,
    var playerProtectionStartYear: Int = 1,
    var playerHasAttackedAI: Boolean = false,
    var sectDetails: Map<String, SectDetail> = emptyMap(),
    var exploredSects: Map<String, ExploredSectInfo> = emptyMap(),
    var scoutInfo: Map<String, SectScoutInfo> = emptyMap()
)
