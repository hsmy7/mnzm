package com.xianxia.sect.core.state

import org.junit.Assert.*
import org.junit.Test

/**
 * GameLifecycle 状态机序列表述测试。
 *
 * ordinal 顺序和 comparison 操作符作为设计文档，
 * 不强制运行时校验（加载管线错误恢复需要跳级/重置）。
 */
class GameLifecycleTransitionTest {

    @Test
    fun `ordinal sequence follows lifecycle progression`() {
        val transitions = listOf(
            GameLifecycle.UNINITIALIZED,
            GameLifecycle.DATA_READY,
            GameLifecycle.SYSTEMS_READY,
            GameLifecycle.MAP_READY,
            GameLifecycle.PLAYING
        )
        transitions.zipWithNext { current, next ->
            assertEquals(
                "正常顺序转移: $current → $next",
                current.ordinal + 1, next.ordinal
            )
        }
    }

    @Test
    fun `ordinal sequence is monotonic`() {
        GameLifecycle.entries.zipWithNext { current, next ->
            assertEquals(
                "状态 ordinal 必须严格递增",
                current.ordinal + 1, next.ordinal
            )
        }
    }

    @Test
    fun `comparison operators reflect progression`() {
        assertTrue(GameLifecycle.UNINITIALIZED < GameLifecycle.DATA_READY)
        assertTrue(GameLifecycle.DATA_READY < GameLifecycle.SYSTEMS_READY)
        assertTrue(GameLifecycle.SYSTEMS_READY < GameLifecycle.MAP_READY)
        assertTrue(GameLifecycle.MAP_READY < GameLifecycle.PLAYING)

        assertTrue(GameLifecycle.MAP_READY >= GameLifecycle.MAP_READY)
        assertTrue(GameLifecycle.PLAYING >= GameLifecycle.MAP_READY)
        assertTrue(GameLifecycle.MAP_READY > GameLifecycle.DATA_READY)
    }

    @Test
    fun `map_ready plus is valid ui transition threshold`() {
        assertTrue("MAP_READY 应 >= MAP_READY",
            GameLifecycle.MAP_READY >= GameLifecycle.MAP_READY)
        assertTrue("PLAYING 应 >= MAP_READY",
            GameLifecycle.PLAYING >= GameLifecycle.MAP_READY)
        assertFalse("UNINITIALIZED 不应 >= MAP_READY",
            GameLifecycle.UNINITIALIZED >= GameLifecycle.MAP_READY)
        assertFalse("DATA_READY 不应 >= MAP_READY",
            GameLifecycle.DATA_READY >= GameLifecycle.MAP_READY)
    }
}
