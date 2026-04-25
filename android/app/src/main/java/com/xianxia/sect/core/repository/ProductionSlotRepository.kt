package com.xianxia.sect.core.repository

import android.util.Log
import com.xianxia.sect.core.concurrent.ShardedSlotLock
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.SlotStateMachine
import com.xianxia.sect.data.local.ProductionSlotDao
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductionSlotRepository @Inject constructor(
    private val dao: ProductionSlotDao,
    private val configService: BuildingConfigService,
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    companion object {
        private const val TAG = "ProductionSlotRepository"

        private val BUILDING_ID_MAP = mapOf(
            BuildingType.ALCHEMY to "alchemy",
            BuildingType.FORGE to "forge",
            BuildingType.MINING to "mining",
            BuildingType.HERB_GARDEN to "herbGarden",
            BuildingType.ADMINISTRATION to "tianshu_hall",
            BuildingType.LIBRARY to "library",
            BuildingType.WEN_DAO_PEAK to "wen_dao_peak",
            BuildingType.QINGYUN_PEAK to "qingyun_peak",
            BuildingType.LAW_ENFORCEMENT_HALL to "law_enforcement_hall",
            BuildingType.MISSION_HALL to "mission_hall",
            BuildingType.REFLECTION_CLIFF to "reflection_cliff"
        )

        fun getBuildingIdForType(buildingType: BuildingType): String {
            return BUILDING_ID_MAP[buildingType] ?: buildingType.name.lowercase()
        }
    }

    private val shardedLock = ShardedSlotLock()
    private val globalMutex = Mutex()
    private val cache = SlotQueryCache()
    private val scope get() = applicationScopeProvider.scope

    private val _slots = MutableStateFlow<List<ProductionSlot>>(emptyList())
    val slots: StateFlow<List<ProductionSlot>> = _slots.asStateFlow()

    val workingSlots: StateFlow<List<ProductionSlot>> = _slots
        .map { cache.getWorkingSlots(it) }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val completedSlots: StateFlow<List<ProductionSlot>> = _slots
        .map { cache.getCompletedSlots(it) }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val idleSlots: StateFlow<List<ProductionSlot>> = _slots
        .map { cache.getIdleSlots(it) }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    suspend fun initialize() {
        globalMutex.withLock {
            val loaded = dao.getAllSync()
            _slots.value = loaded
            cache.invalidate()
            Log.d(TAG, "Initialized with ${loaded.size} slots")
        }
    }

    suspend fun loadSlots(slots: List<ProductionSlot>) {
        globalMutex.withLock {
            _slots.value = slots
            cache.invalidate()
            dao.insertAll(slots)
        }
    }

    fun getSlots(): List<ProductionSlot> = _slots.value

    fun getExpectedSlotCount(buildingType: BuildingType): Int {
        return configService.getSlotCountByType(buildingType)
    }

    fun getSlotByIndex(buildingType: BuildingType, slotIndex: Int): ProductionSlot? {
        return cache.getByIndex(_slots.value, buildingType, slotIndex)
    }

    fun getSlotByBuildingId(buildingId: String, slotIndex: Int): ProductionSlot? {
        return cache.getByBuildingIdIndex(_slots.value, buildingId, slotIndex)
    }

    fun getSlotsByType(buildingType: BuildingType): List<ProductionSlot> {
        return cache.getByType(_slots.value, buildingType)
    }

    fun getSlotsByBuildingId(buildingId: String): List<ProductionSlot> {
        return cache.getByBuildingId(_slots.value, buildingId)
    }

    fun getWorkingSlots(): List<ProductionSlot> = cache.getWorkingSlots(_slots.value)

    fun getCompletedSlots(): List<ProductionSlot> = cache.getCompletedSlots(_slots.value)

    fun getIdleSlots(): List<ProductionSlot> = cache.getIdleSlots(_slots.value)

    fun getFinishedSlots(currentYear: Int, currentMonth: Int): List<ProductionSlot> {
        return cache.getFinishedSlots(_slots.value, currentYear, currentMonth)
    }

    fun getSlotById(slotId: String): ProductionSlot? {
        return _slots.value.find { it.id == slotId }
    }

    suspend fun updateSlot(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> {
        return shardedLock.withLock(buildingType, slotIndex) {
            updateSlotInternal(buildingType, slotIndex, transform)
        }
    }

    private suspend fun updateSlotInternal(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> {
        val currentSlots = _slots.value
        val targetIndex = currentSlots.indexOfFirst {
            it.buildingType == buildingType && it.slotIndex == slotIndex
        }

        if (targetIndex < 0) {
            return Result.failure(IllegalArgumentException("Slot not found: $buildingType[$slotIndex]"))
        }

        val currentSlot = currentSlots[targetIndex]
        val newSlot = transform(currentSlot)

        if (currentSlot.status != newSlot.status) {
            val validation = SlotStateMachine.validateTransition(currentSlot.status, newSlot.status)
            if (validation.isFailure) {
                return Result.failure(validation.exceptionOrNull() ?: IllegalStateException("Slot state transition validation failed without exception"))
            }
        }

        val newSlots = currentSlots.toMutableList()
        newSlots[targetIndex] = newSlot
        _slots.value = newSlots
        cache.markDirty()

        withContext(Dispatchers.IO) {
            dao.update(newSlot)
        }

        Log.d(TAG, "Updated slot: ${buildingType.name}[$slotIndex] ${currentSlot.status} -> ${newSlot.status}")
        return Result.success(newSlot)
    }

    suspend fun updateSlotByBuildingId(
        buildingId: String,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> {
        val slot = getSlotByBuildingId(buildingId, slotIndex)
            ?: return Result.failure(IllegalArgumentException("Slot not found: $buildingId[$slotIndex]"))
        
        return shardedLock.withLock(slot.buildingType, slotIndex) {
            val currentSlots = _slots.value.toMutableList()
            val index = currentSlots.indexOfFirst {
                it.buildingId == buildingId && it.slotIndex == slotIndex
            }

            if (index < 0) {
                return@withLock Result.failure(IllegalArgumentException("Slot not found: $buildingId[$slotIndex]"))
            }

            val currentSlot = currentSlots[index]
            val newSlot = transform(currentSlot)

            if (currentSlot.status != newSlot.status) {
                val validation = SlotStateMachine.validateTransition(currentSlot.status, newSlot.status)
                if (validation.isFailure) {
                    return@withLock Result.failure(validation.exceptionOrNull() ?: IllegalStateException("Slot state transition validation failed without exception"))
                }
            }

            currentSlots[index] = newSlot
            _slots.value = currentSlots
            cache.markDirty()

            dao.update(newSlot)

            Log.d(TAG, "Updated slot: $buildingId[$slotIndex] ${currentSlot.status} -> ${newSlot.status}")
            Result.success(newSlot)
        }
    }

    suspend fun updateSlotAtomic(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> {
        return shardedLock.withLock(buildingType, slotIndex) {
            updateSlotInternal(buildingType, slotIndex, transform)
        }
    }

    suspend fun batchUpdate(updates: List<SlotUpdate>): Result<List<ProductionSlot>> {
        if (updates.isEmpty()) return Result.success(emptyList())
        
        val keys = updates.map { Pair(it.buildingType.name, it.slotIndex) }
        
        return shardedLock.withBatchLock(keys) {
            val currentSlots = _slots.value.toMutableList()
            val updatedSlots = mutableListOf<ProductionSlot>()

            for (update in updates) {
                val index = currentSlots.indexOfFirst {
                    it.buildingType == update.buildingType && it.slotIndex == update.slotIndex
                }
                if (index < 0) continue

                val currentSlot = currentSlots[index]
                val newSlot = update.transform(currentSlot)

                if (currentSlot.status != newSlot.status) {
                    val validation = SlotStateMachine.validateTransition(currentSlot.status, newSlot.status)
                    if (validation.isFailure) continue
                }

                currentSlots[index] = newSlot
                updatedSlots.add(newSlot)
            }

            _slots.value = currentSlots
            cache.markDirty()

            if (updatedSlots.isNotEmpty()) {
                dao.updateAll(updatedSlots)
            }

            Log.d(TAG, "Batch updated ${updatedSlots.size} slots")
            Result.success(updatedSlots)
        }
    }

    suspend fun addSlot(slot: ProductionSlot): Result<ProductionSlot> {
        return globalMutex.withLock {
            val currentSlots = _slots.value.toMutableList()

            val exists = currentSlots.any {
                it.buildingType == slot.buildingType && it.slotIndex == slot.slotIndex
            }
            if (exists) {
                return Result.failure(IllegalArgumentException("Slot already exists: ${slot.buildingType}[${slot.slotIndex}]"))
            }

            currentSlots.add(slot)
            _slots.value = currentSlots
            cache.markDirty()

            dao.insert(slot)

            Log.d(TAG, "Added slot: ${slot.buildingType.name}[${slot.slotIndex}]")
            Result.success(slot)
        }
    }

    suspend fun removeSlot(slotId: String): Result<Boolean> {
        val slot = getSlotById(slotId)
            ?: return Result.failure(IllegalArgumentException("Slot not found: $slotId"))
        
        return globalMutex.withLock {
            val currentSlots = _slots.value.toMutableList()
            val index = currentSlots.indexOfFirst { it.id == slotId }

            if (index < 0) {
                return Result.failure(IllegalArgumentException("Slot not found: $slotId"))
            }

            val removed = currentSlots.removeAt(index)
            _slots.value = currentSlots
            cache.markDirty()

            dao.deleteById(slotId)

            Log.d(TAG, "Removed slot: ${removed.buildingType.name}[${removed.slotIndex}]")
            Result.success(true)
        }
    }

    suspend fun initializeAllSlots(slotId: Int) {
        globalMutex.withLock {
            val allSlots = mutableListOf<ProductionSlot>()
            BuildingType.entries.forEach { buildingType ->
                val slotCount = configService.getSlotCountByType(buildingType)
                (0 until slotCount).forEach { idx ->
                    allSlots.add(ProductionSlot.createIdle(
                        slotIndex = idx,
                        buildingType = buildingType,
                        buildingId = getBuildingIdForType(buildingType)
                    ).copy(slotId = slotId))
                }
            }

            _slots.value = allSlots
            cache.invalidate()
            dao.deleteBySlot(slotId)
            dao.insertAll(allSlots)

            Log.d(TAG, "Initialized ${allSlots.size} slots for all buildings")
        }
    }

    suspend fun initializeSlotsForType(buildingType: BuildingType) {
        globalMutex.withLock {
            val slotCount = configService.getSlotCountByType(buildingType)
            val newSlots = (0 until slotCount).map { idx ->
                ProductionSlot.createIdle(
                    slotIndex = idx,
                    buildingType = buildingType,
                    buildingId = getBuildingIdForType(buildingType)
                )
            }

            val currentSlots = _slots.value.filter { it.buildingType != buildingType }
            val allSlots = currentSlots + newSlots

            _slots.value = allSlots
            cache.markDirty()

            dao.deleteByBuildingType(buildingType)
            dao.insertAll(newSlots)

            Log.d(TAG, "Initialized $slotCount slots for ${buildingType.name}")
        }
    }

    suspend fun syncToDatabase() {
        globalMutex.withLock {
            dao.updateAll(_slots.value)
            Log.d(TAG, "Synced ${_slots.value.size} slots to database")
        }
    }

    suspend fun clear(slotId: Int) {
        globalMutex.withLock {
            _slots.value = emptyList()
            cache.invalidate()
            dao.deleteBySlot(slotId)
            Log.d(TAG, "Cleared all slots for slotId=$slotId")
        }
    }

    suspend fun restoreSlots(slots: List<ProductionSlot>, slotId: Int) {
        globalMutex.withLock {
            _slots.value = slots
            cache.invalidate()
            dao.deleteBySlot(slotId)
            dao.insertAll(slots)
            Log.d(TAG, "Restored ${slots.size} slots from save data for slotId=$slotId")
        }
    }

    fun getStatistics(): SlotCacheStatistics {
        return cache.getStatistics(_slots.value)
    }

    fun isCacheDirty(): Boolean = cache.isDirty()
    
    fun getLockStatistics() = shardedLock.getLockStatistics()
}

data class SlotUpdate(
    val buildingType: BuildingType,
    val slotIndex: Int,
    val transform: (ProductionSlot) -> ProductionSlot
)
