package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Color
import com.xianxia.sect.core.render.RenderFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * SoftwareCanvasBackend 灵田作物层测试（WP6）。
 *
 * 与 C++ 作物段同数学：stage 边界 1/3、2/3 + crossfade × 全局 fade 乘算。
 * 作物层不烘焙 chunk（生长进度频繁变化——chunk 合成后逐帧绘制）。
 *
 * @GraphicsMode(NATIVE)：像素断言需要真实 skia 渲染（LEGACY 模式 getPixel 恒 0）
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendCropTest {

    private lateinit var backend: SoftwareCanvasBackend
    private lateinit var atlas: Bitmap

    @Before
    fun setup() {
        backend = SoftwareCanvasBackend(testRenderConfig())
        // 迷你图集（128x128，不含实际精灵，只验证坐标和帧缓冲区尺寸）
        atlas = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    }

    // ============================================================
    // 灵田作物（WP6，与 C++ 作物段同数学：stage 边界 1/3、2/3 + crossfade × fade）
    // ============================================================

    @Test
    fun `renderFrame - spirit crop drawn with stage crossfade alpha`() {
        val cropAtlas = createCropAtlas()
        val td = createFlatTileData(10, 10)
        // progress=0.5 → stage 1（GROWING），crossfade=(0.5-1/3)×3≈0.5 →
        // 白 255×0.5 混合灰底 100×0.5 ≈ 178（介于纯底 100 与纯源 255 之间）
        val withCrop = backend.renderFrame(
            cropFrame(td, floatArrayOf(0f, 0f, 0.5f)), cropAtlas, 200, 200
        )!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)
        val noCrop = backend.renderFrame(
            cropFrame(td, null), cropAtlas, 200, 200
        )!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)

        // 无作物帧 = 纯灰底（作物源在图集外不可见；采样点无阴影）
        assertNear(100, Color.red(noCrop), tolerance = 4)
        // 有作物帧明显亮于灰底（半透明白混合）
        assertTrue(
            "作物应使采样点变亮: noCrop=#%06X withCrop=#%06X"
                .format(noCrop and 0xFFFFFF, withCrop and 0xFFFFFF),
            Color.red(withCrop) > Color.red(noCrop) + 20
        )
        // 半透明混合证据：介于纯底与纯源之间
        assertTrue(
            "crossfade 半透明混合应介于灰底与白源之间: px=%d".format(Color.red(withCrop)),
            Color.red(withCrop) in 120..230
        )
    }

    @Test
    fun `renderFrame - crop progress change updates pixels`() {
        val cropAtlas = createCropAtlas()
        val td = createFlatTileData(10, 10)
        // 阶段 0 早期（progress=0.1）：crossfade=0.3 → 混合 ≈ 146
        val early = backend.renderFrame(
            cropFrame(td, floatArrayOf(0f, 0f, 0.1f)), cropAtlas, 200, 200
        )!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)
        // 阶段 2 后期（progress=0.9）：crossfade=0.7 → 混合 ≈ 208
        val late = backend.renderFrame(
            cropFrame(td, floatArrayOf(0f, 0f, 0.9f)), cropAtlas, 200, 200
        )!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)

        assertTrue(
            "生长后期应明显亮于早期: early=#%06X late=#%06X"
                .format(early and 0xFFFFFF, late and 0xFFFFFF),
            Color.red(late) > Color.red(early) + 20
        )
    }

    @Test
    fun `renderFrame - crop alpha multiplies with global fade`() {
        val cropAtlas = createCropAtlas()
        val td = createFlatTileData(10, 10)
        // 消底差值断言：crop 对像素的贡献 = withCrop - noCrop（同 fade 下底一致），
        // fade 减半 → crop 贡献显著衰减；fade=0 → 贡献归零（作物不可见）。
        // 不能用绝对方向断言——fade 同时淡化 chunk 底与作物层，绝对值变化被补偿抵消
        val withFull = backend.renderFrame(
            cropFrame(td, floatArrayOf(0f, 0f, 0.9f)), cropAtlas, 200, 200, fadeAlpha = 1f
        )!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)
        val noFull = backend.renderFrame(
            cropFrame(td, null), cropAtlas, 200, 200, fadeAlpha = 1f
        )!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)
        val withHalf = backend.renderFrame(
            cropFrame(td, floatArrayOf(0f, 0f, 0.9f)), cropAtlas, 200, 200, fadeAlpha = 0.5f
        )!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)
        val noHalf = backend.renderFrame(
            cropFrame(td, null), cropAtlas, 200, 200, fadeAlpha = 0.5f
        )!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)
        val withZero = backend.renderFrame(
            cropFrame(td, floatArrayOf(0f, 0f, 0.9f)), cropAtlas, 200, 200, fadeAlpha = 0f
        )!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)
        val noZero = backend.renderFrame(
            cropFrame(td, null), cropAtlas, 200, 200, fadeAlpha = 0f
        )!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)

        val deltaFull = Color.red(withFull) - Color.red(noFull)
        val deltaHalf = Color.red(withHalf) - Color.red(noHalf)
        assertTrue(
            "fade 0.5 时 crop 贡献应显著小于 fade 1: deltaFull=$deltaFull deltaHalf=$deltaHalf",
            deltaFull > deltaHalf + 20
        )
        assertEquals(
            "fade=0 时作物应完全不可见（与无作物帧同像素）: with=#%06X no=#%06X"
                .format(withZero and 0xFFFFFF, noZero and 0xFFFFFF),
            noZero, withZero
        )
    }

    @Test
    fun `renderFrame - crop NaN and out-of-range progress does not crash`() {
        val cropAtlas = createCropAtlas()
        val td = createFlatTileData(10, 10)
        // NaN/Inf/越界/负数：防御路径逐条渲染（不得崩溃、不得污染后续帧）
        val badData = floatArrayOf(
            0f, 0f, Float.NaN,
            1f, 1f, Float.POSITIVE_INFINITY,
            2f, 2f, 2.5f,
            3f, 3f, -1f
        )
        val result = backend.renderFrame(cropFrame(td, badData), cropAtlas, 200, 200)
        assertNotNull("非法作物数据不应 crash", result)
        // 全部非法条目被跳过 → 采样点保持灰底（无作物、无阴影）
        assertNear(100, Color.red(result!!.getPixel(CROP_SAMPLE, CROP_SAMPLE)), tolerance = 4)
    }

    @Test
    fun `renderFrame - crop drawn regardless of other render flags`() {
        // 作物层数据驱动、独立于其他渲染特性开关（无专属 flag——null 数据即关闭）
        val cropAtlas = createCropAtlas()
        val td = createFlatTileData(10, 10)
        val flagsOffBackend = SoftwareCanvasBackend(
            testRenderConfig(
                renderFlags = RenderFlags(buildingShadows = false, selectionHighlight = false)
            )
        )
        val px = flagsOffBackend.renderFrame(
            cropFrame(td, floatArrayOf(0f, 0f, 0.9f)), cropAtlas, 200, 200
        )!!.getPixel(32, 32)
        // 其他 flags 全关时作物仍绘制（白源混合灰底明显变亮）
        assertTrue(
            "作物应不受其他渲染 flag 影响: px=#%06X".format(px and 0xFFFFFF),
            Color.red(px) > 120
        )
    }
}
