package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import kotlinx.coroutines.CoroutineScope

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
    private var isFighting = false  // 双击防抖

    /** 处理兽袭事件 — 选择进贡物资以平息该兽袭。 */
    suspend fun resolveBeastAttackPayTribute(beastLevelId: String): Boolean {
        val success = gameEngine.resolveBeastAttackPayTribute(beastLevelId)
        if (!success) {
            onMessage?.invoke("该妖兽已被击败，无需进贡", false)
        }
        return success
    }

    /** 处理兽袭事件 — 选择战斗抵抗（suspend，调用方 await 完成后清理）。 */
    suspend fun resolveBeastAttackFight(beastLevelId: String): Boolean {
        if (isFighting) return false  // 双击防抖
        isFighting = true
        try {
            val success = gameEngine.resolveBeastAttackFight(beastLevelId)
            if (!success) {
                onMessage?.invoke("该妖兽已被击败", true)
            }
            return success
        } finally {
            isFighting = false
        }
    }

    /** 清空所有待处理的兽袭事件。 */
    fun clearPendingBeastAttacks() {
        gameEngine.launchOnEngine { gameEngine.clearPendingBeastAttacks() }
    }

    /** 移除单个已处理的妖兽攻击（按 ID），其余保留。用于多妖兽逐个处理场景。 */
    fun removePendingBeastAttack(beastLevelId: String) {
        gameEngine.launchOnEngine { gameEngine.removePendingBeastAttack(beastLevelId) }
    }
}
