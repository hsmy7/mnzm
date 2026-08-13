package com.xianxia.sect.core.animation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EasingConstants] 缓动曲线数学测试——每曲线关键点（0 / 0.5 / 1）与单调性。
 */
class EasingConstantsTest {

    @Test
    fun `linear - 关键点 0-05-1 恒等`() {
        assertEquals(0f, EasingConstants.LINEAR(0f), EPS)
        assertEquals(0.5f, EasingConstants.LINEAR(0.5f), EPS)
        assertEquals(1f, EasingConstants.LINEAR(1f), EPS)
    }

    @Test
    fun `easeInCubic - 关键点 0-0125-1`() {
        assertEquals(0f, EasingConstants.EASE_IN_CUBIC(0f), EPS)
        assertEquals(0.125f, EasingConstants.EASE_IN_CUBIC(0.5f), EPS)
        assertEquals(1f, EasingConstants.EASE_IN_CUBIC(1f), EPS)
    }

    @Test
    fun `easeOutCubic - 关键点 0-0875-1`() {
        assertEquals(0f, EasingConstants.EASE_OUT_CUBIC(0f), EPS)
        assertEquals(0.875f, EasingConstants.EASE_OUT_CUBIC(0.5f), EPS)
        assertEquals(1f, EasingConstants.EASE_OUT_CUBIC(1f), EPS)
    }

    @Test
    fun `easeInOutCubic - 关键点 0-05-1`() {
        assertEquals(0f, EasingConstants.EASE_IN_OUT_CUBIC(0f), EPS)
        assertEquals(0.5f, EasingConstants.EASE_IN_OUT_CUBIC(0.5f), EPS)
        assertEquals(1f, EasingConstants.EASE_IN_OUT_CUBIC(1f), EPS)
    }

    @Test
    fun `easeInOutCubic - 对称性 四分点互为镜像`() {
        // 缓入缓出关于 t=0.5 中心对称：f(0.25) + f(0.75) = 1
        val a = EasingConstants.EASE_IN_OUT_CUBIC(0.25f)
        val b = EasingConstants.EASE_IN_OUT_CUBIC(0.75f)
        assertEquals("f(0.25)+f(0.75) 应为 1", 1f, a + b, EPS)
        assertEquals("f(0.25) 应为 0.0625 (4×0.25³)", 0.0625f, a, EPS)
        assertEquals("f(0.75) 应为 0.9375 (1-4×(1-0.75)³)", 0.9375f, b, EPS)
    }

    @Test
    fun `all curves - 定义域内单调不减`() {
        for (curve in ALL_CURVES) {
            var prev = curve(0f)
            for (i in 1..100) {
                val t = i / 100f
                val v = curve(t)
                assertTrue(
                    "curve=$curve t=$t 处不得回落（prev=$prev, v=$v）",
                    v >= prev - EPS
                )
                prev = v
            }
            assertEquals("t=1 终值应为 1", 1f, prev, EPS)
        }
    }

    @Test
    fun `all curves - 端点值 0 与 1`() {
        for (curve in ALL_CURVES) {
            assertEquals("起点应为 0", 0f, curve(0f), EPS)
            assertEquals("终点应为 1", 1f, curve(1f), EPS)
        }
    }

    companion object {
        private const val EPS = 0.0001f

        private val ALL_CURVES = listOf(
            EasingConstants.LINEAR,
            EasingConstants.EASE_IN_CUBIC,
            EasingConstants.EASE_OUT_CUBIC,
            EasingConstants.EASE_IN_OUT_CUBIC
        )
    }
}
