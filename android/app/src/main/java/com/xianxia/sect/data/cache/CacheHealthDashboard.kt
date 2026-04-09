package com.xianxia.sect.data.cache

class CacheHealthDashboard(private val manager: GameDataCacheManager) {

    sealed class CacheAnomaly {
        data class LowHitRate(val rate: Double) : CacheAnomaly()
        object CriticalPressure : CacheAnomaly()
        data class FrequentCorruption(val count: Int) : CacheAnomaly()
        data class HighDirtyRatio(val ratio: Double) : CacheAnomaly()
        object LowMemoryEfficiency : CacheAnomaly()
    }

    data class CacheHealthReport(
        val stats: GlobalCacheStats,
        val memoryEfficiency: Double,
        val diskUtilization: Double,
        val anomalies: List<CacheAnomaly>,
        val recommendations: List<String>,
        val healthScore: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun toJsonSummary(): String = buildString {
            appendLine("{")
            appendLine("  \"healthScore\": $healthScore,")
            appendLine("  \"memoryEfficiency\": ${"%.1f".format(memoryEfficiency * 100)}%,")
            appendLine("  \"diskUtilization\": ${"%.1f".format(diskUtilization * 100)}%,")
            appendLine("  \"anomalyCount\": ${anomalies.size},")
            appendLine("  \"recommendationCount\": ${recommendations.size}")
            appendLine("}")
        }
    }

    fun generateReport(): CacheHealthReport {
        val globalStats = manager.getGlobalStats()
        val memStats = manager.getStats()
        val diskStats = manager.getGlobalStats()

        val memoryEfficiency = if (globalStats.totalMemoryBytes > 0) {
            globalStats.memoryHitRate
        } else 0.0

        val diskUtilization = if (diskStats.totalDiskBytes > 0) {
            diskStats.totalDiskBytes.toDouble() / (100 * 1024 * 1024)
        } else 0.0

        val anomalies = detectAnomalies(globalStats, memStats)
        val recommendations = generateRecommendations(anomalies, globalStats)
        var score = 100

        anomalies.forEach { _ -> score -= 15 }
        score = score.coerceIn(0, 100)

        return CacheHealthReport(
            stats = globalStats,
            memoryEfficiency = memoryEfficiency,
            diskUtilization = diskUtilization.coerceIn(0.0, 1.0),
            anomalies = anomalies,
            recommendations = recommendations,
            healthScore = score
        )
    }

    private fun detectAnomalies(globalStats: GlobalCacheStats, stats: CacheStats): List<CacheAnomaly> {
        val anomalies = mutableListOf<CacheAnomaly>()

        if (globalStats.memoryHitRate < 0.3) {
            anomalies.add(CacheAnomaly.LowHitRate(globalStats.memoryHitRate))
        }
        if (globalStats.pressureLevel == "CRITICAL") {
            anomalies.add(CacheAnomaly.CriticalPressure)
        }
        if (globalStats.corruptionCount > 5) {
            anomalies.add(CacheAnomaly.FrequentCorruption(globalStats.corruptionCount.toInt()))
        }
        if (stats.dirtyCount > 50) {
            anomalies.add(CacheAnomaly.HighDirtyRatio(stats.dirtyCount.toDouble() / (stats.memoryEntryCount + 1)))
        }
        if (globalStats.memoryHitRate < 0.5 && globalStats.smoothedPressureRatio > 0.5f) {
            anomalies.add(CacheAnomaly.LowMemoryEfficiency)
        }

        return anomalies
    }

    private fun generateRecommendations(anomalies: List<CacheAnomaly>, stats: GlobalCacheStats): List<String> {
        val recommendations = mutableListOf<String>()

        anomalies.forEach { anomaly ->
            when (anomaly) {
                is CacheAnomaly.LowHitRate -> {
                    recommendations.add("Memory hit rate low (${"%.1f".format(anomaly.rate * 100)}%): consider increasing cache size or reviewing access patterns")
                }
                is CacheAnomaly.CriticalPressure -> {
                    recommendations.add("Critical memory pressure detected: review memory-intensive operations and consider offloading to disk")
                }
                is CacheAnomaly.FrequentCorruption -> {
                    recommendations.add("${anomaly.count} corruption events: investigate storage hardware or I/O contention")
                }
                is CacheAnomaly.HighDirtyRatio -> {
                    recommendations.add("High dirty-to-total ratio (${"%.1f".format(anomaly.ratio * 100)}%): increase sync frequency or reduce write throughput")
                }
                is CacheAnomaly.LowMemoryEfficiency -> {
                    recommendations.add("Low efficiency under high pressure: review eviction policy and data prioritization")
                }
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add("Cache system operating normally")
        }

        return recommendations
    }
}
