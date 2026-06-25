package com.xianxia.sect.core.perf

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 热状态枚举 — 对应 Android PowerManager 热状态级别
 */
enum class ThermalState {
    NORMAL, LIGHT, MODERATE, SEVERE, EMERGENCY
}

/**
 * ADPF Thermal API 集成 — 监控设备热状态，在过热时降低负载
 * 行业依据: https://developer.android.com/games/optimize/adpf
 */
@Singleton
class ThermalMonitor @Inject constructor(
    @ApplicationContext context: Context
) : ThermalStatusProvider {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val hintManager by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(PerformanceHintManager::class.java)
        } else null
    }

    private var hintSession: Any? = null  // PerformanceHintManager.Session on API 31+

    /** 上一次设置的目标持续时间，用于避免重复调用 updateTargetWorkDuration */
    @Volatile
    private var lastTargetDurationNanos: Long = 0L

    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    /** 当前热状态，以 StateFlow 形式暴露，供 UI 层收集 */
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    init {
        // 每 2 秒轮询一次热状态，避免高频查询
        scope.launch {
            while (true) {
                _thermalState.value = mapStatusToState(currentThermalStatus)
                delay(2000L)
            }
        }
    }

    /** 当前热状态 (0=NONE, 1=LIGHT, 2=MODERATE, 3=SEVERE, 4=Critical, 5=Emergency, 6=Shutdown)
     *  API 29+ 才支持；低版本始终返回 THERMAL_STATUS_NONE (0) */
    val currentThermalStatus: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: 0  // THERMAL_STATUS_NONE
        } else {
            0  // THERMAL_STATUS_NONE not available on API < 29
        }

    /** 是否应降低非关键计算负载 (MODERATE 及以上)。
     *  使用 _thermalState 缓存（每 2s 轮询更新），避免热路径上每 tick 触发 binder 调用。 */
    override fun shouldReduceWorkload(): Boolean =
        _thermalState.value >= ThermalState.MODERATE

    /** 是否应紧急保存并暂停 (SEVERE 及以上)。
     *  使用 _thermalState 缓存，理由同 [shouldReduceWorkload]。 */
    override fun shouldEmergencySave(): Boolean =
        _thermalState.value >= ThermalState.SEVERE

    /** 是否处于轻度过热状态 (LIGHT)。
     *  使用 _thermalState 缓存，理由同 [shouldReduceWorkload]。 */
    fun isLightThrottle(): Boolean =
        _thermalState.value == ThermalState.LIGHT

    private fun mapStatusToState(status: Int): ThermalState = when {
        status >= PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.EMERGENCY
        status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
        status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
        status >= PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
        else -> ThermalState.NORMAL
    }

    /**
     * 创建 Performance Hint Session。
     * 在游戏引擎启动时调用。
     */
    fun createHintSession(targetDurationNanos: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                hintSession = hintManager?.createHintSession(
                    intArrayOf(android.os.Process.myTid()),
                    targetDurationNanos
                )
                lastTargetDurationNanos = targetDurationNanos
            } catch (e: Exception) {
                // Performance Hint API 不可用，静默忽略
            }
        }
    }

    /**
     * 报告当前帧的工作持续时间。
     * 在每个 tick 完成后调用。
     */
    fun reportActualWorkDuration(durationNanos: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                (hintSession as? PerformanceHintManager.Session)?.reportActualWorkDuration(durationNanos)
            } catch (e: Exception) {
                // 静默忽略
            }
        }
    }

    /**
     * 更新目标持续时间。
     * 在热状态变化时调整。
     * 仅当目标值变化时才实际调用 hintSession，避免每 tick 重复设置相同值。
     */
    fun updateTargetDuration(targetDurationNanos: Long) {
        if (targetDurationNanos == lastTargetDurationNanos) return
        lastTargetDurationNanos = targetDurationNanos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                (hintSession as? PerformanceHintManager.Session)?.updateTargetWorkDuration(targetDurationNanos)
            } catch (e: Exception) {
                // 静默忽略
            }
        }
    }

    /**
     * 关闭 Hint Session。
     * 在游戏引擎关闭时调用。
     */
    fun closeHintSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                (hintSession as? PerformanceHintManager.Session)?.close()
            } catch (e: Exception) {
                // 静默忽略
            }
            hintSession = null
            lastTargetDurationNanos = 0L
        }
    }
}
