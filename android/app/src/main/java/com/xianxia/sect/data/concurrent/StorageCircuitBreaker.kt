package com.xianxia.sect.data.concurrent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val recoveryTimeoutMs: Long = 30_000L,
    val halfOpenMaxAttempts: Int = 3,
    val successThresholdToClose: Int = 3,
    val slidingWindowSize: Int = 20
)

data class CircuitBreakerStats(
    val operation: String,
    val state: CircuitState,
    val failureCount: Int,
    val successCount: Int,
    val totalCalls: Long,
    val lastFailureTime: Long,
    val lastStateChangeTime: Long,
    val rejectedCount: Long
)

class OperationCircuitBreaker(
    private val operation: String,
    private val config: CircuitBreakerConfig
) {
    private var state = CircuitState.CLOSED
    private var failureCount = 0
    private var successCount = 0
    private var halfOpenAttempts = 0
    private var consecutiveSuccesses = 0
    private val totalCalls = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    private var lastFailureTime = 0L
    private var lastStateChangeTime = System.currentTimeMillis()

    private val slidingWindow = ArrayDeque<Long>(config.slidingWindowSize)
    private val _stateFlow = MutableStateFlow(CircuitState.CLOSED)
    val stateFlow: StateFlow<CircuitState> = _stateFlow.asStateFlow()

    @Synchronized
    fun allowRequest(): Boolean {
        totalCalls.incrementAndGet()

        when (state) {
            CircuitState.CLOSED -> return true
            CircuitState.OPEN -> {
                val elapsed = System.currentTimeMillis() - lastFailureTime
                if (elapsed >= config.recoveryTimeoutMs) {
                    transitionTo(CircuitState.HALF_OPEN)
                    halfOpenAttempts = 0
                    return true
                }
                rejectedCount.incrementAndGet()
                return false
            }
            CircuitState.HALF_OPEN -> {
                if (halfOpenAttempts < config.halfOpenMaxAttempts) {
                    halfOpenAttempts++
                    return true
                }
                rejectedCount.incrementAndGet()
                return false
            }
        }
    }

    @Synchronized
    fun recordSuccess() {
        recordInWindow(true)

        when (state) {
            CircuitState.CLOSED -> {
                failureCount = 0
            }
            CircuitState.HALF_OPEN -> {
                consecutiveSuccesses++
                if (consecutiveSuccesses >= config.successThresholdToClose) {
                    transitionTo(CircuitState.CLOSED)
                    failureCount = 0
                    consecutiveSuccesses = 0
                }
            }
            CircuitState.OPEN -> {}
        }
    }

    @Synchronized
    fun recordFailure() {
        recordInWindow(false)
        lastFailureTime = System.currentTimeMillis()
        failureCount++
        consecutiveSuccesses = 0

        when (state) {
            CircuitState.CLOSED -> {
                val recentFailures = countRecentFailures()
                if (recentFailures >= config.failureThreshold) {
                    transitionTo(CircuitState.OPEN)
                }
            }
            CircuitState.HALF_OPEN -> {
                transitionTo(CircuitState.OPEN)
            }
            CircuitState.OPEN -> {}
        }
    }

    private fun recordInWindow(success: Boolean) {
        val now = System.currentTimeMillis()
        slidingWindow.addLast(if (success) now else -now)
        while (slidingWindow.size > config.slidingWindowSize) {
            slidingWindow.removeFirst()
        }
    }

    private fun countRecentFailures(): Int {
        return slidingWindow.count { it < 0 }
    }

    private fun transitionTo(newState: CircuitState) {
        val oldState = state
        state = newState
        lastStateChangeTime = System.currentTimeMillis()
        _stateFlow.value = newState
        Log.w(TAG, "Circuit breaker [$operation]: $oldState -> $newState")
    }

    @Synchronized
    fun getStats(): CircuitBreakerStats {
        return CircuitBreakerStats(
            operation = operation,
            state = state,
            failureCount = failureCount,
            successCount = successCount,
            totalCalls = totalCalls.get(),
            lastFailureTime = lastFailureTime,
            lastStateChangeTime = lastStateChangeTime,
            rejectedCount = rejectedCount.get()
        )
    }

    @Synchronized
    fun reset() {
        state = CircuitState.CLOSED
        failureCount = 0
        successCount = 0
        halfOpenAttempts = 0
        consecutiveSuccesses = 0
        slidingWindow.clear()
        _stateFlow.value = CircuitState.CLOSED
        lastStateChangeTime = System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "OpCircuitBreaker"
    }
}

@Singleton
class StorageCircuitBreaker @Inject constructor() {

    private val breakers = ConcurrentHashMap<String, OperationCircuitBreaker>()

    private val defaultConfigs = mapOf(
        "save" to CircuitBreakerConfig(
            failureThreshold = 5,
            recoveryTimeoutMs = 30_000L,
            halfOpenMaxAttempts = 2,
            successThresholdToClose = 3
        ),
        "load" to CircuitBreakerConfig(
            failureThreshold = 8,
            recoveryTimeoutMs = 15_000L,
            halfOpenMaxAttempts = 3,
            successThresholdToClose = 2
        ),
        "cache" to CircuitBreakerConfig(
            failureThreshold = 10,
            recoveryTimeoutMs = 10_000L,
            halfOpenMaxAttempts = 5,
            successThresholdToClose = 2
        ),
        "wal" to CircuitBreakerConfig(
            failureThreshold = 3,
            recoveryTimeoutMs = 60_000L,
            halfOpenMaxAttempts = 1,
            successThresholdToClose = 5
        ),
        "incremental" to CircuitBreakerConfig(
            failureThreshold = 5,
            recoveryTimeoutMs = 30_000L,
            halfOpenMaxAttempts = 2,
            successThresholdToClose = 3
        ),
        "recovery" to CircuitBreakerConfig(
            failureThreshold = 3,
            recoveryTimeoutMs = 120_000L,
            halfOpenMaxAttempts = 1,
            successThresholdToClose = 5
        ),
        "pruning" to CircuitBreakerConfig(
            failureThreshold = 10,
            recoveryTimeoutMs = 60_000L,
            halfOpenMaxAttempts = 3,
            successThresholdToClose = 2
        )
    )

    fun allowRequest(operation: String): Boolean {
        return getOrCreateBreaker(operation).allowRequest()
    }

    fun recordSuccess(operation: String) {
        getOrCreateBreaker(operation).recordSuccess()
    }

    fun recordFailure(operation: String) {
        getOrCreateBreaker(operation).recordFailure()
    }

    fun getState(operation: String): CircuitState {
        return getOrCreateBreaker(operation).getStats().state
    }

    fun getStats(operation: String): CircuitBreakerStats {
        return getOrCreateBreaker(operation).getStats()
    }

    fun getAllStats(): Map<String, CircuitBreakerStats> {
        return breakers.mapValues { it.value.getStats() }
    }

    fun reset(operation: String) {
        breakers[operation]?.reset()
    }

    fun resetAll() {
        breakers.values.forEach { it.reset() }
    }

    private fun getOrCreateBreaker(operation: String): OperationCircuitBreaker {
        return breakers.getOrPut(operation) {
            val config = defaultConfigs[operation] ?: CircuitBreakerConfig()
            OperationCircuitBreaker(operation, config)
        }
    }

    companion object {
        private const val TAG = "StorageCircuitBreaker"
    }
}
