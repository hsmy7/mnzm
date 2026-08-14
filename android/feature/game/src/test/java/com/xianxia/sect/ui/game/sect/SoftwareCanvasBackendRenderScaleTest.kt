package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import com.xianxia.sect.core.render.RenderFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * SoftwareCanvasBackend 渲染分辨率缩放测试（2026-08-14 平板省电）。
 *
 * 覆盖维度：
 * - renderScale 默认 1.0（直渲基线）
 * - 帧缓冲尺寸 = round(物理 × renderScale)（0.75/0.5）
 * - NaN/越界消毒（NaN → 1.0；低于下限 clamp 到 0.5）
 * - 世界→帧缓冲映射 = drawScale（作物层像素断言：同一世界点缩放后位置 × renderScale）
 * - renderScale 变化触发帧缓冲重建
 * - 极小视口兜底（fbW ≥ 1）
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendRenderScaleTest {

    private lateinit var backend: SoftwareCanvasBackend
    private lateinit var atlas: Bitmap

    @Before
    fun setup() {
        backend = SoftwareCanvasBackend(testRenderConfig())
        atlas = createCropAtlas()
    }

    private fun cropTestFrame(): RenderFrame {
        return cropFrame(
            td = createFlatTileData(10, 10),
            cropData = floatArrayOf(0f, 0f, 0.5f)  // 作物 (0,0)，覆盖 (0,0)-(64,64) 世界
        )
    }

    // ── 默认值与尺寸 ──

    @Test
    fun `renderScale - default is 1_0 direct render`() {
        assertEquals(1.0f, backend.renderScale, 0.001f)
        val result = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
        assertEquals(200, result!!.width)
        assertEquals(200, result.height)
    }

    @Test
    fun `renderScale - 0_75 scales frame buffer down`() {
        backend.renderScale = 0.75f
        val result = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
        assertEquals(150, result!!.width)   // round(200 × 0.75)
        assertEquals(150, result.height)
    }

    @Test
    fun `renderScale - 0_5 halves frame buffer`() {
        backend.renderScale = 0.5f
        val result = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
        assertEquals(100, result!!.width)
        assertEquals(100, result.height)
    }

    // ── 消毒 ──

    @Test
    fun `renderScale - NaN sanitized to 1_0`() {
        backend.renderScale = Float.NaN
        assertEquals(1.0f, backend.renderScale, 0.001f)
        val result = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
        assertEquals(200, result!!.width)
    }

    @Test
    fun `renderScale - infinity sanitized to 1_0`() {
        backend.renderScale = Float.POSITIVE_INFINITY
        assertEquals(1.0f, backend.renderScale, 0.001f)
    }

    @Test
    fun `renderScale - below floor clamps to MIN`() {
        backend.renderScale = 0.1f
        assertEquals(0.5f, backend.renderScale, 0.001f)
    }

    @Test
    fun `renderScale - above ceiling clamps to 1_0`() {
        backend.renderScale = 2.5f
        assertEquals(1.0f, backend.renderScale, 0.001f)
    }

    // ── 世界映射 = drawScale（可视内容与物理路径一致，仅像素密度降） ──

    @Test
    fun `renderScale - world to buffer mapping scales by renderScale`() {
        // 作物 (0,0)-(64,64) 世界：renderScale=1.0 时屏幕 (0,0)-(64,64)
        val full = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)!!

        // renderScale=0.5：作物应映射到 (0,0)-(32,32)
        backend.renderScale = 0.5f
        val half = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)!!

        // 1.0 帧的 (8,8)（作物白色）↔ 0.5 帧的 (4,4)（8×0.5）
        val fullPixel = full.getPixel(8, 8)
        val halfPixel = half.getPixel(4, 4)
        assertEquals("降采样后同一世界点的像素应一致（线性映射）", fullPixel, halfPixel)

        // 0.5 帧的 (40,40) 对应世界 (80,80)——作物 (0,64) 之外 → 地面灰底
        val outsideCrop = half.getPixel(40, 40)
        assertTrue(
            "作物区域外应保持地面底色",
            android.graphics.Color.red(outsideCrop) < 200 ||
                android.graphics.Color.green(outsideCrop) < 200
        )
        // 降采样缓冲边角（100×100 的 (96,96)）应同样为地面底色
        val cornerPixel = half.getPixel(96, 96)
        assertTrue(
            "边角应为地面底色",
            android.graphics.Color.red(cornerPixel) < 200 ||
                android.graphics.Color.green(cornerPixel) < 200
        )
    }

    @Test
    fun `renderScale - visible chunk set identical to 1_0`() {
        // 同一相机下 renderScale 不改变可见 chunk 集合——角落与中心都在同一
        // chunk 覆盖范围内（10×10 世界 = 1 个 chunk，两种 scale 都全可见）
        val full = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)!!
        backend.renderScale = 0.5f
        val half = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)!!

        // 全图可见性：两帧角落都有地面内容（非透明）
        assertEquals(
            "1.0 帧角落像素应映射到 0.5 帧对应位置",
            full.getPixel(100, 100), half.getPixel(50, 50)
        )
    }

    // ── 帧缓冲重建 ──

    @Test
    fun `renderScale - change rebuilds frame buffer on next frame`() {
        val first = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)!!
        assertEquals(200, first.width)

        backend.renderScale = 0.75f
        val second = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)!!
        assertEquals(150, second.width)

        // 恢复 1.0 再次重建
        backend.renderScale = 1.0f
        val third = backend.renderFrame(cropTestFrame(), atlas, vpW = 200, vpH = 200)!!
        assertEquals(200, third.width)
    }

    // ── 极小视口兜底 ──

    @Test
    fun `renderScale - tiny viewport coerces to at least 1px`() {
        backend.renderScale = 0.5f
        val result = backend.renderFrame(cropTestFrame(), atlas, vpW = 3, vpH = 3)
        assertNotNull(result)
        assertEquals(2, result!!.width)   // roundToInt(3×0.5)=round(1.5)=2（≥1 兜底）
        assertEquals(2, result.height)
    }
}
