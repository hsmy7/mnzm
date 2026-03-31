package com.xianxia.sect.data.cache

enum class DirtyFlag {
    INSERT,
    UPDATE,
    DELETE
}

sealed class FlushResult {
    object NoChanges : FlushResult()
    data class Success(val count: Int) : FlushResult()
    data class Error(val message: String, val cause: Throwable? = null) : FlushResult()
}

data class CacheStats(
    val memoryHitCount: Long = 0,
    val memoryMissCount: Long = 0,
    val memorySize: Long = 0,
    val memoryEntryCount: Int = 0,
    val diskSize: Long = 0,
    val diskEntryCount: Int = 0,
    val dirtyCount: Int = 0,
    val writeQueueSize: Int = 0
) {
    val memoryHitRate: Double
        get() = if (memoryHitCount + memoryMissCount > 0) {
            memoryHitCount.toDouble() / (memoryHitCount + memoryMissCount)
        } else 0.0

    val totalSize: Long
        get() = memorySize + diskSize
}

data class CacheConfig(
    val memoryCacheSize: Int = 50 * 1024 * 1024,
    val diskCacheSize: Long = 100 * 1024 * 1024,
    val writeBatchSize: Int = 100,
    val writeDelayMs: Long = 1000L,
    val defaultTtl: Long = CacheKey.DEFAULT_TTL,
    val enableDiskCache: Boolean = true,
    val enableCompression: Boolean = true
) {
    companion object {
        val DEFAULT = CacheConfig()
        val HIGH_PERFORMANCE = CacheConfig(
            memoryCacheSize = 100 * 1024 * 1024,
            diskCacheSize = 200 * 1024 * 1024,
            writeBatchSize = 200,
            writeDelayMs = 500L
        )
        val LOW_MEMORY = CacheConfig(
            memoryCacheSize = 20 * 1024 * 1024,
            diskCacheSize = 50 * 1024 * 1024,
            writeBatchSize = 50,
            writeDelayMs = 2000L
        )
    }
}
