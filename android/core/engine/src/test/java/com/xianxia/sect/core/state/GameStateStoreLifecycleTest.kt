@file:Suppress("DEPRECATION") // 旧 API 兼容层测试，有意使用 GameLifecycle

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

        // 重新正常推进（必须按 ordinal+1 顺序）
        store.transitionTo(GameLifecycle.DATA_READY)
        assertEquals(GameLifecycle.DATA_READY, store.gameLifecycle.value)

        store.transitionTo(GameLifecycle.SYSTEMS_READY)
        assertEquals(GameLifecycle.SYSTEMS_READY, store.gameLifecycle.value)

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

    // ==================== BootPhase 新 API ====================

    @Test
    fun `bootPhase default is UNINITIALIZED`() {
        val store = TestLifecycleStore()
        assertEquals(BootPhase.UNINITIALIZED, store.bootPhase.value)
    }

    @Test
    fun `advanceBootPhase progresses forward`() {
        val store = TestLifecycleStore()
        assertEquals(BootPhase.UNINITIALIZED, store.bootPhase.value)

        store.advanceBootPhase()
        assertEquals(BootPhase.DATA_READY, store.bootPhase.value)

        store.advanceBootPhase()
        assertEquals(BootPhase.SYSTEMS_READY, store.bootPhase.value)

        store.advanceBootPhase()
        assertEquals(BootPhase.MAP_READY, store.bootPhase.value)

        store.advanceBootPhase()
        assertEquals(BootPhase.BOOT_COMPLETE, store.bootPhase.value)
    }

    @Test(expected = IllegalStateException::class)
    fun `advanceBootPhase throws after BOOT_COMPLETE`() {
        val store = TestLifecycleStore()
        store.advanceBootPhase() // → DATA_READY
        store.advanceBootPhase() // → SYSTEMS_READY
        store.advanceBootPhase() // → MAP_READY
        store.advanceBootPhase() // → BOOT_COMPLETE
        store.advanceBootPhase() // 应抛异常
    }

    @Test
    fun `resetBootPhase goes to UNINITIALIZED`() {
        val store = TestLifecycleStore()
        store.forceLifecycle(GameLifecycle.MAP_READY)
        assertEquals(GameLifecycle.MAP_READY, store.gameLifecycle.value)

        store.resetBootPhase()
        assertEquals(BootPhase.UNINITIALIZED, store.bootPhase.value)
        assertEquals(GameLifecycle.UNINITIALIZED, store.gameLifecycle.value)
    }

    // ==================== RunState 新 API ====================

    @Test
    fun `runState default is IDLE`() {
        val store = TestLifecycleStore()
        assertEquals(RunState.IDLE, store.runState.value)
    }

    @Test
    fun `setPlaying sets runState to PLAYING and syncs gameLifecycle`() {
        val store = TestLifecycleStore()
        store.advanceBootPhase() // → DATA_READY
        store.advanceBootPhase() // → SYSTEMS_READY
        store.advanceBootPhase() // → MAP_READY
        store.advanceBootPhase() // → BOOT_COMPLETE

        assertEquals(GameLifecycle.MAP_READY, store.gameLifecycle.value)

        store.setPlaying()
        assertEquals(RunState.PLAYING, store.runState.value)
        assertEquals(GameLifecycle.PLAYING, store.gameLifecycle.value)
    }

    @Test
    fun `setReloading sets runState to RELOADING`() {
        val store = TestLifecycleStore()
        store.advanceBootPhase()
        store.advanceBootPhase()
        store.advanceBootPhase()
        store.advanceBootPhase()
        store.setPlaying()
        assertEquals(RunState.PLAYING, store.runState.value)

        store.setReloading()
        assertEquals(RunState.RELOADING, store.runState.value)
    }

    @Test
    fun `gameLifecycle derives from bootPhase and runState`() {
        val store = TestLifecycleStore()

        // UNINITIALIZED + IDLE → UNINITIALIZED
        assertEquals(GameLifecycle.UNINITIALIZED, store.gameLifecycle.value)

        // DATA_READY → DATA_READY
        store.advanceBootPhase()
        assertEquals(GameLifecycle.DATA_READY, store.gameLifecycle.value)

        // SYSTEMS_READY → SYSTEMS_READY
        store.advanceBootPhase()
        assertEquals(GameLifecycle.SYSTEMS_READY, store.gameLifecycle.value)

        // MAP_READY → MAP_READY
        store.advanceBootPhase()
        assertEquals(GameLifecycle.MAP_READY, store.gameLifecycle.value)

        // BOOT_COMPLETE but not PLAYING → MAP_READY
        store.advanceBootPhase()
        assertEquals(GameLifecycle.MAP_READY, store.gameLifecycle.value)

        // BOOT_COMPLETE + PLAYING → PLAYING
        store.setPlaying()
        assertEquals(GameLifecycle.PLAYING, store.gameLifecycle.value)
    }

    @Test
    fun `forceLifecycle resets both bootPhase and runState`() {
        val store = TestLifecycleStore()
        store.advanceBootPhase()
        store.advanceBootPhase()
        store.setPlaying()

        // forceLifecycle(UNINITIALIZED) → both reset
        store.forceLifecycle(GameLifecycle.UNINITIALIZED)
        assertEquals(BootPhase.UNINITIALIZED, store.bootPhase.value)
        assertEquals(RunState.IDLE, store.runState.value)
        assertEquals(GameLifecycle.UNINITIALIZED, store.gameLifecycle.value)
    }
}

/**
 * 轻量级测试桩——模拟 GameStateStore 生命周期双接口行为。
 * 同时实现新旧两套 API，确保迁移期间测试兼容。
 */
private class TestLifecycleStore {

    // 新 API（基础存储）
    private val _bootPhase = MutableStateFlow(BootPhase.UNINITIALIZED)
    private val _runState = MutableStateFlow(RunState.IDLE)
    val bootPhase = _bootPhase.asStateFlow()
    val runState = _runState.asStateFlow()

    // 旧 API（由新 API 派生）
    private val _gameLifecycle = MutableStateFlow(GameLifecycle.UNINITIALIZED)
    val gameLifecycle = _gameLifecycle.asStateFlow()

    private fun syncLifecycle() {
        _gameLifecycle.value = when {
            _runState.value == RunState.PLAYING && _bootPhase.value >= BootPhase.BOOT_COMPLETE -> GameLifecycle.PLAYING
            _bootPhase.value >= BootPhase.MAP_READY -> GameLifecycle.MAP_READY
            _bootPhase.value >= BootPhase.SYSTEMS_READY -> GameLifecycle.SYSTEMS_READY
            _bootPhase.value >= BootPhase.DATA_READY -> GameLifecycle.DATA_READY
            else -> GameLifecycle.UNINITIALIZED
        }
    }

    // 新 API 方法
    fun advanceBootPhase() {
        val current = _bootPhase.value
        val nextOrdinal = current.ordinal + 1
        check(nextOrdinal <= BootPhase.entries.lastIndex) {
            "Already at terminal boot phase: $current"
        }
        _bootPhase.value = BootPhase.entries[nextOrdinal]
        syncLifecycle()
    }

    fun resetBootPhase() {
        _bootPhase.value = BootPhase.UNINITIALIZED
        _runState.value = RunState.IDLE
        syncLifecycle()
    }

    fun setPlaying() {
        _runState.value = RunState.PLAYING
        syncLifecycle()
    }

    fun setReloading() {
        _runState.value = RunState.RELOADING
        syncLifecycle()
    }

    // 旧 API 方法（委托到新 API）
    fun transitionTo(state: GameLifecycle) {
        val targetBoot = when (state) {
            GameLifecycle.DATA_READY -> BootPhase.DATA_READY
            GameLifecycle.SYSTEMS_READY -> BootPhase.SYSTEMS_READY
            GameLifecycle.MAP_READY -> BootPhase.MAP_READY
            GameLifecycle.PLAYING -> BootPhase.BOOT_COMPLETE
            GameLifecycle.UNINITIALIZED -> throw IllegalStateException("Cannot transitionTo UNINITIALIZED")
        }
        val current = _bootPhase.value
        check(current.ordinal + 1 == targetBoot.ordinal) {
            "Illegal lifecyle transition: $current → $state"
        }
        _bootPhase.value = targetBoot
        if (state == GameLifecycle.PLAYING) {
            _runState.value = RunState.PLAYING
        }
        syncLifecycle()
    }

    fun forceLifecycle(state: GameLifecycle) {
        when (state) {
            GameLifecycle.UNINITIALIZED -> {
                _bootPhase.value = BootPhase.UNINITIALIZED
                _runState.value = RunState.IDLE
            }
            GameLifecycle.PLAYING -> {
                _bootPhase.value = BootPhase.BOOT_COMPLETE
                _runState.value = RunState.PLAYING
            }
            else -> {
                _bootPhase.value = when (state) {
                    GameLifecycle.DATA_READY -> BootPhase.DATA_READY
                    GameLifecycle.SYSTEMS_READY -> BootPhase.SYSTEMS_READY
                    GameLifecycle.MAP_READY -> BootPhase.MAP_READY
                    else -> return
                }
            }
        }
        syncLifecycle()
    }
}
