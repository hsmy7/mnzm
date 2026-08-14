package com.xianxia.sect.core.render

import com.xianxia.sect.core.perf.GpuTier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 渲染分辨率缩放策略测试（2026-08-14 平板省电）。
 *
 * 覆盖维度：
 * - 面积分级（COMPACT/STANDARD/LARGE/XLARGE 阈值边界）
 * - computeRenderScale 档位矩阵（手机/平板/8K × GPU 档 × 双路径 × qualityFactor）
 * - COMPACT 恒 1.0（手机逐位不变回归基线）
 * - NaN/Inf 消毒、floorTo05 离散化、MIN/MAX clamp
 */
class RenderScalePolicyTest {

    // ── 面积分级 ──

    @Test
    fun `classify - 1080p phone is COMPACT`() {
        assertEquals(ScreenPixelAreaTier.COMPACT, RenderScalePolicy.classifyScreenArea(2400, 1080))
        assertEquals(ScreenPixelAreaTier.COMPACT, RenderScalePolicy.classifyScreenArea(1080, 2400))
    }

    @Test
    fun `classify - boundary at 2_6M pixels is COMPACT`() {
        assertEquals(ScreenPixelAreaTier.COMPACT, RenderScalePolicy.classifyScreenArea(2600, 1000))
    }

    @Test
    fun `classify - 1440p phone is STANDARD`() {
        // 2670×1200 = 3.204M ≤ 3.5M
        assertEquals(ScreenPixelAreaTier.STANDARD, RenderScalePolicy.classifyScreenArea(2670, 1200))
    }

    @Test
    fun `classify - 2560x1600 tablet is LARGE`() {
        // 4.096M ≤ 5M
        assertEquals(ScreenPixelAreaTier.LARGE, RenderScalePolicy.classifyScreenArea(2560, 1600))
    }

    @Test
    fun `classify - 2880x1800 tablet is XLARGE`() {
        // 5.184M > 5M
        assertEquals(ScreenPixelAreaTier.XLARGE, RenderScalePolicy.classifyScreenArea(2880, 1800))
    }

    @Test
    fun `classify - 8k screen is XLARGE`() {
        assertEquals(ScreenPixelAreaTier.XLARGE, RenderScalePolicy.classifyScreenArea(7680, 4320))
    }

    @Test
    fun `classify - zero or negative viewport treated as COMPACT`() {
        assertEquals(ScreenPixelAreaTier.COMPACT, RenderScalePolicy.classifyScreenArea(0, 1080))
        assertEquals(ScreenPixelAreaTier.COMPACT, RenderScalePolicy.classifyScreenArea(2400, 0))
        assertEquals(ScreenPixelAreaTier.COMPACT, RenderScalePolicy.classifyScreenArea(-1, 1080))
    }

    // ── COMPACT 恒 1.0（手机逐位不变回归基线） ──

    @Test
    fun `compute - compact phone always returns 1_0`() {
        // 任意 GPU 档/路径/qualityFactor 组合，手机均不缩放
        for (tier in GpuTier.values()) {
            for (software in listOf(true, false)) {
                assertEquals(
                    "tier=$tier software=$software 应恒 1.0",
                    1.0f,
                    RenderScalePolicy.computeRenderScale(tier, software, 2400, 1080, 1.0f),
                    0.001f
                )
                // 热控降质（qualityFactor 0.4）也不触发缩放
                assertEquals(
                    1.0f,
                    RenderScalePolicy.computeRenderScale(tier, software, 2400, 1080, 0.4f),
                    0.001f
                )
            }
        }
    }

    // ── 平板档位矩阵 ──

    @Test
    fun `compute - LARGE tablet HIGH vulkan is 0_8`() {
        assertEquals(0.8f, RenderScalePolicy.computeRenderScale(GpuTier.HIGH, false, 2560, 1600, 1.0f), 0.001f)
    }

    @Test
    fun `compute - LARGE tablet MEDIUM vulkan is 0_8`() {
        assertEquals(0.8f, RenderScalePolicy.computeRenderScale(GpuTier.MEDIUM, false, 2560, 1600, 1.0f), 0.001f)
    }

    @Test
    fun `compute - LARGE tablet MEDIUM software is 0_6`() {
        // min(0.8, 0.8) × 0.8 = 0.64 → floorTo05 = 0.6
        assertEquals(0.6f, RenderScalePolicy.computeRenderScale(GpuTier.MEDIUM, true, 2560, 1600, 1.0f), 0.001f)
    }

    @Test
    fun `compute - LARGE tablet LOW software is 0_5 clamp`() {
        // min(0.6, 0.8) × 0.8 = 0.48 → floorTo05 = 0.45 → clamp 0.5
        assertEquals(0.5f, RenderScalePolicy.computeRenderScale(GpuTier.LOW, true, 2560, 1600, 1.0f), 0.001f)
    }

    @Test
    fun `compute - XLARGE tablet ULTRA vulkan is 0_7`() {
        assertEquals(0.7f, RenderScalePolicy.computeRenderScale(GpuTier.ULTRA, false, 2880, 1800, 1.0f), 0.001f)
    }

    @Test
    fun `compute - 8k screen ULTRA vulkan is 0_7`() {
        assertEquals(0.7f, RenderScalePolicy.computeRenderScale(GpuTier.ULTRA, false, 7680, 4320, 1.0f), 0.001f)
    }

    @Test
    fun `compute - STANDARD 1440p phone HIGH vulkan is 0_9`() {
        assertEquals(0.9f, RenderScalePolicy.computeRenderScale(GpuTier.HIGH, false, 2670, 1200, 1.0f), 0.001f)
    }

    // ── qualityFactor 叠加（仅非 COMPACT 生效） ──

    @Test
    fun `compute - LARGE tablet thermal quality 0_6 gives 0_5 clamp`() {
        // 0.8 × 0.6 = 0.48 → floorTo05 = 0.45 → clamp 0.5
        assertEquals(0.5f, RenderScalePolicy.computeRenderScale(GpuTier.HIGH, false, 2560, 1600, 0.6f), 0.001f)
    }

    @Test
    fun `compute - qualityFactor NaN sanitized to 1_0`() {
        assertEquals(0.8f, RenderScalePolicy.computeRenderScale(GpuTier.HIGH, false, 2560, 1600, Float.NaN), 0.001f)
    }

    @Test
    fun `compute - qualityFactor infinity sanitized to 1_0`() {
        assertEquals(
            0.8f,
            RenderScalePolicy.computeRenderScale(GpuTier.HIGH, false, 2560, 1600, Float.POSITIVE_INFINITY),
            0.001f
        )
    }

    @Test
    fun `compute - qualityFactor below floor clamps to MIN_QUALITY_FACTOR`() {
        // 0.8 × 0.4(clamp 下限) = 0.32 → floorTo05 = 0.3 → clamp 0.5
        assertEquals(0.5f, RenderScalePolicy.computeRenderScale(GpuTier.HIGH, false, 2560, 1600, 0.01f), 0.001f)
    }

    // ── floorTo05 离散化 ──

    @Test
    fun `floorTo05 - exact halves unchanged`() {
        assertEquals(0.5f, RenderScalePolicy.floorTo05(0.5f), 0.001f)
        assertEquals(0.8f, RenderScalePolicy.floorTo05(0.8f), 0.001f)
        assertEquals(1.0f, RenderScalePolicy.floorTo05(1.0f), 0.001f)
    }

    @Test
    fun `floorTo05 - rounds down to 0_05 grid`() {
        assertEquals(0.6f, RenderScalePolicy.floorTo05(0.64f), 0.001f)
        assertEquals(0.45f, RenderScalePolicy.floorTo05(0.48f), 0.001f)
        assertEquals(0.75f, RenderScalePolicy.floorTo05(0.79f), 0.001f)
    }

    @Test
    fun `floorTo05 - NaN sanitized to max`() {
        assertEquals(RenderScalePolicy.MAX_RENDER_SCALE, RenderScalePolicy.floorTo05(Float.NaN), 0.001f)
    }

    // ── screenFactor 档位映射 ──

    @Test
    fun `screenFactor - four tiers map correctly`() {
        assertEquals(1.0f, RenderScalePolicy.screenFactor(ScreenPixelAreaTier.COMPACT), 0.001f)
        assertEquals(0.9f, RenderScalePolicy.screenFactor(ScreenPixelAreaTier.STANDARD), 0.001f)
        assertEquals(0.8f, RenderScalePolicy.screenFactor(ScreenPixelAreaTier.LARGE), 0.001f)
        assertEquals(0.7f, RenderScalePolicy.screenFactor(ScreenPixelAreaTier.XLARGE), 0.001f)
    }
}
