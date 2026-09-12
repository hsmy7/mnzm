package com.xianxia.sect.core.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FadeTransition] 纯函数测试——淡入 alpha 数学与 clamp 防御。
 */
class FadeTransitionTest {

    private val durationNs = 300L * 1_000_000L

    @Test
    fun `alphaAt - t=0 returns 0 (fully transparent)`() {
        assertEquals(0f, FadeTransition.alphaAt(0L, durationNs), EPS)
    }

    @Test
    fun `alphaAt - elapsed equals duration returns 1 (fully opaque)`() {
        assertEquals(1f, FadeTransition.alphaAt(durationNs, durationNs), EPS)
    }

    @Test
    fun `alphaAt - elapsed beyond duration clamps to 1`() {
        assertEquals(1f, FadeTransition.alphaAt(durationNs + 5_000_000L, durationNs), EPS)
    }

    @Test
    fun `alphaAt - negative elapsed (clock wrap) clamps to 0`() {
        assertEquals(0f, FadeTransition.alphaAt(-1L, durationNs), EPS)
    }

    @Test
    fun `alphaAt - non-positive duration returns 1 (fade disabled)`() {
        assertEquals(1f, FadeTransition.alphaAt(0L, 0L), EPS)
        assertEquals(1f, FadeTransition.alphaAt(123L, -5L), EPS)
    }

    @Test
    fun `alphaAt - monotonically non-decreasing over time`() {
        var prev = 0f
        for (step in 0..20) {
            val elapsed = step * durationNs / 20
            val alpha = FadeTransition.alphaAt(elapsed, durationNs)
            assertTrue("alpha must not decrease: step=$step alpha=$alpha prev=$prev", alpha >= prev - EPS)
            prev = alpha
        }
    }

    @Test
    fun `alphaAt - EaseOutCubic fast start (midpoint above linear)`() {
        // EaseOutCubic: t=0.5 → 1-(0.5)³ = 0.875 > 线性 0.5（快速启动特征）
        val mid = FadeTransition.alphaAt(durationNs / 2, durationNs)
        assertTrue("midpoint alpha $mid should exceed linear 0.5", mid > 0.5f)
        assertEquals(0.875f, mid, EPS)
    }

    @Test
    fun `alphaAt - default duration constant is positive`() {
        assertTrue(FadeTransition.DEFAULT_DURATION_MS > 0)
    }

    companion object {
        private const val EPS = 0.0001f
    }
}
