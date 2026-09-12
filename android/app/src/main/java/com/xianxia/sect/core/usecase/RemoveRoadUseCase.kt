package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.removeRoad
import com.xianxia.sect.core.util.RoadPlacementResult
import javax.inject.Inject
import javax.inject.Singleton

/** 删除道路用例（删除当前格并重算 4 个邻居，周围道路立即重新拼接）。 */
@Singleton
class RemoveRoadUseCase @Inject constructor(
    private val gameEngine: GameEngine
) {
    operator fun invoke(gridX: Int, gridY: Int): RoadPlacementResult = gameEngine.removeRoad(gridX, gridY)
}
