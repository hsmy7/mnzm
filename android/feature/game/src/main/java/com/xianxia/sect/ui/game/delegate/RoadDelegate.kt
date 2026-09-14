package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.canPlaceRoad
import com.xianxia.sect.core.engine.placeRoad
import com.xianxia.sect.core.engine.removeRoad

/**
 * 石板道路委托（自 GameViewModel 拆出，行为零变更）。
 *
 * 道路放置/删除/可放置判定；放置/删除经引擎线程执行保证与游戏循环一致，
 * 拼接失败时经 [onBlocked] 回调提示（GameViewModel 注入 showError）。
 */
class RoadDelegate(
    private val gameEngine: GameEngine,
    private val onBlocked: (String) -> Unit
) {

    /** 放置道路：目标可放置则自动拼接并更新当前格 + 上下左右共 5 格；经引擎线程执行保证与游戏循环一致。 */
    fun placeRoad(gridX: Int, gridY: Int) {
        gameEngine.launchOnEngine {
            val result = gameEngine.placeRoad(gridX, gridY)
            if (result is com.xianxia.sect.core.util.RoadPlacementResult.Blocked) {
                onBlocked(result.reason)
            }
        }
    }

    /** 删除道路：删除当前格并重算 4 个邻居（周围道路立即重新拼接）；经引擎线程执行。 */
    fun removeRoad(gridX: Int, gridY: Int) {
        gameEngine.launchOnEngine {
            val result = gameEngine.removeRoad(gridX, gridY)
            if (result is com.xianxia.sect.core.util.RoadPlacementResult.Blocked) {
                onBlocked(result.reason)
            }
        }
    }

    /** 判定某格是否可放置道路（界内 + 可建环 + 非建筑/固定结构占位 + 尚未是道路）。 */
    fun canPlaceRoad(gridX: Int, gridY: Int): Boolean = gameEngine.canPlaceRoad(gridX, gridY)
}
