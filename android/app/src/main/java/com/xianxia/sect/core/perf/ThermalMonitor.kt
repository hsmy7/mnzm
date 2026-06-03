package com.xianxia.sect.core.perf

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADPF Thermal API 集成 — 监控设备热状态，在过热时降低负载
 * 行业依据: https://developer.android.com/games/optimize/adpf
 */
@Singleton
class ThermalMonitor @Inject constructor(
    @ApplicationContext context: Context
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /** 当前热状态 (0=NONE, 1=LIGHT, 2=MODERATE, 3=SEVERE, 4=Critical, 5=Emergency, 6=Shutdown) */
    val currentThermalStatus: Int
        get() = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE

    /** 是否应降低非关键计算负载 (MODERATE 及以上) */
    fun shouldReduceWorkload(): Boolean =
        currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    /** 是否应紧急保存并暂停 (SEVERE 及以上) */
    fun shouldEmergencySave(): Boolean =
        currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

    /** 是否处于轻度过热状态 (LIGHT) */
    fun isLightThrottle(): Boolean =
        currentThermalStatus == PowerManager.THERMAL_STATUS_LIGHT
}
