package com.xianxia.sect.data.cache

data class UnifiedCacheConfig(
    val memoryCacheSize: Long = 16 * 1024 * 1024,
    val diskCacheSize: Long = 50 * 1024 * 1024,
    val enableDiskCache: Boolean = true,
    val warmDataTtlMs: Long = 300_000L,
    val hotDataTtlMs: Long = 60_000L,
    val defaultTtlMs: Long = 120_000L,
    val cleanupIntervalMs: Long = 60_000L,
    val statsLogIntervalMs: Long = 300_000L
) {
    companion object {
        val DEFAULT = UnifiedCacheConfig()
        
        val DATA_TYPE_TTL: Map<String, Long> = mapOf(
            "disciple" to DEFAULT.warmDataTtlMs,
            "equipment" to DEFAULT.warmDataTtlMs,
            "manual" to DEFAULT.warmDataTtlMs,
            "pill" to DEFAULT.hotDataTtlMs,
            "material" to DEFAULT.hotDataTtlMs,
            "herb" to DEFAULT.hotDataTtlMs,
            "seed" to DEFAULT.hotDataTtlMs,
            "game_data" to DEFAULT.warmDataTtlMs,
            "battle_log" to DEFAULT.hotDataTtlMs,
            "event" to DEFAULT.hotDataTtlMs,
            "team" to DEFAULT.warmDataTtlMs,
            "building_slot" to DEFAULT.warmDataTtlMs
        )
    }
}
