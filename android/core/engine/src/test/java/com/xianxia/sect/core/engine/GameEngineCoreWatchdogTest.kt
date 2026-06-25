package com.xianxia.sect.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 看门狗指数退避算法单元测试。
 *
 * 测试 [GameEngineCore.computeWatchdogBackoff] 的退避计算逻辑：
 * - tick 停滞时退避间隔翻倍
 * - 退避间隔上限截断
 * - tick 恢复后重置为基础间隔
 */
class GameEngineCoreWatchdogTest {

    private val baseIntervalMs = 3_000L
    private val maxBackoffMs = GameEngineCore.WATCHDOG_MAX_BACKOFF_MS

    // ── 停滞 → 翻倍 ──

    @Test
    fun `backoff - first stall doubles from base`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = baseIntervalMs,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        assertEquals(6_000L, result)
    }

    @Test
    fun `backoff - second stall doubles to 4x base`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 6_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        assertEquals(12_000L, result)
    }

    @Test
    fun `backoff - third stall doubles to 8x base`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 12_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        assertEquals(24_000L, result)
    }

    @Test
    fun `backoff - fourth stall doubles to 16x base`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 24_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        // 48000 > 30000，截断为上限
        assertEquals(maxBackoffMs, result)
    }

    // ── 上限截断 ──

    @Test
    fun `backoff - already at max stays at max`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = maxBackoffMs,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        assertEquals(maxBackoffMs, result)
    }

    @Test
    fun `backoff - near max doubles and caps`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 20_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        // 40000 > 30000，截断为上限
        assertEquals(maxBackoffMs, result)
    }

    // ── 恢复 → 重置 ──

    @Test
    fun `backoff - recovery resets to base after one stall`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 6_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = true
        )
        assertEquals(baseIntervalMs, result)
    }

    @Test
    fun `backoff - recovery resets to base after multiple stalls`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 24_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = true
        )
        assertEquals(baseIntervalMs, result)
    }

    @Test
    fun `backoff - recovery resets from max backoff`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = maxBackoffMs,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = true
        )
        assertEquals(baseIntervalMs, result)
    }

    // ── 边界 ──

    @Test
    fun `backoff - conservative OEM 5s base doubles to 10s`() {
        val conservativeBase = 5_000L
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = conservativeBase,
            baseIntervalMs = conservativeBase,
            hasRecovered = false
        )
        assertEquals(10_000L, result)
    }

    @Test
    fun `backoff - base already equals max, no doubling`() {
        // 极端情况：基础间隔已等于上限（30s）
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = maxBackoffMs,
            baseIntervalMs = maxBackoffMs,
            hasRecovered = false
        )
        assertEquals(maxBackoffMs, result)
    }
}
