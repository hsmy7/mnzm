package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.markWarningStageShown
import com.xianxia.sect.core.model.AttackWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * AI 宗门进攻预警处理委托。
 *
 * 职责：标记已展示阶段，以及预警响应式状态。
 */
class WarningDelegate(
    private val gameEngine: GameEngine,
    private val flowScope: CoroutineScope
) {
    /** AI 宗门进攻预警列表 */
    val attackWarnings: StateFlow<List<AttackWarning>> = gameEngine.gameData
        .map { it.activeAttackWarnings }
        .distinctUntilChanged()
        .stateIn(flowScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 已展示过的预警阶段 ID 列表 */
    val shownWarningStageIds: StateFlow<List<String>> = gameEngine.gameData
        .map { it.shownWarningStageIds }
        .distinctUntilChanged()
        .stateIn(flowScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 标记某预警阶段已展示过，避免重复弹出。 */
    fun markWarningStageShown(stageKey: String) {
        gameEngine.launchOnEngine { gameEngine.markWarningStageShown(stageKey) }
    }
}
