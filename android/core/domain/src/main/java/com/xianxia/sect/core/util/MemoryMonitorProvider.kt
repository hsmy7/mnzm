package com.xianxia.sect.core.util

/**
 * 内存监控接口，抽象 Android 平台相关的内存信息获取。
 * 实现由 :app 模块提供。
 */
interface MemoryMonitorProvider {

    data class MemoryInfo(
        val totalMemory: Long,
        val availableMemory: Long,
        val usedMemory: Long,
        val usedPercent: Double,
        val isLowMemory: Boolean,
        val isWarning: Boolean,
        val isCritical: Boolean
    )

    fun getCurrentMemoryInfo(): MemoryInfo?
}
