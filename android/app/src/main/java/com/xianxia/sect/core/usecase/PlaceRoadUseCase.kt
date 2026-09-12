package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.placeRoad
import com.xianxia.sect.core.util.RoadPlacementResult
import javax.inject.Inject
import javax.inject.Singleton

/** 放置道路用例（玩家点击网格 → 程序自动拼接并更新当前格 + 上下左右共 5 格）。 */
@Singleton
class PlaceRoadUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    operator fun invoke(gridX: Int, gridY: Int): RoadPlacementResult = gameEngine.placeRoad(gridX, gridY)
}
