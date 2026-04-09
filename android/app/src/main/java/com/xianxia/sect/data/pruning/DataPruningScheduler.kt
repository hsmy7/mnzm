package com.xianxia.sect.data.pruning

import android.util.Log
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.concurrent.StorageCircuitBreaker
import com.xianxia.sect.data.concurrent.StorageScopeManager
import com.xianxia.sect.data.concurrent.StorageSubsystem
import com.xianxia.sect.data.local.GameDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class PruningConfig(
    val checkIntervalMs: Long = 300_000L,
    val maxBattleLogs: Int = StorageConstants.DEFAULT_MAX_BATTLE_LOGS,
    val maxGameEvents: Int = StorageConstants.DEFAULT_MAX_GAME_EVENTS,
    val battleLogRetentionMs: Long = 7 * 24 * 60 * 60 * 1000L,
    val gameEventRetentionMs: Long = 14 * 24 * 60 * 60 * 1000L,
    val slotIds: List<Int> = listOf(0, 1, 2, 3, 4),
    val enableAutoPruning: Boolean = true
)

data class PruningResult(
    val battleLogsDeleted: Int,
    val eventsDeleted: Int,
    val walSizeBeforeBytes: Long,
    val walSizeAfterBytes: Long,
    val dbSizeBeforeBytes: Long,
    val dbSizeAfterBytes: Long,
    val elapsedMs: Long
)

data class PruningStats(
    val totalPruningRuns: Long,
    val totalBattleLogsDeleted: Long,
    val totalEventsDeleted: Long,
    val lastPruningTime: Long,
    val lastPruningResult: PruningResult?,
    val isRunning: Boolean
)

@Singleton
class DataPruningScheduler @Inject constructor(
    private val database: GameDatabase,
    private val scopeManager: StorageScopeManager,
    private val circuitBreaker: StorageCircuitBreaker
) {
    private val config = PruningConfig()
    private var pruningJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    private val totalPruningRuns = AtomicLong(0)
    private val totalBattleLogsDeleted = AtomicLong(0)
    private val totalEventsDeleted = AtomicLong(0)
    private var lastPruningTime = 0L
    private var lastPruningResult: PruningResult? = null

    fun start() {
        if (!config.enableAutoPruning) {
            Log.i(TAG, "Auto pruning is disabled by config")
            return
        }

        if (pruningJob?.isActive == true) {
            Log.w(TAG, "Pruning scheduler already running")
            return
        }

        pruningJob = scopeManager.scopeFor(StorageSubsystem.PRUNING).launch {
            Log.i(TAG, "Data pruning scheduler started (interval=${config.checkIntervalMs}ms)")
            delay(config.checkIntervalMs)

            while (isActive) {
                try {
                    if (circuitBreaker.allowRequest("pruning")) {
                        performPruning()
                    } else {
                        Log.w(TAG, "Pruning skipped: circuit breaker is OPEN")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Pruning failed", e)
                    circuitBreaker.recordFailure("pruning")
                }

                delay(config.checkIntervalMs)
            }
        }
    }

    fun stop() {
        pruningJob?.cancel()
        pruningJob = null
        Log.i(TAG, "Data pruning scheduler stopped")
    }

    suspend fun performPruning(): PruningResult {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Pruning already in progress, skipping")
            return lastPruningResult ?: PruningResult(0, 0, 0, 0, 0, 0, 0)
        }

        scopeManager.beginOperation(StorageSubsystem.PRUNING)
        val startTime = System.currentTimeMillis()

        return try {
            val dbSizeBefore = database.getDatabaseSize()
            val walSizeBefore = database.getWalFileSize()

            val battleLogCutoff = System.currentTimeMillis() - config.battleLogRetentionMs
            val eventCutoff = System.currentTimeMillis() - config.gameEventRetentionMs

            var totalLogsDeleted = 0
            var totalEventsDeleted = 0

            for (slotId in config.slotIds) {
                try {
                    database.battleLogDao().deleteOld(slotId, battleLogCutoff)
                    totalLogsDeleted++
                } catch (e: Exception) {
                    Log.d(TAG, "Battle log pruning for slot $slotId: ${e.message}")
                }

                try {
                    database.gameEventDao().deleteOld(slotId, eventCutoff)
                    totalEventsDeleted++
                } catch (e: Exception) {
                    Log.d(TAG, "Game event pruning for slot $slotId: ${e.message}")
                }
            }

            val dbSizeAfter = database.getDatabaseSize()
            val walSizeAfter = database.getWalFileSize()
            val elapsed = System.currentTimeMillis() - startTime

            val result = PruningResult(
                battleLogsDeleted = totalLogsDeleted,
                eventsDeleted = totalEventsDeleted,
                walSizeBeforeBytes = walSizeBefore,
                walSizeAfterBytes = walSizeAfter,
                dbSizeBeforeBytes = dbSizeBefore,
                dbSizeAfterBytes = dbSizeAfter,
                elapsedMs = elapsed
            )

            this.totalPruningRuns.incrementAndGet()
            this.totalBattleLogsDeleted.addAndGet(totalLogsDeleted.toLong())
            this.totalEventsDeleted.addAndGet(totalEventsDeleted.toLong())
            lastPruningTime = System.currentTimeMillis()
            lastPruningResult = result

            circuitBreaker.recordSuccess("pruning")

            Log.i(TAG, "Pruning complete: logs=$totalLogsDeleted, events=$totalEventsDeleted, " +
                "dbSize=${dbSizeBefore / 1024}KB->${dbSizeAfter / 1024}KB, elapsed=${elapsed}ms")

            result
        } catch (e: Exception) {
            circuitBreaker.recordFailure("pruning")
            Log.e(TAG, "Pruning failed", e)
            PruningResult(0, 0, 0, 0, 0, 0, System.currentTimeMillis() - startTime)
        } finally {
            isRunning.set(false)
            scopeManager.endOperation(StorageSubsystem.PRUNING)
        }
    }

    fun getStats(): PruningStats {
        return PruningStats(
            totalPruningRuns = totalPruningRuns.get(),
            totalBattleLogsDeleted = totalBattleLogsDeleted.get(),
            totalEventsDeleted = totalEventsDeleted.get(),
            lastPruningTime = lastPruningTime,
            lastPruningResult = lastPruningResult,
            isRunning = isRunning.get()
        )
    }

    companion object {
        private const val TAG = "DataPruningScheduler"
    }
}
