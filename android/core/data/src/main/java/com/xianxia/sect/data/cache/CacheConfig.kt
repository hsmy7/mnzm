package com.xianxia.sect.data.cache

/**
 * 缓存清理策略枚举
 */
enum class CacheEvictionPolicy(val description: String) {
    LRU("最近最少使用"),
    LFU("最不经常使用"),
    FIFO("先进先出"),
    TTL("基于过期时间"),
    ADAPTIVE("自适应策略（结合 LRU + 访问频率）")
}

/**
 * 分级缓存配置
 */
data class TieredConfig(
    val hotDataMaxSize: Int = 100,          // 热数据最大条目数
    val warmDataMaxSize: Int = 500,         // 温数据最大条目数
    val coldDataMaxSize: Int = 1000,        // 冷数据最大条目数
    val hotAccessThreshold: Int = 10,       // 热数据访问阈值（访问次数）
    val warmAccessThreshold: Int = 3,       // 温数据访问阈值
    val hotTtlMs: Long = 5 * 60 * 1000L,   // 热数据 TTL: 5分钟
    val warmTtlMs: Long = 30 * 60 * 1000L, // 温数据 TTL: 30分钟
    val coldTtlMs: Long = 2 * 60 * 60 * 1000L // 冷数据 TTL: 2小时
)

/**
 * 内存压力响应配置
 */
data class MemoryPressureResponseConfig(
    val moderatePressureCacheReduction: Float = 0.5f,   // 中等压力时缓存缩减比例
    val highPressureCacheReduction: Float = 0.3f,        // 高压力时缓存缩减比例
    val criticalPressureCacheReduction: Float = 0.1f,     // 危急压力时缓存缩减比例
    val enableAutoGcOnHighPressure: Boolean = true,       // 高压时自动 GC
    val enableEmergencyPurgeOnCritical: Boolean = true     // 危急时紧急清理
)

/**
 * 缓存配置数据类
 *
 * 用于配置 GameDataCacheManager 的行为参数，
 * 包括内存缓存、磁盘缓存、写入批处理等策略。
 *
 * 支持动态调整：可通过 updateXxx 方法在运行时修改配置，
 * 所有修改会立即生效。
 */
data class CacheConfig(
    val memoryCacheSize: Long = 4 * 1024 * 1024,    // 4MB 默认内存缓存
    val diskCacheSize: Long = 100 * 1024 * 1024,      // 100MB 默认磁盘缓存
    val writeBatchSize: Int = 100,                      // 批量写入大小
    val writeDelayMs: Long = 1000L,                     // 写入延迟（毫秒）
    val enableDiskCache: Boolean = true,                // 是否启用磁盘缓存
    val enableCompression: Boolean = true,              // 是否启用压缩
    val maxEntryCount: Int = 2000,                      // 最大缓存条目数
    val evictionPolicy: CacheEvictionPolicy = CacheEvictionPolicy.ADAPTIVE,  // 淘汰策略
    val cleanupIntervalMs: Long = 30_000L,              // 清理间隔（30秒）
    val tieredConfig: TieredConfig = TieredConfig(),  // 分级缓存配置
    val pressureResponseConfig: MemoryPressureResponseConfig = MemoryPressureResponseConfig()  // 内存压力响应配置
) {
    companion object {
        /**
         * 默认配置
         *
         * 调整说明：
         * - 内存缓存：4MB（原 64MB → 4MB，数据量有限）
         * - 磁盘缓存：100MB（保持不变）
         */
        val DEFAULT = CacheConfig()

        /**
         * 低内存设备配置
         */
        fun lowMemoryConfig(): CacheConfig = CacheConfig(
            memoryCacheSize = 20 * 1024 * 1024,   // 20MB
            diskCacheSize = 40 * 1024 * 1024,     // 40MB
            writeBatchSize = 50,
            writeDelayMs = 2000L,
            enableDiskCache = true,
            enableCompression = true,
            maxEntryCount = 500,
            evictionPolicy = CacheEvictionPolicy.LRU,
            cleanupIntervalMs = 15_000L,           // 低配设备更频繁清理
            tieredConfig = TieredConfig(
                hotDataMaxSize = 50,
                warmDataMaxSize = 200,
                coldDataMaxSize = 300,
                hotAccessThreshold = 5,
                warmAccessThreshold = 2
            ),
            pressureResponseConfig = MemoryPressureResponseConfig(
                moderatePressureCacheReduction = 0.4f,
                highPressureCacheReduction = 0.2f,
                criticalPressureCacheReduction = 0.05f
            )
        )

        /**
         * 标准配置
         */
        fun standardConfig(): CacheConfig = CacheConfig(
            memoryCacheSize = 50 * 1024 * 1024,   // 50MB
            diskCacheSize = 100 * 1024 * 1024,    // 100MB
            writeBatchSize = 200,
            writeDelayMs = 500L,
            enableDiskCache = true,
            enableCompression = true,
            maxEntryCount = 1500,
            evictionPolicy = CacheEvictionPolicy.ADAPTIVE,
            cleanupIntervalMs = 30_000L
        )

        /**
         * 高性能配置
         */
        fun highPerformanceConfig(): CacheConfig = CacheConfig(
            memoryCacheSize = 100 * 1024 * 1024,  // 100MB
            diskCacheSize = 200 * 1024 * 1024,    // 200MB
            writeBatchSize = 200,
            writeDelayMs = 500L,
            enableDiskCache = true,
            enableCompression = true,
            maxEntryCount = 3000,
            evictionPolicy = CacheEvictionPolicy.ADAPTIVE,
            cleanupIntervalMs = 30_000L,
            tieredConfig = TieredConfig(
                hotDataMaxSize = 200,
                warmDataMaxSize = 800,
                coldDataMaxSize = 2000,
                hotAccessThreshold = 15,
                warmAccessThreshold = 5
            )
        )
    }

    /**
     * 创建新的配置实例，仅修改指定字段
     *
     * @param block 配置修改块
     * @return 新的配置实例
     */
    fun copy(block: Builder.() -> Unit): CacheConfig {
        val builder = Builder(this)
        builder.block()
        return builder.build()
    }

    /**
     * 配置构建器，用于动态调整配置
     */
    class Builder(private val config: CacheConfig = DEFAULT) {
        var memoryCacheSize: Long = config.memoryCacheSize
        var diskCacheSize: Long = config.diskCacheSize
        var writeBatchSize: Int = config.writeBatchSize
        var writeDelayMs: Long = config.writeDelayMs
        var enableDiskCache: Boolean = config.enableDiskCache
        var enableCompression: Boolean = config.enableCompression
        var maxEntryCount: Int = config.maxEntryCount
        var evictionPolicy: CacheEvictionPolicy = config.evictionPolicy
        var cleanupIntervalMs: Long = config.cleanupIntervalMs
        var tieredConfig: TieredConfig = config.tieredConfig
        var pressureResponseConfig: MemoryPressureResponseConfig = config.pressureResponseConfig

        fun build(): CacheConfig = CacheConfig(
            memoryCacheSize = memoryCacheSize,
            diskCacheSize = diskCacheSize,
            writeBatchSize = writeBatchSize,
            writeDelayMs = writeDelayMs,
            enableDiskCache = enableDiskCache,
            enableCompression = enableCompression,
            maxEntryCount = maxEntryCount,
            evictionPolicy = evictionPolicy,
            cleanupIntervalMs = cleanupIntervalMs,
            tieredConfig = tieredConfig,
            pressureResponseConfig = pressureResponseConfig
        )
    }

    /**
     * 根据内存压力等级计算应该使用的缓存大小比例
     */
    fun getCacheSizeRatioForPressure(pressureOrdinal: Int): Float {
        return when (pressureOrdinal) {
            0 -> 1.0f      // LOW - 使用完整大小
            1 -> 0.8f      // NORMAL - 80%
            2 -> pressureResponseConfig.moderatePressureCacheReduction  // MODERATE
            3 -> pressureResponseConfig.highPressureCacheReduction       // HIGH
            4 -> pressureResponseConfig.criticalPressureCacheReduction   // CRITICAL
            else -> 0.5f
        }
    }

    /**
     * 根据内存压力调整后的实际内存缓存大小
     */
    fun getAdjustedMemoryCacheSize(pressureOrdinal: Int): Long {
        return (memoryCacheSize * getCacheSizeRatioForPressure(pressureOrdinal)).toLong()
            .coerceAtLeast(5 * 1024 * 1024)  // 最少 5MB
    }

    /**
     * 根据内存压力调整后的实际最大条目数
     */
    fun getAdjustedMaxEntryCount(pressureOrdinal: Int): Int {
        return (maxEntryCount * getCacheSizeRatioForPressure(pressureOrdinal)).toInt()
            .coerceAtLeast(50)  // 最少 50 条
    }
}
