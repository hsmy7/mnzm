package com.xianxia.sect.data.partition

enum class DataZone(
    val displayName: String,
    val description: String,
    val priority: Int,
    val defaultTtlMs: Long,
    val supportsCompression: Boolean,
    val defaultAlgorithm: CompressionType
) {
    HOT(
        displayName = "热数据区",
        description = "内存驻留，LZ4压缩，实时保存",
        priority = 1,
        defaultTtlMs = Long.MAX_VALUE,
        supportsCompression = true,
        defaultAlgorithm = CompressionType.LZ4
    ),
    WARM(
        displayName = "温数据区",
        description = "磁盘缓存，LZ4压缩，按需加载",
        priority = 2,
        defaultTtlMs = 3600_000L * 24,
        supportsCompression = true,
        defaultAlgorithm = CompressionType.LZ4
    ),
    COLD(
        displayName = "冷数据区",
        description = "仅数据库，LZ4压缩，延迟加载",
        priority = 3,
        defaultTtlMs = 3600_000L * 24 * 7,
        supportsCompression = true,
        defaultAlgorithm = CompressionType.LZ4
    );

    fun isHotterThan(other: DataZone): Boolean = priority < other.priority

    fun isColderThan(other: DataZone): Boolean = priority > other.priority

    companion object {
        fun fromName(name: String): DataZone {
            return values().find { it.name.equals(name, ignoreCase = true) } ?: WARM
        }

        fun fromPriority(priority: Int): DataZone {
            return values().find { it.priority == priority } ?: WARM
        }
    }
}

enum class CompressionType(
    val displayName: String,
    val speedRatio: Double,
    val compressionRatio: Double
) {
    LZ4("LZ4", 10.0, 2.5);

    fun toCacheCompressionAlgorithm(): com.xianxia.sect.data.cache.CompressionAlgorithm {
        return com.xianxia.sect.data.cache.CompressionAlgorithm.LZ4
    }

    companion object {
        fun fromName(name: String): CompressionType {
            return LZ4
        }
    }
}

enum class DataType(
    val displayName: String,
    val defaultZone: DataZone,
    val estimatedSizeBytes: Long,
    val accessFrequency: AccessFrequency
) {
    DISCIPLE(
        displayName = "弟子数据",
        defaultZone = DataZone.HOT,
        estimatedSizeBytes = 1024,
        accessFrequency = AccessFrequency.HIGH
    ),
    EQUIPMENT(
        displayName = "装备数据",
        defaultZone = DataZone.HOT,
        estimatedSizeBytes = 512,
        accessFrequency = AccessFrequency.HIGH
    ),
    GAME_DATA(
        displayName = "游戏核心数据",
        defaultZone = DataZone.HOT,
        estimatedSizeBytes = 2048,
        accessFrequency = AccessFrequency.HIGH
    ),
    MANUAL(
        displayName = "功法数据",
        defaultZone = DataZone.WARM,
        estimatedSizeBytes = 256,
        accessFrequency = AccessFrequency.MEDIUM
    ),
    PILL(
        displayName = "丹药数据",
        defaultZone = DataZone.WARM,
        estimatedSizeBytes = 128,
        accessFrequency = AccessFrequency.MEDIUM
    ),
    MATERIAL(
        displayName = "材料数据",
        defaultZone = DataZone.WARM,
        estimatedSizeBytes = 128,
        accessFrequency = AccessFrequency.MEDIUM
    ),
    HERB(
        displayName = "灵草数据",
        defaultZone = DataZone.WARM,
        estimatedSizeBytes = 64,
        accessFrequency = AccessFrequency.MEDIUM
    ),
    SEED(
        displayName = "种子数据",
        defaultZone = DataZone.WARM,
        estimatedSizeBytes = 64,
        accessFrequency = AccessFrequency.MEDIUM
    ),
    TEAM(
        displayName = "队伍数据",
        defaultZone = DataZone.WARM,
        estimatedSizeBytes = 512,
        accessFrequency = AccessFrequency.MEDIUM
    ),
    BUILDING_SLOT(
        displayName = "建筑槽位",
        defaultZone = DataZone.WARM,
        estimatedSizeBytes = 256,
        accessFrequency = AccessFrequency.MEDIUM
    ),
    EVENT(
        displayName = "事件数据",
        defaultZone = DataZone.COLD,
        estimatedSizeBytes = 512,
        accessFrequency = AccessFrequency.LOW
    ),
    BATTLE_LOG(
        displayName = "战斗日志",
        defaultZone = DataZone.COLD,
        estimatedSizeBytes = 1024,
        accessFrequency = AccessFrequency.LOW
    ),
    ARCHIVED_EVENT(
        displayName = "归档事件",
        defaultZone = DataZone.COLD,
        estimatedSizeBytes = 512,
        accessFrequency = AccessFrequency.RARE
    ),
    ARCHIVED_BATTLE_LOG(
        displayName = "归档战斗日志",
        defaultZone = DataZone.COLD,
        estimatedSizeBytes = 1024,
        accessFrequency = AccessFrequency.RARE
    ),
    ALLIANCE(
        displayName = "联盟数据",
        defaultZone = DataZone.WARM,
        estimatedSizeBytes = 256,
        accessFrequency = AccessFrequency.MEDIUM
    );

    fun toCacheKeyType(): String {
        return when (this) {
            DISCIPLE -> com.xianxia.sect.data.cache.CacheKey.TYPE_DISCIPLE
            EQUIPMENT -> com.xianxia.sect.data.cache.CacheKey.TYPE_EQUIPMENT
            MANUAL -> com.xianxia.sect.data.cache.CacheKey.TYPE_MANUAL
            PILL -> com.xianxia.sect.data.cache.CacheKey.TYPE_PILL
            MATERIAL -> com.xianxia.sect.data.cache.CacheKey.TYPE_MATERIAL
            HERB -> com.xianxia.sect.data.cache.CacheKey.TYPE_HERB
            SEED -> com.xianxia.sect.data.cache.CacheKey.TYPE_SEED
            TEAM -> com.xianxia.sect.data.cache.CacheKey.TYPE_TEAM
            GAME_DATA -> com.xianxia.sect.data.cache.CacheKey.TYPE_GAME_DATA
            BUILDING_SLOT -> com.xianxia.sect.data.cache.CacheKey.TYPE_BUILDING_SLOT
            EVENT -> com.xianxia.sect.data.cache.CacheKey.TYPE_EVENT
            BATTLE_LOG -> com.xianxia.sect.data.cache.CacheKey.TYPE_BATTLE_LOG
            ALLIANCE -> com.xianxia.sect.data.cache.CacheKey.TYPE_ALLIANCE
            else -> name.lowercase()
        }
    }

    companion object {
        fun fromCacheKeyType(type: String): DataType {
            return values().find { it.toCacheKeyType() == type } ?: GAME_DATA
        }

        fun fromName(name: String): DataType {
            return values().find { it.name.equals(name, ignoreCase = true) } ?: GAME_DATA
        }
    }
}

enum class AccessFrequency(
    val displayName: String,
    val accessesPerHour: IntRange
) {
    HIGH("高频访问", 60..Int.MAX_VALUE),
    MEDIUM("中频访问", 10..59),
    LOW("低频访问", 1..9),
    RARE("极少访问", 0..0);

    fun shouldPromote(): Boolean = this == HIGH

    fun shouldDemote(): Boolean = this == RARE
}

data class ZoneTransition(
    val from: DataZone,
    val to: DataZone,
    val reason: TransitionReason,
    val timestamp: Long = System.currentTimeMillis()
)

enum class TransitionReason {
    ACCESS_FREQUENCY_HIGH,
    ACCESS_FREQUENCY_LOW,
    MANUAL_PROMOTION,
    MANUAL_DEMOTION,
    MEMORY_PRESSURE,
    CACHE_EVICTION,
    DATA_AGE,
    USER_REQUEST
}
