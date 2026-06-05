package com.xianxia.sect.core.util

import android.os.Build
import android.util.Log

/**
 * 跨厂商设备兼容层
 * 华为 HarmonyOS/EMUI 与标准 AOSP 存在多处差异，集中在此处理
 */
object DeviceCompatibilityHelper {

    private const val TAG = "DeviceCompat"

    val isHuaweiOrHonor: Boolean by lazy {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        listOf(m, b).any { it.contains("huawei") || it.contains("honor") }
    }

    val isVivo: Boolean by lazy {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        listOf(m, b).any { it.contains("vivo") || it.contains("iqoo") }
    }

    /**
     * 华为设备专用：跳过 AndroidKeyStore StrongBox
     */
    fun shouldSkipStrongBox(): Boolean = isHuaweiOrHonor

    /**
     * 华为设备专用：使用较低的目标堆利用率
     * 华为固件常限制单进程堆上限为 256-384MB（而非标准 512MB）
     */
    fun getTargetHeapUtilization(): Float = if (isHuaweiOrHonor) 0.65f else 0.75f

    /**
     * 获取 TapTap SDK 初始化超时（毫秒）
     * 华为 HarmonyOS 兼容层执行开销更高，需要更长超时
     */
    fun getTapTapInitTimeoutMs(): Long = if (isHuaweiOrHonor) 15_000L else 8_000L

    fun logDeviceInfo() {
        Log.i(TAG, """
            |=== Device Compatibility Info ===
            |Manufacturer: ${Build.MANUFACTURER}
            |Brand: ${Build.BRAND}
            |Model: ${Build.MODEL}
            |SDK: ${Build.VERSION.SDK_INT}
            |Is Huawei/Honor: $isHuaweiOrHonor
            |Is Vivo: $isVivo
            |======================================
        """.trimMargin())
    }
}
