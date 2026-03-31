package com.xianxia.sect.data.chunked

import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.cache.GameDataCacheManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LazyLoadManager(
    private val storage: ChunkedFileStorage,
    private val cacheManager: GameDataCacheManager,
    private val serializer: Serializer = KotlinJsonSerializer(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "LazyLoadManager"
        private const val PRELOAD_QUEUE_CAPACITY = 100
        private const val MAX_CONCURRENT_LOADS = 4
        private const val CACHE_WARM_THRESHOLD = 0.7
        private const val MAX_MUTEX_CACHE_SIZE = 500
        private const val CLEANUP_INTERVAL_MS = 60_000L
    }

    private val loadMutexes = ConcurrentHashMap<String, Mutex>()
    private val preloadQueue = Channel<PreloadTask>(capacity = PRELOAD_QUEUE_CAPACITY)
    private val loadingJobs = ConcurrentHashMap<String, Job>()

    private val _loadStats = MutableStateFlow(LoadStats())
    val loadStats: StateFlow<LoadStats> = _loadStats.asStateFlow()

    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val preloadCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)

    private val currentSlot = AtomicInteger(1)
    private val isInitialized = AtomicBoolean(false)

    private val initMutex = Mutex()
    private var cleanupJob: Job? = null

    data class PreloadTask(
        val chunkIds: List<String>,
        val priority: Int,
        val callback: (() -> Unit)? = null
    )

    data class LoadStats(
        val hitCount: Long = 0,
        val missCount: Long = 0,
        val preloadCount: Long = 0,
        val errorCount: Long = 0,
        val hitRate: Double = 0.0,
        val averageLoadTimeMs: Double = 0.0
    )

    init {
        startPreloadProcessor()
        startCleanupTask()
    }

    suspend fun initialize(slot: Int) {
        initMutex.withLock {
            currentSlot.set(slot)
            isInitialized.set(true)
            Log.i(TAG, "LazyLoadManager initialized for slot $slot")
        }
    }

    private fun getLoadMutex(chunkId: String): Mutex {
        if (loadMutexes.size > MAX_MUTEX_CACHE_SIZE) {
            cleanupMutexes()
        }
        return loadMutexes.getOrPut(chunkId) { Mutex() }
    }

    private fun cleanupMutexes() {
        val iterator = loadMutexes.entries.iterator()
        var cleaned = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.isLocked) {
                iterator.remove()
                cleaned++
            }
        }
        if (cleaned > 0) {
            Log.d(TAG, "Cleaned up $cleaned unused mutexes")
        }
    }

    private fun startCleanupTask() {
        cleanupJob = scope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupMutexes()
                cleanupLoadingJobs()
            }
        }
    }

    private fun cleanupLoadingJobs() {
        val iterator = loadingJobs.entries.iterator()
        var cleaned = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.value.isActive) {
                iterator.remove()
                cleaned++
            }
        }
        if (cleaned > 0) {
            Log.d(TAG, "Cleaned up $cleaned completed loading jobs")
        }
    }

    suspend fun <T : Any> loadDisciple(discipleId: String): T? {
        val cacheKey = CacheKey.disciple(currentSlot.get(), discipleId)

        val cached = cacheManager.getOrNull<T>(cacheKey)
        if (cached != null) {
            hitCount.incrementAndGet()
            updateStats()
            return cached
        }

        missCount.incrementAndGet()
        updateStats()

        val chunkId = "disciple_$discipleId"
        val mutex = getLoadMutex(chunkId)

        return mutex.withLock {
            val cachedAfterLock = cacheManager.getOrNull<T>(cacheKey)
            if (cachedAfterLock != null) {
                return@withLock cachedAfterLock
            }

            val startTime = System.currentTimeMillis()
            val chunk = storage.readChunk(currentSlot.get(), chunkId)

            if (chunk == null) {
                Log.w(TAG, "Disciple chunk not found: $discipleId")
                errorCount.incrementAndGet()
                updateStats()
                return@withLock null
            }

            try {
                val disciple = serializer.deserialize(chunk.data, Disciple::class.java)
                cacheManager.putWithoutTracking(cacheKey, disciple)

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Loaded disciple $discipleId in ${elapsed}ms")

                @Suppress("UNCHECKED_CAST")
                disciple as T
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize disciple $discipleId", e)
                errorCount.incrementAndGet()
                updateStats()
                null
            }
        }
    }

    suspend fun loadEquipment(equipmentId: String): Equipment? {
        return loadItem(equipmentId, "equipment", Equipment::class.java) { slot, id -> CacheKey.equipment(slot, id) }
    }

    suspend fun loadManual(manualId: String): Manual? {
        return loadItem(manualId, "manual", Manual::class.java) { slot, id -> CacheKey.manual(slot, id) }
    }

    suspend fun loadPill(pillId: String): Pill? {
        return loadItem(pillId, "pill", Pill::class.java) { slot, id -> CacheKey.pill(slot, id) }
    }

    suspend fun loadMaterial(materialId: String): Material? {
        return loadItem(materialId, "material", Material::class.java) { slot, id -> CacheKey.material(slot, id) }
    }

    suspend fun loadHerb(herbId: String): Herb? {
        return loadItem(herbId, "herb", Herb::class.java) { slot, id -> CacheKey.herb(slot, id) }
    }

    suspend fun loadSeed(seedId: String): Seed? {
        return loadItem(seedId, "seed", Seed::class.java) { slot, id -> CacheKey.seed(slot, id) }
    }

    private suspend fun <T : Any> loadItem(
        itemId: String,
        itemType: String,
        clazz: Class<T>,
        cacheKeyFactory: (Int, String) -> CacheKey
    ): T? {
        val slot = currentSlot.get()
        val cacheKey = cacheKeyFactory(slot, itemId)

        val cached = cacheManager.getOrNull<T>(cacheKey)
        if (cached != null) {
            hitCount.incrementAndGet()
            updateStats()
            return cached
        }

        missCount.incrementAndGet()
        updateStats()

        val chunkId = "${itemType}_$itemId"
        val mutex = getLoadMutex(chunkId)

        return mutex.withLock {
            val cachedAfterLock = cacheManager.getOrNull<T>(cacheKey)
            if (cachedAfterLock != null) {
                return@withLock cachedAfterLock
            }

            val startTime = System.currentTimeMillis()
            val chunk = storage.readChunk(currentSlot.get(), chunkId)

            if (chunk == null) {
                Log.w(TAG, "$itemType chunk not found: $itemId")
                errorCount.incrementAndGet()
                updateStats()
                return@withLock null
            }

            try {
                val item = serializer.deserialize(chunk.data, clazz)
                cacheManager.putWithoutTracking(cacheKey, item)

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Loaded $itemType $itemId in ${elapsed}ms")

                item
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize $itemType $itemId", e)
                errorCount.incrementAndGet()
                updateStats()
                null
            }
        }
    }

    suspend fun <T : Any> loadDisciples(discipleIds: List<String>): List<T> {
        return discipleIds.mapNotNull { id -> loadDisciple<T>(id) }
    }

    suspend fun loadGameData(): GameData? {
        val slot = currentSlot.get()
        val cacheKey = CacheKey.gameData(slot)

        val cached = cacheManager.getOrNull<GameData>(cacheKey)
        if (cached != null) {
            hitCount.incrementAndGet()
            updateStats()
            return cached
        }

        missCount.incrementAndGet()
        updateStats()

        val chunkId = "core_gamedata"
        val mutex = getLoadMutex(chunkId)

        return mutex.withLock {
            val cachedAfterLock = cacheManager.getOrNull<GameData>(cacheKey)
            if (cachedAfterLock != null) {
                return@withLock cachedAfterLock
            }

            val startTime = System.currentTimeMillis()
            val chunk = storage.readChunk(slot, chunkId)

            if (chunk == null) {
                Log.w(TAG, "GameData chunk not found")
                errorCount.incrementAndGet()
                updateStats()
                return@withLock null
            }

            try {
                val gameData = serializer.deserialize(chunk.data, GameData::class.java)
                cacheManager.putWithoutTracking(cacheKey, gameData)

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Loaded GameData in ${elapsed}ms")

                gameData
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize GameData", e)
                errorCount.incrementAndGet()
                updateStats()
                null
            }
        }
    }

    suspend fun loadWorldSects(): List<WorldSect> {
        val chunkId = "world_sects"
        val chunk = storage.readChunk(currentSlot.get(), chunkId)

        if (chunk == null) {
            Log.w(TAG, "WorldSects chunk not found")
            return emptyList()
        }

        return try {
            serializer.deserializeList(chunk.data, WorldSect::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize WorldSects", e)
            emptyList()
        }
    }

    suspend fun loadBattleLogs(): List<BattleLog> {
        val chunkId = "logs_battle"
        val chunk = storage.readChunk(currentSlot.get(), chunkId)

        if (chunk == null) {
            return emptyList()
        }

        return try {
            serializer.deserializeList(chunk.data, BattleLog::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize BattleLogs", e)
            emptyList()
        }
    }

    suspend fun loadGameEvents(): List<GameEvent> {
        val chunkId = "logs_events"
        val chunk = storage.readChunk(currentSlot.get(), chunkId)

        if (chunk == null) {
            return emptyList()
        }

        return try {
            serializer.deserializeList(chunk.data, GameEvent::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize GameEvents", e)
            emptyList()
        }
    }

    fun preloadDisciples(discipleIds: List<String>, priority: Int = 0) {
        if (discipleIds.isEmpty()) return

        val chunkIds = discipleIds.map { "disciple_$it" }
        scope.launch {
            preloadQueue.send(PreloadTask(chunkIds, priority))
        }
    }

    fun preloadItems(itemType: String, itemIds: List<String>, priority: Int = 0) {
        if (itemIds.isEmpty()) return

        val chunkIds = itemIds.map { "${itemType}_$it" }
        scope.launch {
            preloadQueue.send(PreloadTask(chunkIds, priority))
        }
    }

    fun preloadWorldData(priority: Int = 0) {
        scope.launch {
            preloadQueue.send(PreloadTask(
                listOf("world_sects", "world_alliances", "world_relations"),
                priority
            ))
        }
    }

    fun preloadCoreData(priority: Int = 0) {
        scope.launch {
            preloadQueue.send(PreloadTask(
                listOf("core_gamedata"),
                priority
            ))
        }
    }

    private fun startPreloadProcessor() {
        scope.launch {
            while (isActive) {
                val task = preloadQueue.receive()
                processPreloadTask(task)
            }
        }
    }

    private suspend fun processPreloadTask(task: PreloadTask) {
        val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_LOADS)

        kotlinx.coroutines.coroutineScope {
            task.chunkIds.map { chunkId ->
                async {
                    semaphore.acquire()
                    try {
                        loadChunkToCache(chunkId)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        task.callback?.invoke()
    }

    private suspend fun loadChunkToCache(chunkId: String) {
        val existingJob = loadingJobs[chunkId]
        if (existingJob != null && existingJob.isActive) {
            return
        }

        val slot = currentSlot.get()
        val job = scope.launch {
            try {
                val chunk = storage.readChunk(slot, chunkId)
                if (chunk == null) {
                    Log.w(TAG, "Preload chunk not found: $chunkId")
                    return@launch
                }

                val cacheKey = chunkIdToCacheKey(chunkId, slot)
                if (cacheKey != null && !cacheManager.contains(cacheKey)) {
                    val data = deserializeChunk(chunkId, chunk.data)
                    if (data != null) {
                        cacheManager.putWithoutTracking(cacheKey, data)
                        preloadCount.incrementAndGet()
                        updateStats()
                        Log.d(TAG, "Preloaded chunk: $chunkId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to preload chunk: $chunkId", e)
                errorCount.incrementAndGet()
                updateStats()
            } finally {
                loadingJobs.remove(chunkId)
            }
        }

        loadingJobs[chunkId] = job
    }

    private fun chunkIdToCacheKey(chunkId: String, slot: Int = currentSlot.get()): CacheKey? {
        return when {
            chunkId.startsWith("disciple_") -> CacheKey.disciple(slot, chunkId.removePrefix("disciple_"))
            chunkId.startsWith("equipment_") -> CacheKey.equipment(slot, chunkId.removePrefix("equipment_"))
            chunkId.startsWith("manual_") -> CacheKey.manual(slot, chunkId.removePrefix("manual_"))
            chunkId.startsWith("pill_") -> CacheKey.pill(slot, chunkId.removePrefix("pill_"))
            chunkId.startsWith("material_") -> CacheKey.material(slot, chunkId.removePrefix("material_"))
            chunkId.startsWith("herb_") -> CacheKey.herb(slot, chunkId.removePrefix("herb_"))
            chunkId.startsWith("seed_") -> CacheKey.seed(slot, chunkId.removePrefix("seed_"))
            chunkId == "core_gamedata" -> CacheKey.gameData(slot)
            else -> null
        }
    }

    private fun deserializeChunk(chunkId: String, data: ByteArray): Any? {
        return try {
            when {
                chunkId.startsWith("disciple_") -> serializer.deserialize(data, Disciple::class.java)
                chunkId.startsWith("equipment_") -> serializer.deserialize(data, Equipment::class.java)
                chunkId.startsWith("manual_") -> serializer.deserialize(data, Manual::class.java)
                chunkId.startsWith("pill_") -> serializer.deserialize(data, Pill::class.java)
                chunkId.startsWith("material_") -> serializer.deserialize(data, Material::class.java)
                chunkId.startsWith("herb_") -> serializer.deserialize(data, Herb::class.java)
                chunkId.startsWith("seed_") -> serializer.deserialize(data, Seed::class.java)
                chunkId == "core_gamedata" -> serializer.deserialize(data, GameData::class.java)
                chunkId == "world_sects" -> serializer.deserializeList(data, WorldSect::class.java)
                chunkId == "logs_battle" -> serializer.deserializeList(data, BattleLog::class.java)
                chunkId == "logs_events" -> serializer.deserializeList(data, GameEvent::class.java)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize chunk: $chunkId", e)
            null
        }
    }

    fun invalidateCache(chunkId: String) {
        val cacheKey = chunkIdToCacheKey(chunkId)
        if (cacheKey != null) {
            cacheManager.removeWithoutTracking(cacheKey)
            Log.d(TAG, "Invalidated cache for: $chunkId")
        }
    }

    fun invalidateAllCache() {
        cacheManager.clearCache()
        Log.i(TAG, "Invalidated all cache")
    }

    fun warmupCache(chunkIds: List<String>) {
        scope.launch {
            chunkIds.forEach { chunkId ->
                loadChunkToCache(chunkId)
            }
            Log.i(TAG, "Cache warmup completed for ${chunkIds.size} chunks")
        }
    }

    private fun updateStats() {
        val hits = hitCount.get()
        val misses = missCount.get()
        val total = hits + misses
        val hitRate = if (total > 0) hits.toDouble() / total else 0.0

        _loadStats.value = LoadStats(
            hitCount = hits,
            missCount = misses,
            preloadCount = preloadCount.get(),
            errorCount = errorCount.get(),
            hitRate = hitRate
        )
    }

    fun getStats(): LoadStats = _loadStats.value

    fun shutdown() {
        cleanupJob?.cancel()
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        loadMutexes.clear()
        scope.cancel()
        Log.i(TAG, "LazyLoadManager shutdown completed")
    }
}
