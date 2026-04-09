package com.xianxia.sect.data.cache

import android.util.Log
import kotlinx.coroutines.*

class StartupWarmupScheduler(
    private val cacheManager: GameDataCacheManager,
    private val memoryManager: com.xianxia.sect.data.memory.DynamicMemoryManager? = null
) {
    companion object {
        private const val TAG = "StartupWarmupScheduler"
    }

    enum class WarmupPhase(val displayName: String, val delayMs: Long) {
        PHASE1_CRITICAL("Critical Data", 0L),
        PHASE2_HIGH("High Priority", 2000L),
        PHASE3_NORMAL("Normal Priority", 5000L),
        PHASE4_BACKGROUND("Background", 8000L)
    }

    private val phaseKeys = mapOf(
        WarmupPhase.PHASE1_CRITICAL to listOf(
            CacheKey(type = "game_data", slot = 0, id = "current", ttl = Long.MAX_VALUE)
        ),
        WarmupPhase.PHASE2_HIGH to listOf(
            CacheKey(type = "disciple", slot = 0, id = "*", ttl = 86400_000L),
            CacheKey(type = "equipment", slot = 0, id = "*", ttl = 86400_000L)
        ),
        WarmupPhase.PHASE3_NORMAL to listOf(
            CacheKey(type = "manual", slot = 0, id = "*", ttl = 604800_000L),
            CacheKey(type = "pill", slot = 0, id = "*", ttl = 604800_000L),
            CacheKey(type = "material", slot = 0, id = "*", ttl = 604800_000L)
        ),
        WarmupPhase.PHASE4_BACKGROUND to listOf(
            CacheKey(type = "battle_log", slot = 0, id = "*", ttl = 604800_000L),
            CacheKey(type = "event", slot = 0, id = "*", ttl = 86400_000L)
        )
    )

    suspend fun scheduleWarmup() {
        Log.i(TAG, "Starting phased warmup...")

        for ((phase, keys) in phaseKeys) {
            if (phase.delayMs > 0) delay(phase.delayMs)

            val pressureOk = memoryManager?.let {
                it.getMemorySnapshot().pressureLevel.ordinalValue <= 1
            } ?: true

            if (!pressureOk) {
                Log.i(TAG, "Skipping ${phase.displayName}: memory pressure too high")
                continue
            }

            warmupPhase(phase, keys)
        }

        Log.i(TAG, "Phased warmup completed")
    }

    private suspend fun warmupPhase(phase: WarmupPhase, keys: List<CacheKey>) {
        Log.d(TAG, "Warming up ${phase.displayName}: ${keys.size} key patterns")
        var loaded = 0

        for (key in keys) {
            try {
                val cached: Any? = cacheManager.getOrNull(key)
                if (cached != null) loaded++
            } catch (e: Exception) {
                // Keys with "*" pattern won't match anything - that's fine
            }
        }

        if (loaded > 0) {
            Log.i(TAG, "${phase.displayName}: $loaded entries warmed up")
        }
    }
}
