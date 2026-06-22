package com.xianxia.sect.core.util

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * ## 厂商适配中心
 *
 * 集中识别设备厂商并提供差异化适配配置。通过 [profile] 属性暴露
 * 当前厂商的完整配置（WakeLock tag、电池优化引导、堆利用率等），
 * 消除各调用处的 `if (isHonor)` / `if (isHuawei)` 硬编码分支。
 *
 * 新增厂商只需在 [PROFILES] 中追加一个 [ManufacturerProfile] 实例。
 *
 * @see WakeLockManager
 * @see BatteryOptimizationHelper
 * @see DeviceCompatibilityHelper
 */
object ManufacturerAdapter {

    enum class Manufacturer {
        HUAWEI, XIAOMI, OPPO, VIVO, HONOR, SAMSUNG, OTHER
    }

    val current: Manufacturer by lazy {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        when {
            listOf(m, b).any { it.contains("huawei") } -> Manufacturer.HUAWEI
            listOf(m, b).any { it.contains("honor") } -> Manufacturer.HONOR
            listOf(m, b).any { it.contains("xiaomi") || it.contains("redmi") } -> Manufacturer.XIAOMI
            listOf(m, b).any { it.contains("oppo") || it.contains("realme") || it.contains("oneplus") } -> Manufacturer.OPPO
            listOf(m, b).any { it.contains("vivo") || it.contains("iqoo") } -> Manufacturer.VIVO
            listOf(m, b).any { it.contains("samsung") } -> Manufacturer.SAMSUNG
            else -> Manufacturer.OTHER
        }
    }

    /**
     * 当前设备的厂商配置。数据驱动所有厂商差异化参数。
     *
     * 调用方（WakeLockManager/BatteryOptimizationHelper/DeviceCompatibilityHelper）
     * 通过此属性读取参数，不再硬编码厂商判断。
     */
    val profile: ManufacturerProfile by lazy {
        PROFILES[current] ?: PROFILES.getValue(Manufacturer.OTHER)
    }

    /** 执行当前厂商的适配策略 */
    fun apply(context: Context) {
        Log.i("ManufacturerAdapter", "Applying adaptations for: $current (${Build.MANUFACTURER} ${Build.MODEL})")
        if (profile.needsBatteryGuide && !BatteryOptimizationHelper.isExempted(context)) {
            Log.w("ManufacturerAdapter",
                "$current device detected and battery optimization not exempted. " +
                "Will guide user in GameActivity.")
        }
    }

    /**
     * 各厂商完整配置表。新增厂商在此追加一项即可，无需修改调用方代码。
     */
    private val PROFILES: Map<Manufacturer, ManufacturerProfile> = mapOf(
        Manufacturer.HUAWEI to ManufacturerProfile(
            manufacturer = Manufacturer.HUAWEI,
            // 华为 EMUI/HarmonyOS 的 HwPFWService 仅放行 6 个 Audio* tag，
            // 使用 "AudioMix" 绕过进程终止检测。来源: dontkillmyapp.com/huawei
            wakeLockTag = "AudioMix",
            needsBatteryGuide = true,
            batteryGuideText = "检测到华为/荣耀设备，为确保游戏流畅运行，建议进行以下设置：\n" +
                "1. 允许电池优化豁免\n" +
                "2. 设置 → 应用 → 启动管理 → 关闭自动管理\n" +
                "3. 多任务界面下滑锁定应用",
            launchSettingsComponent = ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // 华为固件常限制单进程堆上限 256-384MB（而非标准 512MB）
            targetHeapUtilization = 0.65f,
            // 华为 HarmonyOS 兼容层执行开销更高，需要更长超时
            tapTapInitTimeoutMs = 15_000L,
            skipStrongBox = true,
        ),
        Manufacturer.HONOR to ManufacturerProfile(
            manufacturer = Manufacturer.HONOR,
            // 荣耀 MagicOS 无 HwPFWService（2020年脱离华为后自研），
            // 使用标准 tag 确保 MagicOS 电源管理正确识别 WakeLock 来源
            wakeLockTag = "XianxiaSect::GameLoop",
            needsBatteryGuide = true,
            batteryGuideText = "检测到华为/荣耀设备，为确保游戏流畅运行，建议进行以下设置：\n" +
                "1. 允许电池优化豁免\n" +
                "2. 设置 → 应用 → 启动管理 → 关闭自动管理\n" +
                "3. 多任务界面下滑锁定应用",
            // MagicOS 保留类似华为的启动管理页，失败则 fallback 到通用设置
            launchSettingsComponent = ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            targetHeapUtilization = 0.65f,
            tapTapInitTimeoutMs = 15_000L,
            skipStrongBox = true,
        ),
        Manufacturer.VIVO to ManufacturerProfile(
            manufacturer = Manufacturer.VIVO,
            // vivo/iQOO OriginOS 无 WakeLock tag 白名单，使用标准 tag
            wakeLockTag = "XianxiaSect::GameLoop",
            // i管家后台清理 + 冻结机制激进，需引导用户关闭后台冻结
            needsBatteryGuide = true,
            batteryGuideText = "检测到 vivo/iQOO 设备，为确保游戏流畅运行，建议进行以下设置：\n" +
                "1. 允许电池优化豁免\n" +
                "2. i管家 → 应用管理 → 自启动 → 允许自启动\n" +
                "3. i管家 → 后台耗电 → 关闭后台冻结\n" +
                "4. 多任务界面下滑锁定应用",
            launchSettingsComponent = ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            targetHeapUtilization = 0.75f,
            tapTapInitTimeoutMs = 8_000L,
            skipStrongBox = false,
        ),
        Manufacturer.XIAOMI to ManufacturerProfile(
            manufacturer = Manufacturer.XIAOMI,
            wakeLockTag = "XianxiaSect::GameLoop",
            // MIUI 神隐模式对后台进程管理激进
            needsBatteryGuide = true,
            batteryGuideText = "检测到小米设备，建议进行以下设置：\n" +
                "1. 设置 → 省电与电池 → 应用智能省电 → 选择无限制\n" +
                "2. 多任务界面长按锁定应用",
            launchSettingsComponent = ComponentName(
                "com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings"
            ),
            targetHeapUtilization = 0.75f,
            tapTapInitTimeoutMs = 8_000L,
            skipStrongBox = false,
        ),
        Manufacturer.OPPO to ManufacturerProfile(
            manufacturer = Manufacturer.OPPO,
            wakeLockTag = "XianxiaSect::GameLoop",
            // ColorOS 后台冻结策略
            needsBatteryGuide = true,
            batteryGuideText = "检测到 OPPO 设备，建议进行以下设置：\n" +
                "1. 设置 → 应用 → 自启动管理 → 允许自启动\n" +
                "2. 多任务界面锁定应用",
            launchSettingsComponent = ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            targetHeapUtilization = 0.75f,
            tapTapInitTimeoutMs = 8_000L,
            skipStrongBox = false,
        ),
        Manufacturer.SAMSUNG to ManufacturerProfile(
            manufacturer = Manufacturer.SAMSUNG,
            // 三星最接近原生 Android，通常不需要特殊处理
            wakeLockTag = "XianxiaSect::GameLoop",
            needsBatteryGuide = false,
            batteryGuideText = "",
            launchSettingsComponent = null,
            targetHeapUtilization = 0.75f,
            tapTapInitTimeoutMs = 8_000L,
            skipStrongBox = false,
        ),
        Manufacturer.OTHER to ManufacturerProfile(
            manufacturer = Manufacturer.OTHER,
            wakeLockTag = "XianxiaSect::GameLoop",
            needsBatteryGuide = false,
            batteryGuideText = "",
            launchSettingsComponent = null,
            targetHeapUtilization = 0.75f,
            tapTapInitTimeoutMs = 8_000L,
            skipStrongBox = false,
        ),
    )
}

/**
 * 厂商完整配置文件。
 *
 * 将 WakeLock tag、电池优化引导、堆利用率等参数集中为数据，
 * 消除调用处的厂商硬编码判断。
 */
data class ManufacturerProfile(
    val manufacturer: ManufacturerAdapter.Manufacturer,
    /** WakeLock tag。华为用 "AudioMix" 绕过 HwPFWService 白名单检测 */
    val wakeLockTag: String,
    /** 是否需要电池优化引导 */
    val needsBatteryGuide: Boolean,
    /** 引导文案（needsBatteryGuide=true 时使用） */
    val batteryGuideText: String,
    /** 厂商自启动/电源管理设置页 ComponentName，null 表示无专用页 */
    val launchSettingsComponent: ComponentName?,
    /** 目标堆利用率。华为/荣耀固件堆受限用 0.65 */
    val targetHeapUtilization: Float,
    /** TapTap SDK 初始化超时（ms）。华为 HarmonyOS 兼容层开销更高 */
    val tapTapInitTimeoutMs: Long,
    /** 是否跳过 AndroidKeyStore StrongBox。华为/荣耀固件兼容性差 */
    val skipStrongBox: Boolean,
)
