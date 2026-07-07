package com.xianxia.sect.core.thermal

/**
 * ThermalReader — 跨平台热状态读取接口。
 *
 * 三通道策略：
 * 1. `getThermalHeadroom()` (API 30+) — 首选，主动预测未来热状态
 * 2. `addThermalStatusListener()` (API 29+) — 辅助，被动回调
 * 3. 降级：sysfs + BatteryManager — 覆盖 minSdk=24
 *
 * iOS 移植时实现 [thermalState] 即可。
 */
interface ThermalReader {

    /**
     * 热余量 (0.0~1.0+)。
     * - 0.0 = 无热压力
     * - 0.85 = 即将降频
     * - 1.0 = 严重降频
     * - NaN = 不可用
     *
     * 预测未来 [forecastSeconds] 秒的热状态。
     * 调用频率 ≤ 1Hz，超过返回 NaN。
     */
    val thermalHeadroom: Float

    /** 热余量预测的时间跨度（秒），默认 10 */
    val forecastSeconds: Int get() = 10

    /**
     * 当前热状态等级（兼容 iOS ProcessInfo.ThermalState）。
     * - NOMINAL — 正常
     * - FAIR — 轻度，可考虑降级
     * - SERIOUS — 严重，需立即降级
     * - CRITICAL — 临界，最低功耗运行
     * - UNKNOWN — 不可用
     */
    val thermalState: ThermalState

    /**
     * 温度值（°C），-1f 表示不可用。
     * 优先使用 [thermalHeadroom] 估算，降级时尝试 sysfs/BatteryManager。
     */
    val temperatureCelsius: Float

    /**
     * 注册热状态变化回调。
     * 当热状态变化时调用 [onStateChanged]。
     * 返回 false 表示该平台不支持回调注册。
     */
    fun registerThermalCallback(onStateChanged: (ThermalState) -> Unit): Boolean

    /** 注销热状态回调 */
    fun unregisterThermalCallback()
}

/**
 * 热状态枚举 — 与 iOS ProcessInfo.ThermalState 对齐。
 *
 * 映射关系：
 * - NOMINAL  ↔ iOS .nominal  ↔ Android THERMAL_STATUS_NONE
 * - FAIR     ↔ iOS .fair     ↔ Android THERMAL_STATUS_LIGHT
 * - SERIOUS  ↔ iOS .serious  ↔ Android THERMAL_STATUS_MODERATE
 * - CRITICAL ↔ iOS .critical ↔ Android THERMAL_STATUS_SEVERE+
 * - UNKNOWN  — 平台不可用
 */
enum class ThermalState {
    NOMINAL,
    FAIR,
    SERIOUS,
    CRITICAL,
    UNKNOWN
}
