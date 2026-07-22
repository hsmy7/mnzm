package com.xianxia.sect.ui.game.delegate

import android.content.ComponentCallbacks2
import android.util.Log
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GameLoopDelegate(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val systemManager: SystemManager,
    private val scope: CoroutineScope,
    private val onShowError: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "GameLoopDelegate"
    }

    init {
        scope.launch(Dispatchers.Default) {
            systemManager.errors.collect { error ->
                val msg = error.error.stackTraceToString()
                Log.e(TAG, "System error in ${error.systemName} (${error.tickType}): $msg")
                try {
                    val crashReport = Class.forName("com.tencent.bugly.crashreport.CrashReport")
                    crashReport.getMethod("postCatchedException", Throwable::class.java)
                        .invoke(null, error.error)
                } catch (e: Exception) { Log.w(TAG, "Bugly not available", e) }
                onShowError("系统异常：${error.systemName}")
            }
        }
        launchMainThreadHealthCheck()
    }

    private fun launchMainThreadHealthCheck() {
        scope.launch(Dispatchers.Main) {
            var lastTick = 0L
            var stallCount = 0
            while (isActive) {
                delay(1000)
                try {
                    val currentTick = gameEngineCore.tickCount.value
                    if (gameEngineCore.isPausedDirect) { stallCount = 0; lastTick = currentTick; continue }
                    if (currentTick == lastTick) {
                        stallCount++
                        if (stallCount >= 3) {
                            Log.w(TAG, "HealthCheck: game loop stalled, emergency restarting")
                            withContext(Dispatchers.Default) { gameEngineCore.emergencyRestartGameLoop() }
                            stallCount = 0
                        }
                    } else { stallCount = 0 }
                    lastTick = currentTick
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { DomainLog.e(TAG, "HealthCheck: error", e) }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun onMemoryPressure(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            Log.w(TAG, "Memory pressure: $level, releasing resources")
            gameEngine.releaseMemory(level)
        }
    }

    fun clearResources() {
        Log.i(TAG, "Clearing GameViewModel resources")
        try { gameEngineCore.stopGameLoop() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Log.w(TAG, "stopGameLoop failed: ${e.message}") }
    }

    private val _cultivationProgress = MutableStateFlow(0f)
    val cultivationProgress: StateFlow<Float> = _cultivationProgress.asStateFlow()

    fun updateCultivationProgress(target: Float) {
        scope.launch {
            val current = _cultivationProgress.value
            val diff = target - current
            val steps = 6
            for (i in 1..steps) {
                _cultivationProgress.value = current + diff * i / steps
                delay(16)
            }
            _cultivationProgress.value = target
        }
    }

    @Volatile
    var interpolationFactor: Float = 0f
        private set

    fun updateInterpolationFactor(alpha: Float) { interpolationFactor = alpha }
}
