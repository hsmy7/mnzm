package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.Mission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xianxia.sect.core.engine.startMission

/**
 * 任务派遣委托（自 GameViewModel 拆出，行为零变更）。
 *
 * 外出任务开始（选中弟子编队派发），失败经 [onError] 回调提示。
 */
class MissionDelegate(
    private val gameEngine: GameEngine,
    private val onError: (String) -> Unit
) {

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    fun startMission(mission: Mission, selectedDisciples: List<DiscipleAggregate>) {
        gameEngine.launchOnEngine {
            try { gameEngine.startMission(mission, selectedDisciples.map { it.toDisciple() }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { withContext(Dispatchers.Main) { onError(e.message ?: "开始任务失败") } }
        }
    }
}
