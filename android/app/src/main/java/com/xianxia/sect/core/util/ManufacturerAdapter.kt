package com.xianxia.sect.core.util

import android.content.Context
import android.os.Build
import android.util.Log

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

    /** 执行当前厂商的适配策略 */
    fun apply(context: Context) {
        Log.i("ManufacturerAdapter", "Applying adaptations for: $current (${Build.MANUFACTURER} ${Build.MODEL})")
        when (current) {
            Manufacturer.HUAWEI -> applyHuaweiFixes(context)
            Manufacturer.HONOR -> applyHonorFixes(context)
            Manufacturer.XIAOMI -> applyXiaomiFixes(context)
            Manufacturer.OPPO -> applyOppoFixes(context)
            Manufacturer.VIVO -> applyVivoFixes(context)
            Manufacturer.SAMSUNG -> applySamsungFixes(context)
            Manufacturer.OTHER -> { /* 无特殊处理 */ }
        }
    }

    private fun applyHuaweiFixes(context: Context) {
        // 1. Room WAL checkpoint 保守模式：
        //    华为 EROFS 文件系统下 WAL 性能较差，
        //    在 GameDatabase 初始化时通过 DatabaseModule 传递
        //    manufacturer-aware WAL 配置（更频繁的 checkpoint）
        // 2. 降低 heap 目标利用率 (0.65)：
        //    避免触发华为 ROM 内置的激进 GC 策略
        // 3. 请求电池优化豁免 + 引导用户关闭自动管理
        //    实际操作在 GameActivity 中通过 BatteryOptimizationHelper 完成
        if (!BatteryOptimizationHelper.isExempted(context)) {
            Log.w("ManufacturerAdapter",
                "Huawei device detected and battery optimization not exempted. " +
                "Will guide user in GameActivity.")
        }
    }

    private fun applyXiaomiFixes(context: Context) {
        // 1. "神隐模式"白名单引导 → GameActivity 中提示用户
        // 2. 电池优化豁免 → GameActivity 中通过 BatteryOptimizationHelper 引导
        if (!BatteryOptimizationHelper.isExempted(context)) {
            Log.w("ManufacturerAdapter",
                "Xiaomi device detected and battery optimization not exempted. " +
                "Will guide user in GameActivity.")
        }
    }

    private fun applyOppoFixes(context: Context) {
        // 1. 自启动白名单引导 → GameActivity 中提示用户
        if (!BatteryOptimizationHelper.isExempted(context)) {
            Log.w("ManufacturerAdapter",
                "OPPO device detected and battery optimization not exempted. " +
                "Will guide user in GameActivity.")
        }
    }

    private fun applyVivoFixes(context: Context) {
        // 1. VivoGCJITOptimizer（已在 MainActivity 中调用）
        // 2. i管家自启动引导（Vivo 通常不需要手动设置）
    }

    private fun applyHonorFixes(context: Context) {
        // 荣耀 MagicOS 电源管理与华为 EMUI/HarmonyOS 完全不同：
        // - 无 HwPFWService（WakeLock AudioMix 标签无意义，WakeLockManager 已处理）
        // - 线程挂起策略可能更激进（GameEngineCore 已加固 antiFreezeDelay + 看门狗）
        // - 电池优化豁免使用标准 Android API
        val magicOsVer = detectMagicOSVersion()
        Log.i("ManufacturerAdapter",
            "Honor MagicOS $magicOsVer (${Build.MODEL}, SDK ${Build.VERSION.SDK_INT})")
        if (!BatteryOptimizationHelper.isExempted(context)) {
            Log.w("ManufacturerAdapter",
                "Honor device detected and battery optimization not exempted. " +
                "Will guide user in GameActivity.")
        }
    }

    private fun applySamsungFixes(context: Context) {
        // 三星最接近原生 Android，通常不需要特殊处理
        // 仅需：Galaxy Store 审核要求 + Good Guardians 优化建议
    }

    /**
     * 检测 MagicOS 版本。
     *
     * Honor 脱离华为后（2020年11月），MagicOS 系列从 EMUI fork 独立发展。
     * 版本号格式示例：
     * - MagicOS 6.0 / 6.1（Android 12，Honor 70 出厂版本）
     * - MagicOS 7.0 / 7.1（Android 13）
     * - MagicOS 8.0（Android 14）
     *
     * 优先从 Build.DISPLAY 读取，失败时回退到系统属性
     * ro.build.version.emui（历史遗留，部分 MagicOS 版本仍设置此属性）。
     */
    private fun detectMagicOSVersion(): String {
        val display = Build.DISPLAY
        if (display.contains("MagicOS", ignoreCase = true)) return display
        return try {
            val c = Class.forName("android.os.SystemProperties")
            (c.getMethod("get", String::class.java, String::class.java)
                .invoke(null, "ro.build.version.emui", "unknown") as? String) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
