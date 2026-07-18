package com.xianxia.sect.core.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.xianxia.sect.core.util.DomainLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AndroidThermalReader — Android 平台三通道温度读取实现。
 *
 * ## 通道优先级
 * 1. `PowerManager.getThermalHeadroom(10)` (API 30+) — 主动预测热余量
 * 2. `PowerManager.currentThermalStatus` (API 29+) — 被动热状态
 * 3. sysfs `/sys/class/thermal/` + `BatteryManager` — 降级路径
 *
 * ## 使用限制
 * - `getThermalHeadroom()` 调用频率 ≤ 1Hz，超过返回 NaN
 * - 首次调用可能需要几秒采样才返回准确预测值
 * - 部分厂商设备 `currentThermalStatus` 始终返回 NONE，需结合 headroom 判断
 *
 * @param context Application context
 */
@Singleton
class AndroidThermalReader @Inject constructor(
    @ApplicationContext private val context: Context
) : ThermalReader {

    companion object {
        private const val TAG = "AndroidThermalReader"
        /** 查询间隔（ms）— 官方建议 ≤ 1Hz */
        private const val QUERY_INTERVAL_MS = 2_000L
        /** 预测跨度（秒） */
        private const val FORECAST_SECONDS = 10
        /** sysfs 热区文件路径 */
        private val THERMAL_PATHS = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp",
            "/sys/class/thermal/thermal_zone10/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone1/temp",
        )
    }

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var lastQueryMs = 0L
    private var registeredCallback: ((ThermalState) -> Unit)? = null

    /** 注册的热状态回调（平台级）— 惰性初始化避免 TapTap 环境类加载崩溃 */
    @get:RequiresApi(Build.VERSION_CODES.R)
    private val platformCallback: PowerManager.OnThermalStatusChangedListener by lazy {
        PowerManager.OnThermalStatusChangedListener { status ->
            val state = thermalStatusToState(status)
            DomainLog.d(TAG, "Thermal status callback: status=$status → $state")
            registeredCallback?.invoke(state)
        }
    }

    override val forecastSeconds: Int get() = FORECAST_SECONDS

    // ──────────────────────────────────────────────────────────
    // Channel 1: PowerManager.getThermalHeadroom() (API 30+)
    // ──────────────────────────────────────────────────────────

    override val thermalHeadroom: Float
        get() {
            val pm = powerManager ?: return Float.NaN
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN

            val now = System.currentTimeMillis()
            if (now - lastQueryMs < QUERY_INTERVAL_MS) return Float.NaN
            lastQueryMs = now

            return try {
                val hr = pm.getThermalHeadroom(FORECAST_SECONDS)
                if (hr.isNaN() || hr < 0f) Float.NaN else hr
            } catch (e: Exception) {
                DomainLog.w(TAG, "getThermalHeadroom failed: ${e.message}")
                Float.NaN
            }
        }

    // ──────────────────────────────────────────────────────────
    // Channel 2: PowerManager.currentThermalStatus (API 29+)
    // ──────────────────────────────────────────────────────────

    override val thermalState: ThermalState
        get() {
            val pm = powerManager ?: return ThermalState.UNKNOWN
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.UNKNOWN

            return try {
                thermalStatusToState(pm.currentThermalStatus)
            } catch (e: Exception) {
                DomainLog.w(TAG, "currentThermalStatus failed: ${e.message}")
                ThermalState.UNKNOWN
            }
        }

    private fun thermalStatusToState(status: Int): ThermalState = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.FAIR
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.SERIOUS
        PowerManager.THERMAL_STATUS_SEVERE,
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
        else -> ThermalState.UNKNOWN
    }

    // ──────────────────────────────────────────────────────────
    // Channel 3: sysfs + BatteryManager (fallback)
    // ──────────────────────────────────────────────────────────

    override val temperatureCelsius: Float
        get() {
            // 优先用 headroom 反推温度
            val hr = thermalHeadroom
            if (!hr.isNaN() && hr >= 0f) {
                return headroomToTemperature(hr)
            }

            // 降级 1: sysfs
            val sysfsTemp = readSysfsTemperature()
            if (sysfsTemp >= 0f) return sysfsTemp

            // 降级 2: BatteryManager
            return readBatteryTemperature()
        }

    /** 从 sysfs 热区文件读取温度 */
    private fun readSysfsTemperature(): Float {
        for (path in THERMAL_PATHS) {
            try {
                val content = File(path).readText().trim()
                val temp = content.toFloatOrNull() ?: continue
                val celsius = if (temp > 1000) temp / 1000f else temp
                if (celsius in 15f..80f) return celsius // 合理性检查
            } catch (_: Exception) { continue }
        }
        return -1f
    }

    /** 从 BatteryManager 读取电池温度 */
    private fun readBatteryTemperature(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return -1f
        return try {
            val batteryManagerClass = Class.forName("android.os.BatteryManager")
            val getIntProperty = batteryManagerClass.getMethod(
                "getIntProperty", Int::class.javaPrimitiveType
            )
            val temp = context.getSystemService(Context.BATTERY_SERVICE)?.let { service ->
                getIntProperty.invoke(service, 2 /* BATTERY_PROPERTY_TEMPERATURE */) as? Int
            } ?: return -1f
            if (temp == Int.MIN_VALUE) -1f else temp / 10f
        } catch (_: Exception) { -1f }
    }

    // ──────────────────────────────────────────────────────────
    // Headroom → 温度估算（经验公式）
    // ──────────────────────────────────────────────────────────

    /** headroom (0.0~1.0+) → 估算温度 (°C) */
    private fun headroomToTemperature(headroom: Float): Float = when {
        headroom >= 1.0f -> 46f  // SEVERE+
        headroom >= 0.95f -> 44f // MODERATE→SEVERE
        headroom >= 0.85f -> 42f // MODERATE
        headroom >= 0.5f -> 40f  // LIGHT
        headroom >= 0.05f -> 38f // 轻微发热
        else -> 36f              // NONE
    }

    // ──────────────────────────────────────────────────────────
    // 回调注册（API 29+）
    // ──────────────────────────────────────────────────────────

    override fun registerThermalCallback(onStateChanged: (ThermalState) -> Unit): Boolean {
        val pm = powerManager ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        return try {
            registeredCallback = onStateChanged
            pm.addThermalStatusListener(platformCallback)
            DomainLog.i(TAG, "Thermal callback registered")
            true
        } catch (e: Exception) {
            DomainLog.w(TAG, "addThermalStatusListener failed: ${e.message}")
            registeredCallback = null
            false
        }
    }

    override fun unregisterThermalCallback() {
        val pm = powerManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                pm.removeThermalStatusListener(platformCallback)
            } catch (_: Exception) { }
        }
        registeredCallback = null
    }
}
