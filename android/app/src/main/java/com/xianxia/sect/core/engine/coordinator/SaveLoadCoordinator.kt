package com.xianxia.sect.core.engine.coordinator

import android.util.Log
import com.xianxia.sect.core.util.ListenerManager
import com.xianxia.sect.core.util.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveLoadCoordinator @Inject constructor(
    private val performanceMonitor: PerformanceMonitor
) {
    companion object {
        private const val TAG = "SaveLoadCoordinator"
        private const val SAVE_TIMEOUT_MS = 5000L
        private const val LOAD_TIMEOUT_MS = 10000L
        private const val SLOW_SAVE_THRESHOLD_MS = 500L
        private const val SLOW_LOAD_THRESHOLD_MS = 2000L
    }
    
    data class SaveLoadContext(
        val operationType: OperationType,
        val saveSlot: Int,
        val dataSize: Long = 0
    )
    
    data class SaveLoadResult(
        val success: Boolean,
        val durationMs: Long,
        val dataSize: Long = 0,
        val errorMessage: String? = null,
        val timedOut: Boolean = false
    )
    
    enum class OperationType {
        QUICK_SAVE,
        MANUAL_SAVE,
        AUTO_SAVE,
        LOAD,
        EMERGENCY_SAVE
    }
    
    interface SaveLoadListener {
        fun onOperationStart(context: SaveLoadContext)
        fun onOperationEnd(context: SaveLoadContext, result: SaveLoadResult)
        fun onProgressUpdate(context: SaveLoadContext, progress: Float)
    }
    
    private val listeners = ListenerManager<SaveLoadListener>(TAG)
    
    fun addListener(listener: SaveLoadListener) = listeners.add(listener)
    
    fun removeListener(listener: SaveLoadListener) = listeners.remove(listener)
    
    suspend fun <T> executeSaveWithMonitoring(
        operationType: OperationType,
        saveSlot: Int,
        saveOperation: suspend () -> T
    ): SaveLoadResult {
        val startTime = System.currentTimeMillis()
        val context = SaveLoadContext(operationType, saveSlot)
        
        notifyOperationStart(context)
        
        return try {
            performanceMonitor.measureOperation("save_${operationType.name.lowercase()}") {
                withContext(Dispatchers.IO) {
                    saveOperation()
                }
            }
            
            val durationMs = System.currentTimeMillis() - startTime
            
            if (durationMs > SLOW_SAVE_THRESHOLD_MS) {
                Log.w(TAG, "Slow save operation: $operationType took ${durationMs}ms")
            }
            
            val result = SaveLoadResult(
                success = true,
                durationMs = durationMs
            )
            
            notifyOperationEnd(context, result)
            result
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "Save operation failed: $operationType", e)
            
            val result = SaveLoadResult(
                success = false,
                durationMs = durationMs,
                errorMessage = e.message
            )
            
            notifyOperationEnd(context, result)
            result
        }
    }
    
    suspend fun <T> executeLoadWithMonitoring(
        saveSlot: Int,
        loadOperation: suspend () -> T
    ): Pair<SaveLoadResult, T?> {
        val startTime = System.currentTimeMillis()
        val context = SaveLoadContext(OperationType.LOAD, saveSlot)
        
        notifyOperationStart(context)
        notifyProgressUpdate(context, 0.1f)
        
        return try {
            notifyProgressUpdate(context, 0.3f)
            
            val data = performanceMonitor.measureOperation("load_game") {
                withContext(Dispatchers.IO) {
                    loadOperation()
                }
            }
            
            notifyProgressUpdate(context, 0.9f)
            
            val durationMs = System.currentTimeMillis() - startTime
            
            if (durationMs > SLOW_LOAD_THRESHOLD_MS) {
                Log.w(TAG, "Slow load operation took ${durationMs}ms")
            }
            
            val result = SaveLoadResult(
                success = true,
                durationMs = durationMs
            )
            
            notifyProgressUpdate(context, 1.0f)
            notifyOperationEnd(context, result)
            
            Pair(result, data)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "Load operation failed", e)
            
            val result = SaveLoadResult(
                success = false,
                durationMs = durationMs,
                errorMessage = e.message
            )
            
            notifyOperationEnd(context, result)
            Pair(result, null)
        }
    }
    
    inline fun <T> executeEmergencySave(
        saveOperation: () -> T
    ): SaveLoadResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            saveOperation()
            
            val durationMs = System.currentTimeMillis() - startTime
            SaveLoadResult(success = true, durationMs = durationMs)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            Log.e("SaveLoadCoordinator", "Emergency save failed", e)
            SaveLoadResult(
                success = false,
                durationMs = durationMs,
                errorMessage = e.message
            )
        }
    }
    
    fun validateSaveData(data: Any?, expectedVersion: Int? = null): ValidationResult {
        if (data == null) {
            return ValidationResult.NULL_DATA
        }
        
        return try {
            when (data) {
                is Map<*, *> -> {
                    if (data.isEmpty()) ValidationResult.EMPTY_DATA
                    else {
                        if (expectedVersion != null) {
                            val version = data["version"] as? Int
                            if (version != expectedVersion) {
                                ValidationResult.VERSION_MISMATCH
                            } else ValidationResult.VALID
                        } else ValidationResult.VALID
                    }
                }
                is List<*> -> {
                    if (data.isEmpty()) ValidationResult.EMPTY_DATA else ValidationResult.VALID
                }
                else -> ValidationResult.VALID
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save data validation failed", e)
            ValidationResult.VALIDATION_ERROR
        }
    }
    
    enum class ValidationResult {
        VALID,
        NULL_DATA,
        EMPTY_DATA,
        VERSION_MISMATCH,
        VALIDATION_ERROR
    }
    
    private fun notifyOperationStart(context: SaveLoadContext) = 
        listeners.notify { it.onOperationStart(context) }
    
    private fun notifyOperationEnd(context: SaveLoadContext, result: SaveLoadResult) = 
        listeners.notify { it.onOperationEnd(context, result) }
    
    private fun notifyProgressUpdate(context: SaveLoadContext, progress: Float) = 
        listeners.notify { it.onProgressUpdate(context, progress) }
    
    fun cleanup() {
        listeners.clear()
    }
}
