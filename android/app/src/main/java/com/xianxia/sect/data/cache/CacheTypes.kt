package com.xianxia.sect.data.cache

enum class CachePriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW,
    BACKGROUND
}

enum class CacheStrategy {
    WRITE_THROUGH,
    WRITE_BEHIND,
    WRITE_AROUND,
    CACHE_ASIDE
}

enum class CacheZone {
    HOT,
    WARM,
    COLD
}

data class CachePolicy(
    val priority: CachePriority,
    val ttl: Long,
    val strategy: CacheStrategy,
    val maxSize: Int = 100,
    val compress: Boolean = false,
    val evictOnLowMemory: Boolean = true
)

object CacheTypeConfig {
    val DATA_TYPE_PRIORITIES: Map<String, CachePriority> = mapOf(
        "game_data" to CachePriority.CRITICAL,
        "disciple" to CachePriority.HIGH,
        "equipment" to CachePriority.HIGH,
        "manual" to CachePriority.NORMAL,
        "pill" to CachePriority.NORMAL,
        "material" to CachePriority.NORMAL,
        "herb" to CachePriority.LOW,
        "seed" to CachePriority.LOW,
        "battle_log" to CachePriority.LOW,
        "event" to CachePriority.BACKGROUND
    )

    val DATA_TYPE_TTL: Map<String, Long> = mapOf(
        "game_data" to Long.MAX_VALUE,
        "disciple" to 3600_000L * 24,
        "equipment" to 3600_000L * 24,
        "manual" to 3600_000L * 24 * 7,
        "pill" to 3600_000L * 24 * 7,
        "material" to 3600_000L * 24 * 7,
        "herb" to 3600_000L * 24 * 3,
        "seed" to 3600_000L * 24 * 3,
        "battle_log" to 3600_000L * 24 * 7,
        "event" to 3600_000L * 24
    )

    val DATA_TYPE_STRATEGY: Map<String, CacheStrategy> = mapOf(
        "game_data" to CacheStrategy.WRITE_THROUGH,
        "disciple" to CacheStrategy.WRITE_BEHIND,
        "equipment" to CacheStrategy.WRITE_BEHIND,
        "manual" to CacheStrategy.WRITE_BEHIND,
        "pill" to CacheStrategy.WRITE_AROUND,
        "material" to CacheStrategy.WRITE_AROUND,
        "herb" to CacheStrategy.WRITE_AROUND,
        "seed" to CacheStrategy.WRITE_AROUND,
        "battle_log" to CacheStrategy.WRITE_AROUND,
        "event" to CacheStrategy.WRITE_AROUND
    )

    val DATA_TYPE_SWR_POLICY: Map<String, SwrPolicy> = mapOf(
        "game_data" to SwrPolicy(ttlMs = Long.MAX_VALUE, staleWhileRevalidateMs = Long.MAX_VALUE),
        "disciple" to SwrPolicy(ttlMs = 24 * 3600_000L, staleWhileRevalidateMs = 10 * 60_000L),
        "equipment" to SwrPolicy(ttlMs = 24 * 3600_000L, staleWhileRevalidateMs = 5 * 60_000L),
        "manual" to SwrPolicy(ttlMs = 7 * 24 * 3600_000L, staleWhileRevalidateMs = 30 * 60_000L),
        "pill" to SwrPolicy(ttlMs = 7 * 24 * 3600_000L, staleWhileRevalidateMs = 30 * 60_000L),
        "material" to SwrPolicy(ttlMs = 7 * 24 * 3600_000L, staleWhileRevalidateMs = 30 * 60_000L),
        "herb" to SwrPolicy(ttlMs = 3 * 24 * 3600_000L, staleWhileRevalidateMs = 15 * 60_000L),
        "seed" to SwrPolicy(ttlMs = 3 * 24 * 3600_000L, staleWhileRevalidateMs = 15 * 60_000L),
        "battle_log" to SwrPolicy(ttlMs = 7 * 24 * 3600_000L, staleWhileRevalidateMs = 60 * 60_000L),
        "event" to SwrPolicy(ttlMs = 24 * 3600_000L, staleWhileRevalidateMs = 0L),
        "save_data" to SwrPolicy(ttlMs = 300_000L, staleWhileRevalidateMs = 60_000L),
        "team" to SwrPolicy(ttlMs = 24 * 3600_000L, staleWhileRevalidateMs = 10 * 60_000L),
        "building_slot" to SwrPolicy(ttlMs = 24 * 3600_000L, staleWhileRevalidateMs = 10 * 60_000L)
    )

    const val DEFAULT_MEMORY_CACHE_SIZE: Long = 64 * 1024 * 1024L
    const val WARM_DATA_TTL_MS: Long = 3600_000L * 24
    const val CLEANUP_INTERVAL_MS: Long = 60_000L
    const val STATS_UPDATE_INTERVAL_MS: Long = 10_000L
    const val FLUSH_INTERVAL_MS: Long = 30_000L
}

data class CacheStats(
    val memoryHitCount: Long = 0,
    val memoryMissCount: Long = 0,
    val memorySize: Long = 0,
    val memoryEntryCount: Int = 0,
    val diskSize: Long = 0,
    val diskEntryCount: Int = 0,
    val dirtyCount: Int = 0,
    val writeQueueSize: Int = 0,
    val hotDataCount: Int = 0,           // 热数据条目数
    val warmDataCount: Int = 0,          // 温数据条目数
    val coldDataCount: Int = 0,          // 冷数据条目数
    val evictedCount: Long = 0,          // 被淘汰的条目总数
    val totalAccessCount: Long = 0,      // 总访问次数
    val memoryPressureLevel: Int = 0     // 当前内存压力等级 (0-4)
) {
    val memoryHitRate: Double
        get() = if (memoryHitCount + memoryMissCount > 0) {
            memoryHitCount.toDouble() / (memoryHitCount + memoryMissCount)
        } else 0.0

    /** 获取总条目数（热+温+冷） */
    val totalTieredEntries: Int get() = hotDataCount + warmDataCount + coldDataCount

    /** 获取内存使用率 */
    val memoryUsagePercent: Double
        get() = if (memoryEntryCount > 0) memorySize.toDouble() / (memoryEntryCount * 1000) else 0.0

    /**
     * 获取压力等级的可读名称
     */
    fun getPressureLevelName(): String = when (memoryPressureLevel) {
        0 -> "LOW"
        1 -> "NORMAL"
        2 -> "MODERATE"
        3 -> "HIGH"
        4 -> "CRITICAL"
        else -> "UNKNOWN"
    }

    /**
     * 获取格式化的统计摘要
     */
    fun formatSummary(): String = buildString {
        appendLine("Cache Statistics:")
        appendLine("  Hit Rate: ${"%.2f%%".format(memoryHitRate * 100)}")
        appendLine("  Memory: ${memorySize / 1024}KB (${memoryEntryCount} entries)")
        appendLine("  Disk: ${diskSize / 1024}KB (${diskEntryCount} entries)")
        appendLine("  Tier [H:${hotDataCount}/W:${warmDataCount}/C:${coldDataCount}]")
        appendLine("  Pressure: ${getPressureLevelName()}")
        appendLine("  Evicted: $evictedCount")
        appendLine("  Total Accesses: $totalAccessCount")
    }
}

enum class DirtyFlag {
    INSERT,
    UPDATE,
    DELETE
}

sealed class FlushResult {
    data class Success(val count: Int) : FlushResult()
    object NoChanges : FlushResult()
    data class Error(val message: String, val cause: Throwable? = null) : FlushResult()
}
