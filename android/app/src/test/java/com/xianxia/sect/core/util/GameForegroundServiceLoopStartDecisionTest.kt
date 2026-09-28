package com.xianxia.sect.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GameForegroundServiceLoopStartDecisionTest —— 缺陷 #12（方案 §9.1）决策表锁定：
 * START_STICKY 系统重建（null intent）**不得**自动启动游戏循环，
 * 仅显式 [GameForegroundService.ACTION_START] 启动。
 *
 * 「切后台 = 停循环」口径（离线收益上限设计依赖）由本决策表代码化，
 * 不再依赖真机验证；循环恢复路径 = GameActivity.onResume（resumeFromBackground +
 * 显式 ACTION_START）与看门狗兜底（AlarmWatchdogReceiver 显式 ACTION_START）。
 */
class GameForegroundServiceLoopStartDecisionTest {

    @Test
    fun `explicit ACTION_START starts the loop`() {
        assertTrue(GameForegroundService.shouldAutoStartLoop(GameForegroundService.ACTION_START))
    }

    @Test
    fun `null intent from START_STICKY rebuild does not start the loop`() {
        assertFalse(GameForegroundService.shouldAutoStartLoop(null))
    }

    @Test
    fun `pause resume and stop actions do not start the loop`() {
        assertFalse(GameForegroundService.shouldAutoStartLoop(GameForegroundService.ACTION_PAUSE))
        assertFalse(GameForegroundService.shouldAutoStartLoop(GameForegroundService.ACTION_RESUME))
        assertFalse(GameForegroundService.shouldAutoStartLoop(GameForegroundService.ACTION_STOP))
    }

    @Test
    fun `unknown action does not start the loop`() {
        assertFalse(GameForegroundService.shouldAutoStartLoop("com.xianxia.sect.action.UNKNOWN"))
    }
}
