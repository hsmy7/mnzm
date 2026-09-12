package com.xianxia.sect.platform

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import com.xianxia.sect.core.perf.PerformanceHintPort
import com.xianxia.sect.core.perf.ThermalStatusReader
import com.xianxia.sect.core.util.DomainLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android 热状态读取实现（ThermalStatusReader 端口）。
 * 语义随 ThermalMonitor 端口化自原 ThermalMonitor 构造期逻辑原样迁入。
 */
@Singleton
class AndroidThermalStatusReader @Inject constructor(
    @ApplicationContext context: Context
) : ThermalStatusReader {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override val currentThermalStatus: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: 0  // THERMAL_STATUS_NONE
        } else {
            0  // THERMAL_STATUS_NONE not available on API < 29
        }
}

/**
 * Android ADPF PerformanceHint 会话实现（PerformanceHintPort 端口）。
 * 只做裸 API 调用与能力探测；线程绑定守卫/异常降级全部在引擎侧 ThermalMonitor。
 */
@Singleton
class AndroidPerformanceHintPort @Inject constructor(
    @ApplicationContext context: Context
) : PerformanceHintPort {

    // ADPF 为可选能力：部分 ROM/老设备未发布 performance_hint 服务，
    // getSystemService(Class) 会抛 ServiceNotFoundException——降级 null
    //（acquire 返回 null，引擎侧空守卫自动停用）
    private val hintManager: PerformanceHintManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("TooGenericExceptionCaught")
            try {
                context.getSystemService(PerformanceHintManager::class.java)
            } catch (e: Exception) {
                DomainLog.w(TAG, "performance_hint unavailable: ${e.message}")
                null
            }
        } else null

    override val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override fun acquire(tids: IntArray, targetDurationNanos: Long): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return hintManager?.createHintSession(tids, targetDurationNanos)
    }

    override fun reportWorkDuration(session: Any, durationNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        (session as PerformanceHintManager.Session).reportActualWorkDuration(durationNanos)
    }

    override fun updateTargetDuration(session: Any, targetDurationNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        (session as PerformanceHintManager.Session).updateTargetWorkDuration(targetDurationNanos)
    }

    override fun release(session: Any) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        (session as PerformanceHintManager.Session).close()
    }

    override fun currentThreadId(): Int = android.os.Process.myTid()

    private companion object {
        const val TAG = "AndroidPerfHintPort"
    }
}
