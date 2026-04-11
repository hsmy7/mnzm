package com.xianxia.sect.data.pipeline

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.cache.GameDataCacheManager
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

enum class Subsystem { WAL, CACHE }

class AtomicSavePipeline(
    private val context: Context,
    private val wal: WALProvider,
    private val cacheManager: GameDataCacheManager,
    private val engine: com.xianxia.sect.data.engine.UnifiedStorageEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    enum class Phase { IDLE, PREPARING, COMMITTING, ROLLING_BACK, COMPLETED, FAILED }

    sealed class Participant {
        data class WALParticipant(val txnId: Long) : Participant()
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
                    Log.e(TAG, "[slot=$slot] Phase 1 Prepare timeout")
                    return@withLock AtomicSaveResult(
                        success = false,
                        phase = Phase.FAILED,
                        elapsedMs = System.currentTimeMillis() - startTime,
                        error = "Phase 1 (Prepare) timeout"
                    )
                }

                if (prepared.isEmpty()) {
                    Log.w(TAG, "[slot=$slot] No participants prepared")
                    return@withLock AtomicSaveResult(
                        success = true,
                        phase = Phase.COMPLETED,
                        elapsedMs = System.currentTimeMillis() - startTime
                    )
                }

                currentPhase = Phase.COMMITTING

                val committed = withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                    phase2Commit(prepared, slot, gameEvent, currentGameYear)
                } ?: run {
                    Log.e(TAG, "[slot=$slot] Phase 2 Commit timeout")
                    rollback(prepared, Subsystem.WAL)
                    return@withLock AtomicSaveResult(
                        success = false,
                        phase = Phase.FAILED,
                        participants = serializeParticipants(prepared),
                        elapsedMs = System.currentTimeMillis() - startTime,
                        error = "Phase 2 (Commit) timeout"
                    )
                }

                if (!committed) {
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
                Log.e(TAG, "[slot=$slot] Atomic save exception", e)
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

        val walResult = prepareWAL(slot, saveData)
        if (walResult != null) {
            participants[Subsystem.WAL] = walResult
        } else {
            Log.e(TAG, "[slot=$slot] [Prepare] WAL failed, aborting")
            rollback(participants, Subsystem.WAL)
            return emptyMap()
        }

        participants[Subsystem.CACHE] = Participant.CacheFlushParticipant(0)

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
        val dbWriteSuccess = writeSaveDataToDatabase(slot)
        if (!dbWriteSuccess) {
            Log.e(TAG, "[slot=$slot] [Commit] Room Database write FAILED")
            return false
        }

        val walParticipant = participants[Subsystem.WAL] as? Participant.WALParticipant
        if (walParticipant != null) {
            val result = wal.commit(txnId = walParticipant.txnId, gameEvent = gameEvent, currentGameYear = currentGameYear)
            if (!result.isSuccess) {
                Log.e(TAG, "[Commit] WAL commit failed for txnId=${walParticipant.txnId}")
                return false
            }
        }

        val cacheP = participants[Subsystem.CACHE] as? Participant.CacheFlushParticipant
        if (cacheP != null) {
            try {
                cacheManager.flushDirty()
            } catch (e: Exception) {
                Log.w(TAG, "[Commit] Cache flush failed", e)
            }
        }

        return true
    }

    private suspend fun rollback(preparedParticipants: Map<Subsystem, Participant>, failureAt: Subsystem) {
        currentPhase = Phase.ROLLING_BACK
        Log.w(TAG, "--- Rollback initiated: failureAt=$failureAt ---")

        val reverseOrder = listOf(Subsystem.CACHE, Subsystem.WAL)

        for (subsystem in reverseOrder.dropWhile { it != failureAt }) {
            when (subsystem) {
                Subsystem.WAL -> {
                    val p = preparedParticipants[Subsystem.WAL] as? Participant.WALParticipant
                    if (p != null) {
                        try {
                            wal.abort(p.txnId)
                        } catch (e: Exception) {
                            Log.e(TAG, "[Rollback] WAL abort error", e)
                        }
                    }
                }
                Subsystem.CACHE -> {
                    try {
                        cacheManager.invalidateSlot(currentSlot)
                    } catch (e: Exception) {
                        Log.w(TAG, "[Rollback] Cache invalidateSlot error", e)
                    }
                }
            }
        }

        this.saveDataRef = null
    }

    private fun serializeParticipants(participants: Map<Subsystem, Participant>): Map<String, Any> {
        return participants.map { (subsystem, participant) ->
            subsystem.name to when (participant) {
                is Participant.WALParticipant -> mapOf("txnId" to participant.txnId)
                is Participant.CacheFlushParticipant -> mapOf("dirtyCount" to participant.dirtyCount)
            }
        }.toMap()
    }

    fun getCurrentPhase(): Phase = currentPhase

    private suspend fun writeSaveDataToDatabase(slot: Int): Boolean {
        val saveDataBytes = saveDataRef ?: return false

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
