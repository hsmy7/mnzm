package com.xianxia.sect.core.util

/**
 * GC 优化接口，抽象 GC 统计信息获取。
 * 实现由 :app 模块提供。
 */
interface GCOptimizerProvider {

    data class GCStats(
        val totalGCCount: Long,
        val totalGCTimeMs: Long,
        val averageGCTimeMs: Double,
        val lastGCTimeMs: Long,
        val timeSinceLastGC: Long
    )

    fun getGCStats(): GCStats
}
