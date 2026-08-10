package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Color
import com.xianxia.sect.core.render.RenderFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * SoftwareCanvasBackend 地图淡入（WP4）+ 装饰 LOD（WP5）测试。
 *
 * - 淡入：合成 paint.alpha 乘数——纯每帧参数，不触发 chunk 重建
 * - LOD：缩放档位防抖——档内微动不重建 chunk，跨档翻转才重建
 *
 * @GraphicsMode(NATIVE)：像素断言需要真实 skia 渲染（LEGACY 模式 getPixel 恒 0）
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendLodFadeTest {

    private lateinit var backend: SoftwareCanvasBackend
    private lateinit var atlas: Bitmap

    @Before
    fun setup() {
        backend = SoftwareCanvasBackend(testRenderConfig())
        // 迷你图集（128x128，不含实际精灵，只验证坐标和帧缓冲区尺寸）
        atlas = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    }

    // ============================================================
    // 地图淡入（WP4，合成 paint.alpha 乘数——纯每帧参数，不触发 chunk 重建）
    // ============================================================

    @Test
    fun `renderFrame - fade alpha blends content toward background`() {
        val td = createFlatTileData(10, 10)
        // (72,40)：阴影条带区（米色底 × 阴影 0.8≈(194,190,182)）——
        // 淡入合成 alpha<1 时向背景米色 (0xF2EDE4) 靠拢 → 变亮
        val full = backend.renderFrame(spiritFieldFrame(td), atlas, 200, 200, fadeAlpha = 1f)!!
            .getPixel(72, 40)
        val half = backend.renderFrame(spiritFieldFrame(td), atlas, 200, 200, fadeAlpha = 0.5f)!!
            .getPixel(72, 40)
        val zero = backend.renderFrame(spiritFieldFrame(td), atlas, 200, 200, fadeAlpha = 0f)!!
            .getPixel(72, 40)

        assertTrue(
            "fade 0.5 应比不透明帧亮（向背景靠拢）: full=#%06X half=#%06X"
                .format(full and 0xFFFFFF, half and 0xFFFFFF),
            Color.red(half) > Color.red(full) + 10
        )
        assertTrue(
            "fade 0 应比 fade 0.5 更亮: half=#%06X zero=#%06X"
                .format(half and 0xFFFFFF, zero and 0xFFFFFF),
            Color.red(zero) > Color.red(half) + 10
        )
        // fade=0 完全等于背景米色（alpha=0 → 仅 drawColor 可见）
        assertNear(0xF2, Color.red(zero), tolerance = 4)
        assertNear(0xED, Color.green(zero), tolerance = 4)
        assertNear(0xE4, Color.blue(zero), tolerance = 4)
    }

    @Test
    fun `renderFrame - fade alpha change does not corrupt subsequent opaque frame`() {
        val td = createFlatTileData(10, 10)
        // 淡入帧后紧跟全不透明帧：paint.alpha 必须恢复 255（残留半透明会污染后续帧）
        backend.renderFrame(spiritFieldFrame(td), atlas, 200, 200, fadeAlpha = 0f)
        val after = backend.renderFrame(spiritFieldFrame(td), atlas, 200, 200, fadeAlpha = 1f)!!
            .getPixel(72, 40)
        val baseline = backend.renderFrame(spiritFieldFrame(td), atlas, 200, 200, fadeAlpha = 1f)!!
            .getPixel(72, 40)
        assertEquals(
            "fade 帧后 paint.alpha 必须恢复: after=#%06X baseline=#%06X"
                .format(after and 0xFFFFFF, baseline and 0xFFFFFF),
            baseline, after
        )
    }

    // ============================================================
    // 装饰 LOD（WP5，缩放档位防抖——档内微动不重建 chunk）
    // ============================================================

    @Test
    fun `decor LOD - scale changes within band do not rebuild chunks`() {
        // 档位内（scale≥0.6）缩放微动：decorSkip 恒 false → 不得触发 chunk 重建（防抖）
        val td = createDecorTileData(10, 10)
        backend.renderFrame(spiritFieldFrame(td, scale = 0.8f), atlas, 200, 200)
        val firstBuildCount = backend.chunkRebuildCount
        assertTrue("首帧应发生 chunk 重建", firstBuildCount > 0)

        backend.renderFrame(spiritFieldFrame(td, scale = 0.9f), atlas, 200, 200)
        backend.renderFrame(spiritFieldFrame(td, scale = 0.75f), atlas, 200, 200)
        backend.renderFrame(spiritFieldFrame(td, scale = 0.65f), atlas, 200, 200)

        assertEquals(
            "档位内缩放不应触发 chunk 重建: count=$firstBuildCount",
            firstBuildCount, backend.chunkRebuildCount
        )
    }

    @Test
    fun `decor LOD - crossing zoom threshold rebuilds chunks`() {
        val td = createDecorTileData(10, 10)
        backend.renderFrame(spiritFieldFrame(td, scale = 0.8f), atlas, 200, 200)
        val onBandCount = backend.chunkRebuildCount

        // 跨过 0.6 阈值（0.8 → 0.5）：decorSkip 翻转 → 全部 chunk 重建
        backend.renderFrame(spiritFieldFrame(td, scale = 0.5f), atlas, 200, 200)
        assertTrue(
            "跨档应触发重建: on=$onBandCount after=${backend.chunkRebuildCount}",
            backend.chunkRebuildCount > onBandCount
        )
        val offBandCount = backend.chunkRebuildCount

        // 回到高档（0.5 → 0.8）：再次翻转 → 重建（装饰恢复）
        backend.renderFrame(spiritFieldFrame(td, scale = 0.8f), atlas, 200, 200)
        assertTrue(
            "回档应触发重建: off=$offBandCount after=${backend.chunkRebuildCount}",
            backend.chunkRebuildCount > offBandCount
        )
    }

    @Test
    fun `decor LOD - flag off ignores scale in decor skip`() {
        // decorLod=false：scale 不参与装饰判定（行为 = 特性实现前现状），
        // 跨档缩放不得触发 decorSkip 翻转重建
        val noLodBackend = SoftwareCanvasBackend(
            testRenderConfig(renderFlags = RenderFlags(decorLod = false))
        )
        val td = createDecorTileData(10, 10)
        noLodBackend.renderFrame(spiritFieldFrame(td, scale = 1.0f), atlas, 200, 200)
        val firstBuildCount = noLodBackend.chunkRebuildCount

        noLodBackend.renderFrame(spiritFieldFrame(td, scale = 0.5f), atlas, 200, 200)
        noLodBackend.renderFrame(spiritFieldFrame(td, scale = 0.3f), atlas, 200, 200)

        assertEquals(
            "decorLod=false 时跨档不应重建: count=$firstBuildCount",
            firstBuildCount, noLodBackend.chunkRebuildCount
        )
    }
}
