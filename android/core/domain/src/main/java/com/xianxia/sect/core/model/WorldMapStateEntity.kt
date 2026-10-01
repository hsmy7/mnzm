package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Keep
@Serializable
@Entity(tableName = "world_map_state")
data class WorldMapStateEntity(
    /** 单档单行标识：本表恒一行，主键恒 1 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: Int = 1,
    var worldMapSects: List<WorldSect> = emptyList(),
    var aiSectDisciples: Map<String, List<Disciple>> = emptyMap(),
    var cultivatorCaves: List<CultivatorCave> = emptyList(),
    var caveExplorationTeams: List<CaveExplorationTeam> = emptyList(),
    var worldLevels: List<WorldLevel> = emptyList()
)
