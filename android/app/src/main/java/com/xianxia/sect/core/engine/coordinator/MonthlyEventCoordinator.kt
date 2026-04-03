package com.xianxia.sect.core.engine.coordinator

import android.util.Log
import com.xianxia.sect.core.util.ListenerManager
import com.xianxia.sect.core.util.PerformanceMonitor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonthlyEventCoordinator @Inject constructor(
    private val performanceMonitor: PerformanceMonitor
) {
    companion object {
        private const val TAG = "MonthlyEventCoordinator"
        private const val SLOW_OPERATION_THRESHOLD_MS = 50L
    }
    
    data class MonthlyEventContext(
        val year: Int,
        val month: Int,
        val discipleCount: Int,
        val spiritStones: Long,
        val sectName: String
    )
    
    data class MonthlyEventResult(
        val operationName: String,
        val success: Boolean,
        val durationMs: Long,
        val errorMessage: String? = null
    )
    
    private val eventListeners = ListenerManager<MonthlyEventListener>(TAG)
    
    interface MonthlyEventListener {
        fun onEventStart(context: MonthlyEventContext, operationName: String)
        fun onEventEnd(context: MonthlyEventContext, result: MonthlyEventResult)
        fun onEventError(context: MonthlyEventContext, operationName: String, error: Throwable)
    }
    
    fun addListener(listener: MonthlyEventListener) = eventListeners.add(listener)
    
    fun removeListener(listener: MonthlyEventListener) = eventListeners.remove(listener)
    
    fun <T> executeWithMonitoring(
        operationName: String,
        context: MonthlyEventContext,
        operation: () -> T
    ): Pair<T, Long> {
        val startTime = performanceMonitor.recordMonthlyEventStart(
            operationName,
            context.year,
            context.month,
            context.discipleCount,
            context.spiritStones
        )
        
        notifyEventStart(context, operationName)
        
        try {
            val result = operation()
            
            performanceMonitor.recordMonthlyEventEnd(
                operationName,
                startTime,
                context.year,
                context.month,
                context.discipleCount,
                context.spiritStones,
                true
            )
            
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            notifyEventEnd(context, MonthlyEventResult(operationName, true, durationMs))
            
            return Pair(result, durationMs)
        } catch (e: Exception) {
            performanceMonitor.recordMonthlyEventEnd(
                operationName,
                startTime,
                context.year,
                context.month,
                context.discipleCount,
                context.spiritStones,
                false,
                e.message
            )
            
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            notifyEventEnd(context, MonthlyEventResult(operationName, false, durationMs, e.message))
            notifyEventError(context, operationName, e)
            
            Log.e(TAG, "Error in $operationName - Context: $context", e)
            throw e
        }
    }
    
    fun executeWithExceptionHandling(
        operationName: String,
        context: MonthlyEventContext,
        operation: () -> Unit
    ) {
        try {
            executeWithMonitoring(operationName, context, operation)
        } catch (e: Exception) {
            Log.e(TAG, "Error in $operationName - " +
                "Game: Year=${context.year}, Month=${context.month}, " +
                "Sect=${context.sectName}, Disciples=${context.discipleCount}", e)
        }
    }
    
    fun executeBatch(
        operations: List<Pair<String, () -> Unit>>,
        context: MonthlyEventContext
    ): List<MonthlyEventResult> {
        val results = mutableListOf<MonthlyEventResult>()
        
        operations.forEach { (name, operation) ->
            val result = try {
                val (_, durationMs) = executeWithMonitoring(name, context) {
                    operation()
                }
                MonthlyEventResult(name, true, durationMs)
            } catch (e: Exception) {
                Log.e(TAG, "Error in $name - " +
                    "Game: Year=${context.year}, Month=${context.month}, " +
                    "Sect=${context.sectName}, Disciples=${context.discipleCount}", e)
                MonthlyEventResult(name, false, 0, e.message)
            }
            results.add(result)
        }
        
        return results
    }
    
    fun getPerformanceReport(): String {
        return performanceMonitor.getMonthlyEventPerformanceReport()
    }
    
    fun logPerformanceStats() {
        performanceMonitor.logMonthlyEventStats()
    }
    
    private fun notifyEventStart(context: MonthlyEventContext, operationName: String) = 
        eventListeners.notify { it.onEventStart(context, operationName) }
    
    private fun notifyEventEnd(context: MonthlyEventContext, result: MonthlyEventResult) = 
        eventListeners.notify { it.onEventEnd(context, result) }
    
    private fun notifyEventError(context: MonthlyEventContext, operationName: String, error: Throwable) = 
        eventListeners.notify { it.onEventError(context, operationName, error) }
    
    fun cleanup() {
        eventListeners.clear()
    }
}
