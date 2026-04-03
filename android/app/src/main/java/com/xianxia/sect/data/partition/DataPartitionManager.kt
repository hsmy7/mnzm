package com.xianxia.sect.data.partition

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.cache.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class DataPartitionManager(
    private val context: Context,
    private val config: PartitionConfig = PartitionConfig.DEFAULT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "DataPartitionManager"
        private const val MONITORING_INTERVAL_MS = 60_000L
    }

    private val hotZone: HotZoneManager by lazy {
        HotZoneManager(config.hotZoneConfig, scope)
    }

    private val warmZone: WarmZoneManager by lazy {
        WarmZoneManager(context, config.warmZoneConfig, scope)
    }

    private val coldZone: ColdZoneManager by lazy {
        ColdZoneManager(context, config.coldZoneConfig, scope)
    }

    private val zoneMap = ConcurrentHashMap<String, DataZone>()
    private val transitionHistory = mutableListOf<ZoneTransition>()
    private val transitionCount = AtomicLong(0)
    private val startTime = System.currentTimeMillis()

    private var monitoringJob: Job? = null
    private var databaseLoader: (suspend (CacheKey, DataZone) -> Any?)? = null
    private var databaseWriter: (suspend (CacheKey, Any?, DataZone) -> Boolean)? = null

    private val _partitionState = MutableStateFlow(PartitionState.IDLE)
    val partitionState: StateFlow<PartitionState> = _partitionState.asStateFlow()

    init {
        val configErrors = config.validate()
        if (configErrors.isNotEmpty()) {
            Log.w(TAG, "Partition config validation warnings: ${configErrors.joinToString(", ")}")
        }
        
        setupWriteHandlers()
        if (config.enableAutoTransition) {
            startMonitoring()
        }
    }

    private fun setupWriteHandlers() {
        hotZone.setWriteHandler { key, data ->
            databaseWriter?.invoke(key, data, DataZone.HOT) ?: true
        }

        warmZone.setWriteHandler { key, data ->
            databaseWriter?.invoke(key, data, DataZone.WARM) ?: true
        }

        coldZone.setWriteHandler { key, data ->
            databaseWriter?.invoke(key, data, DataZone.COLD) ?: true
        }

        coldZone.setDatabaseLoader { key ->
            databaseLoader?.invoke(key, DataZone.COLD)
        }
    }

    fun setDatabaseLoader(loader: suspend (CacheKey, DataZone) -> Any?) {
        this.databaseLoader = loader
    }

    fun setDatabaseWriter(writer: suspend (CacheKey, Any?, DataZone) -> Boolean) {
        this.databaseWriter = writer
    }

    suspend fun <T : Any> get(key: CacheKey): T? {
        val zone = getZone(key)
        
        return when (zone) {
            DataZone.HOT -> hotZone.get(key)
            DataZone.WARM -> warmZone.get(key)
            DataZone.COLD -> coldZone.getOrLoadFromDatabase(key)
        }
    }

    suspend fun <T : Any> get(key: CacheKey, zone: DataZone): T? {
        return when (zone) {
            DataZone.HOT -> hotZone.get(key)
            DataZone.WARM -> warmZone.get(key)
            DataZone.COLD -> coldZone.getOrLoadFromDatabase(key)
        }
    }

    suspend fun <T : Any> getOrLoad(key: CacheKey, loader: suspend () -> T): T {
        val cached = get<T>(key)
        if (cached != null) {
            return cached
        }

        val value = loader()
        put(key, value)
        return value
    }

    suspend fun <T : Any> put(key: CacheKey, value: T, zone: DataZone? = null): Boolean {
        val targetZone = zone ?: determineZone(key)
        
        val success = when (targetZone) {
            DataZone.HOT -> hotZone.put(key, value)
            DataZone.WARM -> warmZone.put(key, value)
            DataZone.COLD -> coldZone.put(key, value)
        }

        if (success) {
            zoneMap[key.toString()] = targetZone
        }

        return success
    }

    suspend fun remove(key: CacheKey): Boolean {
        val zone = getZone(key)
        
        val success = when (zone) {
            DataZone.HOT -> hotZone.remove(key)
            DataZone.WARM -> warmZone.remove(key)
            DataZone.COLD -> coldZone.remove(key)
        }

        if (success) {
            zoneMap.remove(key.toString())
        }

        return success
    }

    suspend fun contains(key: CacheKey): Boolean {
        return hotZone.contains(key) || warmZone.contains(key) || coldZone.contains(key)
    }

    suspend fun move(key: CacheKey, targetZone: DataZone, reason: TransitionReason = TransitionReason.USER_REQUEST): Boolean {
        val currentZone = getZone(key)
        if (currentZone == targetZone) return true

        val value = get<Any>(key, currentZone)
        if (value == null) {
            Log.w(TAG, "Cannot move $key: value not found in $currentZone")
            return false
        }

        val added = when (targetZone) {
            DataZone.HOT -> hotZone.put(key, value)
            DataZone.WARM -> warmZone.put(key, value)
            DataZone.COLD -> coldZone.put(key, value)
        }

        if (!added) {
            Log.e(TAG, "Failed to add $key to $targetZone, aborting migration")
            return false
        }

        val removed = when (currentZone) {
            DataZone.HOT -> hotZone.remove(key)
            DataZone.WARM -> warmZone.remove(key)
            DataZone.COLD -> coldZone.remove(key)
        }

        if (!removed) {
            Log.w(TAG, "Failed to remove $key from $currentZone, data may be duplicated in both zones")
        }

        zoneMap[key.toString()] = targetZone
        recordTransition(currentZone, targetZone, reason)
        Log.i(TAG, "Moved $key from $currentZone to $targetZone, reason=$reason")
        return true
    }

    suspend fun promote(key: CacheKey): Boolean {
        val currentZone = getZone(key)
        val targetZone = when (currentZone) {
            DataZone.COLD -> DataZone.WARM
            DataZone.WARM -> DataZone.HOT
            DataZone.HOT -> return true
        }
        
        return move(key, targetZone, TransitionReason.MANUAL_PROMOTION)
    }

    suspend fun demote(key: CacheKey): Boolean {
        val currentZone = getZone(key)
        val targetZone = when (currentZone) {
            DataZone.HOT -> DataZone.WARM
            DataZone.WARM -> DataZone.COLD
            DataZone.COLD -> return true
        }
        
        return move(key, targetZone, TransitionReason.MANUAL_DEMOTION)
    }

    fun getZone(key: CacheKey): DataZone {
        return zoneMap[key.toString()] ?: determineZone(key)
    }

    private fun determineZone(key: CacheKey): DataZone {
        val dataType = DataType.fromCacheKeyType(key.type)
        return dataType.defaultZone
    }

    private fun recordTransition(from: DataZone, to: DataZone, reason: TransitionReason) {
        val transition = ZoneTransition(from, to, reason)
        synchronized(transitionHistory) {
            transitionHistory.add(transition)
            if (transitionHistory.size > 1000) {
                transitionHistory.removeAt(0)
            }
        }
        transitionCount.incrementAndGet()
    }

    fun startMonitoring() {
        if (monitoringJob?.isActive == true) return

        monitoringJob = scope.launch {
            while (isActive) {
                delay(config.monitoringIntervalMs)
                performAutoTransition()
            }
        }
        
        Log.i(TAG, "Monitoring started")
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.i(TAG, "Monitoring stopped")
    }

    private suspend fun performAutoTransition() {
        _partitionState.value = PartitionState.TRANSITIONING

        try {
            val threshold = config.transitionThreshold

            val hotInactiveKeys = hotZone.getInactiveKeys(threshold.demoteTimeWindowMs)
            hotInactiveKeys.forEach { key ->
                val cacheKey = CacheKey.fromString(key)
                val freq = hotZone.getAccessFrequency(cacheKey)
                if (freq == AccessFrequency.RARE || freq == AccessFrequency.LOW) {
                    move(cacheKey, DataZone.WARM, TransitionReason.ACCESS_FREQUENCY_LOW)
                }
            }

            val warmColdKeys = warmZone.getColdDataKeys(threshold.demoteTimeWindowMs)
            warmColdKeys.forEach { key ->
                val cacheKey = CacheKey.fromString(key)
                val freq = warmZone.getAccessFrequency(cacheKey)
                if (freq == AccessFrequency.RARE) {
                    move(cacheKey, DataZone.COLD, TransitionReason.ACCESS_FREQUENCY_LOW)
                }
            }

            val coldPromotableKeys = coldZone.getPromotableKeys(threshold.promoteTimeWindowMs)
            coldPromotableKeys.forEach { key ->
                val cacheKey = CacheKey.fromString(key)
                move(cacheKey, DataZone.WARM, TransitionReason.ACCESS_FREQUENCY_HIGH)
            }

            val warmActiveKeys = warmZone.getActiveKeys(threshold.promoteTimeWindowMs)
            warmActiveKeys.forEach { key ->
                val cacheKey = CacheKey.fromString(key)
                val freq = warmZone.getAccessFrequency(cacheKey)
                if (freq == AccessFrequency.HIGH) {
                    val recentAccess = warmZone.getAccessInfo(cacheKey)?.getRecentAccessCount(threshold.promoteTimeWindowMs) ?: 0
                    if (recentAccess >= threshold.promoteAccessCount) {
                        move(cacheKey, DataZone.HOT, TransitionReason.ACCESS_FREQUENCY_HIGH)
                    }
                }
            }

            checkMemoryPressure()

            _partitionState.value = PartitionState.IDLE
        } catch (e: Exception) {
            Log.e(TAG, "Error during auto transition", e)
            _partitionState.value = PartitionState.ERROR
        }
    }

    private suspend fun checkMemoryPressure() {
        val hotStats = hotZone.getStats()
        val threshold = config.transitionThreshold.memoryPressureThreshold

        if (hotStats.usagePercent > threshold * 100) {
            Log.w(TAG, "Memory pressure detected: ${hotStats.usagePercent}%")

            val keysToDemote = hotZone.getInactiveKeys(3600_000L)
            val demoteCount = (keysToDemote.size * 0.2).toInt().coerceAtLeast(1)
            
            keysToDemote.take(demoteCount).forEach { key ->
                val cacheKey = CacheKey.fromString(key)
                move(cacheKey, DataZone.WARM, TransitionReason.MEMORY_PRESSURE)
            }
        }
    }

    suspend fun preloadHotData(loader: suspend (DataType) -> List<Pair<CacheKey, Any?>>) {
        _partitionState.value = PartitionState.LOADING

        try {
            val hotDataTypes = listOf(DataType.DISCIPLE, DataType.EQUIPMENT, DataType.GAME_DATA)
            
            for (dataType in hotDataTypes) {
                val items = loader(dataType)
                for ((key, value) in items) {
                    if (value != null) {
                        hotZone.put(key, value)
                        zoneMap[key.toString()] = DataZone.HOT
                    }
                }
                Log.i(TAG, "Preloaded ${items.size} ${dataType.displayName} to hot zone")
            }

            _partitionState.value = PartitionState.IDLE
        } catch (e: Exception) {
            Log.e(TAG, "Error during preload", e)
            _partitionState.value = PartitionState.ERROR
        }
    }

    suspend fun preloadWarmData(loader: suspend (DataType) -> List<Pair<CacheKey, Any?>>) {
        val warmDataTypes = listOf(
            DataType.MANUAL, DataType.PILL, DataType.MATERIAL,
            DataType.HERB, DataType.SEED, DataType.TEAM, DataType.BUILDING_SLOT
        )

        for (dataType in warmDataTypes) {
            val items = loader(dataType)
            for ((key, value) in items) {
                if (value != null) {
                    warmZone.put(key, value)
                }
            }
            Log.i(TAG, "Preloaded ${items.size} ${dataType.displayName} to warm zone")
        }
    }

    suspend fun flushAll(): Int {
        var total = 0
        total += hotZone.flush()
        total += warmZone.flush()
        total += coldZone.flush()
        Log.i(TAG, "Flushed $total pending writes")
        return total
    }

    fun startWriteProcessing() {
        hotZone.startWriteProcessing()
        warmZone.startWriteProcessing()
        coldZone.startWriteProcessing()
    }

    fun stopWriteProcessing() {
        hotZone.stopWriteProcessing()
        warmZone.stopWriteProcessing()
        coldZone.stopWriteProcessing()
    }

    suspend fun clearAll() {
        hotZone.clear()
        warmZone.clear()
        coldZone.clear()
        zoneMap.clear()
        synchronized(transitionHistory) {
            transitionHistory.clear()
        }
        Log.i(TAG, "All zones cleared")
    }

    fun cleanExpired(): Int {
        var total = 0
        total += warmZone.cleanExpired()
        total += coldZone.cleanExpired()
        return total
    }

    fun getStats(): PartitionStats {
        return PartitionStats(
            hotZoneStats = hotZone.getStats(),
            warmZoneStats = warmZone.getStats(),
            coldZoneStats = coldZone.getStats(),
            transitionCount = transitionCount.get(),
            uptimeMs = System.currentTimeMillis() - startTime
        )
    }

    fun getZoneStats(zone: DataZone): ZoneStats {
        return when (zone) {
            DataZone.HOT -> hotZone.getStats()
            DataZone.WARM -> warmZone.getStats()
            DataZone.COLD -> coldZone.getStats()
        }
    }

    fun getTransitionHistory(): List<ZoneTransition> {
        return synchronized(transitionHistory) {
            transitionHistory.toList()
        }
    }

    fun getZoneDistribution(): Map<DataZone, Int> {
        val distribution = mutableMapOf<DataZone, Int>()
        distribution[DataZone.HOT] = hotZone.getEntryCount()
        distribution[DataZone.WARM] = warmZone.getEntryCount()
        distribution[DataZone.COLD] = coldZone.getEntryCount()
        return distribution
    }

    fun hotZoneManager(): HotZoneManager = hotZone
    fun warmZoneManager(): WarmZoneManager = warmZone
    fun coldZoneManager(): ColdZoneManager = coldZone

    fun shutdown() {
        stopMonitoring()
        stopWriteProcessing()
        hotZone.shutdown()
        warmZone.shutdown()
        coldZone.shutdown()
        scope.cancel()
        Log.i(TAG, "DataPartitionManager shutdown complete")
    }
}

enum class PartitionState {
    IDLE,
    LOADING,
    TRANSITIONING,
    ERROR
}

data class PartitionEvent(
    val type: PartitionEventType,
    val key: CacheKey? = null,
    val zone: DataZone? = null,
    val message: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class PartitionEventType {
    DATA_LOADED,
    DATA_MOVED,
    ZONE_CLEARED,
    MEMORY_PRESSURE,
    TRANSITION_COMPLETE,
    ERROR
}
