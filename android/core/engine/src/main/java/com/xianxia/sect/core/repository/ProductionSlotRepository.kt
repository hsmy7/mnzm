package com.xianxia.sect.core.repository

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.concurrent.ShardedSlotLock
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.SlotStateMachine
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.flow.*
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductionSlotRepository @Inject constructor(
    private val dao: ProductionSlotDataPort,
    private val configService: BuildingConfigService,
    private val scopeProvider: CoroutineScopeProvider
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
    private val globalMutex = ReentrantLock()
    private val cache = SlotCache()
    private val scope get() = scopeProvider.scope

    private val _slots = MutableStateFlow<List<ProductionSlot>>(emptyList())
    val slots: StateFlow<List<ProductionSlot>> = _slots.asStateFlow()

    val workingSlots: StateFlow<List<ProductionSlot>> = _slots
        .map { cache.updateCache(it); cache.getWorkingSlots() }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val completedSlots: StateFlow<List<ProductionSlot>> = _slots
        .map { cache.updateCache(it); cache.getCompletedSlots() }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val idleSlots: StateFlow<List<ProductionSlot>> = _slots
        .map { cache.updateCache(it); cache.getIdleSlots() }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun initialize() {
        globalMutex.withLock {
            val loaded = dao.getAllSync()
            _slots.value = loaded
            cache.updateCache(loaded)
            DomainLog.d(TAG, "Initialized with ${loaded.size} slots")
        }
    }

    suspend fun loadSlots(slots: List<ProductionSlot>) {
        globalMutex.withLock {
            _slots.value = slots
            cache.updateCache(slots)
        }
        dao.insertAll(slots)
    }

    fun getSlots(): List<ProductionSlot> = _slots.value

    fun getExpectedSlotCount(buildingType: BuildingType): Int {
        return configService.getSlotCountByType(buildingType)
    }

    fun getSlotByIndex(buildingType: BuildingType, slotIndex: Int): ProductionSlot? {
        return cache.getByIndex(buildingType, slotIndex)
    }

    fun getSlotByBuildingId(buildingId: String, slotIndex: Int): ProductionSlot? {
        return cache.getByBuildingIdIndex(buildingId, slotIndex)
    }

    fun getSlotsByType(buildingType: BuildingType): List<ProductionSlot> {
        return cache.getByType(buildingType)
    }

    fun getSlotsByBuildingId(buildingId: String): List<ProductionSlot> {
        return cache.getByBuildingId(buildingId)
    }

    fun getWorkingSlots(): List<ProductionSlot> = cache.getWorkingSlots()

    fun getCompletedSlots(): List<ProductionSlot> = cache.getCompletedSlots()

    fun getIdleSlots(): List<ProductionSlot> = cache.getIdleSlots()

    fun getFinishedSlots(currentYear: Int, currentMonth: Int): List<ProductionSlot> {
        return cache.getFinishedSlots(currentYear, currentMonth)
    }

    fun getSlotById(slotId: String): ProductionSlot? {
        return cache.getById(slotId)
    }

    suspend fun updateSlot(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> {
        val (newSlot, result) = updateSlotInternal(buildingType, slotIndex, transform)
        if (result.isSuccess && newSlot != null) {
            dao.update(newSlot)
        }
        return result
    }

    /**
     * 锁内执行校验和缓存更新，DAO 写入由调用方在锁外执行。
     * 返回 (newSlotOrNull, result)，不从 DAO 写入。
     */
    private fun updateSlotInternal(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Pair<ProductionSlot?, Result<ProductionSlot>> {
        return shardedLock.withLock(buildingType, slotIndex) {
            val currentSlots = _slots.value
            val targetIndex = currentSlots.indexOfFirst {
                it.buildingType == buildingType && it.slotIndex == slotIndex
            }

            if (targetIndex < 0) {
                return@withLock Pair(null, Result.failure(IllegalArgumentException("Slot not found: $buildingType[$slotIndex]")))
            }

            val currentSlot = currentSlots[targetIndex]
            val newSlot = transform(currentSlot)

            if (currentSlot.status != newSlot.status) {
                val validation = SlotStateMachine.validateTransition(currentSlot.status, newSlot.status)
                if (validation.isFailure) {
                    return@withLock Pair(null, Result.failure(validation.exceptionOrNull() ?: IllegalStateException("Slot state transition validation failed without exception")))
                }
            }

            val newSlots = currentSlots.toMutableList()
            newSlots[targetIndex] = newSlot
            _slots.value = newSlots
            cache.updateCache(newSlots)

            DomainLog.d(TAG, "Updated slot: ${buildingType.name}[$slotIndex] ${currentSlot.status} -> ${newSlot.status}")
            Pair(newSlot, Result.success(newSlot))
        }
    }

    suspend fun updateSlotByBuildingId(
        buildingId: String,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> {
        val slot = getSlotByBuildingId(buildingId, slotIndex)
            ?: return Result.failure(IllegalArgumentException("Slot not found: $buildingId[$slotIndex]"))

        val result = shardedLock.withLock(slot.buildingType, slotIndex) {
            val currentSlots = _slots.value.toMutableList()
            val index = currentSlots.indexOfFirst {
                it.buildingId == buildingId && it.slotIndex == slotIndex
            }

            if (index < 0) return@withLock Result.failure(IllegalArgumentException("Slot not found: $buildingId[$slotIndex]"))

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
            cache.updateCache(currentSlots)

            DomainLog.d(TAG, "Updated slot: $buildingId[$slotIndex] ${currentSlot.status} -> ${newSlot.status}")
            Result.success(newSlot)
        }

        if (result.isSuccess) {
            dao.update(result.getOrThrow())
        }
        return result
    }

    suspend fun updateSlotAtomic(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> {
        val (newSlot, result) = updateSlotInternal(buildingType, slotIndex, transform)
        if (result.isSuccess && newSlot != null) {
            dao.update(newSlot)
        }
        return result
    }

    suspend fun batchUpdate(updates: List<SlotUpdate>): Result<List<ProductionSlot>> {
        if (updates.isEmpty()) return Result.success(emptyList())

        val updatedSlots = shardedLock.withBatchLock(updates.map { Pair(it.buildingType.name, it.slotIndex) }) {
            val currentSlots = _slots.value.toMutableList()
            val result = mutableListOf<ProductionSlot>()

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

                result.add(newSlot)
            }

            _slots.value = currentSlots
            cache.updateCache(currentSlots)
            result
        }

        if (updatedSlots.isNotEmpty()) {
            dao.updateAll(updatedSlots)
        }

        DomainLog.d(TAG, "Batch updated ${updatedSlots.size} slots")
        return Result.success(updatedSlots)
    }

    suspend fun addSlot(slot: ProductionSlot): Result<ProductionSlot> {
        val result = globalMutex.withLock {
            val currentSlots = _slots.value.toMutableList()

            val exists = currentSlots.any {
                it.buildingType == slot.buildingType && it.slotIndex == slot.slotIndex
            }
            if (exists) {
                return@withLock Result.failure(IllegalArgumentException("Slot already exists: ${slot.buildingType}[${slot.slotIndex}]"))
            }

            currentSlots.add(slot)
            _slots.value = currentSlots
            cache.updateCache(currentSlots)

            DomainLog.d(TAG, "Added slot: ${slot.buildingType.name}[${slot.slotIndex}]")
            Result.success(slot)
        }

        if (result.isSuccess) {
            dao.insert(slot)
        }
        return result
    }

    suspend fun removeSlot(slotId: String): Result<Boolean> {
        val slot = getSlotById(slotId)
            ?: return Result.failure(IllegalArgumentException("Slot not found: $slotId"))

        val removed = globalMutex.withLock {
            val currentSlots = _slots.value.toMutableList()
            val index = currentSlots.indexOfFirst { it.id == slotId }

            if (index < 0) return@withLock null

            val r = currentSlots.removeAt(index)
            _slots.value = currentSlots
            cache.updateCache(currentSlots)
            r
        }

        if (removed == null) {
            return Result.failure(IllegalArgumentException("Slot not found: $slotId"))
        }

        dao.deleteById(slotId)

        DomainLog.d(TAG, "Removed slot: ${removed.buildingType.name}[${removed.slotIndex}]")
        return Result.success(true)
    }

    suspend fun initializeAllSlots(slotId: Int) {
        val allSlots = globalMutex.withLock {
            val slots = mutableListOf<ProductionSlot>()
            BuildingType.entries.forEach { buildingType ->
                if (buildingType == BuildingType.ALCHEMY || buildingType == BuildingType.FORGE) return@forEach
                val slotCount = configService.getSlotCountByType(buildingType)
                (0 until slotCount).forEach { idx ->
                    slots.add(ProductionSlot.createIdle(
                        slotIndex = idx,
                        buildingType = buildingType,
                        buildingId = getBuildingIdForType(buildingType)
                    ).copy(slotId = slotId))
                }
            }

            _slots.value = slots
            cache.updateCache(slots)
            DomainLog.d(TAG, "Initialized ${slots.size} slots for all buildings")
            slots
        }
        dao.deleteBySlot(slotId)
        dao.insertAll(allSlots)
    }

    suspend fun initializeSlotsForType(buildingType: BuildingType, slotId: Int) {
        if (buildingType == BuildingType.ALCHEMY || buildingType == BuildingType.FORGE) return
        val newSlots = globalMutex.withLock {
            val slotCount = configService.getSlotCountByType(buildingType)
            val slots = (0 until slotCount).map { idx ->
                ProductionSlot.createIdle(
                    slotIndex = idx,
                    buildingType = buildingType,
                    buildingId = getBuildingIdForType(buildingType)
                ).copy(slotId = slotId)
            }

            val currentSlots = _slots.value.filter { it.buildingType != buildingType }
            val allSlots = currentSlots + slots

            _slots.value = allSlots
            cache.updateCache(allSlots)
            DomainLog.d(TAG, "Initialized $slotCount slots for ${buildingType.name} in slotId=$slotId")
            slots
        }
        dao.deleteBySlotAndBuildingType(slotId, buildingType)
        dao.insertAll(newSlots)
    }

    suspend fun syncToDatabase() {
        val currentSlots = globalMutex.withLock {
            _slots.value.also { DomainLog.d(TAG, "Syncing ${it.size} slots to database") }
        }
        dao.updateAll(currentSlots)
    }

    suspend fun clear(slotId: Int) {
        globalMutex.withLock {
            _slots.value = emptyList()
            cache.invalidate()
        }
        dao.deleteBySlot(slotId)
        DomainLog.d(TAG, "Cleared all slots for slotId=$slotId")
    }

    suspend fun restoreSlots(slots: List<ProductionSlot>, slotId: Int) {
        globalMutex.withLock {
            _slots.value = slots
            cache.updateCache(slots)
        }
        dao.deleteBySlot(slotId)
        dao.insertAll(slots)
        DomainLog.d(TAG, "Restored ${slots.size} slots from save data for slotId=$slotId")
    }

    fun getStatistics(): SlotCacheStatistics {
        return cache.getStatistics()
    }

    fun isCacheDirty(): Boolean = cache.isDirty()
    
    fun getLockStatistics() = shardedLock.getLockStatistics()
}

data class SlotUpdate(
    val buildingType: BuildingType,
    val slotIndex: Int,
    val transform: (ProductionSlot) -> ProductionSlot
)
