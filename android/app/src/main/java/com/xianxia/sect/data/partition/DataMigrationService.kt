package com.xianxia.sect.data.partition

import android.util.Log
import com.xianxia.sect.data.cache.CacheKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DataMigrationService(
    private val partitionManager: DataPartitionManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "DataMigrationService"
        private const val DEFAULT_BATCH_SIZE = 100
        private const val DEFAULT_DELAY_MS = 10L
    }

    private val isMigrating = AtomicBoolean(false)
    private val migrationProgress = AtomicInteger(0)
    private val migrationTotal = AtomicInteger(0)
    private val migratedCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)

    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migrationState: StateFlow<MigrationState> = _migrationState.asStateFlow()

    sealed class MigrationState {
        object Idle : MigrationState()
        data class InProgress(val progress: Int, val total: Int) : MigrationState()
        data class Completed(val migrated: Long, val errors: Long) : MigrationState()
        data class Error(val message: String) : MigrationState()
    }

    data class MigrationConfig(
        val batchSize: Int = DEFAULT_BATCH_SIZE,
        val delayBetweenBatches: Long = DEFAULT_DELAY_MS,
        val enableCompression: Boolean = true,
        val validateAfterMigration: Boolean = true,
        val preserveOriginalData: Boolean = true
    )

    data class MigrationResult(
        val totalItems: Int,
        val migratedItems: Int,
        val errors: Int,
        val durationMs: Long,
        val zoneDistribution: Map<DataZone, Int>
    )

    suspend fun migrateToZone(
        keys: List<CacheKey>,
        targetZone: DataZone,
        loader: suspend (CacheKey) -> Any?,
        config: MigrationConfig = MigrationConfig()
    ): MigrationResult {
        if (!isMigrating.compareAndSet(false, true)) {
            Log.w(TAG, "Migration already in progress")
            return MigrationResult(0, 0, 0, 0, emptyMap())
        }

        val startTime = System.currentTimeMillis()
        migrationTotal.set(keys.size)
        migrationProgress.set(0)
        migratedCount.set(0)
        errorCount.set(0)

        _migrationState.value = MigrationState.InProgress(0, keys.size)

        try {
            keys.chunked(config.batchSize).forEach { batch ->
                if (!isMigrating.get()) {
                    Log.i(TAG, "Migration cancelled")
                    return MigrationResult(
                        keys.size, 
                        migratedCount.get().toInt(), 
                        errorCount.get().toInt(),
                        System.currentTimeMillis() - startTime,
                        partitionManager.getZoneDistribution()
                    )
                }

                processMigrationBatch(batch, targetZone, loader, config)
                
                migrationProgress.addAndGet(batch.size)
                _migrationState.value = MigrationState.InProgress(
                    migrationProgress.get(), 
                    migrationTotal.get()
                )

                if (config.delayBetweenBatches > 0) {
                    delay(config.delayBetweenBatches)
                }
            }

            val result = MigrationResult(
                totalItems = keys.size,
                migratedItems = migratedCount.get().toInt(),
                errors = errorCount.get().toInt(),
                durationMs = System.currentTimeMillis() - startTime,
                zoneDistribution = partitionManager.getZoneDistribution()
            )

            _migrationState.value = MigrationState.Completed(
                migratedCount.get(),
                errorCount.get()
            )

            Log.i(TAG, "Migration completed: $result")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            _migrationState.value = MigrationState.Error(e.message ?: "Unknown error")
            return MigrationResult(
                keys.size,
                migratedCount.get().toInt(),
                errorCount.get().toInt(),
                System.currentTimeMillis() - startTime,
                emptyMap()
            )
        } finally {
            isMigrating.set(false)
        }
    }

    private suspend fun processMigrationBatch(
        batch: List<CacheKey>,
        targetZone: DataZone,
        loader: suspend (CacheKey) -> Any?,
        config: MigrationConfig
    ) {
        batch.forEach { key ->
            try {
                val value = loader(key)
                if (value != null) {
                    val success = partitionManager.put(key, value, targetZone)
                    if (success) {
                        migratedCount.incrementAndGet()
                    } else {
                        errorCount.incrementAndGet()
                        Log.w(TAG, "Failed to put $key to $targetZone")
                    }
                } else {
                    Log.d(TAG, "No data for key $key, skipping")
                }
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                Log.e(TAG, "Error migrating $key", e)
            }
        }
    }

    suspend fun migrateByDataType(
        dataType: DataType,
        keyGenerator: (Int) -> CacheKey,
        count: Int,
        loader: suspend (CacheKey) -> Any?,
        config: MigrationConfig = MigrationConfig()
    ): MigrationResult {
        val keys = (0 until count).map { keyGenerator(it) }
        val targetZone = PartitionStrategy.determineZone(dataType)
        
        Log.i(TAG, "Migrating ${dataType.displayName} to $targetZone")
        return migrateToZone(keys, targetZone, loader, config)
    }

    suspend fun rebalanceZones(
        config: MigrationConfig = MigrationConfig()
    ): RebalanceResult {
        if (!isMigrating.compareAndSet(false, true)) {
            return RebalanceResult(0, 0, 0, 0)
        }

        val startTime = System.currentTimeMillis()
        var promoted = 0
        var demoted = 0
        var errors = 0

        try {
            _migrationState.value = MigrationState.InProgress(0, 100)

            val hotStats = partitionManager.getZoneStats(DataZone.HOT)
            val warmStats = partitionManager.getZoneStats(DataZone.WARM)
            val coldStats = partitionManager.getZoneStats(DataZone.COLD)

            val hotZone = partitionManager.hotZoneManager()
            val warmZone = partitionManager.warmZoneManager()
            val coldZone = partitionManager.coldZoneManager()

            val inactiveHotKeys = hotZone.getInactiveKeys(3600_000L * 2)
            for (key in inactiveHotKeys) {
                try {
                    val cacheKey = CacheKey.fromString(key)
                    val freq = hotZone.getAccessFrequency(cacheKey)
                    if (freq == AccessFrequency.RARE || freq == AccessFrequency.LOW) {
                        if (partitionManager.demote(cacheKey)) {
                            demoted++
                        }
                    }
                } catch (e: Exception) {
                    errors++
                    Log.e(TAG, "Error demoting $key", e)
                }
            }

            val coldPromotableKeys = coldZone.getPromotableKeys(3600_000L)
            for (key in coldPromotableKeys) {
                try {
                    val cacheKey = CacheKey.fromString(key)
                    if (partitionManager.promote(cacheKey)) {
                        promoted++
                    }
                } catch (e: Exception) {
                    errors++
                    Log.e(TAG, "Error promoting $key", e)
                }
            }

            val warmColdKeys = warmZone.getColdDataKeys(3600_000L * 24)
            for (key in warmColdKeys) {
                try {
                    val cacheKey = CacheKey.fromString(key)
                    val freq = warmZone.getAccessFrequency(cacheKey)
                    if (freq == AccessFrequency.RARE) {
                        if (partitionManager.demote(cacheKey)) {
                            demoted++
                        }
                    }
                } catch (e: Exception) {
                    errors++
                    Log.e(TAG, "Error demoting $key from warm", e)
                }
            }

            val warmActiveKeys = warmZone.getActiveKeys(3600_000L)
            for (key in warmActiveKeys) {
                try {
                    val cacheKey = CacheKey.fromString(key)
                    val freq = warmZone.getAccessFrequency(cacheKey)
                    if (freq == AccessFrequency.HIGH) {
                        if (partitionManager.promote(cacheKey)) {
                            promoted++
                        }
                    }
                } catch (e: Exception) {
                    errors++
                    Log.e(TAG, "Error promoting $key from warm", e)
                }
            }

            _migrationState.value = MigrationState.Completed(promoted.toLong() + demoted.toLong(), errors.toLong())

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Rebalance completed: promoted=$promoted, demoted=$demoted, errors=$errors, duration=${duration}ms")

            return RebalanceResult(promoted, demoted, errors, duration)

        } catch (e: Exception) {
            Log.e(TAG, "Rebalance failed", e)
            _migrationState.value = MigrationState.Error(e.message ?: "Unknown error")
            return RebalanceResult(promoted, demoted, errors, System.currentTimeMillis() - startTime)
        } finally {
            isMigrating.set(false)
        }
    }

    suspend fun migrateFromLegacy(
        legacyData: Map<String, Any>,
        keyMapper: (String) -> CacheKey,
        config: MigrationConfig = MigrationConfig()
    ): MigrationResult {
        val entries = legacyData.entries.toList()
        val keys = entries.map { keyMapper(it.key) }
        
        suspend fun loader(key: CacheKey): Any? {
            val originalKey = entries.find { keyMapper(it.key) == key }?.key
            return originalKey?.let { legacyData[it] }
        }

        return migrateToZone(keys, DataZone.WARM, ::loader, config)
    }

    fun cancelMigration() {
        if (isMigrating.get()) {
            isMigrating.set(false)
            Log.i(TAG, "Migration cancelled")
        }
    }

    fun getMigrationProgress(): Pair<Int, Int> {
        return Pair(migrationProgress.get(), migrationTotal.get())
    }

    fun isMigrating(): Boolean = isMigrating.get()

    data class RebalanceResult(
        val promotedCount: Int,
        val demotedCount: Int,
        val errorCount: Int,
        val durationMs: Long
    ) {
        val totalOperations: Int
            get() = promotedCount + demotedCount
    }

    fun shutdown() {
        cancelMigration()
        scope.cancel()
        Log.i(TAG, "DataMigrationService shutdown")
    }
}

data class MigrationPlan(
    val id: String,
    val sourceZone: DataZone?,
    val targetZone: DataZone,
    val dataTypes: List<DataType>,
    val estimatedItems: Int,
    val priority: MigrationPriority = MigrationPriority.NORMAL
)

enum class MigrationPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

data class MigrationReport(
    val planId: String,
    val startTime: Long,
    val endTime: Long,
    val totalItems: Int,
    val successItems: Int,
    val failedItems: Int,
    val errors: List<MigrationError>
)

data class MigrationError(
    val key: String,
    val error: String,
    val timestamp: Long = System.currentTimeMillis()
)
