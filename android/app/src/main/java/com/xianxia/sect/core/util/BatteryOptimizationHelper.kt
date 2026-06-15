package com.xianxia.sect.core.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 电池优化检测与厂商白名单引导。
 *
 * 中国 OEM（华为/小米/OPPO/vivo）对后台进程管理极为激进，
 * 即使前台应用的非主线程也可能被挂起。
 * 引导用户将应用加入电池优化白名单是确保游戏循环可靠运行的关键一步。
 *
 * ## 参考来源
 * - Huawei: https://consumer.huawei.com/hk/support/content/zh-hk00428704/
 * - AppKillerManager (GitHub): https://github.com/wezuwiusz/AppKillerManager
 * - dontkillmyapp.com benchmark
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    /** 检查是否已豁免电池优化 */
    fun isExempted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 请求电池优化豁免（弹出系统对话框） */
    fun requestExemption(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isExempted(activity)) return

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open battery optimization settings: ${e.message}")
        }
    }

    /** 华为/荣耀：跳转应用启动管理页（关闭自动管理） */
    fun openHuaweiAppLaunchSettings(context: Context) {
        try {
            val intent = Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened Huawei app launch settings")
        } catch (e: Exception) {
            Log.w(TAG, "Huawei app launch settings not available, falling back: ${e.message}")
            openAppDetailsSettings(context)
        }
    }

    /** 小米：跳转省电设置 → 应用智能省电 */
    fun openXiaomiPowerSettings(context: Context) {
        try {
            val intent = Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"
                )
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened Xiaomi power settings")
        } catch (e: Exception) {
            Log.w(TAG, "Xiaomi power settings not available, falling back: ${e.message}")
            openAppDetailsSettings(context)
        }
    }

    /** OPPO：跳转应用自启动管理 */
    fun openOppoAutoLaunchSettings(context: Context) {
        try {
            val intent = Intent().apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened OPPO auto-launch settings")
        } catch (e: Exception) {
            Log.w(TAG, "OPPO auto-launch settings not available, falling back: ${e.message}")
            openAppDetailsSettings(context)
        }
    }

    /** 通用：跳转应用详情设置页 */
    fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app details settings: ${e.message}")
        }
    }

    /** 是否需要显示华为设备优化引导 */
    fun shouldShowHuaweiGuide(context: Context): Boolean =
        ManufacturerAdapter.current in setOf(
            ManufacturerAdapter.Manufacturer.HUAWEI,
            ManufacturerAdapter.Manufacturer.HONOR
        ) && !isExempted(context)

    /** 获取设备特定的优化引导文案 */
    fun getGuideText(context: Context): String {
        return when (ManufacturerAdapter.current) {
            ManufacturerAdapter.Manufacturer.HUAWEI,
            ManufacturerAdapter.Manufacturer.HONOR ->
                "检测到华为/荣耀设备，为确保游戏流畅运行，建议进行以下设置：\n" +
                "1. 允许电池优化豁免\n" +
                "2. 设置 → 应用 → 启动管理 → 关闭自动管理\n" +
                "3. 多任务界面下滑锁定应用"
            ManufacturerAdapter.Manufacturer.XIAOMI ->
                "检测到小米设备，建议进行以下设置：\n" +
                "1. 设置 → 省电与电池 → 应用智能省电 → 选择无限制\n" +
                "2. 多任务界面长按锁定应用"
            ManufacturerAdapter.Manufacturer.OPPO ->
                "检测到OPPO设备，建议进行以下设置：\n" +
                "1. 设置 → 应用 → 自启动管理 → 允许自启动\n" +
                "2. 多任务界面锁定应用"
            else -> ""
        }
    }
}
