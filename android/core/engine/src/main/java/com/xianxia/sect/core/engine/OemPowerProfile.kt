package com.xianxia.sect.core.engine

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
 * 厂商识别数据经 [injectPlatformManufacturer] 由平台层注入（app 层启动时传
 * `Build.MANUFACTURER`/`Build.BRAND`）——引擎层零 Android 依赖（计划 v2 阶段 7
 * 平台能力接口化 / R-02）。未注入（纯 JVM/测试）按 [OemManufacturer.OTHER] 安全回退。
 * **iOS 对等**：iOS 无 OEM 省电档位概念，不注入即 OTHER（LIGHT 档）。
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

    /** 测试注入：覆盖厂商检测（纯 JVM 下平台厂商串不可用，检测不可用） */
    @Volatile
    internal var manufacturerOverride: OemManufacturer? = null

    /** 平台厂商原始串（manufacturer, brand）；须在首次访问 [current] 前注入 */
    @Volatile
    private var platformManufacturerInfo: Pair<String?, String?>? = null

    /**
     * 平台层注入厂商识别数据（app 层 Application.onCreate 调用：
     * `injectPlatformManufacturer(Build.MANUFACTURER, Build.BRAND)`）。
     * 幂等；首次注入后 [currentManufacturer] 的 lazy 求值锁定结果。
     */
    fun injectPlatformManufacturer(manufacturer: String?, brand: String?) {
        platformManufacturerInfo = manufacturer to brand
    }

    /** 当前设备厂商（平台注入数据识别，仅计算一次；测试可经 [manufacturerOverride] 覆盖） */
    val currentManufacturer: OemManufacturer
        get() = manufacturerOverride ?: detectedManufacturer

    private val detectedManufacturer by lazy {
        platformManufacturerInfo?.let { (m, b) -> detect(m, b) } ?: OemManufacturer.OTHER
    }

    /** 当前设备的电源管理配置 */
    val current: OemPowerProfile by lazy {
        val mfr = currentManufacturer
        OemPowerProfile(mfr, MANUFACTURER_TIERS[mfr] ?: OemPowerTier.LIGHT)
    }

    private fun detect(manufacturer: String?, brand: String?): OemManufacturer {
        // null 防御：极端 ROM 下平台厂商串可能为 null
        val m = manufacturer?.lowercase() ?: ""
        val b = brand?.lowercase() ?: ""
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
