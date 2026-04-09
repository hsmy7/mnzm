package com.xianxia.sect.data.retry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RetryConfigTest {

    @Test
    fun `default RetryConfig has sensible values`() {
        val config = RetryConfig()
        assertEquals(3, config.maxRetries)
        assertEquals(100L, config.initialDelayMs)
        assertEquals(5000L, config.maxDelayMs)
        assertEquals(2.0, config.backoffMultiplier, 0.001)
        assertEquals(0.2, config.jitterFactor, 0.001)
    }

    @Test
    fun `RetryConfig retryableErrors includes OOM and IO`() {
        val config = RetryConfig()
        assertTrue(config.retryableErrors.contains(OutOfMemoryError::class.java))
        assertTrue(config.retryableErrors.contains(java.io.IOException::class.java))
    }

    @Test
    fun `RetryConfig custom values`() {
        val config = RetryConfig(
            maxRetries = 5,
            initialDelayMs = 200L,
            maxDelayMs = 10000L,
            backoffMultiplier = 3.0,
            jitterFactor = 0.5
        )
        assertEquals(5, config.maxRetries)
        assertEquals(200L, config.initialDelayMs)
        assertEquals(10000L, config.maxDelayMs)
        assertEquals(3.0, config.backoffMultiplier, 0.001)
        assertEquals(0.5, config.jitterFactor, 0.001)
    }
}

class RetryResultTest {

    @Test
    fun `Success holds data and attempts`() {
        val result = RetryResult.Success("data", attempts = 3)
        assertEquals("data", result.data)
        assertEquals(3, result.attempts)
    }

    @Test
    fun `Failure holds error and totalAttempts`() {
        val error = RuntimeException("test error")
        val result = RetryResult.Failure(error, totalAttempts = 2)
        assertEquals(error, result.lastError)
        assertEquals(2, result.totalAttempts)
    }

    @Test
    fun `Success with different types`() {
        val intResult = RetryResult.Success(42, attempts = 1)
        assertEquals(42, intResult.data)

        val listResult = RetryResult.Success(listOf(1, 2, 3), attempts = 1)
        assertEquals(listOf(1, 2, 3), listResult.data)
    }
}

class RetryPolicyTest {

    @Test
    fun `executeWithRetry - returns Success on first attempt when operation succeeds`() = runTest {
        val result = RetryPolicy.executeWithRetry {
            "success"
        }
        assertTrue(result is RetryResult.Success)
        assertEquals("success", (result as RetryResult.Success).data)
        assertEquals(1, result.attempts)
    }

    @Test
    fun `executeWithRetry - returns Success after retrying retryable error`() = runTest {
        var attempt = 0
        val config = RetryConfig(
            maxRetries = 3,
            initialDelayMs = 1L,
            maxDelayMs = 10L,
            backoffMultiplier = 1.0,
            jitterFactor = 0.0
        )
        val result = RetryPolicy.executeWithRetry(config) {
            attempt++
            if (attempt < 3) throw java.io.IOException("retryable")
            "recovered"
        }
        assertTrue(result is RetryResult.Success)
        assertEquals("recovered", (result as RetryResult.Success).data)
        assertEquals(3, result.attempts)
    }

    @Test
    fun `executeWithRetry - returns Failure for non-retryable error`() = runTest {
        val config = RetryConfig(
            maxRetries = 3,
            initialDelayMs = 1L,
            retryableErrors = setOf(java.io.IOException::class.java)
        )
        val result = RetryPolicy.executeWithRetry(config) {
            throw IllegalArgumentException("not retryable")
        }
        assertTrue(result is RetryResult.Failure)
        assertEquals(1, (result as RetryResult.Failure).totalAttempts)
        assertTrue(result.lastError is IllegalArgumentException)
    }

    @Test
    fun `executeWithRetry - returns Failure after exhausting retries`() = runTest {
        val config = RetryConfig(
            maxRetries = 2,
            initialDelayMs = 1L,
            maxDelayMs = 10L,
            backoffMultiplier = 1.0,
            jitterFactor = 0.0,
            retryableErrors = setOf(java.io.IOException::class.java)
        )
        val result = RetryPolicy.executeWithRetry(config) {
            throw java.io.IOException("always fails")
        }
        assertTrue(result is RetryResult.Failure)
        assertEquals(3, (result as RetryResult.Failure).totalAttempts)
        assertTrue(result.lastError is java.io.IOException)
    }

    @Test
    fun `executeWithRetry - zero retries means single attempt`() = runTest {
        val config = RetryConfig(maxRetries = 0)
        val result = RetryPolicy.executeWithRetry(config) {
            "single_attempt"
        }
        assertTrue(result is RetryResult.Success)
        assertEquals("single_attempt", (result as RetryResult.Success).data)
        assertEquals(1, result.attempts)
    }

    @Test
    fun `executeWithRetry - zero retries with failure returns Failure immediately`() = runTest {
        val config = RetryConfig(
            maxRetries = 0,
            retryableErrors = setOf(java.io.IOException::class.java)
        )
        val result = RetryPolicy.executeWithRetry(config) {
            throw java.io.IOException("fail")
        }
        assertTrue(result is RetryResult.Failure)
        assertEquals(1, (result as RetryResult.Failure).totalAttempts)
    }

    @Test
    fun `executeWithRetry - OOM is retryable by default`() = runTest {
        var attempt = 0
        val config = RetryConfig(
            maxRetries = 1,
            initialDelayMs = 1L,
            maxDelayMs = 10L,
            backoffMultiplier = 1.0,
            jitterFactor = 0.0
        )
        val result = RetryPolicy.executeWithRetry(config) {
            attempt++
            if (attempt == 1) throw OutOfMemoryError("oom")
            "recovered_from_oom"
        }
        assertTrue(result is RetryResult.Success)
        assertEquals("recovered_from_oom", (result as RetryResult.Success).data)
    }

    @Test
    fun `executeWithRetry - preserves return type`() = runTest {
        val intResult: RetryResult<Int> = RetryPolicy.executeWithRetry { 42 }
        assertTrue(intResult is RetryResult.Success)
        assertEquals(42, (intResult as RetryResult.Success).data)

        val stringResult: RetryResult<String> = RetryPolicy.executeWithRetry { "hello" }
        assertTrue(stringResult is RetryResult.Success)
        assertEquals("hello", (stringResult as RetryResult.Success).data)
    }
}
