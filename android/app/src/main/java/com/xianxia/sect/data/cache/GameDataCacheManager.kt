package com.xianxia.sect.data.cache

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.data.local.GameDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameDataCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GameDatabase,
    private val config: CacheConfig = CacheConfig.DEFAULT
) {
    companion object {
        private const val TAG = "GameDataCacheManager"
        private const val CLEANUP_INTERVAL_MS = 60_000L
        private const val STATS_LOG_INTERVAL_MS = 300_000L
    }

    interface GenericDao<T : Any> {
        suspend fun insert(entity: T)
        suspend fun deleteById(id: String)
    }

    private class DiscipleDaoWrapper(private val dao: com.xianxia.sect.data.local.DiscipleDao) : GenericDao<com.xianxia.sect.core.model.Disciple> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Disciple) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.deleteById(id) }
    }

    private class EquipmentDaoWrapper(private val dao: com.xianxia.sect.data.local.EquipmentDao) : GenericDao<com.xianxia.sect.core.model.Equipment> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Equipment) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.deleteById(id) }
    }

    private class ManualDaoWrapper(private val dao: com.xianxia.sect.data.local.ManualDao) : GenericDao<com.xianxia.sect.core.model.Manual> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Manual) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.delete(com.xianxia.sect.core.model.Manual(id = id)) }
    }

    private class PillDaoWrapper(private val dao: com.xianxia.sect.data.local.PillDao) : GenericDao<com.xianxia.sect.core.model.Pill> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Pill) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.delete(com.xianxia.sect.core.model.Pill(id = id)) }
    }

    private class MaterialDaoWrapper(private val dao: com.xianxia.sect.data.local.MaterialDao) : GenericDao<com.xianxia.sect.core.model.Material> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Material) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.delete(com.xianxia.sect.core.model.Material(id = id)) }
    }

    private class HerbDaoWrapper(private val dao: com.xianxia.sect.data.local.HerbDao) : GenericDao<com.xianxia.sect.core.model.Herb> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Herb) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.delete(com.xianxia.sect.core.model.Herb(id = id)) }
    }

    private class SeedDaoWrapper(private val dao: com.xianxia.sect.data.local.SeedDao) : GenericDao<com.xianxia.sect.core.model.Seed> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.Seed) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.delete(com.xianxia.sect.core.model.Seed(id = id)) }
    }

    private class TeamDaoWrapper(private val dao: com.xianxia.sect.data.local.ExplorationTeamDao) : GenericDao<com.xianxia.sect.core.model.ExplorationTeam> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.ExplorationTeam) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.delete(com.xianxia.sect.core.model.ExplorationTeam(id = id)) }
    }

    private class BuildingSlotDaoWrapper(private val dao: com.xianxia.sect.data.local.BuildingSlotDao) : GenericDao<com.xianxia.sect.core.model.BuildingSlot> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.BuildingSlot) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.delete(com.xianxia.sect.core.model.BuildingSlot(id = id)) }
    }

    private class EventDaoWrapper(private val dao: com.xianxia.sect.data.local.GameEventDao) : GenericDao<com.xianxia.sect.core.model.GameEvent> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.GameEvent) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.deleteById(id) }
    }

    private class BattleLogDaoWrapper(private val dao: com.xianxia.sect.data.local.BattleLogDao) : GenericDao<com.xianxia.sect.core.model.BattleLog> {
        override suspend fun insert(entity: com.xianxia.sect.core.model.BattleLog) { dao.insert(entity) }
        override suspend fun deleteById(id: String) { dao.deleteById(id) }
    }

    private val genericDaoFactory: Map<String, GenericDao<*>> by lazy {
        mapOf(
            CacheKey.TYPE_DISCIPLE to DiscipleDaoWrapper(database.discipleDao()),
            CacheKey.TYPE_EQUIPMENT to EquipmentDaoWrapper(database.equipmentDao()),
            CacheKey.TYPE_MANUAL to ManualDaoWrapper(database.manualDao()),
            CacheKey.TYPE_PILL to PillDaoWrapper(database.pillDao()),
            CacheKey.TYPE_MATERIAL to MaterialDaoWrapper(database.materialDao()),
            CacheKey.TYPE_HERB to HerbDaoWrapper(database.herbDao()),
            CacheKey.TYPE_SEED to SeedDaoWrapper(database.seedDao()),
            CacheKey.TYPE_TEAM to TeamDaoWrapper(database.explorationTeamDao()),
            CacheKey.TYPE_BUILDING_SLOT to BuildingSlotDaoWrapper(database.buildingSlotDao()),
            CacheKey.TYPE_EVENT to EventDaoWrapper(database.gameEventDao()),
            CacheKey.TYPE_BATTLE_LOG to BattleLogDaoWrapper(database.battleLogDao())
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val memoryCache: MemoryCache = MemoryCache(config.memoryCacheSize.toInt())
    
    private val diskCache: DiskCache by lazy {
        DiskCache(context, "game_cache", config.diskCacheSize)
    }
    
    private val dirtyTracker = DirtyTracker()
    
    private val writeQueue: WriteQueue = WriteQueue(config, scope)
    
    private val compressor: DataCompressor = DataCompressor()
    
    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()
    
    private val lastCleanupTime = AtomicLong(System.currentTimeMillis())
    private val lastStatsLogTime = AtomicLong(System.currentTimeMillis())

    private var cleanupJob: Job? = null

    init {
        setupWriteQueue()
        startBackgroundTasks()
        Log.i(TAG, "GameDataCacheManager initialized with config: $config")
    }

    private fun setupWriteQueue() {
        writeQueue.setBatchWriteHandler { tasks ->
            processBatchWrite(tasks)
        }
        writeQueue.startProcessing()
    }

    private fun startBackgroundTasks() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                performCleanup()
            }
        }
    }

    suspend fun <T : Any> get(
        key: CacheKey,
        loader: suspend () -> T
    ): T {
        val cacheKey = key.toString()
        
        val memoryEntry = memoryCache.get(cacheKey)
        if (memoryEntry != null && memoryEntry.value != null) {
            @Suppress("UNCHECKED_CAST")
            return memoryEntry.value as T
        }

        if (config.enableDiskCache) {
            val diskEntry = diskCache.get(cacheKey)
            if (diskEntry != null && diskEntry.value != null) {
                memoryCache.put(cacheKey, diskEntry)
                @Suppress("UNCHECKED_CAST")
                return diskEntry.value as T
            }
        }

        val value = loader()
        val entry = CacheEntry.create(value, key.ttl)
        
        memoryCache.put(cacheKey, entry)
        
        if (config.enableDiskCache) {
            diskCache.put(cacheKey, entry)
        }

        return value
    }

    suspend fun <T : Any> getOrNull(key: CacheKey): T? {
        val cacheKey = key.toString()
        
        val memoryEntry = memoryCache.get(cacheKey)
        if (memoryEntry != null && memoryEntry.value != null) {
            @Suppress("UNCHECKED_CAST")
            return memoryEntry.value as T
        }

        if (config.enableDiskCache) {
            val diskEntry = diskCache.get(cacheKey)
            if (diskEntry != null && diskEntry.value != null) {
                memoryCache.put(cacheKey, diskEntry)
                @Suppress("UNCHECKED_CAST")
                return diskEntry.value as T
            }
        }

        return null
    }

    fun put(key: CacheKey, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }

        val cacheKey = key.toString()
        val entry = CacheEntry.create(value, key.ttl)
        
        memoryCache.put(cacheKey, entry)
        
        if (config.enableDiskCache) {
            diskCache.put(cacheKey, entry)
        }

        dirtyTracker.markUpdate(cacheKey, value)
    }

    fun putWithoutTracking(key: CacheKey, value: Any?) {
        if (value == null) {
            remove(key)
            return
        }

        val cacheKey = key.toString()
        val entry = CacheEntry.create(value, key.ttl)
        
        memoryCache.put(cacheKey, entry)
        
        if (config.enableDiskCache) {
            diskCache.put(cacheKey, entry)
        }
    }

    fun remove(key: CacheKey) {
        val cacheKey = key.toString()
        memoryCache.remove(cacheKey)
        diskCache.remove(cacheKey)
        dirtyTracker.markDelete(cacheKey)
    }

    fun removeWithoutTracking(key: CacheKey) {
        val cacheKey = key.toString()
        memoryCache.remove(cacheKey)
        diskCache.remove(cacheKey)
    }

    fun contains(key: CacheKey): Boolean {
        val cacheKey = key.toString()
        return memoryCache.contains(cacheKey) || diskCache.contains(cacheKey)
    }

    fun markDirty(key: CacheKey, flag: DirtyFlag = DirtyFlag.UPDATE, data: Any? = null) {
        dirtyTracker.markDirty(key.toString(), flag, data)
    }

    fun markInsert(key: CacheKey, data: Any?) {
        dirtyTracker.markInsert(key.toString(), data)
    }

    fun markUpdate(key: CacheKey, data: Any?) {
        dirtyTracker.markUpdate(key.toString(), data)
    }

    fun markDelete(key: CacheKey) {
        dirtyTracker.markDelete(key.toString())
    }

    fun isDirty(key: CacheKey): Boolean {
        return dirtyTracker.isDirty(key.toString())
    }

    suspend fun flushDirty(): FlushResult {
        if (dirtyTracker.isEmpty()) {
            return FlushResult.NoChanges()
        }

        val dirtyEntries = dirtyTracker.drainAll()
        val tasks = dirtyEntries.map { entry ->
            WriteTask(entry.key, entry.flag, entry.timestamp, entry.data)
        }

        val enqueued = writeQueue.enqueueBatch(tasks)
        
        writeQueue.flush()
        
        return FlushResult.Success(enqueued)
    }

    suspend fun sync(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                flushDirty()
                writeQueue.flush()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                false
            }
        }
    }

    fun clearCache(key: CacheKey? = null) {
        if (key != null) {
            val cacheKey = key.toString()
            memoryCache.remove(cacheKey)
            diskCache.remove(cacheKey)
            dirtyTracker.clearDirty(cacheKey)
        } else {
            memoryCache.clear()
            diskCache.clear()
            dirtyTracker.clear()
        }
    }

    fun clearMemoryCache() {
        memoryCache.clear()
    }

    fun clearDiskCache() {
        diskCache.clear()
    }

    private suspend fun processBatchWrite(tasks: List<WriteTask>): Int {
        var successCount = 0
        
        for (task in tasks) {
            try {
                val success = processWriteTask(task)
                if (success) successCount++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process write task: ${task.key}", e)
            }
        }

        Log.d(TAG, "Processed $successCount/${tasks.size} write tasks")
        return successCount
    }

    private suspend fun processWriteTask(task: WriteTask): Boolean {
        val cacheKey = CacheKey.fromString(task.key)

        return when (cacheKey.type) {
            CacheKey.TYPE_GAME_DATA -> processGameDataWrite(task)
            else -> {
                val dao = genericDaoFactory[cacheKey.type]
                if (dao != null) {
                    processGenericWrite(dao, task, cacheKey)
                } else {
                    Log.w(TAG, "Unknown cache type: ${cacheKey.type}")
                    false
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> processGenericWrite(
        dao: GenericDao<T>,
        task: WriteTask,
        cacheKey: CacheKey
    ): Boolean {
        return try {
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    dao.deleteById(cacheKey.id)
                    Log.d(TAG, "Deleted ${cacheKey.type}: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val entity = task.data as? T
                    if (entity != null) {
                        dao.insert(entity)
                        Log.d(TAG, "Inserted/Updated ${cacheKey.type}: $entity")
                        true
                    } else {
                        Log.w(TAG, "${cacheKey.type} data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process ${cacheKey.type} write", e)
            false
        }
    }

    private suspend fun processGameDataWrite(task: WriteTask): Boolean {
        return try {
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.gameDataDao().deleteAll()
                    Log.d(TAG, "Deleted all game data")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val gameData = task.data as? com.xianxia.sect.core.model.GameData
                    if (gameData != null) {
                        database.gameDataDao().insert(gameData)
                        Log.d(TAG, "Inserted/Updated game data")
                        true
                    } else {
                        Log.w(TAG, "GameData is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process game data write", e)
            false
        }
    }

    private suspend fun performCleanup() {
        val now = System.currentTimeMillis()
        
        if (now - lastCleanupTime.get() >= CLEANUP_INTERVAL_MS) {
            val expiredMemory = memoryCache.cleanExpired()
            val expiredDisk = diskCache.cleanExpired()
            
            lastCleanupTime.set(now)
            
            if (expiredMemory > 0 || expiredDisk > 0) {
                Log.d(TAG, "Cleanup completed: memory=$expiredMemory, disk=$expiredDisk")
            }
        }

        if (now - lastStatsLogTime.get() >= STATS_LOG_INTERVAL_MS) {
            updateStats()
            lastStatsLogTime.set(now)
        }
    }

    private fun updateStats() {
        val (memSize, memMaxSize) = memoryCache.getStats()
        val diskStats = diskCache.getStats()
        val dirtyStats = dirtyTracker.getStats()
        val queueStats = writeQueue.getStats()

        val stats = CacheStats(
            memoryHitCount = memoryCache.hitCount(),
            memoryMissCount = memoryCache.missCount(),
            memorySize = memSize.toLong(),
            memoryEntryCount = (memSize / 100).coerceAtLeast(0),
            diskSize = diskStats.totalSize,
            diskEntryCount = diskStats.entryCount.toInt(),
            dirtyCount = dirtyStats.totalCount,
            writeQueueSize = queueStats.queueSize
        )

        _cacheStats.value = stats

        Log.d(TAG, "Cache stats: memoryHitRate=${"%.2f".format(stats.memoryHitRate)}, " +
                "memorySize=${stats.memorySize}, diskSize=${stats.diskSize}, " +
                "dirtyCount=${stats.dirtyCount}, queueSize=${stats.writeQueueSize}")
    }

    fun getStats(): CacheStats {
        updateStats()
        return _cacheStats.value
    }

    fun shutdown() {
        cleanupJob?.cancel()
        writeQueue.stopProcessing()
        scope.cancel()
        Log.i(TAG, "GameDataCacheManager shutdown completed")
    }

    fun preloadData(dataMap: Map<CacheKey, Any>) {
        dataMap.forEach { (key, value) ->
            putWithoutTracking(key, value)
        }
        Log.i(TAG, "Preloaded ${dataMap.size} entries")
    }

    fun warmupCache(keys: List<CacheKey>, loader: suspend (CacheKey) -> Any?) {
        scope.launch {
            keys.forEach { key ->
                try {
                    val value = loader(key)
                    if (value != null) {
                        putWithoutTracking(key, value)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to warmup cache for key: $key", e)
                }
            }
            Log.i(TAG, "Cache warmup completed for ${keys.size} keys")
        }
    }
}
