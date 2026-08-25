package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.RoadPlacementResult

/**
 * 道路操作扩展（GameEngine 单向数据流：UI → ViewModel → GameEngine → RoadFacade → GameStateStore）。
 */
fun GameEngine.placeRoad(gridX: Int, gridY: Int): RoadPlacementResult =
    roadFacade.placeRoad(gridX, gridY)

fun GameEngine.removeRoad(gridX: Int, gridY: Int): RoadPlacementResult =
    roadFacade.removeRoad(gridX, gridY)

fun GameEngine.canPlaceRoad(gridX: Int, gridY: Int): Boolean =
    roadFacade.canPlaceRoad(gridX, gridY)
