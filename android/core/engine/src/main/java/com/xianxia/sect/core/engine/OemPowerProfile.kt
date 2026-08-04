package com.xianxia.sect.core.engine

import android.os.Build

/**
 * OEM 省电策略等级 — 数据驱动，每厂商映射到三档之一。
 *
 * | 等级 | 典型厂商 | 忙等占空比 | 看门狗间隔 |
 * |------|---------|-----------|-----------|
 * | AGGRESSIVE | 小米/红米 HyperOS | ~45% | 2s |
 * | MODERATE | 华为/荣耀/vivo/OPPO | ~12-17% | 3s |
 * | LIGHT | Samsung/原生 | ~3% | 5s |
 */
enum class OemPowerTier(val label: String) {
    AGGRESSIVE("激进"),
    MODERATE("中等"),
    LIGHT("保守");

    /** 防挂起忙等周期：每 N 个微周期做一次忙等（值越小越频繁） */
    val antiFreezeBusyInterval: Long get() = when(this) {
        AGGRESSIVE -> 6L; MODERATE -> 16L; LIGHT -> 64L
    }

    /** 每次忙等持续时长 (ms) */
    val antiFreezeBusyDuration: Long get() = when(this) {
        AGGRESSIVE -> 5L; MODERATE -> 3L; LIGHT -> 2L
    }

    /** 看门狗检查间隔 (ms) */
    val watchdogIntervalMs: Long get() = when(this) {
        AGGRESSIVE -> 2000L; MODERATE -> 3000L; LIGHT -> 5000L
    }
}

/**
 * ## OEM 电源管理配置文件（简化为三档）
 *
 * 原各厂商独立 6 组配置（21 个魔数），现按省电激进程度归为 3 档。
 * 新增厂商只需在 [MANUFACTURER_TIERS] 中映射到三档之一。
 *
 * @see GameEngineCore.antiFreezeDelay
 * @see GameEngineCore.startWatchdog
 */
data class OemPowerProfile(
    val manufacturer: OemManufacturer,
    val tier: OemPowerTier,
) {
    val antiFreezeBusyInterval: Long get() = tier.antiFreezeBusyInterval
    val antiFreezeBusyDuration: Long get() = tier.antiFreezeBusyDuration
    val watchdogIntervalMs: Long get() = tier.watchdogIntervalMs
}

/**
 * 支持的 OEM 厂商枚举。
 */
enum class OemManufacturer {
    HUAWEI, HONOR, XIAOMI, OPPO, VIVO, SAMSUNG, OTHER
}

/**
 * OEM 电源管理配置单例（三档映射）。
 *
 * 通过 [Build.MANUFACTURER] / [Build.BRAND] 识别当前设备厂商，
 * 映射到三档 [OemPowerTier] 之一。
 */
object OemPowerProfileProvider {

    /** 厂商 → 三级映射表 */
    private val MANUFACTURER_TIERS: Map<OemManufacturer, OemPowerTier> = mapOf(
        OemManufacturer.XIAOMI to OemPowerTier.AGGRESSIVE,
        OemManufacturer.HUAWEI to OemPowerTier.MODERATE,
        OemManufacturer.HONOR to OemPowerTier.MODERATE,
        OemManufacturer.VIVO to OemPowerTier.MODERATE,
        OemManufacturer.OPPO to OemPowerTier.MODERATE,
        OemManufacturer.SAMSUNG to OemPowerTier.LIGHT,
        OemManufacturer.OTHER to OemPowerTier.LIGHT,
    )

    /** 测试注入：覆盖厂商检测（纯 JVM 下 Build.MANUFACTURER 为 null，检测不可用） */
    @Volatile
    internal var manufacturerOverride: OemManufacturer? = null

    /** 当前设备厂商（从 Build 识别，仅计算一次；测试可经 [manufacturerOverride] 覆盖） */
    val currentManufacturer: OemManufacturer
        get() = manufacturerOverride ?: detectedManufacturer

    private val detectedManufacturer by lazy { detect() }

    /** 当前设备的电源管理配置 */
    val current: OemPowerProfile by lazy {
        val mfr = currentManufacturer
        OemPowerProfile(mfr, MANUFACTURER_TIERS[mfr] ?: OemPowerTier.LIGHT)
    }

    private fun detect(): OemManufacturer {
        // null 防御：纯 JVM/极端 ROM 下 Build.MANUFACTURER 可能为 null
        val m = Build.MANUFACTURER?.lowercase() ?: ""
        val b = Build.BRAND?.lowercase() ?: ""
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
