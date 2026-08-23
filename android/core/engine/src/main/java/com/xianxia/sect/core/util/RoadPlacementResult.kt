package com.xianxia.sect.core.util

/**
 * 道路放置/删除结果（sealed，避免裸 Boolean 表达失败语义）。
 */
sealed class RoadPlacementResult {
    /** 操作成功。 */
    data class Success(val affectedCells: List<Pair<Int, Int>> = emptyList()) : RoadPlacementResult()

    /** 该格不可建造（已有建筑/固定结构/可建环外/已是道路等原因）。 */
    data class Blocked(val reason: String) : RoadPlacementResult()
}
