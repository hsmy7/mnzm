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
        // 1. AndroidKeyStore 禁用 StrongBox
        // 2. 降低 heap 目标利用率 (0.65)
        // 3. TapTap SDK 超时 15s
        // 4. Room WAL checkpoint 保守模式
    }

    private fun applyXiaomiFixes(context: Context) {
        // 1. "神隐模式"白名单引导
        // 2. 电池优化豁免
        // 3. 后台限制白名单
    }

    private fun applyOppoFixes(context: Context) {
        // 1. 自启动白名单引导
        // 2. 后台冻结豁免
    }

    private fun applyVivoFixes(context: Context) {
        // 1. VivoGCJITOptimizer（已有）
        // 2. i管家自启动引导
    }

    private fun applyHonorFixes(context: Context) {
        // 与华为相同策略（使用 iTrustee + HMS）
        applyHuaweiFixes(context)
    }

    private fun applySamsungFixes(context: Context) {
        // 三星最接近原生 Android，通常不需要特殊处理
        // 仅需：Galaxy Store 审核要求 + Good Guardians 优化建议
    }
}
