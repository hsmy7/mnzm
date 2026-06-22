package com.xianxia.sect.core.engine

import android.os.Build

/**
 * ## OEM 电源管理配置文件
 *
 * 将各厂商电源管理特征参数集中为数据驱动配置，消除引擎代码中的
 * `if (isHonor)` / `if (isHuawei)` 硬编码分支。新增厂商只需在
 * [PROFILES] 中追加一个 [OemPowerProfile] 实例。
 *
 * ### 设计动机
 * 原实现中 `antiFreezeDelay` 和 `startWatchdog` 仅对 Honor 调参，
 * vivo/iQOO(OriginOS)、小米(MIUI 神隐模式)、OPPO(ColorOS) 等激进
 * 电源管理厂商走默认参数，导致游戏线程被挂起、时间停止。
 *
 * ### 参数依据
 * 来源：dontkillmyapp.com 实测、各厂商电源管理机制分析。
 *
 * @see GameEngineCore.antiFreezeDelay
 * @see GameEngineCore.startWatchdog
 */
data class OemPowerProfile(
    /** 厂商枚举 */
    val manufacturer: OemManufacturer,
    /**
     * 防挂起忙等补偿周期：每多少个微周期做一次忙等。
     *
     * 值越小，忙等越频繁，CPU 越活跃，越能对抗 OEM 空闲检测。
     * - 激进 OEM（Honor MagicOS / vivo OriginOS）：16
     * - 中等 OEM（Xiaomi MIUI / OPPO ColorOS）：32
     * - 保守 OEM（Samsung / 原生）：64
     */
    val antiFreezeBusyInterval: Long,
    /**
     * 防挂起忙等时长（ms）。每次忙等持续该时长保持线程 RUNNABLE。
     *
     * 激进 OEM 需更长忙等（4ms）突破更窄的空闲检测窗口。
     */
    val antiFreezeBusyDuration: Long,
    /**
     * 看门狗检查间隔（ms）。
     *
     * 激进 OEM 需更短间隔（3000ms），以便线程被挂起后更快检测并重启循环。
     * 保守 OEM 用 5000ms 即可，降低误判与开销。
     */
    val watchdogIntervalMs: Long,
)

/**
 * 支持的 OEM 厂商枚举。
 *
 * 与 app 模块的 [com.xianxia.sect.core.util.ManufacturerAdapter.Manufacturer]
 * 保持语义一致，但独立存在于 core/engine 模块以遵守模块边界
 *（core/engine 不依赖 app 模块）。
 */
enum class OemManufacturer {
    HUAWEI, HONOR, XIAOMI, OPPO, VIVO, SAMSUNG, OTHER
}

/**
 * OEM 电源管理配置单例。
 *
 * 通过 [Build.MANUFACTURER] / [Build.BRAND] 识别当前设备厂商，
 * 返回对应的 [OemPowerProfile]。识别逻辑仅在此处维护一份。
 */
object OemPowerProfileProvider {

    private val PROFILES: Map<OemManufacturer, OemPowerProfile> = mapOf(
        OemManufacturer.HUAWEI to OemPowerProfile(
            manufacturer = OemManufacturer.HUAWEI,
            // 华为 EMUI/HarmonyOS PowerGenie 空闲检测窗口 ~50-100ms，保守参数即可
            antiFreezeBusyInterval = 64L,
            antiFreezeBusyDuration = 2L,
            watchdogIntervalMs = 5000L,
        ),
        OemManufacturer.HONOR to OemPowerProfile(
            manufacturer = OemManufacturer.HONOR,
            // 荣耀 MagicOS 空闲检测窗口 ~10-30ms，更激进地保持 CPU 活跃
            antiFreezeBusyInterval = 16L,
            antiFreezeBusyDuration = 4L,
            watchdogIntervalMs = 3000L,
        ),
        OemManufacturer.VIVO to OemPowerProfile(
            // vivo/iQOO OriginOS 挂起策略与 MagicOS 同级激进
            //（i管家后台清理 + 冻结机制），使用 Honor 同级参数
            manufacturer = OemManufacturer.VIVO,
            antiFreezeBusyInterval = 16L,
            antiFreezeBusyDuration = 4L,
            watchdogIntervalMs = 3000L,
        ),
        OemManufacturer.XIAOMI to OemPowerProfile(
            // 小米 MIUI 神隐模式空闲检测窗口中等激进
            manufacturer = OemManufacturer.XIAOMI,
            antiFreezeBusyInterval = 32L,
            antiFreezeBusyDuration = 3L,
            watchdogIntervalMs = 4000L,
        ),
        OemManufacturer.OPPO to OemPowerProfile(
            // OPPO ColorOS 后台冻结策略中等激进
            manufacturer = OemManufacturer.OPPO,
            antiFreezeBusyInterval = 32L,
            antiFreezeBusyDuration = 3L,
            watchdogIntervalMs = 4000L,
        ),
        OemManufacturer.SAMSUNG to OemPowerProfile(
            // 三星最接近原生 Android，保守参数
            manufacturer = OemManufacturer.SAMSUNG,
            antiFreezeBusyInterval = 64L,
            antiFreezeBusyDuration = 2L,
            watchdogIntervalMs = 5000L,
        ),
        OemManufacturer.OTHER to OemPowerProfile(
            manufacturer = OemManufacturer.OTHER,
            antiFreezeBusyInterval = 64L,
            antiFreezeBusyDuration = 2L,
            watchdogIntervalMs = 5000L,
        ),
    )

    private val DEFAULT_PROFILE: OemPowerProfile =
        PROFILES.getValue(OemManufacturer.OTHER)

    /** 当前设备厂商（从 Build 识别，仅计算一次） */
    val currentManufacturer: OemManufacturer by lazy { detect() }

    /** 当前设备的电源管理配置 */
    val current: OemPowerProfile by lazy { PROFILES[currentManufacturer] ?: DEFAULT_PROFILE }

    private fun detect(): OemManufacturer {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            listOf(m, b).any { it.contains("huawei") } -> OemManufacturer.HUAWEI
            listOf(m, b).any { it.contains("honor") } -> OemManufacturer.HONOR
            listOf(m, b).any { it.contains("xiaomi") || it.contains("redmi") } -> OemManufacturer.XIAOMI
            listOf(m, b).any { it.contains("oppo") || it.contains("realme") || it.contains("oneplus") } -> OemManufacturer.OPPO
            listOf(m, b).any { it.contains("vivo") || it.contains("iqoo") } -> OemManufacturer.VIVO
            listOf(m, b).any { it.contains("samsung") } -> OemManufacturer.SAMSUNG
            else -> OemManufacturer.OTHER
        }
    }
}
