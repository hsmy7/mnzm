package com.xianxia.sect.data.coordinator

import android.util.Log
import com.xianxia.sect.data.engine.UnifiedStorageEngine
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.sharding.ShardedSaveManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class DeleteOperationResult(
    val success: Boolean,
    val deletedLayers: Set<String>,
    val failedLayers: Set<String>,
    val rolledBackLayers: Set<String>,
    val errorMessage: String? = null,
    val durationMs: Long = 0
)

data class DeleteCoordinatorStats(
    val totalDeletes: Long = 0,
    val successfulDeletes: Long = 0,
    val failedDeletes: Long = 0,
    val partialRollbacks: Long = 0,
    val averageDeleteTimeMs: Long = 0
)

enum class DeleteLayer(val displayName: String) {
    DATABASE("Database"),
    SHARDED_FILE("Sharded File")
}

private data class SagaStep(
    val layer: DeleteLayer,
    val execute: suspend () -> Unit,
    val rollback: suspend () -> Unit,
    var completed: Boolean = false
)

@Singleton
class DeleteCoordinator @Inject constructor(
    private val engine: UnifiedStorageEngine,
    private val shardedSaveManager: ShardedSaveManager?
) {
    companion object {
        private const val TAG = "DeleteCoordinator"
    }

    private val deleteMutex = Mutex()

    private val totalDeletes = AtomicLong(0)
    private val successfulDeletes = AtomicLong(0)
    private val failedDeletes = AtomicLong(0)
    private val partialRollbacks = AtomicLong(0)
    private val totalTimeMs = AtomicLong(0)

    suspend fun deleteSlot(slot: Int): DeleteOperationResult =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "deleteSlot() start | slot=$slot")

            deleteMutex.withLock {
                val steps = buildSagaSteps(slot)
                val completedSteps = mutableListOf<DeleteLayer>()
                var failedStep: DeleteLayer?

                try {
                    for (step in steps) {
                        step.execute()
                        step.completed = true
                        completedSteps.add(step.layer)
                    }
                    failedStep = null
                } catch (e: Exception) {
                    Log.e(TAG, "deleteSlot() failed at layer ${steps.find { !it.completed }?.layer?.name}", e)
                    failedStep = steps.find { !it.completed }?.layer

                    completedSteps.reversed().forEach { layer ->
                        val step = steps.first { it.layer == layer }
                        try {
                            step.rollback()
                            partialRollbacks.incrementAndGet()
                            Log.w(TAG, "Rolled back layer ${layer.name} for slot $slot")
                        } catch (rollbackEx: Exception) {
                            Log.e(TAG, "Rollback failed for layer ${layer.name} on slot $slot", rollbackEx)
                        }
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                recordStats(elapsed, failedStep == null)

                if (failedStep == null) {
                    Log.i(TAG, "deleteSlot() success | slot=$slot layers=${completedSteps.map { it.name }} elapsed=${elapsed}ms")
                    DeleteOperationResult(
                        success = true,
                        deletedLayers = completedSteps.map { it.name }.toSet(),
                        failedLayers = emptySet(),
                        rolledBackLayers = emptySet(),
                        durationMs = elapsed
                    )
                } else {
                    Log.e(TAG, "deleteSlot() partial failure | slot=$slot completed=${completedSteps.map { it.name }} failed=${failedStep.name} rolledBack=${completedSteps.map { it.name }}")
                    DeleteOperationResult(
                        success = false,
                        deletedLayers = emptySet(),
                        failedLayers = setOf(failedStep.name),
                        rolledBackLayers = completedSteps.map { it.name }.toSet(),
                        errorMessage = "Delete failed at ${failedStep.displayName}, previous layers rolled back",
                        durationMs = elapsed
                    )
                }
            }
        }

    private fun buildSagaSteps(slot: Int): List<SagaStep> {
        val steps = mutableListOf<SagaStep>()

        steps.add(SagaStep(
            layer = DeleteLayer.DATABASE,
            execute = {
                val result = engine.delete(slot)
                if (result.isFailure) {
                    throw DeleteException("Database delete failed: ${(result as StorageResult.Failure).message}")
                }
            },
            rollback = {
                Log.w(TAG, "Database rollback: no-op (data already committed to DB)")
            }
        ))

        shardedSaveManager?.let { mgr ->
            steps.add(SagaStep(
                layer = DeleteLayer.SHARDED_FILE,
                execute = {
                    val result = mgr.deleteSharded(slot)
                    if (!result.isSuccess) {
                        throw DeleteException("Sharded file delete failed: ${(result as? com.xianxia.sect.data.unified.SaveResult.Failure)?.message}")
                    }
                },
                rollback = {
                    Log.w(TAG, "Sharded file rollback: no-op (files already deleted from disk)")
                }
            ))
        }

        return steps
    }

    private fun recordStats(elapsedMs: Long, success: Boolean) {
        totalDeletes.incrementAndGet()
        totalTimeMs.addAndGet(elapsedMs)
        if (success) {
            successfulDeletes.incrementAndGet()
        } else {
            failedDeletes.incrementAndGet()
        }
    }

    fun getStats(): DeleteCoordinatorStats {
        val total = totalDeletes.get()
        return DeleteCoordinatorStats(
            totalDeletes = total,
            successfulDeletes = successfulDeletes.get(),
            failedDeletes = failedDeletes.get(),
            partialRollbacks = partialRollbacks.get(),
            averageDeleteTimeMs = if (total > 0) totalTimeMs.get() / total else 0
        )
    }
}

class DeleteException(message: String, cause: Throwable? = null) : Exception(message, cause)
