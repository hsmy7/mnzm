package com.xianxia.sect.data.pipeline

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.incremental.IncrementalStorageManager
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import com.xianxia.sect.data.serialization.unified.SaveDataConverter
import com.xianxia.sect.data.serialization.unified.SerializableSaveData
import com.xianxia.sect.data.wal.WALProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class AtomicSaveResult(
    val success: Boolean,
    val phase: AtomicSavePipeline.Phase,
    val participants: Map<String, Any> = emptyMap(),
    val elapsedMs: Long = 0,
    val error: String? = null
)

data class PrepareIntent(
    val subsystem: Subsystem,
    val slot: Int,
    val operation: String,
    val dataHash: String,
    val preparedAtMs: Long = System.currentTimeMillis()
)

data class CompensationRecord(
    val intent: PrepareIntent,
    val executedAtMs: Long,
    val compensationNeeded: Boolean
)

enum class Subsystem { WAL, INCREMENTAL, CACHE }

class AtomicSavePipeline(
    private val context: Context,
    private val wal: WALProvider,
    private val incrementalManager: IncrementalStorageManager,
    private val cacheManager: GameDataCacheManager,
    private val engine: com.xianxia.sect.data.engine.UnifiedStorageEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    enum class Phase { IDLE, PREPARING, COMMITTING, ROLLING_BACK, COMPLETED, FAILED }

    sealed class Participant {
        data class WALParticipant(val txnId: Long) : Participant()
        data class IncrementalParticipant(val deltaVersion: Long) : Participant()
        data class CacheFlushParticipant(val dirtyCount: Int) : Participant()
    }

    companion object {
        const val TAG = "AtomicSavePipeline"
        private const val PHASE_TIMEOUT_MS = 10_000L
    }

    private val pipelineMutex = Mutex()
    @Volatile
    private var currentPhase = Phase.IDLE
    @Volatile
    private var currentSlot = 0
    private var saveDataRef: ByteArray? = null

    suspend fun executeAtomicSave(
        slot: Int,
        saveData: ByteArray,
        gameEvent: String? = null,
        currentGameYear: Int = 0
    ): AtomicSaveResult {
        val startTime = System.currentTimeMillis()

        return pipelineMutex.withLock {
            currentPhase = Phase.PREPARING
            this.currentSlot = slot
            this.saveDataRef = saveData
            Log.d(TAG, "[slot=$slot] === Atomic Save Start ===")

            try {
                val prepared = withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                    phase1Prepare(slot, saveData)
                } ?: run {
                    Log.e(TAG, "[slot=$slot] Phase 1 Prepare timeout after ${PHASE_TIMEOUT_MS}ms")
                    return@withLock AtomicSaveResult(
                        success = false,
                        phase = Phase.FAILED,
                        elapsedMs = System.currentTimeMillis() - startTime,
                        error = "Phase 1 (Prepare) timeout"
                    )
                }

                if (prepared.isEmpty()) {
                    Log.w(TAG, "[slot=$slot] No participants prepared, nothing to commit")
                    return@withLock AtomicSaveResult(
                        success = true,
                        phase = Phase.COMPLETED,
                        elapsedMs = System.currentTimeMillis() - startTime
                    )
                }

                currentPhase = Phase.COMMITTING
                Log.d(TAG, "[slot=$slot] Phase 1 complete, participants: ${prepared.keys.joinToString(",")}")

                val committed = withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                    phase2Commit(prepared, slot, gameEvent, currentGameYear)
                } ?: run {
                    Log.e(TAG, "[slot=$slot] Phase 2 Commit timeout after ${PHASE_TIMEOUT_MS}ms")
                    rollback(prepared, Subsystem.WAL)
                    return@withLock AtomicSaveResult(
                        success = false,
                        phase = Phase.FAILED,
                        participants = serializeParticipants(prepared),
                        elapsedMs = System.currentTimeMillis() - startTime,
                        error = "Phase 2 (Commit) timeout, rollback initiated"
                    )
                }

                if (!committed) {
                    Log.e(TAG, "[slot=$slot] Phase 2 Commit failed")
                    rollback(prepared, Subsystem.WAL)
                    return@withLock AtomicSaveResult(
                        success = false,
                        phase = Phase.FAILED,
                        participants = serializeParticipants(prepared),
                        elapsedMs = System.currentTimeMillis() - startTime,
                        error = "Phase 2 (Commit) failed"
                    )
                }

                currentPhase = Phase.COMPLETED
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "[slot=$slot] === Atomic Save Success in ${elapsed}ms ===")

                AtomicSaveResult(
                    success = true,
                    phase = Phase.COMPLETED,
                    participants = serializeParticipants(prepared),
                    elapsedMs = elapsed
                )

            } catch (e: Exception) {
                Log.e(TAG, "[slot=$slot] Atomic save exception: ${e.message}", e)
                currentPhase = Phase.FAILED
                AtomicSaveResult(
                    success = false,
                    phase = Phase.FAILED,
                    elapsedMs = System.currentTimeMillis() - startTime,
                    error = e.message ?: "Unknown exception"
                )
            } finally {
                currentPhase = Phase.IDLE
                this.saveDataRef = null
            }
        }
    }

    private suspend fun phase1Prepare(slot: Int, saveData: ByteArray): Map<Subsystem, Participant> {
        val participants = mutableMapOf<Subsystem, Participant>()

        Log.d(TAG, "[slot=$slot] --- Phase 1: Prepare (validation only) ---")

        val walResult = prepareWAL(slot, saveData)
        if (walResult != null) {
            participants[Subsystem.WAL] = walResult
            Log.d(TAG, "[slot=$slot] [Prepare] WAL ready: txnId=${(walResult as Participant.WALParticipant).txnId}")
        } else {
            Log.e(TAG, "[slot=$slot] [Prepare] WAL failed, aborting prepare phase")
            rollback(participants, Subsystem.WAL)
            return emptyMap()
        }

        participants[Subsystem.CACHE] = Participant.CacheFlushParticipant(0)
        Log.d(TAG, "[slot=$slot] [Prepare] Cache validated (will flush in commit phase)")

        val chainHealth = incrementalManager.getChainHealthReport(slot)
        if (chainHealth.healthStatus != com.xianxia.sect.data.incremental.ChainHealthStatus.BROKEN) {
            participants[Subsystem.INCREMENTAL] = Participant.IncrementalParticipant(chainHealth.chainLength.toLong())
            Log.d(TAG, "[slot=$slot] [Prepare] Incremental validated: chainLength=${chainHealth.chainLength}")
        } else {
            Log.w(TAG, "[slot=$slot] [Prepare] Incremental chain broken (non-fatal)")
        }

        Log.d(TAG, "[slot=$slot] --- Phase 1 Complete: ${participants.size} subsystems prepared ---")
        return participants
    }

    private suspend fun prepareWAL(slot: Int, saveData: ByteArray): Participant.WALParticipant? {
        return try {
            val result = wal.beginTransaction(
                slot = slot,
                operation = com.xianxia.sect.data.wal.WALEntryType.DATA,
                snapshotProvider = { saveData }
            )
            if (result.isSuccess) {
                Participant.WALParticipant(result.getOrThrow())
            } else {
                Log.e(TAG, "[Prepare] WAL beginTransaction failed")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Prepare] WAL beginTransaction exception", e)
            null
        }
    }

    private suspend fun phase2Commit(
        participants: Map<Subsystem, Participant>,
        slot: Int,
        gameEvent: String?,
        currentGameYear: Int
    ): Boolean {
        Log.d(TAG, "[slot=$slot] --- Phase 2: Commit (execute real operations) ---")

        val dbWriteSuccess = writeSaveDataToDatabase(slot)
        if (!dbWriteSuccess) {
            Log.e(TAG, "[slot=$slot] [Commit] CRITICAL: Room Database write FAILED! Aborting commit.")
            return false
        }
        Log.d(TAG, "[slot=$slot] [Commit] Room Database write succeeded")

        val walParticipant = participants[Subsystem.WAL] as? Participant.WALParticipant
        if (walParticipant != null) {
            val result = wal.commit(txnId = walParticipant.txnId, gameEvent = gameEvent, currentGameYear = currentGameYear)
            if (!result.isSuccess) {
                Log.e(TAG, "[Commit] WAL commit failed for txnId=${walParticipant.txnId}: ${if (result.isFailure) "WAL error" else "Unknown error"}")
                return false
            }
            Log.d(TAG, "[slot=$slot] [Commit] WAL committed: txnId=${walParticipant.txnId}")
        }

        val cacheP = participants[Subsystem.CACHE] as? Participant.CacheFlushParticipant
        if (cacheP != null) {
            try {
                cacheManager.flushDirty()
                Log.d(TAG, "[slot=$slot] [Commit] Cache flushed successfully")
            } catch (e: Exception) {
                Log.w(TAG, "[Commit] Cache flush failed", e)
            }
        }

        val incP = participants[Subsystem.INCREMENTAL] as? Participant.IncrementalParticipant
        if (incP != null) {
            try {
                val saveData = saveDataRef
                if (saveData != null) {
                    try {
                        val serializableData = NullSafeProtoBuf.protoBuf.decodeFromByteArray(SerializableSaveData.serializer(), saveData)
                        val saveDataObj = SaveDataConverter().fromSerializable(serializableData)
                        incrementalManager.saveIncremental(slot, saveDataObj)
                        Log.d(TAG, "[slot=$slot] [Commit] Incremental saved successfully")
                    } catch (e: Exception) {
                        Log.w(TAG, "[slot=$slot] [Commit] Incremental save failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[slot=$slot] [Commit] Incremental participant error", e)
            }
        }

        Log.d(TAG, "[slot=$slot] --- Phase 2 Complete: All subsystems committed ---")
        return true
    }

    private suspend fun rollback(preparedParticipants: Map<Subsystem, Participant>, failureAt: Subsystem) {
        currentPhase = Phase.ROLLING_BACK
        Log.w(TAG, "--- Rollback initiated: failureAt=$failureAt, participants=${preparedParticipants.keys} ---")

        val reverseOrder = listOf(Subsystem.INCREMENTAL, Subsystem.CACHE, Subsystem.WAL)

        for (subsystem in reverseOrder.dropWhile { it != failureAt }) {
            when (subsystem) {
                Subsystem.WAL -> {
                    val p = preparedParticipants[Subsystem.WAL] as? Participant.WALParticipant
                    if (p != null) {
                        try {
                            wal.abort(p.txnId)
                            Log.d(TAG, "[Rollback] WAL aborted: txnId=${p.txnId}")
                        } catch (e: Exception) {
                            Log.e(TAG, "[Rollback] WAL abort error", e)
                        }
                    }
                }
                Subsystem.CACHE -> {
                    Log.d(TAG, "[Rollback] Cache: marking re-dirty via invalidate slot=$currentSlot")
                    try {
                        cacheManager.invalidateSlot(currentSlot)
                    } catch (e: Exception) {
                        Log.w(TAG, "[Rollback] Cache invalidateSlot error", e)
                    }
                }
                Subsystem.INCREMENTAL -> {
                    Log.d(TAG, "[Rollback] Incremental: last delta may be inconsistent, will be cleaned on next compaction")
                }
            }
        }

        this.saveDataRef = null
        Log.w(TAG, "--- Rollback completed ---")
    }

    private fun serializeParticipants(participants: Map<Subsystem, Participant>): Map<String, Any> {
        return participants.map { (subsystem, participant) ->
            subsystem.name to when (participant) {
                is Participant.WALParticipant -> mapOf("txnId" to participant.txnId)
                is Participant.IncrementalParticipant -> mapOf("deltaVersion" to participant.deltaVersion)
                is Participant.CacheFlushParticipant -> mapOf("dirtyCount" to participant.dirtyCount)
            }
        }.toMap()
    }

    fun getCurrentPhase(): Phase = currentPhase

    private suspend fun writeSaveDataToDatabase(slot: Int): Boolean {
        val saveDataBytes = saveDataRef ?: run {
            Log.e(TAG, "[slot=$slot] saveDataRef is null, cannot write to database")
            return false
        }

        return try {
            val serializableData = NullSafeProtoBuf.protoBuf.decodeFromByteArray(
                SerializableSaveData.serializer(),
                saveDataBytes
            )
            val saveDataObj = SaveDataConverter().fromSerializable(serializableData)

            engine.writeDataToDatabaseDirectly(slot, saveDataObj)
        } catch (e: Exception) {
            Log.e(TAG, "[slot=$slot] Failed to write save data to database", e)
            false
        }
    }

    fun shutdown() {
        scope.cancel()
        Log.i(TAG, "AtomicSavePipeline shutdown completed")
    }
}
