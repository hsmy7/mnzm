package com.xianxia.sect.core.repository

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.concurrent.ShardedSlotLock
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.SlotStateMachine
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock
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
    /**
     * 进程级协程写锁（2026-08-09 B5 根治 + 2026-08-09 对抗性审查 M1 扩展）：
     * 串行化"缓存 RMW + DAO 写"整段。分片锁是 JVM 锁无法包 suspend 的 DAO 写，
     * 原实现 DAO 写在锁外——月变 resetSlotToIdle 与 auto-restart 排班并发时
     * DAO 乱序，缓存与数据库分叉（排班结果丢失/材料双扣）。
     *
     * 覆盖范围：全部"改缓存 + 写 DAO"的挂起入口（update 族 + addSlot/removeSlot/
     * loadSlots/restoreSlots/initialize 族/syncToDatabase/clear）。仅 [initialize]
     * 例外：同步方法 + 同步 DAO（getAllSync），只在启动单线程调用，无需协程锁。
     *
     * 锁序恒为 writeMutex →（globalMutex/shardedLock/JVM 锁），无反向获取，
     * 不存在死锁；锁内 transform 均为纯 copy 表达式（无锁内二次获取）。
     */
    private val writeMutex = Mutex()
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
            val loaded = sanitizeSlots(dao.getAllSync())
            _slots.value = loaded
            cache.updateCache(loaded)
            DomainLog.d(TAG, "Initialized with ${loaded.size} slots")
        }
    }

    suspend fun loadSlots(slots: List<ProductionSlot>) {
        val sanitized = sanitizeSlots(slots)
        writeMutex.withLock {
            globalMutex.withLock {
                _slots.value = sanitized
                cache.updateCache(sanitized)
            }
            dao.insertAll(sanitized)
        }
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
    ): Result<ProductionSlot> = writeMutex.withLock {
        val (newSlot, result) = updateSlotInternal(buildingType, slotIndex, transform)
        if (result.isSuccess && newSlot != null) {
            dao.update(newSlot)
        }
        result
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
    ): Result<ProductionSlot> = writeMutex.withLock {
        val slot = getSlotByBuildingId(buildingId, slotIndex)
            ?: return@withLock Result.failure(IllegalArgumentException("Slot not found: $buildingId[$slotIndex]"))

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
        result
    }

    suspend fun updateSlotAtomic(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> = writeMutex.withLock {
        val (newSlot, result) = updateSlotInternal(buildingType, slotIndex, transform)
        if (result.isSuccess && newSlot != null) {
            dao.update(newSlot)
        }
        result
    }

    @Suppress("TooGenericExceptionCaught") // DAO 写入失败需全类兜底回滚（参照 ProductionCoordinator 先例），CancellationException 已先行重抛
    suspend fun batchUpdate(updates: List<SlotUpdate>): Result<List<ProductionSlot>> = writeMutex.withLock {
        if (updates.isEmpty()) return@withLock Result.success(emptyList())

        // 回滚基准：DAO 写失败时恢复内存（对抗性审查发现 2 修复）。
        // 批量版是"全有或全无"语义（单次 updateAll），失败必须整体回滚内存，
        // 否则内存已清/DB 未清分叉（读档后槽位复活或残留占用）。
        val oldSlots = _slots.value
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

                // 内存写回必须与 DAO 同步（此前漏写：仅 result 入 DAO，_slots 仍为旧值，
                // 内存与数据库分叉——本方法首次启用（死亡清理批处理）时暴露）
                currentSlots[index] = newSlot
                result.add(newSlot)
            }

            _slots.value = currentSlots
            cache.updateCache(currentSlots)
            result
        }

        if (updatedSlots.isNotEmpty()) {
            try {
                dao.updateAll(updatedSlots)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 回滚内存（旧值快照来自锁前，仅覆盖 DAO 失败瞬间的批量写入；
                // 并发逐槽 updateSlot 不在回滚范围——DAO 失败是异常路径，且
                // 回滚比"内存与 DB 分叉"（读档后幽灵占用）危害小得多）
                DomainLog.w(TAG, "batchUpdate DAO 写入失败，回滚内存", e)
                globalMutex.withLock {
                    _slots.value = oldSlots
                    cache.updateCache(oldSlots)
                }
                throw e
            }
        }

        DomainLog.d(TAG, "Batch updated ${updatedSlots.size} slots")
        Result.success(updatedSlots)
    }

    suspend fun addSlot(slot: ProductionSlot): Result<ProductionSlot> = writeMutex.withLock {
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
        result
    }

    suspend fun removeSlot(slotId: String): Result<Boolean> = writeMutex.withLock {
        val slot = getSlotById(slotId)
            ?: return@withLock Result.failure(IllegalArgumentException("Slot not found: $slotId"))

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
            return@withLock Result.failure(IllegalArgumentException("Slot not found: $slotId"))
        }

        dao.deleteById(slotId)

        DomainLog.d(TAG, "Removed slot: ${removed.buildingType.name}[${removed.slotIndex}]")
        Result.success(true)
    }

    suspend fun initializeAllSlots(slotId: Int) {
        writeMutex.withLock {
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
    }

    suspend fun initializeSlotsForType(buildingType: BuildingType, slotId: Int) {
        if (buildingType == BuildingType.ALCHEMY || buildingType == BuildingType.FORGE) return
        writeMutex.withLock {
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
    }

    suspend fun syncToDatabase() {
        val currentSlots = globalMutex.withLock {
            _slots.value.also { DomainLog.d(TAG, "Syncing ${it.size} slots to database") }
        }
        writeMutex.withLock { dao.updateAll(currentSlots) }
    }

    suspend fun clear(slotId: Int) {
        writeMutex.withLock {
            globalMutex.withLock {
                _slots.value = emptyList()
                cache.invalidate()
            }
            dao.deleteBySlot(slotId)
            DomainLog.d(TAG, "Cleared all slots for slotId=$slotId")
        }
    }

    suspend fun restoreSlots(slots: List<ProductionSlot>, slotId: Int) {
        val sanitized = sanitizeSlots(slots)
        writeMutex.withLock {
            globalMutex.withLock {
                _slots.value = sanitized
                cache.updateCache(sanitized)
            }
            dao.deleteBySlot(slotId)
            dao.insertAll(sanitized)
            DomainLog.d(TAG, "Restored ${sanitized.size} slots from save data for slotId=$slotId")
        }
    }

    fun getStatistics(): SlotCacheStatistics {
        return cache.getStatistics()
    }

    fun isCacheDirty(): Boolean = cache.isDirty()
    
    fun getLockStatistics() = shardedLock.getLockStatistics()

    /**
     * 净化外部数据源（读档/DAO）携带的 null 槽位元素（Bugly #13014）。
     * 非空类型上的 null 比较是编译器警告但运行时正确——null 只可能由
     * 损坏存档反序列化或旧版本写入产生。
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitizeSlots(slots: List<ProductionSlot>): List<ProductionSlot> {
        val sanitized = if (slots.any { it == null }) slots.filterNotNull() else slots
        if (sanitized.size != slots.size) {
            DomainLog.w(TAG, "净化 ${slots.size - sanitized.size} 个 null 生产槽位")
        }
        return sanitized
    }
}

data class SlotUpdate(
    val buildingType: BuildingType,
    val slotIndex: Int,
    val transform: (ProductionSlot) -> ProductionSlot
)
