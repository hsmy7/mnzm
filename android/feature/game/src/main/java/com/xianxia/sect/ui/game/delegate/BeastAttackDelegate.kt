package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 凶兽袭击事件处理委托。
 *
 * 职责：进贡/战斗/清空待处理兽袭事件。
 */
class BeastAttackDelegate(
    private val gameEngine: GameEngine,
    private val scope: CoroutineScope
) {
    /** 处理兽袭事件 — 选择进贡物资以平息该兽袭。 */
    fun resolveBeastAttackPayTribute(beastLevelId: String) {
        gameEngine.resolveBeastAttackPayTribute(beastLevelId)
    }

    /** 处理兽袭事件 — 选择战斗抵抗。 */
    fun resolveBeastAttackFight(beastLevelId: String) {
        scope.launch {
            gameEngine.resolveBeastAttackFight(beastLevelId)
        }
    }

    /** 清空所有待处理的兽袭事件。 */
    fun clearPendingBeastAttacks() {
        gameEngine.clearPendingBeastAttacks()
    }
}
