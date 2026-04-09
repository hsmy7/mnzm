@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.orchestrator

import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.GameRepository
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.concurrent.StorageCircuitBreaker
import com.xianxia.sect.data.concurrent.StorageScopeManager
import com.xianxia.sect.data.concurrent.StorageSubsystem
import com.xianxia.sect.data.engine.UnifiedStorageEngine
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.memory.DynamicMemoryManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveRepository
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.data.wal.WALProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class SavePriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

enum class OrchestratorState {
    INITIALIZING,
    READY,
    DEGRADED,
    EMERGENCY,
    SHUTTING_DOWN
}

data class OrchestratorDiagnostics(
    val state: OrchestratorState,
    val totalSaves: Long,
    val totalLoads: Long,
    val failedSaves: Long,
    val failedLoads: Long,
    val avgSaveTimeMs: Long,
    val avgLoadTimeMs: Long,
    val lastSaveTimeMs: Long,
    val lastLoadTimeMs: Long,
    val activeOperations: Int,
    val circuitBreakerStats: Map<String, com.xianxia.sect.data.concurrent.CircuitBreakerStats>,
    val scopeDiagnostics: com.xianxia.sect.data.concurrent.StorageScopeManager.ScopeDiagnostics
)

@Singleton
class StorageOrchestrator @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val saveRepository: SaveRepository,
    private val gameRepository: GameRepository,
    private val engine: UnifiedStorageEngine,
    private val cacheManager: GameDataCacheManager,
    private val database: GameDatabase,
    private val wal: WALProvider,
    private val memoryManager: DynamicMemoryManager,
    private val scopeManager: StorageScopeManager,
    private val circuitBreaker: StorageCircuitBreaker
) {
    private val _state = MutableStateFlow(OrchestratorState.INITIALIZING)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    private val isInitialized = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)

    private val totalSaves = AtomicLong(0)
    private val totalLoads = AtomicLong(0)
    private val failedSaves = AtomicLong(0)
    private val failedLoads = AtomicLong(0)
    private val totalSaveTimeMs = AtomicLong(0)
    private val totalLoadTimeMs = AtomicLong(0)
    private val lastSaveTimeMs = AtomicLong(0)
    private val lastLoadTimeMs = AtomicLong(0)

    private val emergencySaveInProgress = AtomicBoolean(false)

    fun initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            _state.value = OrchestratorState.READY
            Log.i(TAG, "StorageOrchestrator initialized and ready")
        }
    }

    suspend fun saveSlot(slotId: Int, saveData: SaveData, priority: SavePriority = SavePriority.NORMAL): SaveResult<Unit> {
        if (isShuttingDown.get()) {
            Log.w(TAG, "saveSlot rejected: orchestrator is shutting down")
            return SaveResult.Failure(SaveError.SAVE_FAILED, "Storage system is shutting down")
        }

        if (!circuitBreaker.allowRequest("save")) {
            Log.w(TAG, "saveSlot rejected by circuit breaker for slot $slotId")
            failedSaves.incrementAndGet()
            return SaveResult.Failure(SaveError.SAVE_FAILED, "Save circuit breaker is OPEN - too many recent failures")
        }

        val startTime = System.currentTimeMillis()
        scopeManager.beginOperation(StorageSubsystem.SAVE)

        return try {
            withContext(scopeManager.scopeFor(StorageSubsystem.SAVE).coroutineContext) {
                checkMemoryForSave(saveData)

                val result = saveRepository.save(slotId, saveData).map { Unit }

                val elapsed = System.currentTimeMillis() - startTime
                totalSaves.incrementAndGet()
                totalSaveTimeMs.addAndGet(elapsed)
                lastSaveTimeMs.set(elapsed)

                if (result is SaveResult.Success) {
                    circuitBreaker.recordSuccess("save")
                    Log.d(TAG, "saveSlot success: slot=$slotId, priority=$priority, elapsed=${elapsed}ms")
                } else {
                    circuitBreaker.recordFailure("save")
                    failedSaves.incrementAndGet()
                    Log.w(TAG, "saveSlot failed: slot=$slotId, result=$result")
                }

                result
            }
        } catch (e: OutOfMemoryError) {
            circuitBreaker.recordFailure("save")
            failedSaves.incrementAndGet()
            Log.e(TAG, "saveSlot OOM for slot $slotId", e)
            handleMemoryEmergency()
            SaveResult.Failure(SaveError.OUT_OF_MEMORY, "Out of memory during save")
        } catch (e: Exception) {
            circuitBreaker.recordFailure("save")
            failedSaves.incrementAndGet()
            Log.e(TAG, "saveSlot exception for slot $slotId", e)
            SaveResult.Failure(SaveError.SAVE_FAILED, e.message ?: "Save failed")
        } finally {
            scopeManager.endOperation(StorageSubsystem.SAVE)
        }
    }

    suspend fun loadSlot(slotId: Int): SaveResult<SaveData> {
        if (isShuttingDown.get()) {
            Log.w(TAG, "loadSlot rejected: orchestrator is shutting down")
            return SaveResult.Failure(SaveError.LOAD_FAILED, "Storage system is shutting down")
        }

        if (!circuitBreaker.allowRequest("load")) {
            Log.w(TAG, "loadSlot rejected by circuit breaker for slot $slotId")
            failedLoads.incrementAndGet()
            return SaveResult.Failure(SaveError.LOAD_FAILED, "Load circuit breaker is OPEN - too many recent failures")
        }

        val startTime = System.currentTimeMillis()
        scopeManager.beginOperation(StorageSubsystem.LOAD)

        return try {
            withContext(scopeManager.scopeFor(StorageSubsystem.LOAD).coroutineContext) {
                val result = saveRepository.load(slotId)

                val elapsed = System.currentTimeMillis() - startTime
                totalLoads.incrementAndGet()
                totalLoadTimeMs.addAndGet(elapsed)
                lastLoadTimeMs.set(elapsed)

                if (result is SaveResult.Success) {
                    circuitBreaker.recordSuccess("load")
                    Log.d(TAG, "loadSlot success: slot=$slotId, elapsed=${elapsed}ms")
                } else {
                    circuitBreaker.recordFailure("load")
                    failedLoads.incrementAndGet()
                    Log.w(TAG, "loadSlot failed: slot=$slotId")
                }

                result
            }
        } catch (e: Exception) {
            circuitBreaker.recordFailure("load")
            failedLoads.incrementAndGet()
            Log.e(TAG, "loadSlot exception for slot $slotId", e)
            SaveResult.Failure(SaveError.LOAD_FAILED, e.message ?: "Load failed")
        } finally {
            scopeManager.endOperation(StorageSubsystem.LOAD)
        }
    }

    suspend fun deleteSlot(slotId: Int): SaveResult<Unit> {
        return try {
            saveRepository.delete(slotId).map { Unit }
        } catch (e: Exception) {
            Log.e(TAG, "deleteSlot exception for slot $slotId", e)
            SaveResult.Failure(SaveError.DELETE_FAILED, e.message ?: "Delete failed")
        }
    }

    suspend fun hasSaveData(slotId: Int): Boolean {
        return try {
            saveRepository.hasSave(slotId)
        } catch (e: Exception) {
            Log.e(TAG, "hasSaveData exception for slot $slotId", e)
            false
        }
    }

    suspend fun emergencySave(slotId: Int, saveData: SaveData): SaveResult<Unit> {
        if (!emergencySaveInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Emergency save already in progress, skipping")
            return SaveResult.Failure(SaveError.SAVE_FAILED, "Emergency save already in progress")
        }

        _state.value = OrchestratorState.EMERGENCY

        return try {
            Log.w(TAG, "=== EMERGENCY SAVE STARTED for slot $slotId ===")
            val result = saveSlot(slotId, saveData, SavePriority.CRITICAL)
            if (result is SaveResult.Success) {
                Log.i(TAG, "=== EMERGENCY SAVE SUCCEEDED for slot $slotId ===")
            } else {
                Log.e(TAG, "=== EMERGENCY SAVE FAILED for slot $slotId ===")
            }
            result
        } finally {
            emergencySaveInProgress.set(false)
            if (_state.value == OrchestratorState.EMERGENCY) {
                _state.value = OrchestratorState.READY
            }
        }
    }

    fun getGameDataFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<GameData?> =
        gameRepository.getGameData(slotId)

    suspend fun getGameDataSync(slotId: Int = GameRepository.DEFAULT_SLOT_ID): GameData? =
        gameRepository.getGameDataSync(slotId)

    suspend fun saveGameData(gameData: GameData) = gameRepository.saveGameData(gameData)

    fun getDisciplesFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Disciple>> =
        gameRepository.getDisciples(slotId)

    suspend fun getDiscipleById(id: String, slotId: Int = GameRepository.DEFAULT_SLOT_ID): Disciple? =
        gameRepository.getDiscipleById(id, slotId)

    suspend fun addDisciple(disciple: Disciple) = gameRepository.addDisciple(disciple)
    suspend fun updateDisciple(disciple: Disciple) = gameRepository.updateDisciple(disciple)
    suspend fun deleteDisciple(disciple: Disciple) = gameRepository.deleteDisciple(disciple)

    fun getEquipmentFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Equipment>> =
        gameRepository.getEquipment(slotId)

    suspend fun addEquipment(equipment: Equipment) = gameRepository.addEquipment(equipment)
    suspend fun updateEquipment(equipment: Equipment) = gameRepository.updateEquipment(equipment)
    suspend fun deleteEquipment(equipment: Equipment) = gameRepository.deleteEquipment(equipment)

    fun getManualsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Manual>> =
        gameRepository.getManuals(slotId)

    suspend fun addManual(manual: Manual) = gameRepository.addManual(manual)
    suspend fun updateManual(manual: Manual) = gameRepository.updateManual(manual)

    fun getPillsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Pill>> =
        gameRepository.getPills(slotId)

    suspend fun addPill(pill: Pill) = gameRepository.addPill(pill)
    suspend fun updatePill(pill: Pill) = gameRepository.updatePill(pill)

    fun getMaterialsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Material>> =
        gameRepository.getMaterials(slotId)

    suspend fun addMaterial(material: Material) = gameRepository.addMaterial(material)
    suspend fun updateMaterial(material: Material) = gameRepository.updateMaterial(material)

    fun getSeedsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Seed>> =
        gameRepository.getSeeds(slotId)

    suspend fun addSeed(seed: Seed) = gameRepository.addSeed(seed)
    suspend fun updateSeed(seed: Seed) = gameRepository.updateSeed(seed)

    fun getHerbsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<Herb>> =
        gameRepository.getHerbs(slotId)

    suspend fun addHerb(herb: Herb) = gameRepository.addHerb(herb)
    suspend fun updateHerb(herb: Herb) = gameRepository.updateHerb(herb)

    fun getTeamsFlow(slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<ExplorationTeam>> =
        gameRepository.getTeams(slotId)

    suspend fun addTeam(team: ExplorationTeam) = gameRepository.addTeam(team)
    suspend fun updateTeam(team: ExplorationTeam) = gameRepository.updateTeam(team)

    fun getEventsFlow(limit: Int = 50, slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<GameEvent>> =
        gameRepository.getRecentEvents(limit, slotId)

    suspend fun addEvent(event: GameEvent) = gameRepository.addEvent(event)

    suspend fun deleteOldEvents(before: Long, slotId: Int = GameRepository.DEFAULT_SLOT_ID) =
        gameRepository.deleteOldEvents(before, slotId)

    fun getBattleLogsFlow(limit: Int = 50, slotId: Int = GameRepository.DEFAULT_SLOT_ID): Flow<List<BattleLog>> =
        gameRepository.getRecentBattleLogs(limit, slotId)

    suspend fun addBattleLog(log: BattleLog) = gameRepository.addBattleLog(log)

    suspend fun deleteOldBattleLogs(before: Long, slotId: Int = GameRepository.DEFAULT_SLOT_ID) =
        gameRepository.deleteOldBattleLogs(before, slotId)

    suspend fun initializeNewGame(): GameData {
        Log.i(TAG, "initializeNewGame() called")
        return gameRepository.initializeNewGame()
    }

    suspend fun clearAllData(slotId: Int = GameRepository.DEFAULT_SLOT_ID) {
        Log.w(TAG, "clearAllData() called - this will delete all data in slot $slotId")
        gameRepository.clearAllData(slotId)
    }

    fun getDiagnostics(): OrchestratorDiagnostics {
        val saves = totalSaves.get()
        val loads = totalLoads.get()
        return OrchestratorDiagnostics(
            state = _state.value,
            totalSaves = saves,
            totalLoads = loads,
            failedSaves = failedSaves.get(),
            failedLoads = failedLoads.get(),
            avgSaveTimeMs = if (saves > 0) totalSaveTimeMs.get() / saves else 0,
            avgLoadTimeMs = if (loads > 0) totalLoadTimeMs.get() / loads else 0,
            lastSaveTimeMs = lastSaveTimeMs.get(),
            lastLoadTimeMs = lastLoadTimeMs.get(),
            activeOperations = scopeManager.activeOperationCount(),
            circuitBreakerStats = circuitBreaker.getAllStats(),
            scopeDiagnostics = scopeManager.getDiagnostics()
        )
    }

    private fun checkMemoryForSave(saveData: SaveData) {
        val snapshot = memoryManager.getMemorySnapshot()
        if (!snapshot.hasSufficientMemory) {
            Log.w(TAG, "Insufficient memory for save: available=${snapshot.availableRamBytes}, required=${snapshot.requiredForSaveData}")
            memoryManager.requestDegradation()
        }
    }

    private fun handleMemoryEmergency() {
        Log.w(TAG, "Memory emergency detected during save")
        memoryManager.forceGcAndWait(500L)
        memoryManager.requestDegradation()
    }

    fun shutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) return

        _state.value = OrchestratorState.SHUTTING_DOWN
        Log.i(TAG, "StorageOrchestrator shutting down")

        scopeManager.shutdown()
        database.shutdown()

        Log.i(TAG, "StorageOrchestrator shutdown complete")
    }

    companion object {
        private const val TAG = "StorageOrchestrator"
    }
}
