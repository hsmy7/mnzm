package com.xianxia.sect.data.preload

import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.partition.DataPartitionManager
import com.xianxia.sect.data.partition.DataZone
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.LinkedList

object PreloadConfig {
    const val MAX_PRELOAD_ITEMS = 100
    const val PRELOAD_BATCH_SIZE = 10
    const val PRELOAD_DELAY_MS = 100L
    const val PREDICTION_WINDOW_MS = 60_000L
    const val ACCESS_HISTORY_SIZE = 100
    
    val PRIORITY_WEIGHTS = mapOf(
        "recency" to 0.3f,
        "frequency" to 0.3f,
        "context" to 0.2f,
        "type" to 0.2f
    )
    
    const val WARMUP_MIN_HIT_RATE = 0.6f
    const val WARMUP_ADAPTIVE_THRESHOLD = 0.75f
    const val WARMUP_MAX_MEMORY_PERCENT = 0.3f
    const val WARMUP_IDLE_DELAY_MS = 5000L
    const val WARMUP_PRIORITY_BOOST_FACTOR = 1.5f
    const val WARMUP_DECAY_FACTOR = 0.95f
    const val WARMUP_MIN_SAMPLES = 10
}

enum class WarmupStrategy {
    AGGRESSIVE,
    BALANCED,
    CONSERVATIVE,
    ADAPTIVE
}

data class WarmupConfig(
    val strategy: WarmupStrategy = WarmupStrategy.ADAPTIVE,
    val maxMemoryBytes: Long = 50 * 1024 * 1024,
    val targetHitRate: Float = 0.8f,
    val maxPreloadTime: Long = 3000L,
    val enableBackgroundWarmup: Boolean = true,
    val enablePredictiveWarmup: Boolean = true
)

interface WarmupDataLoader {
    suspend fun loadData(key: CacheKey): Any?
}

data class WarmupStats(
    val totalWarmups: Long = 0,
    val successfulWarmups: Long = 0,
    val itemsWarmed: Int = 0,
    val avgWarmupTimeMs: Long = 0,
    val memoryUsedBytes: Long = 0,
    val hitRateAfterWarmup: Float = 0f,
    val strategyUsed: WarmupStrategy = WarmupStrategy.ADAPTIVE
)

data class AccessPattern(
    val key: String,
    val dataType: String,
    val accessCount: Int,
    val lastAccessTime: Long,
    val avgInterval: Long,
    val contextKeys: Set<String>
)

data class PreloadPrediction(
    val key: String,
    val dataType: String,
    val probability: Float,
    val priority: Int,
    val estimatedAccessTime: Long
)

data class PreloadStats(
    val totalPredictions: Long = 0,
    val accuratePredictions: Long = 0,
    val preloadedItems: Int = 0,
    val cacheHitsFromPreload: Long = 0,
    val accuracy: Float = 0f,
    val warmupStats: WarmupStats = WarmupStats()
)

class CacheWarmupManager(
    private val preloader: PredictivePreloader,
    private val scope: CoroutineScope,
    private val dataLoader: WarmupDataLoader? = null
) {
    companion object {
        private const val TAG = "CacheWarmupManager"
    }
    
    private var currentConfig = WarmupConfig()
    private var currentStrategy = WarmupStrategy.ADAPTIVE
    
    private val totalWarmups = AtomicLong(0)
    private val successfulWarmups = AtomicLong(0)
    private val itemsWarmed = AtomicInteger(0)
    private val totalWarmupTime = AtomicLong(0)
    private val memoryUsed = AtomicLong(0)
    
    private val hitRateHistory = ConcurrentLinkedDeque<Float>()
    private val strategyPerformance = ConcurrentHashMap<WarmupStrategy, Float>()
    
    private var warmupJob: Job? = null
    private var isShuttingDown = false
    
    private val _warmupState = MutableStateFlow<WarmupState>(WarmupState.Idle)
    val warmupState: StateFlow<WarmupState> = _warmupState.asStateFlow()
    
    private val _currentStats = MutableStateFlow(WarmupStats())
    val currentStats: StateFlow<WarmupStats> = _currentStats.asStateFlow()
    
    sealed class WarmupState {
        object Idle : WarmupState()
        data class WarmingUp(val progress: Float, val strategy: WarmupStrategy) : WarmupState()
        data class Evaluating(val hitRate: Float) : WarmupState()
        data class Adapting(val newStrategy: WarmupStrategy) : WarmupState()
    }
    
    init {
        strategyPerformance[WarmupStrategy.AGGRESSIVE] = 0.7f
        strategyPerformance[WarmupStrategy.BALANCED] = 0.75f
        strategyPerformance[WarmupStrategy.CONSERVATIVE] = 0.65f
        strategyPerformance[WarmupStrategy.ADAPTIVE] = 0.8f
        
        if (currentConfig.enableBackgroundWarmup) {
            startBackgroundWarmup()
        }
    }
    
    fun configure(config: WarmupConfig) {
        currentConfig = config
        currentStrategy = config.strategy
        Log.i(TAG, "Warmup config updated: strategy=${config.strategy}, targetHitRate=${config.targetHitRate}")
    }
    
    private fun startBackgroundWarmup() {
        warmupJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(PreloadConfig.WARMUP_IDLE_DELAY_MS)
                
                val currentHitRate = calculateCurrentHitRate()
                
                if (currentHitRate < PreloadConfig.WARMUP_MIN_HIT_RATE) {
                    performAdaptiveWarmup()
                }
                
                evaluateAndAdaptStrategy(currentHitRate)
            }
        }
    }
    
    suspend fun performWarmup(
        gameData: GameData? = null,
        disciples: List<Disciple>? = null,
        forceStrategy: WarmupStrategy? = null
    ): WarmupResult {
        val strategy = forceStrategy ?: currentStrategy
        val startTime = System.currentTimeMillis()
        
        _warmupState.value = WarmupState.WarmingUp(0f, strategy)
        totalWarmups.incrementAndGet()
        
        return try {
            val items = selectItemsForWarmup(strategy, gameData, disciples)
            val warmed = executeWarmup(items, strategy)
            
            val elapsed = System.currentTimeMillis() - startTime
            successfulWarmups.incrementAndGet()
            itemsWarmed.addAndGet(warmed)
            totalWarmupTime.addAndGet(elapsed)
            
            val hitRate = calculateCurrentHitRate()
            recordHitRate(hitRate)
            
            _warmupState.value = WarmupState.Evaluating(hitRate)
            
            updateStats(strategy, warmed, elapsed, hitRate)
            
            WarmupResult(
                success = true,
                itemsWarmed = warmed,
                elapsedMs = elapsed,
                hitRate = hitRate,
                strategy = strategy
            )
        } catch (e: Exception) {
            Log.e(TAG, "Warmup failed", e)
            WarmupResult(success = false, strategy = strategy)
        } finally {
            _warmupState.value = WarmupState.Idle
        }
    }
    
    private suspend fun performAdaptiveWarmup() {
        val bestStrategy = selectBestStrategy()
        
        if (bestStrategy != currentStrategy) {
            _warmupState.value = WarmupState.Adapting(bestStrategy)
            currentStrategy = bestStrategy
            Log.i(TAG, "Adapting warmup strategy to $bestStrategy")
        }
        
        performWarmup()
    }
    
    private fun selectItemsForWarmup(
        strategy: WarmupStrategy,
        gameData: GameData?,
        disciples: List<Disciple>?
    ): List<PreloadPrediction> {
        val predictions = preloader.getPredictions().toMutableList()
        
        if (gameData != null) {
            predictions.add(PreloadPrediction(
                key = "${CacheKey.TYPE_GAME_DATA}:${gameData.currentSlot}",
                dataType = CacheKey.TYPE_GAME_DATA,
                probability = 1.0f,
                priority = 10,
                estimatedAccessTime = System.currentTimeMillis()
            ))
        }
        
        disciples?.filter { it.isAlive }?.let { aliveDisciples ->
            val limit = when (strategy) {
                WarmupStrategy.AGGRESSIVE -> aliveDisciples.size
                WarmupStrategy.BALANCED -> minOf(20, aliveDisciples.size)
                WarmupStrategy.CONSERVATIVE -> minOf(10, aliveDisciples.size)
                WarmupStrategy.ADAPTIVE -> calculateAdaptiveLimit(aliveDisciples.size)
            }
            
            aliveDisciples.take(limit).forEach { disciple ->
                predictions.add(PreloadPrediction(
                    key = "${CacheKey.TYPE_DISCIPLE}:${disciple.id}",
                    dataType = CacheKey.TYPE_DISCIPLE,
                    probability = 0.9f,
                    priority = 9,
                    estimatedAccessTime = System.currentTimeMillis() + 30_000L
                ))
            }
        }
        
        val maxItems = when (strategy) {
            WarmupStrategy.AGGRESSIVE -> PreloadConfig.MAX_PRELOAD_ITEMS
            WarmupStrategy.BALANCED -> PreloadConfig.MAX_PRELOAD_ITEMS * 3 / 4
            WarmupStrategy.CONSERVATIVE -> PreloadConfig.MAX_PRELOAD_ITEMS / 2
            WarmupStrategy.ADAPTIVE -> calculateAdaptiveLimit(PreloadConfig.MAX_PRELOAD_ITEMS)
        }
        
        return predictions
            .sortedByDescending { it.probability }
            .take(maxItems)
    }
    
    private suspend fun executeWarmup(items: List<PreloadPrediction>, strategy: WarmupStrategy): Int {
        var warmed = 0
        val batchSize = when (strategy) {
            WarmupStrategy.AGGRESSIVE -> PreloadConfig.PRELOAD_BATCH_SIZE * 2
            WarmupStrategy.BALANCED -> PreloadConfig.PRELOAD_BATCH_SIZE
            WarmupStrategy.CONSERVATIVE -> PreloadConfig.PRELOAD_BATCH_SIZE / 2
            WarmupStrategy.ADAPTIVE -> calculateAdaptiveBatchSize()
        }
        
        val delayMs = when (strategy) {
            WarmupStrategy.AGGRESSIVE -> PreloadConfig.PRELOAD_DELAY_MS / 2
            WarmupStrategy.BALANCED -> PreloadConfig.PRELOAD_DELAY_MS
            WarmupStrategy.CONSERVATIVE -> PreloadConfig.PRELOAD_DELAY_MS * 2
            WarmupStrategy.ADAPTIVE -> calculateAdaptiveDelay()
        }
        
        items.chunked(batchSize.coerceAtLeast(1)).forEach { batch ->
            batch.forEach { prediction ->
                try {
                    val key = CacheKey.fromString(prediction.key)
                    
                    if (dataLoader != null) {
                        val data = dataLoader.loadData(key)
                        if (data != null) {
                            preloader.markAsPreloaded(prediction.key)
                            warmed++
                        }
                    } else {
                        preloader.recordAccess(key)
                        preloader.markAsPreloaded(prediction.key)
                        warmed++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to warmup: ${prediction.key}", e)
                }
            }
            
            _warmupState.value = WarmupState.WarmingUp(
                warmed.toFloat() / items.size,
                strategy
            )
            
            delay(delayMs)
        }
        
        return warmed
    }
    
    private fun calculateAdaptiveLimit(baseLimit: Int): Int {
        val hitRate = calculateCurrentHitRate()
        val memoryPressure = getMemoryPressure()
        
        return when {
            hitRate < 0.5f -> (baseLimit * 1.2).toInt()
            hitRate > 0.85f -> (baseLimit * 0.7).toInt()
            memoryPressure > 0.8f -> (baseLimit * 0.5).toInt()
            else -> baseLimit
        }
    }
    
    private fun calculateAdaptiveBatchSize(): Int {
        val hitRate = calculateCurrentHitRate()
        return when {
            hitRate < 0.5f -> PreloadConfig.PRELOAD_BATCH_SIZE * 2
            hitRate > 0.85f -> PreloadConfig.PRELOAD_BATCH_SIZE / 2
            else -> PreloadConfig.PRELOAD_BATCH_SIZE
        }.coerceIn(1, 20)
    }
    
    private fun calculateAdaptiveDelay(): Long {
        val memoryPressure = getMemoryPressure()
        return when {
            memoryPressure > 0.8f -> PreloadConfig.PRELOAD_DELAY_MS * 3
            memoryPressure > 0.6f -> PreloadConfig.PRELOAD_DELAY_MS * 2
            else -> PreloadConfig.PRELOAD_DELAY_MS
        }
    }
    
    private fun calculateCurrentHitRate(): Float {
        val stats = preloader.stats.value
        val total = stats.cacheHitsFromPreload + (stats.totalPredictions - stats.accuratePredictions)
        return if (total > 0) stats.cacheHitsFromPreload.toFloat() / total else 0f
    }
    
    private fun getMemoryPressure(): Float {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        return used.toFloat() / max
    }
    
    private fun recordHitRate(hitRate: Float) {
        hitRateHistory.addLast(hitRate)
        while (hitRateHistory.size > 100) {
            hitRateHistory.removeFirst()
        }
    }
    
    private fun evaluateAndAdaptStrategy(currentHitRate: Float) {
        if (hitRateHistory.size < PreloadConfig.WARMUP_MIN_SAMPLES) return
        
        val historyList = hitRateHistory.toList()
        val recentAvg = historyList.takeLast(10).average().toFloat()
        val strategyScore = strategyPerformance[currentStrategy] ?: 0.7f
        
        val newScore = strategyScore * PreloadConfig.WARMUP_DECAY_FACTOR + 
                       currentHitRate * (1 - PreloadConfig.WARMUP_DECAY_FACTOR)
        strategyPerformance[currentStrategy] = newScore
        
        if (recentAvg < currentConfig.targetHitRate * 0.8f) {
            val bestStrategy = selectBestStrategy()
            if (bestStrategy != currentStrategy) {
                currentStrategy = bestStrategy
                Log.i(TAG, "Strategy adapted to $bestStrategy due to low hit rate: $recentAvg")
            }
        }
    }
    
    private fun selectBestStrategy(): WarmupStrategy {
        val memoryPressure = getMemoryPressure()
        
        return when {
            memoryPressure > 0.85f -> WarmupStrategy.CONSERVATIVE
            memoryPressure > 0.7f -> WarmupStrategy.BALANCED
            else -> strategyPerformance.maxByOrNull { it.value }?.key ?: WarmupStrategy.ADAPTIVE
        }
    }
    
    private fun updateStats(strategy: WarmupStrategy, items: Int, time: Long, hitRate: Float) {
        _currentStats.value = WarmupStats(
            totalWarmups = totalWarmups.get(),
            successfulWarmups = successfulWarmups.get(),
            itemsWarmed = itemsWarmed.get(),
            avgWarmupTimeMs = if (successfulWarmups.get() > 0) 
                totalWarmupTime.get() / successfulWarmups.get() else 0,
            memoryUsedBytes = memoryUsed.get(),
            hitRateAfterWarmup = hitRate,
            strategyUsed = strategy
        )
    }
    
    fun getStrategyPerformance(): Map<WarmupStrategy, Float> {
        return strategyPerformance.toMap()
    }
    
    fun shutdown() {
        isShuttingDown = true
        warmupJob?.cancel()
    }
}

data class WarmupResult(
    val success: Boolean,
    val itemsWarmed: Int = 0,
    val elapsedMs: Long = 0,
    val hitRate: Float = 0f,
    val strategy: WarmupStrategy
)

sealed class PreloadEvent {
    data class PredictionMade(val key: String, val probability: Float) : PreloadEvent()
    data class PreloadStarted(val count: Int) : PreloadEvent()
    data class PreloadCompleted(val count: Int, val elapsedMs: Long) : PreloadEvent()
    data class CacheHit(val key: String, val wasPreloaded: Boolean) : PreloadEvent()
}

class PredictivePreloader(
    private val partitionManager: DataPartitionManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "PredictivePreloader"
    }

    private val accessHistory = ConcurrentHashMap<String, MutableList<Long>>()
    private val accessPatterns = ConcurrentHashMap<String, AccessPattern>()
    private val predictions = ConcurrentHashMap<String, PreloadPrediction>()
    private val preloadedKeys = ConcurrentHashMap.newKeySet<String>()
    
    private val contextHistory = LinkedList<String>()
    private val maxContextHistory = 20
    
    private val totalPredictions = AtomicLong(0)
    private val accuratePredictions = AtomicLong(0)
    private val cacheHitsFromPreload = AtomicLong(0)
    
    private val _stats = MutableStateFlow(PreloadStats())
    val stats: StateFlow<PreloadStats> = _stats.asStateFlow()
    
    private val _preloadState = MutableStateFlow<PreloadState>(PreloadState.Idle)
    val preloadState: StateFlow<PreloadState> = _preloadState.asStateFlow()
    
    private var preloadJob: Job? = null
    private var analysisJob: Job? = null
    private var isShuttingDown = false
    
    private lateinit var warmupManager: CacheWarmupManager
    
    val warmupState: StateFlow<CacheWarmupManager.WarmupState> by lazy { 
        warmupManager.warmupState 
    }
    
    val warmupStats: StateFlow<WarmupStats> by lazy { 
        warmupManager.currentStats 
    }

    sealed class PreloadState {
        object Idle : PreloadState()
        data class Analyzing(val progress: Float) : PreloadState()
        data class Preloading(val count: Int, val progress: Float) : PreloadState()
        data class Error(val message: String) : PreloadState()
    }

    init {
        warmupManager = CacheWarmupManager(this, scope)
        startBackgroundAnalysis()
    }

    private fun startBackgroundAnalysis() {
        analysisJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(30_000L)
                analyzePatterns()
                updatePredictions()
            }
        }
    }

    fun recordAccess(key: CacheKey) {
        val keyStr = key.toString()
        val now = System.currentTimeMillis()
        
        synchronized(accessHistory) {
            val history = accessHistory.getOrPut(keyStr) { mutableListOf() }
            history.add(now)
            
            if (history.size > PreloadConfig.ACCESS_HISTORY_SIZE) {
                history.removeAt(0)
            }
        }
        
        synchronized(contextHistory) {
            contextHistory.add(keyStr)
            if (contextHistory.size > maxContextHistory) {
                contextHistory.removeFirst()
            }
        }
        
        updateAccessPattern(keyStr, key.type, now)
        
        if (preloadedKeys.contains(keyStr)) {
            cacheHitsFromPreload.incrementAndGet()
        }
    }

    private fun updateAccessPattern(key: String, dataType: String, accessTime: Long) {
        val history = accessHistory[key] ?: return
        
        val avgInterval = if (history.size > 1) {
            val intervals = history.zipWithNext { a, b -> b - a }
            intervals.average().toLong()
        } else {
            0L
        }
        
        val contextKeys = synchronized(contextHistory) {
            contextHistory.filter { element -> element != key }.toSet()
        }
        
        accessPatterns[key] = AccessPattern(
            key = key,
            dataType = dataType,
            accessCount = history.size,
            lastAccessTime = accessTime,
            avgInterval = avgInterval,
            contextKeys = contextKeys
        )
    }

    private fun analyzePatterns() {
        _preloadState.value = PreloadState.Analyzing(0f)
        
        try {
            val now = System.currentTimeMillis()
            val recentThreshold = now - PreloadConfig.PREDICTION_WINDOW_MS
            
            accessPatterns.values.forEach { pattern ->
                val recencyScore = calculateRecencyScore(pattern.lastAccessTime, now)
                val frequencyScore = calculateFrequencyScore(pattern.accessCount)
                val contextScore = calculateContextScore(pattern.contextKeys)
                val typeScore = calculateTypeScore(pattern.dataType)
                
                val weights = PreloadConfig.PRIORITY_WEIGHTS
                val probability = recencyScore * (weights["recency"] ?: 0.3f) +
                                 frequencyScore * (weights["frequency"] ?: 0.3f) +
                                 contextScore * (weights["context"] ?: 0.2f) +
                                 typeScore * (weights["type"] ?: 0.2f)
                
                if (probability > 0.5f) {
                    val estimatedAccessTime = if (pattern.avgInterval > 0) {
                        pattern.lastAccessTime + pattern.avgInterval
                    } else {
                        now + 60_000L
                    }
                    
                    predictions[pattern.key] = PreloadPrediction(
                        key = pattern.key,
                        dataType = pattern.dataType,
                        probability = probability,
                        priority = (probability * 10).toInt(),
                        estimatedAccessTime = estimatedAccessTime
                    )
                }
            }
            
            _preloadState.value = PreloadState.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Pattern analysis failed", e)
            _preloadState.value = PreloadState.Error(e.message ?: "Analysis failed")
        }
    }

    private fun calculateRecencyScore(lastAccessTime: Long, now: Long): Float {
        val age = now - lastAccessTime
        return when {
            age < 60_000L -> 1.0f
            age < 300_000L -> 0.8f
            age < 600_000L -> 0.6f
            age < 1800_000L -> 0.4f
            else -> 0.2f
        }
    }

    private fun calculateFrequencyScore(accessCount: Int): Float {
        return when {
            accessCount >= 20 -> 1.0f
            accessCount >= 10 -> 0.8f
            accessCount >= 5 -> 0.6f
            accessCount >= 3 -> 0.4f
            else -> 0.2f
        }
    }

    private fun calculateContextScore(contextKeys: Set<String>): Float {
        val now = System.currentTimeMillis()
        val recentContextAccess = contextKeys.count { key ->
            val pattern = accessPatterns[key]
            pattern != null && (now - pattern.lastAccessTime) < 300_000L
        }
        
        return when {
            recentContextAccess >= 5 -> 1.0f
            recentContextAccess >= 3 -> 0.7f
            recentContextAccess >= 1 -> 0.4f
            else -> 0.1f
        }
    }

    private fun calculateTypeScore(dataType: String): Float {
        return when (dataType) {
            CacheKey.TYPE_GAME_DATA -> 1.0f
            CacheKey.TYPE_DISCIPLE -> 0.9f
            CacheKey.TYPE_EQUIPMENT -> 0.7f
            CacheKey.TYPE_MANUAL -> 0.6f
            CacheKey.TYPE_PILL, CacheKey.TYPE_MATERIAL -> 0.5f
            CacheKey.TYPE_HERB, CacheKey.TYPE_SEED -> 0.4f
            else -> 0.3f
        }
    }

    private fun updatePredictions() {
        val now = System.currentTimeMillis()
        
        predictions.values.removeAll { prediction ->
            prediction.estimatedAccessTime < now - 300_000L
        }
        
        totalPredictions.addAndGet(predictions.size.toLong())
        updateStats()
    }

    suspend fun analyzeAndPreload(gameData: GameData, disciples: List<Disciple>) {
        _preloadState.value = PreloadState.Analyzing(0f)
        
        try {
            val predictions = generateContextualPredictions(gameData, disciples)
            
            _preloadState.value = PreloadState.Analyzing(0.5f)
            
            val toPreload = predictions
                .sortedByDescending { it.probability }
                .take(PreloadConfig.MAX_PRELOAD_ITEMS)
            
            _preloadState.value = PreloadState.Preloading(toPreload.size, 0f)
            
            executePreload(toPreload)
            
            _preloadState.value = PreloadState.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Analyze and preload failed", e)
            _preloadState.value = PreloadState.Error(e.message ?: "Preload failed")
        }
    }

    private fun generateContextualPredictions(
        gameData: GameData,
        disciples: List<Disciple>
    ): List<PreloadPrediction> {
        val predictions = mutableListOf<PreloadPrediction>()
        val now = System.currentTimeMillis()
        
        predictions.add(PreloadPrediction(
            key = "${CacheKey.TYPE_GAME_DATA}:${gameData.currentSlot}",
            dataType = CacheKey.TYPE_GAME_DATA,
            probability = 1.0f,
            priority = 10,
            estimatedAccessTime = now
        ))
        
        disciples.filter { it.isAlive }.take(20).forEach { disciple ->
            predictions.add(PreloadPrediction(
                key = "${CacheKey.TYPE_DISCIPLE}:${disciple.id}",
                dataType = CacheKey.TYPE_DISCIPLE,
                probability = 0.9f,
                priority = 9,
                estimatedAccessTime = now + 30_000L
            ))
        }
        
        return predictions
    }

    private suspend fun executePreload(predictions: List<PreloadPrediction>) {
        val startTime = System.currentTimeMillis()
        var loaded = 0
        
        predictions.chunked(PreloadConfig.PRELOAD_BATCH_SIZE).forEach { batch ->
            batch.forEach { prediction ->
                try {
                    val key = CacheKey.fromString(prediction.key)
                    val zone = when (prediction.priority) {
                        in 8..10 -> DataZone.HOT
                        in 5..7 -> DataZone.WARM
                        else -> DataZone.COLD
                    }
                    
                    preloadedKeys.add(prediction.key)
                    loaded++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to preload: ${prediction.key}", e)
                }
            }
            
            _preloadState.value = PreloadState.Preloading(
                predictions.size,
                loaded.toFloat() / predictions.size
            )
            
            delay(PreloadConfig.PRELOAD_DELAY_MS)
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Preloaded $loaded items in ${elapsed}ms")
        updateStats()
    }

    fun preloadByTypes(slot: Int, dataTypes: List<String>) {
        scope.launch {
            val predictions = dataTypes.flatMap { type ->
                generateTypePredictions(slot, type)
            }
            
            executePreload(predictions.sortedByDescending { it.probability }
                .take(PreloadConfig.MAX_PRELOAD_ITEMS))
        }
    }

    private fun generateTypePredictions(slot: Int, dataType: String): List<PreloadPrediction> {
        val now = System.currentTimeMillis()
        
        return accessPatterns.values
            .filter { it.dataType == dataType }
            .map { pattern ->
                PreloadPrediction(
                    key = pattern.key,
                    dataType = pattern.dataType,
                    probability = calculateTypeProbability(pattern),
                    priority = (calculateTypeProbability(pattern) * 10).toInt(),
                    estimatedAccessTime = now + pattern.avgInterval
                )
            }
    }

    private fun calculateTypeProbability(pattern: AccessPattern): Float {
        val recencyScore = calculateRecencyScore(pattern.lastAccessTime, System.currentTimeMillis())
        val frequencyScore = calculateFrequencyScore(pattern.accessCount)
        return (recencyScore + frequencyScore) / 2
    }

    fun getPredictions(): List<PreloadPrediction> {
        return predictions.values.sortedByDescending { it.probability }
    }

    fun getAccessPatterns(): List<AccessPattern> {
        return accessPatterns.values.sortedByDescending { it.accessCount }
    }

    fun isPreloaded(key: CacheKey): Boolean {
        return preloadedKeys.contains(key.toString())
    }
    
    fun markAsPreloaded(key: String) {
        preloadedKeys.add(key)
    }

    fun clearPreloadedKeys() {
        preloadedKeys.clear()
    }

    private fun updateStats() {
        val total = totalPredictions.get()
        val accurate = accuratePredictions.get()
        
        _stats.value = PreloadStats(
            totalPredictions = total,
            accuratePredictions = accurate,
            preloadedItems = preloadedKeys.size,
            cacheHitsFromPreload = cacheHitsFromPreload.get(),
            accuracy = if (total > 0) accurate.toFloat() / total else 0f,
            warmupStats = warmupManager.currentStats.value
        )
    }
    
    suspend fun performCacheWarmup(
        gameData: GameData? = null,
        disciples: List<Disciple>? = null,
        strategy: WarmupStrategy? = null
    ): WarmupResult {
        return warmupManager.performWarmup(gameData, disciples, strategy)
    }
    
    fun configureWarmup(config: WarmupConfig) {
        warmupManager.configure(config)
    }
    
    fun getWarmupStrategyPerformance(): Map<WarmupStrategy, Float> {
        return warmupManager.getStrategyPerformance()
    }

    fun shutdown() {
        isShuttingDown = true
        preloadJob?.cancel()
        analysisJob?.cancel()
        warmupManager.shutdown()
        scope.cancel()
        Log.i(TAG, "PredictivePreloader shutdown completed")
    }
}
