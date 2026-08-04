package com.xianxia.sect.ui.game.delegate

import android.content.ComponentCallbacks2
import android.util.Log
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.monitor.StallVerdict
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

        /**
         * 健康检查开关（测试环境禁用）。
         *
         * 健康检查每秒访问 gameEngineCore.tickCount/isPausedDirect——在
         * mockk relaxed mock 环境下每次属性访问都触发 Kotlin 反射
         *（findBackingField → 全类成员解析 → 大量类加载，MR-JAR 版本化
         * 条目读取极慢），实测 GameViewModelTest 卡死（jstack：
         * JarFile.getVersionedEntry）。测试环境无真实游戏循环，健康检查
         * 无意义，禁用之。
         *
         * 与 [com.xianxia.sect.core.state.DiscipleTables.writeGuardEnabled]
         * 同模式的测试开关。
         */
        @Volatile
        var healthCheckEnabled: Boolean = true
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
        if (!healthCheckEnabled) return  // 测试环境禁用（mock 反射卡死，见 companion）
        scope.launch(Dispatchers.Main) {
            var stallCount = 0
            while (isActive) {
                delay(1000)
                try {
                    // 统一判据：tickCount + 世界时间 + 暂停租约（GameEngineCore.progressVerdict）。
                    // PausedByOwner（用户主动暂停/秘境租约有效）豁免——用户暂停永不自动恢复
                    val verdict = gameEngineCore.progressVerdict()
                    if (verdict == StallVerdict.Healthy || verdict == StallVerdict.PausedByOwner) {
                        stallCount = 0
                        continue
                    }
                    stallCount++
                    if (stallCount >= 3) {
                        Log.w(TAG, "HealthCheck: game loop stalled (verdict=$verdict), emergency restarting")
                        withContext(Dispatchers.Default) {
                            gameEngineCore.handleWatchdogVerdict(verdict)
                        }
                        stallCount = 0
                    }
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
