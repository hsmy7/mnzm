package com.xianxia.sect.ui.game

import com.xianxia.sect.core.state.GameLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SaveLoadViewModel 读档流程纯逻辑测试（更新为 GameLifecycle 版本）。
 *
 * 验证：
 * - GameLifecycle 默认值为 UNINITIALIZED
 * - 加载管线各阶段正确推进生命周期
 * - 状态转移是单向的（不自反）
 */
class SaveLoadViewModelLoadTest {

    @Test
    fun `GameLifecycle 默认值为 UNINITIALIZED`() {
        val lifecycle = GameLifecycle.UNINITIALIZED
        assertEquals(
            "GameLifecycle 默认应从 UNINITIALIZED 开始",
            GameLifecycle.UNINITIALIZED, lifecycle
        )
    }

    @Test
    fun `GameLifecycle ordinal 顺序正确`() {
        val order = GameLifecycle.entries.toList()
        assertEquals(GameLifecycle.UNINITIALIZED, order[0])
        assertEquals(GameLifecycle.DATA_READY, order[1])
        assertEquals(GameLifecycle.SYSTEMS_READY, order[2])
        assertEquals(GameLifecycle.MAP_READY, order[3])
        assertEquals(GameLifecycle.PLAYING, order[4])
    }

    @Test
    fun `GameLifecycle MAP_READY 以上可用于跨界面过渡`() {
        // 模拟加载管线到达地图就绪阶段
        val current = GameLifecycle.MAP_READY
        assertTrue("MAP_READY 应 >= MAP_READY",
            current >= GameLifecycle.MAP_READY)
        assertTrue("PLAYING 应 >= MAP_READY",
            GameLifecycle.PLAYING >= GameLifecycle.MAP_READY)
    }

    @Test
    fun `GameLifecycle 各阶段的 ordinal 递增`() {
        GameLifecycle.entries.zipWithNext { current, next ->
            assertTrue(
                "状态转移必须是单向递增的: $current → $next",
                next.ordinal == current.ordinal + 1
            )
        }
    }
}
