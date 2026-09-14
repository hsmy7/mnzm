package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.engine.GameEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 凶兽袭击事件处理委托。
 *
 * 职责：排期妖兽攻击的战斗触发（世界地图手动进攻）/ 移除单个已处理排期。
 */
class BeastAttackDelegate(
    private val gameEngine: GameEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onMessage: ((message: String, isError: Boolean) -> Unit)? = null
) {
    private var isFighting = false  // 双击防抖

    /** 处理兽袭事件 — 选择战斗抵抗（suspend，调用方 await 完成后清理）。 */
    suspend fun resolveBeastAttackFight(beastLevelId: String): Boolean = withContext(dispatcher) {
        if (isFighting) return@withContext false  // 双击防抖
        isFighting = true
        try {
            val success = gameEngine.resolveBeastAttackFight(beastLevelId)
            if (!success) {
                onMessage?.invoke("该妖兽已被击败", true)
            }
            success
        } finally {
            isFighting = false
        }
    }

    /** 锁定妖兽：打开详情弹窗时调用，月度结算跳过 AI 攻击 */
    fun lockBeast(beastId: String) {
        gameEngine.launchOnEngine { gameEngine.lockBeastView(beastId) }
    }

    /** 解锁妖兽：关闭详情弹窗时调用，AI 可正常进攻 */
    fun unlockBeast(beastId: String) {
        if (beastId.isEmpty()) return
        gameEngine.launchOnEngine { gameEngine.unlockBeastView(beastId) }
    }

    /** 移除单个已处理的妖兽攻击（按 ID），其余保留。用于多妖兽逐个处理场景。 */
    fun removePendingBeastAttack(beastLevelId: String) {
        gameEngine.launchOnEngine { gameEngine.removePendingBeastAttack(beastLevelId) }
    }
}
