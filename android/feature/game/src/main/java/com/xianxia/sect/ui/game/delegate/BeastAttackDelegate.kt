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
    private val scope: CoroutineScope,
    private val onMessage: ((message: String, isError: Boolean) -> Unit)? = null
) {
    /** 处理兽袭事件 — 选择进贡物资以平息该兽袭。 */
    suspend fun resolveBeastAttackPayTribute(beastLevelId: String): Boolean {
        val success = gameEngine.resolveBeastAttackPayTribute(beastLevelId)
        if (!success) {
            onMessage?.invoke("该妖兽已被击败，无需进贡", false)
        }
        return success
    }

    /** 处理兽袭事件 — 选择战斗抵抗。 */
    fun resolveBeastAttackFight(beastLevelId: String) {
        scope.launch {
            val success = gameEngine.resolveBeastAttackFight(beastLevelId)
            if (!success) {
                onMessage?.invoke("该妖兽已被击败", true)
            }
        }
    }

    /** 清空所有待处理的兽袭事件。 */
    fun clearPendingBeastAttacks() {
        gameEngine.clearPendingBeastAttacks()
    }
}
