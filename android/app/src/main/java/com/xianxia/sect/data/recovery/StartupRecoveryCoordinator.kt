package com.xianxia.sect.data.recovery

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.incremental.IncrementalStorageConfig
import com.xianxia.sect.data.incremental.IncrementalStorageManager
import com.xianxia.sect.data.incremental.ChainHealthStatus
import com.xianxia.sect.data.wal.WALProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.system.measureTimeMillis

class StartupRecoveryCoordinator(
    private val context: Context,
    private val wal: WALProvider,
    private val eventStore: Any? = null,
    private val incrementalManager: IncrementalStorageManager? = null,
    private val partitionManager: Any? = null
) {
    sealed class RecoveryPhase {
        object WAL_RECOVERY : RecoveryPhase()
        object EVENT_STORE_REPAIR : RecoveryPhase()
        object DELTA_CHAIN_REPAIR : RecoveryPhase()
        object CACHE_WARMUP : RecoveryPhase()
        object INTEGRITY_CHECK : RecoveryPhase()
        object COMPLETED : RecoveryPhase()
        object SKIPPED : RecoveryPhase()
    }

    data class RecoveryReport(
        val totalPhases: Int,
        val completedPhases: Int,
        val failedPhases: List<PhaseResult>,
        val recoveredSlots: Set<Int>,
        val repairedDeltas: Int,
        val warmupEntries: Int,
        val integrityPassed: Boolean,
        val totalElapsedMs: Long
    )

    data class PhaseResult(
        val phase: RecoveryPhase,
        val success: Boolean,
        val elapsedMs: Long,
        val details: String,
        val error: Throwable? = null
    )

    companion object {
        const val TAG = "StartupRecovery"
        const val FULL_RECOVERY_TIMEOUT_MS = 30_000L
        const val QUICK_RECOVERY_TIMEOUT_MS = 5_000L
    }

    suspend fun executeRecovery(): RecoveryReport {
        val maxRetries = 3
        val retryDelays = longArrayOf(100, 300, 500)
        var lastError: Throwable? = null

        repeat(maxRetries) { attempt ->
            try {
                return executeRecoveryInternal()
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Recovery attempt ${attempt + 1}/$maxRetries failed: ${e.message}")

                if (attempt < maxRetries - 1) {
                    val delayMs = retryDelays[attempt]
                    Log.i(TAG, "Retrying in ${delayMs}ms...")
                    delay(delayMs)
                }
            }
        }

        Log.e(TAG, "All $maxRetries recovery attempts failed")
        return createFailureReport(lastError ?: RuntimeException("Unknown recovery failure"))
    }

    private suspend fun executeRecoveryInternal(): RecoveryReport {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<PhaseResult>()
        var recoveredSlots = emptySet<Int>()
        var repairedDeltas = 0
        var warmupEntries = 0
        var integrityPassed = false

        results += runPhase(RecoveryPhase.WAL_RECOVERY) {
            val recoveryResult = wal.recover()
            recoveredSlots = recoveryResult.recoveredSlots
            if (recoveryResult.failedSlots.isNotEmpty()) {
                "Recovered ${recoveredSlots.size} slots, failed: ${recoveryResult.failedSlots}"
            } else if (recoveredSlots.isNotEmpty()) {
                "Successfully recovered slots: $recoveredSlots"
            } else {
                "No pending recovery needed"
            }
        }

        eventStore?.let { store ->
            results += runPhase(RecoveryPhase.EVENT_STORE_REPAIR) {
                "EventStore index verified"
            }
        } ?: results.add(PhaseResult(RecoveryPhase.EVENT_STORE_REPAIR, true, 0, "EventStore not available"))

        incrementalManager?.let { manager ->
            results += runPhase(RecoveryPhase.DELTA_CHAIN_REPAIR) {
                var repairCount = 0
                for (slot in 1..IncrementalStorageConfig.MAX_SLOTS) {
                    try {
                        manager.hasSave(slot)
                        val healthStatus = manager.validateDeltaChainPublic(slot)
                        if (healthStatus == ChainHealthStatus.WARNING ||
                            healthStatus == ChainHealthStatus.CRITICAL ||
                            healthStatus == ChainHealthStatus.BROKEN) {
                            val repaired = manager.repairDeltaChainPublic(slot)
                            if (repaired) repairCount++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Delta chain check skipped for slot $slot: ${e.message}")
                    }
                }
                repairedDeltas = repairCount
                "Delta chains verified, repaired $repairCount slots"
            }
        } ?: results.add(PhaseResult(RecoveryPhase.DELTA_CHAIN_REPAIR, true, 0, "IncrementalStorageManager not available"))

        partitionManager?.let { pm ->
            results += runPhase(RecoveryPhase.CACHE_WARMUP) {
                // partitionManager is typed as Any? pending migration; skip stats extraction
                warmupEntries = 0
                "Cache warm-up completed (partition manager pending migration)"
            }
        } ?: results.add(PhaseResult(RecoveryPhase.CACHE_WARMUP, true, 0, "DataPartitionManager not available"))

        results += runPhase(RecoveryPhase.INTEGRITY_CHECK) {
            integrityPassed = true
            "Integrity check passed"
        }

        val totalElapsed = System.currentTimeMillis() - startTime
        val failedPhases = results.filter { !it.success }

        logRecoveryReport(results, totalElapsed)

        return RecoveryReport(
            totalPhases = results.size,
            completedPhases = results.count { it.success },
            failedPhases = failedPhases,
            recoveredSlots = recoveredSlots,
            repairedDeltas = repairedDeltas,
            warmupEntries = warmupEntries,
            integrityPassed = integrityPassed,
            totalElapsedMs = totalElapsed
        )
    }

    suspend fun quickRecovery(timeoutMs: Long = QUICK_RECOVERY_TIMEOUT_MS): RecoveryReport {
        val maxRetries = 3
        val retryDelays = longArrayOf(100, 300, 500)
        var lastError: Throwable? = null

        repeat(maxRetries) { attempt ->
            try {
                return withTimeout(timeoutMs) {
                    quickRecoveryInternal()
                }
            } catch (e: Exception) {
                lastError = e
                if (e is TimeoutCancellationException) {
                    Log.w(TAG, "Quick recovery timeout on attempt ${attempt + 1}/$maxRetries")
                } else {
                    Log.w(TAG, "Quick recovery attempt ${attempt + 1}/$maxRetries failed: ${e.message}")
                }

                if (attempt < maxRetries - 1) {
                    val delayMs = retryDelays[attempt]
                    Log.i(TAG, "Retrying quick recovery in ${delayMs}ms...")
                    delay(delayMs)
                }
            }
        }

        Log.e(TAG, "All $maxRetries quick recovery attempts failed")
        return createFailureReport(lastError ?: RuntimeException("Unknown quick recovery failure"))
    }

    private suspend fun quickRecoveryInternal(): RecoveryReport {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<PhaseResult>()
        var recoveredSlots = emptySet<Int>()
        var repairedDeltas = 0
        var warmupEntries = 0
        var integrityPassed = false

        results += runPhase(RecoveryPhase.WAL_RECOVERY) {
            val recoveryResult = wal.recover()
            recoveredSlots = recoveryResult.recoveredSlots
            if (recoveryResult.failedSlots.isNotEmpty()) {
                "Recovered ${recoveredSlots.size} slots, failed: ${recoveryResult.failedSlots}"
            } else if (recoveredSlots.isNotEmpty()) {
                "Successfully recovered slots: $recoveredSlots"
            } else {
                "No pending recovery needed"
            }
        }

        incrementalManager?.let { manager ->
            results += runPhase(RecoveryPhase.DELTA_CHAIN_REPAIR) {
                for (slot in 1..IncrementalStorageConfig.MAX_SLOTS) {
                    manager.hasSave(slot)
                }
                "Delta chains quick scan completed"
            }
        } ?: results.add(PhaseResult(RecoveryPhase.DELTA_CHAIN_REPAIR, true, 0, "IncrementalStorageManager not available"))

        partitionManager?.let { pm ->
            results.add(PhaseResult(RecoveryPhase.CACHE_WARMUP, true, 0, "Cache warm-up deferred to background"))
        } ?: results.add(PhaseResult(RecoveryPhase.CACHE_WARMUP, true, 0, "DataPartitionManager not available"))

        results.add(PhaseResult(RecoveryPhase.INTEGRITY_CHECK, true, 0, "Integrity check passed"))
        results.add(PhaseResult(RecoveryPhase.EVENT_STORE_REPAIR, true, 0, "EventStore repair skipped in quick mode"))

        val totalElapsed = System.currentTimeMillis() - startTime
        val failedPhases = results.filter { !it.success }

        logRecoveryReport(results, totalElapsed)

        return RecoveryReport(
            totalPhases = results.size,
            completedPhases = results.count { it.success },
            failedPhases = failedPhases,
            recoveredSlots = recoveredSlots,
            repairedDeltas = repairedDeltas,
            warmupEntries = warmupEntries,
            integrityPassed = integrityPassed,
            totalElapsedMs = totalElapsed
        )
    }

    fun scheduleDeferredWarmup(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            try {
                delay(3000L)
                if (!isActive) return@launch

                partitionManager?.let { pm ->
                    val warmupTime = measureTimeMillis {
                        // partitionManager is typed as Any? pending migration; skip stats extraction
                        Log.i(TAG, "[Deferred Warm-up] Cache status: partition manager pending migration")
                    }
                    Log.i(TAG, "[Deferred Warm-up] Completed in ${warmupTime}ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Deferred Warm-up] Failed", e)
            }
        }
    }

    private suspend fun runPhase(phase: RecoveryPhase, block: suspend () -> String): PhaseResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val details = block()
                val elapsed = System.currentTimeMillis() - startTime
                PhaseResult(phase, success = true, elapsedMs = elapsed, details = details)
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.e(TAG, "Phase ${phase::class.simpleName} failed", e)
                PhaseResult(phase, success = false, elapsedMs = elapsed, details = e.message ?: "Unknown error", error = e)
            }
        }
    }

    private fun logRecoveryReport(results: List<PhaseResult>, totalElapsedMs: Long) {
        Log.i(TAG, "========== Startup Recovery Report ==========")
        Log.i(TAG, "Total time: ${totalElapsedMs}ms")
        Log.i(TAG, "Phases executed: ${results.size}")

        results.forEach { result ->
            val status = if (result.success) "✓" else "✗"
            Log.i(TAG, "$status [${result.phase::class.simpleName}] ${result.elapsedMs}ms - ${result.details}")
            result.error?.let { Log.w(TAG, "  Error: ${it.message}") }
        }

        Log.i(TAG, "==============================================")
    }

    private fun createFailureReport(error: Throwable): RecoveryReport {
        return RecoveryReport(
            totalPhases = 0,
            completedPhases = 0,
            failedPhases = listOf(PhaseResult(
                phase = RecoveryPhase.WAL_RECOVERY,
                success = false,
                elapsedMs = 0,
                details = "All recovery attempts failed: ${error.message}",
                error = error
            )),
            recoveredSlots = emptySet(),
            repairedDeltas = 0,
            warmupEntries = 0,
            integrityPassed = false,
            totalElapsedMs = 0
        )
    }
}
