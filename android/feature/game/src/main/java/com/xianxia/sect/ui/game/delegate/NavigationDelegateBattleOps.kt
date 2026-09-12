package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.engine.attackWorldLevel
import com.xianxia.sect.core.engine.clearPendingBattleResult

// ── 战斗域操作扩展（自 NavigationDelegate 拆出，行为零变更）──────────────────
// attackWorldLevel / dismissBattleResult 是寄居在路由门面里的战斗域操作；
// batch-02 TooManyFunctions 收敛（类内 ≤19）外移为同包扩展，调用点语法不变。

private const val TAG = "NavigationDelegate"

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
fun NavigationDelegate.attackWorldLevel(levelId: String, discipleIds: List<String?>) {
    gameEngine.launchOnEngine {
        try {
            gameEngine.attackWorldLevel(levelId, discipleIds)
        } catch (e: Exception) {
            Log.e(TAG, "attackWorldLevel failed: levelId=$levelId", e)
        }
    }
}

fun NavigationDelegate.dismissBattleResult() {
    gameEngine.launchOnEngine { gameEngine.clearPendingBattleResult() }
}
