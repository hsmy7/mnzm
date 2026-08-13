package com.xianxia.sect.core.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JitterSmoother] 一阶滤波测试（2026-08-13 批次 3）。
 *
 * 核心断言：滤波只作用于渲染插值因子——收敛性、钳制、重置语义。
 */
class JitterSmootherTest {

    private companion object {
        const val EPS = 0.0001f
    }

    @Test
    fun `filter - 首个样本直接采用不引入延迟`() {
        val smoother = JitterSmoother()
        assertEquals("首帧 raw 直通", 0.4f, smoother.filter(0.4f), EPS)
    }

    @Test
    fun `filter - 恒定输入收敛到该值`() {
        val smoother = JitterSmoother(smoothingFactor = 0.5f)
        smoother.filter(0f)
        repeat(20) { smoother.filter(1f) }
        assertTrue("20 次后接近 1", smoother.filter(1f) > 0.999f)
    }

    @Test
    fun `filter - 每 tick 锯齿输入输出为连续变化（无 09 到 01 跳变）`() {
        // 模拟 10Hz 逻辑帧：每 tick alpha 从 ~0.9 跳回 ~0.1
        val smoother = JitterSmoother(smoothingFactor = 0.5f)
        var output = smoother.filter(0.9f)
        val sawSamples = mutableListOf<Float>()
        repeat(10) {
            output = smoother.filter(0.1f)
            sawSamples += output
            output = smoother.filter(0.9f)
            sawSamples += output
        }
        // 相邻输出变化量远小于 raw 的 0.8 跳变（平滑生效）
        for (i in 1 until sawSamples.size) {
            val delta = kotlin.math.abs(sawSamples[i] - sawSamples[i - 1])
            assertTrue("相邻滤波输出跳变 $delta 应远小于 raw 0.8", delta < 0.5f)
        }
    }

    @Test
    fun `filter - 越界输入钳制`() {
        val smoother = JitterSmoother()
        assertEquals("负值钳制为 0（首帧直通）", 0f, smoother.filter(-5f), EPS)
        val fresh = JitterSmoother()
        assertEquals("超 1 钳制为 1（首帧直通）", 1f, fresh.filter(99f), EPS)
    }

    @Test
    fun `reset - 清空状态后下一帧直通`() {
        val smoother = JitterSmoother(smoothingFactor = 0.5f)
        smoother.filter(1f)
        smoother.reset()
        assertEquals("reset 后首帧直通", 0.3f, smoother.filter(0.3f), EPS)
    }
}
