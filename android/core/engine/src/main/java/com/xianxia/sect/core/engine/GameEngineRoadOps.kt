package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.RoadPlacementResult

/**
 * 道路操作扩展（GameEngine 单向数据流：UI → ViewModel → GameEngine → RoadFacade → GameStateStore）。
 *
 * 灵石/道路数据同步契约（2026-08-31 根因修复）：
 * 道路放置/删除是纯 Kotlin 侧变更（`roads` 字段未迁移 C++，`spiritStones` 已迁移）。
 * AUTHORITATIVE tick 步骤 ②' 的 `resetReverseAccumulator()` 会无条件清空反向捕获累加器，
 * 若放置变更只靠 tick 步骤 ⑤ 顺带回导，捕获在 tick 间隙被清空 → C++ 真相源永远不知晓
 * 灵石扣除 → 后续 C++ 侧灵石变化经前向镜像覆盖 Kotlin → 玩家看到"灵石未扣除"。
 * 因此放置/删除成功后**立即**增量回导（本事务的反向捕获此时仍在累加器内，引擎单线程
 * 事务→回导无抢占窗口），失败降级全量兜底；native 不可用/OFF 模式内部静默降级（既有契约）。
 */
fun GameEngine.placeRoad(gridX: Int, gridY: Int): RoadPlacementResult {
    val result = roadFacade.placeRoad(gridX, gridY)
    if (result is RoadPlacementResult.Success) {
        syncRoadChangeToNative()
    }
    return result
}

fun GameEngine.removeRoad(gridX: Int, gridY: Int): RoadPlacementResult {
    val result = roadFacade.removeRoad(gridX, gridY)
    if (result is RoadPlacementResult.Success) {
        syncRoadChangeToNative()
    }
    return result
}

fun GameEngine.canPlaceRoad(gridX: Int, gridY: Int): Boolean =
    roadFacade.canPlaceRoad(gridX, gridY)

/**
 * 道路变更即时回导 C++ 真相源：消费当前反向捕获窗口（含刚提交的放置/删除事务），
 * 增量失败降级全量（restoreRng=false，与 tick 步骤 ⑤ 失败语义一致）。
 */
private fun GameEngine.syncRoadChangeToNative() {
    if (!stateSyncService.applyDirtyToNative()) {
        stateSyncService.importToNative(restoreRng = false)
    }
}
