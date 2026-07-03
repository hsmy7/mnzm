package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.system.GameTimeClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * 暂停/恢复/调速管理委托 — 控制游戏时钟的暂停与恢复。
 */
class SaveLoadPauseDelegate(
    private val gameEngineCore: GameEngineCore,
    private val gameClock: GameTimeClock
) {
    private val TAG = "SaveLoadPauseDelegate"

    private val _timeScale = MutableStateFlow(1)
    val timeScale: StateFlow<Int> = _timeScale.asStateFlow()

    val isPaused: Flow<Boolean> = gameEngineCore.state
        .map { it.isPaused }

    var wasRunningBeforeBackground = false

    suspend fun togglePause() {
        if (gameEngineCore.state.value.isPaused) {
            gameEngineCore.resume()
            if (!gameEngineCore.isGameLoopRunning) {
                gameEngineCore.startGameLoop()
            }
        } else {
            gameEngineCore.pause()
        }
        Log.d(TAG, "Pause toggled: paused=${gameEngineCore.state.value.isPaused}")
    }

    fun setTimeSpeed(speed: Int) {
        _timeScale.value = speed
        gameClock.setSpeed(speed)
        Log.d(TAG, "Time speed set to: $speed")
    }

    fun pauseForBackground() {
        wasRunningBeforeBackground = gameEngineCore.isGameLoopRunning
        if (wasRunningBeforeBackground) {
            gameEngineCore.stopGameLoop()
            Log.d(TAG, "Game loop stopped for background")
        }
    }

    fun resumeFromBackground() {
        if (wasRunningBeforeBackground) {
            gameEngineCore.startGameLoop()
            Log.d(TAG, "Game loop resumed from background")
        }
    }
}
