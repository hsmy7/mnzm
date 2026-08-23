package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.Serializable

/**
 * 石板道路数据（网格坐标 + 邻接位掩码 + 形态）。
 *
 * 道路系统核心：玩家只负责放置，程序按上下左右邻居实时推导 [bitMask]
 * 并映射到 [roadType]（见 [com.xianxia.sect.core.engine.RoadTiling]）。
 * 经 CollectionConverters 以 Protobuf Base64 落入 game_data.roads 列（同 placedBuildings）。
 *
 * @property gridX 网格 X（与现有 GridSystem/GridSnapHelper 同一坐标系）
 * @property gridY 网格 Y
 * @property bitMask 邻接位掩码（上=1 右=2 下=4 左=8；0 = 无邻居）
 * @property roadType 道路形态名（[com.xianxia.sect.core.engine.RoadTileType].name，存档人可读）
 */
@Immutable
@Keep
@Serializable
data class RoadData(
    @ProtoNumber(1) val gridX: Int = 0,
    @ProtoNumber(2) val gridY: Int = 0,
    @ProtoNumber(3) val bitMask: Int = 0,
    @ProtoNumber(4) val roadType: String = "SINGLE"
)
