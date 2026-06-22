package com.xianxia.sect.core.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * ## 电池优化检测与厂商白名单引导
 *
 * 中国 OEM（华为/小米/OPPO/vivo/荣耀）对后台进程管理极为激进，
 * 即使前台应用的非主线程也可能被挂起。
 * 引导用户将应用加入电池优化白名单是确保游戏循环可靠运行的关键一步。
 *
 * ## 数据驱动
 * 引导文案与厂商设置页跳转由 [ManufacturerAdapter.profile] 提供，
 * 本类只负责通用逻辑（检测豁免、请求豁免、跳转设置页 fallback）。
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

    /**
     * 是否需要显示电池优化引导。
     *
     * 由 [ManufacturerAdapter.profile.needsBatteryGuide] 数据驱动，
     * 覆盖全部激进 OEM（华为/荣耀/vivo/小米/OPPO）。
     */
    fun shouldShowGuide(context: Context): Boolean =
        ManufacturerAdapter.profile.needsBatteryGuide && !isExempted(context)

    /**
     * 获取设备特定的优化引导文案。
     *
     * 由 [ManufacturerAdapter.profile.batteryGuideText] 数据驱动。
     * 无需引导的厂商返回空字符串。
     */
    fun getGuideText(context: Context): String =
        ManufacturerAdapter.profile.batteryGuideText

    /**
     * 跳转厂商自启动/电源管理设置页。
     *
     * 由 [ManufacturerAdapter.profile.launchSettingsComponent] 数据驱动。
     * 若厂商无专用设置页（ComponentName 为 null）或跳转失败，
     * fallback 到应用详情设置页。
     */
    fun openLaunchSettings(context: Context) {
        val component = ManufacturerAdapter.profile.launchSettingsComponent
        if (component != null) {
            try {
                val intent = Intent().apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    this.component = component
                }
                context.startActivity(intent)
                Log.d(TAG, "Opened launch settings for ${ManufacturerAdapter.current}: $component")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Launch settings ($component) not available, falling back: ${e.message}")
            }
        }
        openAppDetailsSettings(context)
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
}
