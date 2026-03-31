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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val memoryCache: MemoryCache = MemoryCache(config.memoryCacheSize)
    
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
            return FlushResult.NoChanges
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
            CacheKey.TYPE_DISCIPLE -> processDiscipleWrite(task)
            CacheKey.TYPE_EQUIPMENT -> processEquipmentWrite(task)
            CacheKey.TYPE_MANUAL -> processManualWrite(task)
            CacheKey.TYPE_PILL -> processPillWrite(task)
            CacheKey.TYPE_MATERIAL -> processMaterialWrite(task)
            CacheKey.TYPE_HERB -> processHerbWrite(task)
            CacheKey.TYPE_SEED -> processSeedWrite(task)
            CacheKey.TYPE_TEAM -> processTeamWrite(task)
            CacheKey.TYPE_GAME_DATA -> processGameDataWrite(task)
            CacheKey.TYPE_BUILDING_SLOT -> processBuildingSlotWrite(task)
            CacheKey.TYPE_EVENT -> processEventWrite(task)
            CacheKey.TYPE_BATTLE_LOG -> processBattleLogWrite(task)
            else -> {
                Log.w(TAG, "Unknown cache type: ${cacheKey.type}")
                false
            }
        }
    }

    private suspend fun processDiscipleWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.discipleDao().deleteById(cacheKey.id)
                    Log.d(TAG, "Deleted disciple: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val disciple = task.data as? Disciple
                    if (disciple != null) {
                        database.discipleDao().insert(disciple)
                        Log.d(TAG, "Inserted/Updated disciple: ${disciple.id}")
                        true
                    } else {
                        Log.w(TAG, "Disciple data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process disciple write", e)
            false
        }
    }

    private suspend fun processEquipmentWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.equipmentDao().deleteById(cacheKey.id)
                    Log.d(TAG, "Deleted equipment: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val equipment = task.data as? Equipment
                    if (equipment != null) {
                        database.equipmentDao().insert(equipment)
                        Log.d(TAG, "Inserted/Updated equipment: ${equipment.id}")
                        true
                    } else {
                        Log.w(TAG, "Equipment data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process equipment write", e)
            false
        }
    }

    private suspend fun processManualWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.manualDao().delete(com.xianxia.sect.core.model.Manual(id = cacheKey.id))
                    Log.d(TAG, "Deleted manual: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val manual = task.data as? com.xianxia.sect.core.model.Manual
                    if (manual != null) {
                        database.manualDao().insert(manual)
                        Log.d(TAG, "Inserted/Updated manual: ${manual.id}")
                        true
                    } else {
                        Log.w(TAG, "Manual data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process manual write", e)
            false
        }
    }

    private suspend fun processPillWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.pillDao().delete(com.xianxia.sect.core.model.Pill(id = cacheKey.id))
                    Log.d(TAG, "Deleted pill: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val pill = task.data as? com.xianxia.sect.core.model.Pill
                    if (pill != null) {
                        database.pillDao().insert(pill)
                        Log.d(TAG, "Inserted/Updated pill: ${pill.id}")
                        true
                    } else {
                        Log.w(TAG, "Pill data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process pill write", e)
            false
        }
    }

    private suspend fun processMaterialWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.materialDao().delete(com.xianxia.sect.core.model.Material(id = cacheKey.id))
                    Log.d(TAG, "Deleted material: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val material = task.data as? com.xianxia.sect.core.model.Material
                    if (material != null) {
                        database.materialDao().insert(material)
                        Log.d(TAG, "Inserted/Updated material: ${material.id}")
                        true
                    } else {
                        Log.w(TAG, "Material data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process material write", e)
            false
        }
    }

    private suspend fun processHerbWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.herbDao().delete(com.xianxia.sect.core.model.Herb(id = cacheKey.id))
                    Log.d(TAG, "Deleted herb: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val herb = task.data as? com.xianxia.sect.core.model.Herb
                    if (herb != null) {
                        database.herbDao().insert(herb)
                        Log.d(TAG, "Inserted/Updated herb: ${herb.id}")
                        true
                    } else {
                        Log.w(TAG, "Herb data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process herb write", e)
            false
        }
    }

    private suspend fun processSeedWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.seedDao().delete(com.xianxia.sect.core.model.Seed(id = cacheKey.id))
                    Log.d(TAG, "Deleted seed: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val seed = task.data as? com.xianxia.sect.core.model.Seed
                    if (seed != null) {
                        database.seedDao().insert(seed)
                        Log.d(TAG, "Inserted/Updated seed: ${seed.id}")
                        true
                    } else {
                        Log.w(TAG, "Seed data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process seed write", e)
            false
        }
    }

    private suspend fun processTeamWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.explorationTeamDao().delete(com.xianxia.sect.core.model.ExplorationTeam(id = cacheKey.id))
                    Log.d(TAG, "Deleted team: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val team = task.data as? com.xianxia.sect.core.model.ExplorationTeam
                    if (team != null) {
                        database.explorationTeamDao().insert(team)
                        Log.d(TAG, "Inserted/Updated team: ${team.id}")
                        true
                    } else {
                        Log.w(TAG, "Team data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process team write", e)
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

    private suspend fun processBuildingSlotWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.buildingSlotDao().delete(com.xianxia.sect.core.model.BuildingSlot(id = cacheKey.id))
                    Log.d(TAG, "Deleted building slot: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val slot = task.data as? com.xianxia.sect.core.model.BuildingSlot
                    if (slot != null) {
                        database.buildingSlotDao().insert(slot)
                        Log.d(TAG, "Inserted/Updated building slot: ${slot.id}")
                        true
                    } else {
                        Log.w(TAG, "BuildingSlot data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process building slot write", e)
            false
        }
    }

    private suspend fun processEventWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.gameEventDao().deleteById(cacheKey.id)
                    Log.d(TAG, "Deleted event: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val event = task.data as? com.xianxia.sect.core.model.GameEvent
                    if (event != null) {
                        database.gameEventDao().insert(event)
                        Log.d(TAG, "Inserted/Updated event: ${event.id}")
                        true
                    } else {
                        Log.w(TAG, "GameEvent data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process event write", e)
            false
        }
    }

    private suspend fun processBattleLogWrite(task: WriteTask): Boolean {
        return try {
            val cacheKey = CacheKey.fromString(task.key)
            when (task.flag) {
                DirtyFlag.DELETE -> {
                    database.battleLogDao().deleteById(cacheKey.id)
                    Log.d(TAG, "Deleted battle log: ${cacheKey.id}")
                    true
                }
                DirtyFlag.INSERT, DirtyFlag.UPDATE -> {
                    val log = task.data as? com.xianxia.sect.core.model.BattleLog
                    if (log != null) {
                        database.battleLogDao().insert(log)
                        Log.d(TAG, "Inserted/Updated battle log: ${log.id}")
                        true
                    } else {
                        Log.w(TAG, "BattleLog data is null for task: ${task.key}")
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process battle log write", e)
            false
        }
    }

    private fun performCleanup() {
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
