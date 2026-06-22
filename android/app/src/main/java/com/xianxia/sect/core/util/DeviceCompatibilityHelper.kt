package com.xianxia.sect.core.util

import android.os.Build
import android.util.Log

/**
 * 跨厂商设备兼容层
 * 华为 HarmonyOS/EMUI 与标准 AOSP 存在多处差异，集中在此处理
 */
object DeviceCompatibilityHelper {

    private const val TAG = "DeviceCompat"

    val isHuawei: Boolean by lazy {
        ManufacturerAdapter.current == ManufacturerAdapter.Manufacturer.HUAWEI
    }

    val isHonor: Boolean by lazy {
        ManufacturerAdapter.current == ManufacturerAdapter.Manufacturer.HONOR
    }

    val isHuaweiOrHonor: Boolean by lazy {
        isHuawei || isHonor
    }

    val isVivo: Boolean by lazy {
        ManufacturerAdapter.current == ManufacturerAdapter.Manufacturer.VIVO
    }

    /**
     * 是否跳过 AndroidKeyStore StrongBox。
     * 由 [ManufacturerAdapter.profile.skipStrongBox] 数据驱动。
     */
    fun shouldSkipStrongBox(): Boolean = ManufacturerAdapter.profile.skipStrongBox

    /**
     * 目标堆利用率。
     * 由 [ManufacturerAdapter.profile.targetHeapUtilization] 数据驱动。
     * 华为/荣耀固件常限制单进程堆上限为 256-384MB（而非标准 512MB）。
     */
    fun getTargetHeapUtilization(): Float = ManufacturerAdapter.profile.targetHeapUtilization

    /**
     * 获取 TapTap SDK 初始化超时（毫秒）。
     * 由 [ManufacturerAdapter.profile.tapTapInitTimeoutMs] 数据驱动。
     * 华为 HarmonyOS 兼容层执行开销更高，需要更长超时。
     */
    fun getTapTapInitTimeoutMs(): Long = ManufacturerAdapter.profile.tapTapInitTimeoutMs

    fun logDeviceInfo() {
        Log.i(TAG, """
            |=== Device Compatibility Info ===
            |Manufacturer: ${Build.MANUFACTURER}
            |Brand: ${Build.BRAND}
            |Model: ${Build.MODEL}
            |SDK: ${Build.VERSION.SDK_INT}
            |Is Huawei/Honor: $isHuaweiOrHonor
            |Is Vivo: $isVivo
            |ManufacturerAdapter: ${ManufacturerAdapter.current}
            |======================================
        """.trimMargin())
    }
}
