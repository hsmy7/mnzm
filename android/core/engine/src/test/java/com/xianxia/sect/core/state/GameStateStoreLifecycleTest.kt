package com.xianxia.sect.core.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * GameStateStore 生命周期双接口验证。
 *
 * - transitionTo: 正常路径，校验 ordinal+1，非法转移抛 IllegalStateException
 * - forceLifecycle: 错误恢复，无条件跳转
 */
class GameStateStoreLifecycleTest {

    // ==================== transitionTo（严格校验） ====================

    @Test
    fun `default lifecycle is UNINITIALIZED`() {
        val store = TestLifecycleStore()
        assertEquals(GameLifecycle.UNINITIALIZED, store.gameLifecycle.value)
    }

    @Test
    fun `transitionTo follows forward progression`() {
        val store = TestLifecycleStore()

        store.transitionTo(GameLifecycle.DATA_READY)
        assertEquals(GameLifecycle.DATA_READY, store.gameLifecycle.value)

        store.transitionTo(GameLifecycle.SYSTEMS_READY)
        assertEquals(GameLifecycle.SYSTEMS_READY, store.gameLifecycle.value)

        store.transitionTo(GameLifecycle.MAP_READY)
        assertEquals(GameLifecycle.MAP_READY, store.gameLifecycle.value)

        store.transitionTo(GameLifecycle.PLAYING)
        assertEquals(GameLifecycle.PLAYING, store.gameLifecycle.value)
    }

    @Test(expected = IllegalStateException::class)
    fun `transitionTo throws on skip`() {
        val store = TestLifecycleStore()
        store.transitionTo(GameLifecycle.PLAYING) // UNINITIALIZED → PLAYING 跳级
    }

    @Test(expected = IllegalStateException::class)
    fun `transitionTo throws on regress`() {
        val store = TestLifecycleStore()
        store.transitionTo(GameLifecycle.DATA_READY)
        store.transitionTo(GameLifecycle.UNINITIALIZED) // 回退
    }

    @Test(expected = IllegalStateException::class)
    fun `transitionTo throws on self-loop`() {
        val store = TestLifecycleStore()
        store.transitionTo(GameLifecycle.DATA_READY)
        store.transitionTo(GameLifecycle.DATA_READY) // 自环
    }

    // ==================== forceLifecycle（无条件跳转） ====================

    @Test
    fun `forceLifecycle allows skipping states`() {
        val store = TestLifecycleStore()
        store.forceLifecycle(GameLifecycle.PLAYING) // UNINITIALIZED → PLAYING
        assertEquals(GameLifecycle.PLAYING, store.gameLifecycle.value)
    }

    @Test
    fun `forceLifecycle allows regressing`() {
        val store = TestLifecycleStore()
        store.forceLifecycle(GameLifecycle.DATA_READY)
        store.forceLifecycle(GameLifecycle.UNINITIALIZED) // 回退
        assertEquals(GameLifecycle.UNINITIALIZED, store.gameLifecycle.value)
    }

    @Test
    fun `forceLifecycle allows self-loop`() {
        val store = TestLifecycleStore()
        store.forceLifecycle(GameLifecycle.DATA_READY)

        store.forceLifecycle(GameLifecycle.DATA_READY) // 自环不抛异常
        assertEquals(GameLifecycle.DATA_READY, store.gameLifecycle.value)
    }

    @Test
    fun `forceLifecycle then transitionTo works for restart`() {
        val store = TestLifecycleStore()

        // 模拟重启：从 PLAYING 往下走 complete reset
        store.forceLifecycle(GameLifecycle.PLAYING)
        assertEquals(GameLifecycle.PLAYING, store.gameLifecycle.value)

        store.forceLifecycle(GameLifecycle.UNINITIALIZED) // 重置
        assertEquals(GameLifecycle.UNINITIALIZED, store.gameLifecycle.value)

        // 重新正常推进
        store.transitionTo(GameLifecycle.MAP_READY)
        assertEquals(GameLifecycle.MAP_READY, store.gameLifecycle.value)

        store.transitionTo(GameLifecycle.PLAYING)
        assertEquals(GameLifecycle.PLAYING, store.gameLifecycle.value)
    }

    // ==================== StateFlow 发射 ====================

    @Test
    fun `gameLifecycle StateFlow emits on transitionTo`() = runBlocking {
        val store = TestLifecycleStore()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        val emitted = mutableListOf<GameLifecycle>()
        val job = scope.launch {
            store.gameLifecycle.collect { emitted.add(it) }
        }

        store.transitionTo(GameLifecycle.DATA_READY)
        store.transitionTo(GameLifecycle.SYSTEMS_READY)
        store.transitionTo(GameLifecycle.MAP_READY)
        store.transitionTo(GameLifecycle.PLAYING)

        job.cancel()
        scope.cancel()

        assertEquals(5, emitted.size)
    }
}

/**
 * 轻量级测试桩——模拟 GameStateStore 生命周期双接口行为。
 */
private class TestLifecycleStore {

    private val _gameLifecycle = MutableStateFlow(GameLifecycle.UNINITIALIZED)
    val gameLifecycle = _gameLifecycle.asStateFlow()

    fun transitionTo(state: GameLifecycle) {
        val current = _gameLifecycle.value
        check(current.ordinal + 1 == state.ordinal) {
            "Illegal lifecyle transition: $current → $state"
        }
        _gameLifecycle.value = state
    }

    fun forceLifecycle(state: GameLifecycle) {
        _gameLifecycle.value = state
    }
}
