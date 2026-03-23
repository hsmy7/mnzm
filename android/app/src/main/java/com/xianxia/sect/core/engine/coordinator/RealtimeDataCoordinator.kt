package com.xianxia.sect.core.engine.coordinator

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.CircularBuffer
import com.xianxia.sect.core.util.ListenerManager
import com.xianxia.sect.core.util.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeDataCoordinator @Inject constructor(
    private val performanceMonitor: PerformanceMonitor
) {
    companion object {
        private const val TAG = "RealtimeDataCoordinator"
        private const val UPDATE_INTERVAL_MS = GameConfig.Performance.UPDATE_INTERVAL_MS
        private const val BATCH_THRESHOLD = GameConfig.Performance.BATCH_THRESHOLD
        private const val MAX_BATCH_SAMPLES = GameConfig.Performance.MAX_BATCH_SAMPLES
    }
    
    data class RealtimeUpdateBatch(
        val timestamp: Long = System.currentTimeMillis(),
        val cultivationUpdates: Map<String, Double> = emptyMap(),
        val equipmentNurtureUpdates: Map<String, Double> = emptyMap(),
        val manualProficiencyUpdates: Map<String, Double> = emptyMap()
    )
    
    data class RealtimeDataStats(
        val totalUpdates: Long,
        val averageBatchSize: Double,
        val averageUpdateTimeMs: Double,
        val maxUpdateTimeMs: Long,
        val lastUpdateTimeMs: Long
    )
    
    interface RealtimeDataListener {
        fun onDataUpdated(batch: RealtimeUpdateBatch)
    }
    
    private val listeners = ListenerManager<RealtimeDataListener>(TAG)
    
    private val scope = CoroutineScope(Dispatchers.Default)
    private var updateJob: Job? = null
    @Volatile private var isRunning = false
    
    private var totalUpdates = 0L
    private val batchSizes = CircularBuffer<Int>(MAX_BATCH_SAMPLES)
    private val updateTimes = CircularBuffer<Long>(MAX_BATCH_SAMPLES)
    @Volatile private var maxUpdateTime = 0L
    @Volatile private var lastUpdateTime = 0L
    
    private val pendingCultivationUpdates = mutableMapOf<String, Double>()
    private val pendingEquipmentUpdates = mutableMapOf<String, Double>()
    private val pendingManualUpdates = mutableMapOf<String, Double>()
    
    private val updateLock = Any()
    
    @Volatile
    private var onBatchReady: ((RealtimeUpdateBatch) -> Unit)? = null
    
    fun setOnBatchReadyListener(listener: (RealtimeUpdateBatch) -> Unit) {
        onBatchReady = listener
    }
    
    fun addListener(listener: RealtimeDataListener) = listeners.add(listener)
    
    fun removeListener(listener: RealtimeDataListener) = listeners.remove(listener)
    
    fun start() {
        if (isRunning) return
        isRunning = true
        
        updateJob = scope.launch {
            Log.i(TAG, "Realtime data coordinator started")
            
            while (isActive && isRunning) {
                try {
                    processBatch()
                    delay(UPDATE_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in realtime update", e)
                    delay(100)
                }
            }
            
            Log.i(TAG, "Realtime data coordinator stopped")
        }
    }
    
    fun stop() {
        isRunning = false
        updateJob?.cancel()
        updateJob = null
        Log.i(TAG, "Realtime data coordinator stopped")
    }
    
    fun queueCultivationUpdate(discipleId: String, cultivation: Double) {
        synchronized(updateLock) {
            pendingCultivationUpdates[discipleId] = cultivation
        }
    }
    
    fun queueEquipmentUpdate(equipmentId: String, progress: Double) {
        synchronized(updateLock) {
            pendingEquipmentUpdates[equipmentId] = progress
        }
    }
    
    fun queueManualUpdate(key: String, proficiency: Double) {
        synchronized(updateLock) {
            pendingManualUpdates[key] = proficiency
        }
    }
    
    fun queueBatchUpdate(
        cultivationUpdates: Map<String, Double>,
        equipmentUpdates: Map<String, Double>,
        manualUpdates: Map<String, Double>
    ) {
        synchronized(updateLock) {
            pendingCultivationUpdates.putAll(cultivationUpdates)
            pendingEquipmentUpdates.putAll(equipmentUpdates)
            pendingManualUpdates.putAll(manualUpdates)
        }
    }
    
    private fun processBatch() {
        val startTime = System.currentTimeMillis()
        
        val batch = synchronized(updateLock) {
            if (pendingCultivationUpdates.isEmpty() && 
                pendingEquipmentUpdates.isEmpty() && 
                pendingManualUpdates.isEmpty()) {
                return
            }
            
            val batch = RealtimeUpdateBatch(
                timestamp = System.currentTimeMillis(),
                cultivationUpdates = pendingCultivationUpdates.toMap(),
                equipmentNurtureUpdates = pendingEquipmentUpdates.toMap(),
                manualProficiencyUpdates = pendingManualUpdates.toMap()
            )
            
            pendingCultivationUpdates.clear()
            pendingEquipmentUpdates.clear()
            pendingManualUpdates.clear()
            
            batch
        }
        
        totalUpdates++
        val batchSize = batch.cultivationUpdates.size + 
                        batch.equipmentNurtureUpdates.size + 
                        batch.manualProficiencyUpdates.size
        batchSizes.add(batchSize)
        
        val updateTime = System.currentTimeMillis() - startTime
        updateTimes.add(updateTime)
        maxUpdateTime = maxOf(maxUpdateTime, updateTime)
        lastUpdateTime = updateTime
        
        onBatchReady?.invoke(batch)
        notifyDataUpdated(batch)
    }
    
    fun forceFlush() {
        processBatch()
    }
    
    fun getStats(): RealtimeDataStats {
        val avgBatchSize = batchSizes.average()
        val avgUpdateTime = updateTimes.average()
        
        return RealtimeDataStats(
            totalUpdates = totalUpdates,
            averageBatchSize = avgBatchSize,
            averageUpdateTimeMs = avgUpdateTime,
            maxUpdateTimeMs = maxUpdateTime,
            lastUpdateTimeMs = lastUpdateTime
        )
    }
    
    fun getPendingCount(): Int {
        return synchronized(updateLock) {
            pendingCultivationUpdates.size + 
            pendingEquipmentUpdates.size + 
            pendingManualUpdates.size
        }
    }
    
    private fun notifyDataUpdated(batch: RealtimeUpdateBatch) = 
        listeners.notify { it.onDataUpdated(batch) }
    
    fun cleanup() {
        stop()
        listeners.clear()
        onBatchReady = null
        synchronized(updateLock) {
            pendingCultivationUpdates.clear()
            pendingEquipmentUpdates.clear()
            pendingManualUpdates.clear()
        }
        batchSizes.clear()
        updateTimes.clear()
    }
}
