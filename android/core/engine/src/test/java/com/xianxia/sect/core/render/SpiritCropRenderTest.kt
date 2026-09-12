package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SpiritCropRender 纯函数测试（灵田生长动画阶段/淡化）。
 */
class SpiritCropRenderTest {

    // ============================================================
    // computeStage — 阶段边界（1/3、2/3）
    // ============================================================

    @Test
    fun `computeStage - zero progress is stage 0`() {
        assertEquals(0, SpiritCropRender.computeStage(0f))
    }

    @Test
    fun `computeStage - below one third is stage 0`() {
        assertEquals(0, SpiritCropRender.computeStage(0.32f))
    }

    @Test
    fun `computeStage - one third boundary is stage 1`() {
        assertEquals(1, SpiritCropRender.computeStage(1f / 3f))
    }

    @Test
    fun `computeStage - between one and two thirds is stage 1`() {
        assertEquals(1, SpiritCropRender.computeStage(0.5f))
    }

    @Test
    fun `computeStage - two thirds boundary is stage 2`() {
        assertEquals(2, SpiritCropRender.computeStage(2f / 3f))
    }

    @Test
    fun `computeStage - full progress is stage 2`() {
        assertEquals(2, SpiritCropRender.computeStage(1f))
    }

    @Test
    fun `computeStage - out of range clamped`() {
        assertEquals(0, SpiritCropRender.computeStage(-0.5f))
        assertEquals(2, SpiritCropRender.computeStage(3f))
    }

    @Test
    fun `computeStage - NaN and Infinity defended to stage 0`() {
        assertEquals(0, SpiritCropRender.computeStage(Float.NaN))
        assertEquals(0, SpiritCropRender.computeStage(Float.POSITIVE_INFINITY))
        assertEquals(0, SpiritCropRender.computeStage(Float.NEGATIVE_INFINITY))
    }

    // ============================================================
    // crossfade — 阶段内线性淡入
    // ============================================================

    @Test
    fun `crossfade - zero progress is zero`() {
        assertEquals(0f, SpiritCropRender.crossfade(0f), 0.001f)
    }

    @Test
    fun `crossfade - stage start is zero`() {
        // 1/3 边界：阶段 1 起点 → alpha 0
        assertEquals(0f, SpiritCropRender.crossfade(1f / 3f), 0.001f)
        assertEquals(0f, SpiritCropRender.crossfade(2f / 3f), 0.001f)
    }

    @Test
    fun `crossfade - stage midpoint is half`() {
        // 阶段 1 中点 = 0.5 → local = (0.5 - 1/3)*3 = 0.5
        assertEquals(0.5f, SpiritCropRender.crossfade(0.5f), 0.001f)
    }

    @Test
    fun `crossfade - full progress is one`() {
        assertEquals(1f, SpiritCropRender.crossfade(1f), 0.001f)
    }

    @Test
    fun `crossfade - monotonic within each stage`() {
        // 设计语义：每阶段内单调淡入（0→1）；阶段切换点（1/3、2/3）alpha 重置回 0
        // 是脉冲式生长动画的预期行为（精灵换阶段 + 重新淡入），C++ 侧同数学。
        var prev = SpiritCropRender.crossfade(0f)
        for (i in 1..30) {
            val p = i / 30f
            val cur = SpiritCropRender.crossfade(p)
            val stageBoundary = SpiritCropRender.computeStage(p) / 3f
            if (p != stageBoundary) {
                assert(cur >= prev) { "阶段内 crossfade 应单调不减: p=$p cur=$cur prev=$prev" }
            }
            prev = cur
        }
    }

    @Test
    fun `crossfade - NaN and Infinity defended to zero`() {
        assertEquals(0f, SpiritCropRender.crossfade(Float.NaN), 0.001f)
        assertEquals(0f, SpiritCropRender.crossfade(Float.POSITIVE_INFINITY), 0.001f)
    }

    @Test
    fun `crossfade - out of range clamped`() {
        assertEquals(0f, SpiritCropRender.crossfade(-1f), 0.001f)
        assertEquals(1f, SpiritCropRender.crossfade(2f), 0.001f)
    }
}
