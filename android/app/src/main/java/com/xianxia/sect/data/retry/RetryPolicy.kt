package com.xianxia.sect.data.retry

import android.util.Log
import kotlin.random.Random
import kotlin.math.min
import kotlin.math.pow

data class RetryConfig(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 100L,
    val maxDelayMs: Long = 5000L,
    val backoffMultiplier: Double = 2.0,
    val jitterFactor: Double = 0.2,
    val retryableErrors: Set<Class<out Throwable>> = setOf(
        OutOfMemoryError::class.java,
        java.io.IOException::class.java,
        java.net.SocketException::class.java
    )
)

sealed class RetryResult<out T> {
    data class Success<T>(val data: T, val attempts: Int) : RetryResult<T>()
    data class Failure(val lastError: Throwable, val totalAttempts: Int) : RetryResult<Nothing>()
}

object RetryPolicy {
    private const val TAG = "RetryPolicy"

    suspend fun <T> executeWithRetry(
        config: RetryConfig = RetryConfig(),
        operation: suspend () -> T
    ): RetryResult<T> {
        var lastError: Throwable? = null

        repeat(config.maxRetries + 1) { attempt ->
            try {
                val result = operation()
                if (attempt > 0) {
                    Log.d(TAG, "Operation succeeded after $attempt attempt(s)")
                }
                return RetryResult.Success(result, attempts = attempt + 1)
            } catch (e: Throwable) {
                lastError = e

                // Check if this error type is retryable
                val isRetryable = config.retryableErrors.any { retryableClass ->
                    retryableClass.isInstance(e)
                }

                if (!isRetryable) {
                    Log.w(TAG, "Non-retryable error encountered on attempt ${attempt + 1}: ${e.javaClass.simpleName} - ${e.message}")
                    return RetryResult.Failure(e, totalAttempts = attempt + 1)
                }

                // If this is the last attempt, don't delay, just fail
                if (attempt >= config.maxRetries) {
                    Log.e(TAG, "Operation failed after ${attempt + 1} attempt(s), last error: ${e.javaClass.simpleName}")
                    return RetryResult.Failure(e, totalAttempts = attempt + 1)
                }

                // Calculate exponential backoff with jitter
                val baseDelay = config.initialDelayMs * config.backoffMultiplier.pow(attempt.toDouble())
                val cappedDelay = min(baseDelay.toLong(), config.maxDelayMs)

                // Apply jitter: delay = delay * (1 - jitterFactor + jitterFactor * 2 * random)
                val randomFactor = Random.nextDouble()
                val jitteredDelay = (cappedDelay * (1.0 - config.jitterFactor + config.jitterFactor * 2.0 * randomFactor)).toLong()

                Log.w(TAG, "Attempt ${attempt + 1}/${config.maxRetries + 1} failed: ${e.javaClass.simpleName}, " +
                        "retrying in ${jitteredDelay}ms...")

                kotlinx.coroutines.delay(jitteredDelay)
            }
        }

        // This should theoretically never be reached, but handle it for safety
        val error = lastError ?: IllegalStateException("Unknown error during retry")
        return RetryResult.Failure(error, totalAttempts = config.maxRetries + 1)
    }
}
