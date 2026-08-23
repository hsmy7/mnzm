package com.xianxia.sect.core.engine.domain.road

import com.xianxia.sect.core.util.RoadPlacementResult

/**
 * 道路系统门面（对应 BuildingFacade 的镜像）。
 *
 * 玩家只负责在网格放置/删除道路，程序按邻接位掩码自动拼接。所有状态变更
 * 经 GameStateStore 原子事务完成，UI 层不得直接调用。
 */
interface RoadFacade {

    /** 判定某格是否可放置道路（界内 + 可建环 + 非建筑/固定结构占位 + 尚未是道路）。 */
    fun canPlaceRoad(gridX: Int, gridY: Int): Boolean

    /** 放置道路：目标须可放置；成功后重算当前格 + 上下左右共 5 格位掩码。 */
    fun placeRoad(gridX: Int, gridY: Int): RoadPlacementResult

    /** 删除道路：仅删除当前格并重算 4 个邻居（周围道路立即重新拼接）。 */
    fun removeRoad(gridX: Int, gridY: Int): RoadPlacementResult
}
