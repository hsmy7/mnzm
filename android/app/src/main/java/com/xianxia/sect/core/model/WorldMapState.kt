package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

/**
 * 世界地图与外交状态
 *
 * 包含世界地图宗门、已探索宗门信息、宗门侦查信息、AI宗门间关系等数据。
 * 从 GameData 上帝对象中拆分出来，降低 copy() 时的复制开销。
 */
@Serializable
data class WorldMapState(
    val worldMapSects: List<WorldSect> = emptyList(),
    val exploredSects: Map<String, ExploredSectInfo> = emptyMap(),
    val scoutInfo: Map<String, SectScoutInfo> = emptyMap(),
    val sectRelations: List<SectRelation> = emptyList()
)
