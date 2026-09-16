package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.RoadPlacementResult

/**
 * 道路操作扩展（GameEngine 单向数据流：UI → ViewModel → GameEngine → RoadFacade → GameStateStore）。
 *
 * 数据同步契约（w3-13 反向通道删除后，handover §2.82）：
 * 道路放置/删除在 AUTHORITATIVE 稳态经 `RoadFacadeImpl` 的 native 事务臂
 * （`ROAD_PLACE`/`ROAD_REMOVE`）执行，C++ 直接写 `roads`/`spiritStones` 并经
 * 前向镜像回流——本层只做结果转发，无回导职责；native 不可用/降级时走
 * Kotlin 回退臂（此时 Kotlin 即真相源，同样无需回导）。
 */
fun GameEngine.placeRoad(gridX: Int, gridY: Int): RoadPlacementResult =
    roadFacade.placeRoad(gridX, gridY)

fun GameEngine.removeRoad(gridX: Int, gridY: Int): RoadPlacementResult =
    roadFacade.removeRoad(gridX, gridY)

fun GameEngine.canPlaceRoad(gridX: Int, gridY: Int): Boolean =
    roadFacade.canPlaceRoad(gridX, gridY)
