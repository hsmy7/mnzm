package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.appeaseAttackingSect
import com.xianxia.sect.core.engine.becomeVassalOfAttacker
import com.xianxia.sect.core.engine.markWarningStageShown
import com.xianxia.sect.core.model.AttackWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * AI 宗门进攻预警处理委托。
 *
 * 职责：安抚/成为附庸/标记已展示阶段，以及预警响应式状态。
 */
class WarningDelegate(
    private val gameEngine: GameEngine,
    private val scope: CoroutineScope
) {
    /** AI 宗门进攻预警列表 */
    val attackWarnings: StateFlow<List<AttackWarning>> = gameEngine.gameData
        .map { it.activeAttackWarnings }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 已展示过的预警阶段 ID 列表 */
    val shownWarningStageIds: StateFlow<List<String>> = gameEngine.gameData
        .map { it.shownWarningStageIds }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 处理 AI 宗门进攻预警 — 选择安抚（支付资源）以避免进攻。 */
    fun resolveAttackWarningAppease(sectId: String) {
        scope.launch { gameEngine.appeaseAttackingSect(sectId) }
    }

    /** 处理 AI 宗门进攻预警 — 选择成为附庸以避免进攻。 */
    fun resolveAttackWarningVassal(sectId: String) {
        scope.launch { gameEngine.becomeVassalOfAttacker(sectId) }
    }

    /** 标记某预警阶段已展示过，避免重复弹出。 */
    fun markWarningStageShown(stageKey: String) {
        scope.launch { gameEngine.markWarningStageShown(stageKey) }
    }
}
