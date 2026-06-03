package com.xianxia.sect.core.model.domain

import com.xianxia.sect.core.model.*

/**
 * 世界地图领域状态 — 从 GameData 中提取的世界地图相关字段聚合。
 *
 * 纯领域模型，仅用于业务层传递。序列化由 GameData 负责，本类不标 @Serializable，
 * 以允许使用 Map 类型（与 GameData 字段类型一致），避免 ProtoBuf 兼容问题。
 */
data class WorldMapDomainState(
    val worldMapSects: List<WorldSect> = emptyList(),
    val aiSectDisciples: Map<String, List<Disciple>> = emptyMap(),
    val cultivatorCaves: List<CultivatorCave> = emptyList(),
    val caveExplorationTeams: List<CaveExplorationTeam> = emptyList(),
    val aiCaveTeams: List<AICaveTeam> = emptyList(),
    val worldLevels: List<WorldLevel> = emptyList()
)

/** 从 GameData 提取世界地图领域状态 */
fun GameData.extractWorldMapState(): WorldMapDomainState = WorldMapDomainState(
    worldMapSects = worldMapSects,
    aiSectDisciples = aiSectDisciples,
    cultivatorCaves = cultivatorCaves,
    caveExplorationTeams = caveExplorationTeams,
    aiCaveTeams = aiCaveTeams,
    worldLevels = worldLevels
)

/** 将世界地图领域状态合并回 GameData */
fun GameData.mergeWorldMapState(state: WorldMapDomainState): GameData = copy(
    worldMapSects = state.worldMapSects,
    aiSectDisciples = state.aiSectDisciples,
    cultivatorCaves = state.cultivatorCaves,
    caveExplorationTeams = state.caveExplorationTeams,
    aiCaveTeams = state.aiCaveTeams,
    worldLevels = state.worldLevels
)
