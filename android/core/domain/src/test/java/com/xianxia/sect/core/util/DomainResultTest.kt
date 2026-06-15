package com.xianxia.sect.core.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DomainResult] 契约测试。
 *
 * 覆盖：三态构造、map/flatMap 透传、getOrNull/isSuccess、
 * 以及 catching 的关键契约——CancellationException 必须重新抛出（不可吞入 Failure）。
 *
 * 命名规范：`方法名_状态_预期行为`（规范 §9.4 Given-When-Then）。
 */
class DomainResultTest {

    private val sampleError: AppError.Domain = AppError.Domain.Inventory.Full()
    private val otherError: AppError.Domain = AppError.Domain.Inventory.NotFound("x")

    // ==================== 三态构造 ====================

    @Test
    fun success_holdsData() {
        val r: DomainResult<Int> = DomainResult.Success(42)
        assertEquals(42, r.getOrNull())
        assertTrue(r.isSuccess)
        assertFalse(r.isFailure)
        assertNull(r.errorOrNull())
    }

    @Test
    fun partial_holdsDataAndOverflow() {
        val r = DomainResult.Partial("item", overflow = 5)
        assertEquals("item", r.getOrNull())
        assertEquals(5, r.overflow)
        assertTrue(r.isSuccess)   // Partial 也算成功路径
    }

    @Test
    fun failure_holdsError() {
        val r: DomainResult<Int> = DomainResult.Failure(sampleError)
        assertFalse(r.isSuccess)
        assertTrue(r.isFailure)
        assertNull(r.getOrNull())
        assertEquals(sampleError, r.errorOrNull())
    }

    // ==================== map ====================

    @Test
    fun map_successTransformsData() {
        val r: DomainResult<Int> = DomainResult.Success(2)
        val mapped = r.map { it * 10 }
        assertEquals(20, mapped.getOrNull())
    }

    @Test
    fun map_partialKeepsOverflow() {
        val r = DomainResult.Partial(2, overflow = 3)
        val mapped = r.map { it * 10 }
        assertEquals(20, mapped.getOrNull())
        assertEquals(3, (mapped as DomainResult.Partial).overflow)
    }

    @Test
    fun map_failurePassesThrough() {
        val r: DomainResult<Int> = DomainResult.Failure(sampleError)
        val mapped = r.map { it * 10 }
        assertTrue(mapped.isFailure)
        assertEquals(sampleError, mapped.errorOrNull())
    }

    // ==================== flatMap ====================

    @Test
    fun flatMap_successChainsNext() {
        val r: DomainResult<Int> = DomainResult.Success(1)
        val chained = r.flatMap { DomainResult.Success(it + 1) }
        assertEquals(2, chained.getOrNull())
    }

    @Test
    fun flatMap_successCanProduceFailure() {
        val r: DomainResult<Int> = DomainResult.Success(1)
        val chained = r.flatMap { DomainResult.Failure(otherError) }
        assertTrue(chained.isFailure)
        assertEquals(otherError, chained.errorOrNull())
    }

    @Test
    fun flatMap_failureShortCircuits() {
        val r: DomainResult<Int> = DomainResult.Failure(sampleError)
        val chained = r.flatMap { DomainResult.Success(it + 1) }
        assertTrue(chained.isFailure)
        assertEquals(sampleError, chained.errorOrNull())
    }

    // ==================== catching：关键契约 ====================

    @Test
    fun catching_successReturnsSuccess() {
        val r = DomainResult.catching { 10 }
        assertEquals(10, r.getOrNull())
    }

    @Test
    fun catching_normalExceptionReturnsFailure() {
        val r = DomainResult.catching<Int> { throw IllegalStateException("boom") }
        assertTrue(r.isFailure)
    }

    /**
     * 核心契约：CancellationException 必须重新抛出，不可吞入 Failure。
     * 这是修复旧 ProductionError.catching 吞噬 bug 的关键验证。
     */
    @Test(expected = CancellationException::class)
    fun catching_cancellationExceptionIsRethrown() {
        DomainResult.catching<Int> {
            throw CancellationException("cancelled")
        }
    }

    @Test
    fun catching_customDomainUsedOnFailure() {
        val custom = AppError.Domain.Disciple.NotFound("d1")
        val r = DomainResult.catching(custom) { throw RuntimeException() }
        assertEquals(custom, r.errorOrNull())
    }
}
