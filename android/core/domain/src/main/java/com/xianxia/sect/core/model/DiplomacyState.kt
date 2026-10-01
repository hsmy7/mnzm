package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Keep
@Serializable
@Entity(tableName = "diplomacy_state")
data class DiplomacyState(
    /** 单档单行标识：本表恒一行，主键恒 1 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: Int = 1,
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
