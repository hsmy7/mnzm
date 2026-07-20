package com.xianxia.sect.core.performance

interface MemoryInfoProvider {

    data class MemoryInfo(
        val availMem: Long,
        val totalMem: Long,
        val isLowMemory: Boolean,
        val threshold: Long = 0L
    )

    fun getMemoryInfo(): MemoryInfo

    fun getAvailMemRatio(): Float {
        val info = getMemoryInfo()
        return if (info.totalMem > 0) info.availMem.toFloat() / info.totalMem.toFloat() else 0f
    }
}
