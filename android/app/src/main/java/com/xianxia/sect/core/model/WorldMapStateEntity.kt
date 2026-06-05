package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Keep
@Serializable
@Entity(
    tableName = "world_map_state",
    primaryKeys = ["slot_id"],
    indices = [Index(value = ["slot_id"], unique = true)]
)
data class WorldMapStateEntity(
    @ColumnInfo(name = "slot_id")
    var slotId: Int = 1,
    var worldMapSects: List<WorldSect> = emptyList(),
    var aiSectDisciples: Map<String, List<Disciple>> = emptyMap(),
    var cultivatorCaves: List<CultivatorCave> = emptyList(),
    var caveExplorationTeams: List<CaveExplorationTeam> = emptyList(),
    var aiCaveTeams: List<AICaveTeam> = emptyList(),
    var worldLevels: List<WorldLevel> = emptyList()
)
