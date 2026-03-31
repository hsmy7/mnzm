package com.xianxia.sect.data.v2

object StorageArchitecture {
    const val VERSION = 2_0_0
    const val VERSION_NAME = "2.0.0"
    
    object Database {
        const val CURRENT_VERSION = 60
        const val MIN_SUPPORTED_VERSION = 54
    }
    
    object Memory {
        const val HOT_ZONE_RATIO = 0.4f
        const val WARM_ZONE_RATIO = 0.35f
        const val COLD_ZONE_RATIO = 0.25f
        
        const val MIN_MEMORY_MB = 64
        const val MAX_MEMORY_MB = 256
        
        const val GC_THRESHOLD_RATIO = 0.85f
        const val CRITICAL_MEMORY_RATIO = 0.95f
    }
    
    object Cache {
        const val L1_CACHE_SIZE = 4 * 1024 * 1024
        const val L2_CACHE_SIZE = 16 * 1024 * 1024
        const val L3_CACHE_SIZE = 64 * 1024 * 1024
        
        const val L1_TTL_MS = 5 * 60 * 1000L
        const val L2_TTL_MS = 30 * 60 * 1000L
        const val L3_TTL_MS = 2 * 60 * 60 * 1000L
    }
    
    object WriteQueue {
        const val HIGH_PRIORITY_CAPACITY = 100
        const val NORMAL_PRIORITY_CAPACITY = 500
        const val LOW_PRIORITY_CAPACITY = 1000
        
        const val BATCH_SIZE = 50
        const val FLUSH_INTERVAL_MS = 1000L
        const val MAX_RETRY_COUNT = 3
    }
    
    object Snapshot {
        const val FULL_SNAPSHOT_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val INCREMENTAL_SNAPSHOT_INTERVAL_MS = 30 * 60 * 1000L
        const val MAX_SNAPSHOTS_PER_SLOT = 20
        const val MAX_INCREMENTAL_CHAIN = 10
    }
    
    object Compression {
        const val LEVEL_FAST = 1
        const val LEVEL_BALANCED = 6
        const val LEVEL_MAX = 9
        
        const val THRESHOLD_BYTES = 1024
        const val MIN_COMPRESSION_RATIO = 0.8f
    }
    
    object EventSourcing {
        const val MAX_EVENT_HISTORY = 10000
        const val EVENT_RETENTION_DAYS = 30
        const val SNAPSHOT_THRESHOLD = 1000
    }
    
    object Migration {
        const val BATCH_SIZE = 1000
        const val PROGRESS_REPORT_INTERVAL = 100
        const val BACKUP_RETENTION_DAYS = 7
    }
}

enum class StoragePriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW,
    BACKGROUND
}

enum class DataState {
    ACTIVE,
    INACTIVE,
    ARCHIVED,
    DELETED
}

enum class CompressionStrategy {
    NONE,
    FAST,
    BALANCED,
    MAXIMUM,
    ADAPTIVE
}

enum class WriteStrategy {
    SYNC,
    ASYNC,
    BATCHED,
    DELAYED
}

data class StorageConfig(
    val enableEventSourcing: Boolean = true,
    val enableAdaptiveMemory: Boolean = true,
    val enableTieredCompression: Boolean = true,
    val enablePriorityWriteQueue: Boolean = true,
    val enableAutoMigration: Boolean = true,
    val compressionStrategy: CompressionStrategy = CompressionStrategy.ADAPTIVE,
    val maxMemoryMB: Int = StorageArchitecture.Memory.MAX_MEMORY_MB,
    val snapshotIntervalMs: Long = StorageArchitecture.Snapshot.INCREMENTAL_SNAPSHOT_INTERVAL_MS
)

data class StorageMetrics(
    val totalReads: Long = 0,
    val totalWrites: Long = 0,
    val cacheHits: Long = 0,
    val cacheMisses: Long = 0,
    val avgReadTimeMs: Double = 0.0,
    val avgWriteTimeMs: Double = 0.0,
    val memoryUsageMB: Double = 0.0,
    val diskUsageMB: Double = 0.0,
    val pendingWrites: Int = 0,
    val lastSnapshotTime: Long = 0,
    val eventCount: Long = 0
) {
    val cacheHitRate: Double get() = if (totalReads > 0) cacheHits.toDouble() / totalReads else 0.0
    val healthScore: Double get() = calculateHealthScore()
    
    private fun calculateHealthScore(): Double {
        var score = 100.0
        
        if (cacheHitRate < 0.7) score -= (0.7 - cacheHitRate) * 30
        if (avgReadTimeMs > 50) score -= (avgReadTimeMs - 50) * 0.5
        if (avgWriteTimeMs > 100) score -= (avgWriteTimeMs - 100) * 0.3
        if (pendingWrites > 100) score -= (pendingWrites - 100) * 0.1
        
        return score.coerceIn(0.0, 100.0)
    }
}

sealed class StorageEvent {
    data class DataChanged(
        val dataType: String,
        val recordId: String,
        val operation: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : StorageEvent()
    
    data class CacheEvicted(
        val key: String,
        val reason: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : StorageEvent()
    
    data class SnapshotCreated(
        val snapshotId: String,
        val type: String,
        val size: Long,
        val timestamp: Long = System.currentTimeMillis()
    ) : StorageEvent()
    
    data class MigrationProgress(
        val current: Int,
        val total: Int,
        val phase: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : StorageEvent()
    
    data class MemoryPressure(
        val usagePercent: Double,
        val action: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : StorageEvent()
    
    data class Error(
        val message: String,
        val exception: Throwable?,
        val timestamp: Long = System.currentTimeMillis()
    ) : StorageEvent()
}
