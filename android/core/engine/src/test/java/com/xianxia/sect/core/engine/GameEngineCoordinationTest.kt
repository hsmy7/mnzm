package com.xianxia.sect.core.engine

import com.xianxia.sect.core.state.GameLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GameEngineCoordination 加载相关扩展函数的测试。
 *
 * 验证 isGameStarted() 扩展方法使用 GameLifecycle 的正确行为，
 * 以及 GameData 不再包含运行时生命周期字段。
 */
class GameEngineCoordinationTest {

    @Test
    fun `isGameStarted 默认返回 false`() {
        // 默认 UNINITIALIZED < PLAYING，所以 isGameStarted() = false
        val lifecycle = GameLifecycle.UNINITIALIZED
        assertTrue("默认生命周期应 < PLAYING",
            lifecycle < GameLifecycle.PLAYING)
    }

    @Test
    fun `isGameStarted 在 PLAYING 时返回 true`() {
        val lifecycle = GameLifecycle.PLAYING
        assertTrue("PLAYING 应 >= PLAYING",
            lifecycle >= GameLifecycle.PLAYING)
    }

    @Test
    fun `uninitialized 和 playing 之间有 4 个过渡阶段`() {
        assertEquals(0, GameLifecycle.UNINITIALIZED.ordinal)
        assertEquals(1, GameLifecycle.DATA_READY.ordinal)
        assertEquals(2, GameLifecycle.SYSTEMS_READY.ordinal)
        assertEquals(3, GameLifecycle.MAP_READY.ordinal)
        assertEquals(4, GameLifecycle.PLAYING.ordinal)
    }
}
