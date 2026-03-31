package com.xianxia.sect.data.partition

data class ZoneConfig(
    val zone: DataZone,
    val maxSizeBytes: Long,
    val compressionType: CompressionType,
    val ttlMs: Long,
    val evictionPolicy: EvictionPolicy,
    val preloadStrategy: PreloadStrategy,
    val writeStrategy: WriteStrategy
) {
    companion object {
        val HOT_DEFAULT = ZoneConfig(
            zone = DataZone.HOT,
            maxSizeBytes = 20 * 1024 * 1024,
            compressionType = CompressionType.LZ4,
            ttlMs = Long.MAX_VALUE,
            evictionPolicy = EvictionPolicy.NEVER,
            preloadStrategy = PreloadStrategy.ON_STARTUP,
            writeStrategy = WriteStrategy.IMMEDIATE
        )

        val WARM_DEFAULT = ZoneConfig(
            zone = DataZone.WARM,
            maxSizeBytes = 50 * 1024 * 1024,
            compressionType = CompressionType.LZ4,
            ttlMs = 3600_000L * 24,
            evictionPolicy = EvictionPolicy.LRU,
            preloadStrategy = PreloadStrategy.ON_DEMAND,
            writeStrategy = WriteStrategy.BATCHED
        )

        val COLD_DEFAULT = ZoneConfig(
            zone = DataZone.COLD,
            maxSizeBytes = 100 * 1024 * 1024,
            compressionType = CompressionType.ZSTD,
            ttlMs = 3600_000L * 24 * 7,
            evictionPolicy = EvictionPolicy.AGE_BASED,
            preloadStrategy = PreloadStrategy.LAZY,
            writeStrategy = WriteStrategy.DEFERRED
        )

        fun forZone(zone: DataZone): ZoneConfig {
            return when (zone) {
                DataZone.HOT -> HOT_DEFAULT
                DataZone.WARM -> WARM_DEFAULT
                DataZone.COLD -> COLD_DEFAULT
            }
        }
    }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (maxSizeBytes <= 0) {
            errors.add("maxSizeBytes must be positive")
        }
        
        if (ttlMs <= 0 && ttlMs != Long.MAX_VALUE) {
            errors.add("ttlMs must be positive or Long.MAX_VALUE")
        }

        return errors
    }
}

enum class EvictionPolicy {
    NEVER,
    LRU,
    LFU,
    AGE_BASED,
    SIZE_BASED
}

enum class PreloadStrategy {
    ON_STARTUP,
    ON_DEMAND,
    LAZY,
    PREDICTIVE
}

enum class WriteStrategy {
    IMMEDIATE,
    BATCHED,
    DEFERRED,
    MANUAL
}

data class PartitionConfig(
    val hotZoneConfig: ZoneConfig = ZoneConfig.HOT_DEFAULT,
    val warmZoneConfig: ZoneConfig = ZoneConfig.WARM_DEFAULT,
    val coldZoneConfig: ZoneConfig = ZoneConfig.COLD_DEFAULT,
    val enableAutoTransition: Boolean = true,
    val transitionThreshold: TransitionThreshold = TransitionThreshold.DEFAULT,
    val monitoringIntervalMs: Long = 60_000L
) {
    fun getConfigForZone(zone: DataZone): ZoneConfig {
        return when (zone) {
            DataZone.HOT -> hotZoneConfig
            DataZone.WARM -> warmZoneConfig
            DataZone.COLD -> coldZoneConfig
        }
    }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        errors.addAll(hotZoneConfig.validate().map { "HOT: $it" })
        errors.addAll(warmZoneConfig.validate().map { "WARM: $it" })
        errors.addAll(coldZoneConfig.validate().map { "COLD: $it" })

        if (monitoringIntervalMs < 1000) {
            errors.add("monitoringIntervalMs should be at least 1000ms")
        }

        return errors
    }

    companion object {
        val DEFAULT = PartitionConfig()

        val HIGH_PERFORMANCE = PartitionConfig(
            hotZoneConfig = ZoneConfig(
                zone = DataZone.HOT,
                maxSizeBytes = 50 * 1024 * 1024,
                compressionType = CompressionType.LZ4,
                ttlMs = Long.MAX_VALUE,
                evictionPolicy = EvictionPolicy.NEVER,
                preloadStrategy = PreloadStrategy.ON_STARTUP,
                writeStrategy = WriteStrategy.IMMEDIATE
            ),
            warmZoneConfig = ZoneConfig(
                zone = DataZone.WARM,
                maxSizeBytes = 100 * 1024 * 1024,
                compressionType = CompressionType.LZ4,
                ttlMs = 3600_000L * 24,
                evictionPolicy = EvictionPolicy.LRU,
                preloadStrategy = PreloadStrategy.ON_DEMAND,
                writeStrategy = WriteStrategy.IMMEDIATE
            ),
            coldZoneConfig = ZoneConfig(
                zone = DataZone.COLD,
                maxSizeBytes = 200 * 1024 * 1024,
                compressionType = CompressionType.ZSTD,
                ttlMs = 3600_000L * 24 * 7,
                evictionPolicy = EvictionPolicy.AGE_BASED,
                preloadStrategy = PreloadStrategy.LAZY,
                writeStrategy = WriteStrategy.BATCHED
            ),
            enableAutoTransition = true,
            transitionThreshold = TransitionThreshold.AGGRESSIVE
        )

        val LOW_MEMORY = PartitionConfig(
            hotZoneConfig = ZoneConfig(
                zone = DataZone.HOT,
                maxSizeBytes = 10 * 1024 * 1024,
                compressionType = CompressionType.LZ4,
                ttlMs = Long.MAX_VALUE,
                evictionPolicy = EvictionPolicy.LRU,
                preloadStrategy = PreloadStrategy.ON_DEMAND,
                writeStrategy = WriteStrategy.IMMEDIATE
            ),
            warmZoneConfig = ZoneConfig(
                zone = DataZone.WARM,
                maxSizeBytes = 30 * 1024 * 1024,
                compressionType = CompressionType.LZ4,
                ttlMs = 3600_000L * 12,
                evictionPolicy = EvictionPolicy.LRU,
                preloadStrategy = PreloadStrategy.LAZY,
                writeStrategy = WriteStrategy.BATCHED
            ),
            coldZoneConfig = ZoneConfig(
                zone = DataZone.COLD,
                maxSizeBytes = 50 * 1024 * 1024,
                compressionType = CompressionType.ZSTD,
                ttlMs = 3600_000L * 24 * 3,
                evictionPolicy = EvictionPolicy.AGE_BASED,
                preloadStrategy = PreloadStrategy.LAZY,
                writeStrategy = WriteStrategy.DEFERRED
            ),
            enableAutoTransition = true,
            transitionThreshold = TransitionThreshold.CONSERVATIVE
        )
    }
}

data class TransitionThreshold(
    val promoteAccessCount: Int,
    val demoteAccessCount: Int,
    val promoteTimeWindowMs: Long,
    val demoteTimeWindowMs: Long,
    val memoryPressureThreshold: Double
) {
    companion object {
        val DEFAULT = TransitionThreshold(
            promoteAccessCount = 10,
            demoteAccessCount = 2,
            promoteTimeWindowMs = 3600_000L,
            demoteTimeWindowMs = 3600_000L * 24,
            memoryPressureThreshold = 0.8
        )

        val AGGRESSIVE = TransitionThreshold(
            promoteAccessCount = 5,
            demoteAccessCount = 5,
            promoteTimeWindowMs = 1800_000L,
            demoteTimeWindowMs = 3600_000L * 12,
            memoryPressureThreshold = 0.7
        )

        val CONSERVATIVE = TransitionThreshold(
            promoteAccessCount = 20,
            demoteAccessCount = 1,
            promoteTimeWindowMs = 7200_000L,
            demoteTimeWindowMs = 3600_000L * 48,
            memoryPressureThreshold = 0.9
        )
    }
}

data class ZoneStats(
    val zone: DataZone,
    val entryCount: Int,
    val totalSizeBytes: Long,
    val maxSizeBytes: Long,
    val hitCount: Long,
    val missCount: Long,
    val evictionCount: Long,
    val compressionSavedBytes: Long,
    val averageAccessTimeMs: Double
) {
    val usagePercent: Double
        get() = if (maxSizeBytes > 0) totalSizeBytes.toDouble() / maxSizeBytes * 100 else 0.0

    val hitRate: Double
        get() = if (hitCount + missCount > 0) hitCount.toDouble() / (hitCount + missCount) else 0.0

    val compressionRatio: Double
        get() = if (totalSizeBytes > 0) (totalSizeBytes + compressionSavedBytes).toDouble() / totalSizeBytes else 1.0

    fun isHealthy(): Boolean {
        return usagePercent < 90.0 && hitRate > 0.5
    }
}

data class PartitionStats(
    val hotZoneStats: ZoneStats,
    val warmZoneStats: ZoneStats,
    val coldZoneStats: ZoneStats,
    val transitionCount: Long,
    val uptimeMs: Long
) {
    val totalEntries: Int
        get() = hotZoneStats.entryCount + warmZoneStats.entryCount + coldZoneStats.entryCount

    val totalSizeBytes: Long
        get() = hotZoneStats.totalSizeBytes + warmZoneStats.totalSizeBytes + coldZoneStats.totalSizeBytes

    val overallHitRate: Double
        get() {
            val totalHits = hotZoneStats.hitCount + warmZoneStats.hitCount + coldZoneStats.hitCount
            val totalMisses = hotZoneStats.missCount + warmZoneStats.missCount + coldZoneStats.missCount
            return if (totalHits + totalMisses > 0) totalHits.toDouble() / (totalHits + totalMisses) else 0.0
        }

    val totalCompressionSaved: Long
        get() = hotZoneStats.compressionSavedBytes + warmZoneStats.compressionSavedBytes + coldZoneStats.compressionSavedBytes
}
