package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SpiritCropRender.smoothedProgress] 插值消费链测试（2026-08-13 批次 3）。
 */
class SpiritCropRenderSmoothedTest {

    private companion object {
        const val EPS = 0.0001f
    }

    @Test
    fun `smoothedProgress - 无历史时直通当前值`() {
        assertEquals(0.5f, SpiritCropRender.smoothedProgress(null, 0.5f, 0.5f), EPS)
    }

    @Test
    fun `smoothedProgress - alpha 取 0 保持上一帧 取 1 取当前帧`() {
        assertEquals("alpha=0 → 上一帧", 0.2f, SpiritCropRender.smoothedProgress(0.2f, 0.5f, 0f), EPS)
        assertEquals("alpha=1 → 当前帧", 0.5f, SpiritCropRender.smoothedProgress(0.2f, 0.5f, 1f), EPS)
        assertEquals("alpha=0.5 → 中点", 0.35f, SpiritCropRender.smoothedProgress(0.2f, 0.5f, 0.5f), EPS)
    }

    @Test
    fun `smoothedProgress - 非法输入防御`() {
        val nan = Float.NaN
        assertEquals("cur NaN 原样返回", nan, SpiritCropRender.smoothedProgress(0.1f, nan, 0.5f))
        assertEquals("prev NaN → 直通 cur", 0.7f, SpiritCropRender.smoothedProgress(nan, 0.7f, 0.5f), EPS)
        assertEquals("alpha 越界钳制", 0.5f, SpiritCropRender.smoothedProgress(0.2f, 0.5f, 99f), EPS)
    }

    @Test
    fun `smoothedProgress - 输出钳制 0 到 1`() {
        assertEquals(1f, SpiritCropRender.smoothedProgress(0.9f, 1f, 5f), EPS)
        assertEquals(0f, SpiritCropRender.smoothedProgress(0.1f, 0f, 5f), EPS)
    }
}
