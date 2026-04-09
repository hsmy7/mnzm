package com.xianxia.sect.data.cache

import org.junit.Assert.*
import org.junit.Test

class CacheTypesTest {

    // ==================== CachePriority ====================

    @Test
    fun `CachePriority has 5 values`() {
        assertEquals(5, CachePriority.values().size)
        val values = setOf(*CachePriority.values())
        assertTrue(values.contains(CachePriority.CRITICAL))
        assertTrue(values.contains(CachePriority.HIGH))
        assertTrue(values.contains(CachePriority.NORMAL))
        assertTrue(values.contains(CachePriority.LOW))
        assertTrue(values.contains(CachePriority.BACKGROUND))
    }

    // ==================== CacheStrategy ====================

    @Test
    fun `CacheStrategy has 4 values`() {
        assertEquals(4, CacheStrategy.values().size)
        val values = setOf(*CacheStrategy.values())
        assertTrue(values.contains(CacheStrategy.WRITE_THROUGH))
        assertTrue(values.contains(CacheStrategy.WRITE_BEHIND))
        assertTrue(values.contains(CacheStrategy.WRITE_AROUND))
        assertTrue(values.contains(CacheStrategy.CACHE_ASIDE))
    }

    // ==================== CacheZone ====================

    @Test
    fun `CacheZone has 3 values`() {
        assertEquals(3, CacheZone.values().size)
        val values = setOf(*CacheZone.values())
        assertTrue(values.contains(CacheZone.HOT))
        assertTrue(values.contains(CacheZone.WARM))
        assertTrue(values.contains(CacheZone.COLD))
    }

    // ==================== CachePolicy ====================

    @Test
    fun `CachePolicy constructor sets fields correctly`() {
        val policy = CachePolicy(
            priority = CachePriority.HIGH,
            ttl = 5000L,
            strategy = CacheStrategy.WRITE_BEHIND,
            maxSize = 200,
            compress = true,
            evictOnLowMemory = false
        )
        assertEquals(CachePriority.HIGH, policy.priority)
        assertEquals(5000L, policy.ttl)
        assertEquals(CacheStrategy.WRITE_BEHIND, policy.strategy)
        assertEquals(200, policy.maxSize)
        assertTrue(policy.compress)
        assertFalse(policy.evictOnLowMemory)
    }

    @Test
    fun `CachePolicy default values`() {
        val policy = CachePolicy(
            priority = CachePriority.NORMAL,
            ttl = 1000L,
            strategy = CacheStrategy.WRITE_THROUGH
        )
        assertEquals(100, policy.maxSize)
        assertFalse(policy.compress)
        assertTrue(policy.evictOnLowMemory)
    }

    // ==================== CacheTypeConfig ====================

    @Test
    fun `DATA_TYPE_PRIORITIES contains expected keys`() {
        val expectedKeys = setOf(
            "game_data", "disciple", "equipment", "manual",
            "pill", "material", "herb", "seed", "battle_log", "event"
        )
        assertEquals(expectedKeys, CacheTypeConfig.DATA_TYPE_PRIORITIES.keys)
    }

    @Test
    fun `DATA_TYPE_TTL contains expected keys with reasonable values`() {
        val keys = CacheTypeConfig.DATA_TYPE_TTL.keys
        val expectedKeys = setOf(
            "game_data", "disciple", "equipment", "manual",
            "pill", "material", "herb", "seed", "battle_log", "event"
        )
        assertEquals(expectedKeys, keys)

        for ((key, ttl) in CacheTypeConfig.DATA_TYPE_TTL) {
            assertTrue("TTL for $key should be positive: $ttl", ttl > 0)
        }
        assertEquals(Long.MAX_VALUE, CacheTypeConfig.DATA_TYPE_TTL["game_data"])
    }

    @Test
    fun `DATA_TYPE_STRATEGY contains expected keys`() {
        val expectedKeys = setOf(
            "game_data", "disciple", "equipment", "manual",
            "pill", "material", "herb", "seed", "battle_log", "event"
        )
        assertEquals(expectedKeys, CacheTypeConfig.DATA_TYPE_STRATEGY.keys)
    }

    @Test
    fun `DATA_TYPE_SWR_POLICY contains expected keys`() {
        val swrKeys = CacheTypeConfig.DATA_TYPE_SWR_POLICY.keys
        assertTrue(swrKeys.contains("game_data"))
        assertTrue(swrKeys.contains("disciple"))
        assertTrue(swrKeys.contains("save_data"))
        assertTrue(swrKeys.contains("team"))
        assertTrue(swrKeys.size >= 12)
    }

    @Test
    fun `DEFAULT_MEMORY_CACHE_SIZE is 64MB`() {
        assertEquals(64 * 1024 * 1024L, CacheTypeConfig.DEFAULT_MEMORY_CACHE_SIZE)
    }

    @Test
    fun `WARM_DATA_TTL_MS is 24 hours`() {
        assertEquals(3600_000L * 24, CacheTypeConfig.WARM_DATA_TTL_MS)
    }

    // ==================== CacheStats ====================

    @Test
    fun `CacheStats default values are all zero`() {
        val stats = CacheStats()
        assertEquals(0L, stats.memoryHitCount)
        assertEquals(0L, stats.memoryMissCount)
        assertEquals(0L, stats.memorySize)
        assertEquals(0, stats.memoryEntryCount)
        assertEquals(0L, stats.diskSize)
        assertEquals(0, stats.diskEntryCount)
        assertEquals(0, stats.dirtyCount)
        assertEquals(0, stats.writeQueueSize)
        assertEquals(0, stats.hotDataCount)
        assertEquals(0, stats.warmDataCount)
        assertEquals(0, stats.coldDataCount)
        assertEquals(0L, stats.evictedCount)
        assertEquals(0L, stats.totalAccessCount)
        assertEquals(0, stats.memoryPressureLevel)
    }

    @Test
    fun `memoryHitRate returns hit over total when total is positive`() {
        val stats = CacheStats(memoryHitCount = 80, memoryMissCount = 20)
        assertEquals(0.8, stats.memoryHitRate, 0.001)
    }

    @Test
    fun `memoryHitRate returns 0 when no hits or misses`() {
        val stats = CacheStats()
        assertEquals(0.0, stats.memoryHitRate, 0.001)
    }

    @Test
    fun `totalTieredEntries equals sum of hot warm cold`() {
        val stats = CacheStats(hotDataCount = 10, warmDataCount = 20, coldDataCount = 5)
        assertEquals(35, stats.totalTieredEntries)
    }

    @Test
    fun `formatSummary returns non-empty string`() {
        val stats = CacheStats(
            memoryHitCount = 100,
            memoryMissCount = 50,
            hotDataCount = 5,
            warmDataCount = 10,
            coldDataCount = 3
        )
        val summary = stats.formatSummary()
        assertTrue(summary.isNotEmpty())
        assertTrue(summary.contains("Cache Statistics"))
        assertTrue(summary.contains("Hit Rate"))
    }

    @Test
    fun `getPressureLevelName maps levels correctly`() {
        assertEquals("LOW", CacheStats(memoryPressureLevel = 0).getPressureLevelName())
        assertEquals("NORMAL", CacheStats(memoryPressureLevel = 1).getPressureLevelName())
        assertEquals("MODERATE", CacheStats(memoryPressureLevel = 2).getPressureLevelName())
        assertEquals("HIGH", CacheStats(memoryPressureLevel = 3).getPressureLevelName())
        assertEquals("CRITICAL", CacheStats(memoryPressureLevel = 4).getPressureLevelName())
        assertEquals("UNKNOWN", CacheStats(memoryPressureLevel = 99).getPressureLevelName())
    }

    // ==================== DirtyFlag ====================

    @Test
    fun `DirtyFlag has 3 values`() {
        assertEquals(3, DirtyFlag.values().size)
        val values = setOf(*DirtyFlag.values())
        assertTrue(values.contains(DirtyFlag.INSERT))
        assertTrue(values.contains(DirtyFlag.UPDATE))
        assertTrue(values.contains(DirtyFlag.DELETE))
    }

    // ==================== FlushResult ====================

    @Test
    fun `FlushResult Success holds count`() {
        val result = FlushResult.Success(42)
        assertEquals(42, result.count)
    }

    @Test
    fun `FlushResult NoChanges is singleton`() {
        assertSame(FlushResult.NoChanges, FlushResult.NoChanges)
    }

    @Test
    fun `FlushResult Error holds message and optional cause`() {
        val cause = RuntimeException("root cause")
        val result = FlushResult.Error("failed", cause)
        assertEquals("failed", result.message)
        assertEquals(cause, result.cause)
    }

    // ==================== SwrPolicy ====================

    @Test
    fun `SwrPolicy constructor sets fields correctly`() {
        val policy = SwrPolicy(ttlMs = 10000L, staleWhileRevalidateMs = 3000L)
        assertEquals(10000L, policy.ttlMs)
        assertEquals(3000L, policy.staleWhileRevalidateMs)
    }

    @Test
    fun `SwrPolicy default staleWhileRevalidateMs is 25 percent of ttlMs`() {
        val policy = SwrPolicy(ttlMs = 10000L)
        assertEquals(2500L, policy.staleWhileRevalidateMs)
    }

    @Test
    fun `SwrPolicy totalWindowMs equals ttl plus stale window`() {
        val policy = SwrPolicy(ttlMs = 8000L, staleWhileRevalidateMs = 2000L)
        assertEquals(10000L, policy.totalWindowMs)
    }
}
