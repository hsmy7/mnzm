package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
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
 * SoftwareCanvasBackend 地图淡入 + 装饰 LOD 测试。
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
        atlas = createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    }

    // ============================================================
    // 地图淡入（合成 paint.alpha 乘数——纯每帧参数，不触发 chunk 重建）
    // ============================================================

    @Test
    fun `renderFrame - fade alpha blends content toward background`() {
        val td = createFlatTileData(10, 10)
        // (72,40)：阴影条带区（chunk 底 × 阴影暗化 ≈(189,190,181)）——
        // 淡入合成 alpha<1 时向背景 **SkyBackground（蓝**）靠拢 → 变暗/变蓝。
        // 注：RenderFlags.buildingShadows 默认已关闭，此处显式开启以构造
        // "内容（阴影暗化）≠ 背景"的对比，验证淡入向背景（天空）靠拢。
        val fadeBackend = SoftwareCanvasBackend(
            testRenderConfig(renderFlags = RenderFlags(buildingShadows = true))
        )
        val full = fadeBackend.renderFrame(spiritFieldFrame(td), atlas, 200, 200, fadeAlpha = 1f)!!
            .getPixel(72, 40)
        val half = fadeBackend.renderFrame(spiritFieldFrame(td), atlas, 200, 200, fadeAlpha = 0.5f)!!
            .getPixel(72, 40)
        val zero = fadeBackend.renderFrame(spiritFieldFrame(td), atlas, 200, 200, fadeAlpha = 0f)!!
            .getPixel(72, 40)

        // fade 降低 → 内容向蓝天背景靠拢（红通道随天空蓝色下降）
        assertTrue(
            "fade 0.5 应比不透明帧更蓝（向天空靠拢）: full=#%06X half=#%06X"
                .format(full and 0xFFFFFF, half and 0xFFFFFF),
            Color.red(half) < Color.red(full) - 10
        )
        assertTrue(
            "fade 0 应比 fade 0.5 更蓝（纯天空）: half=#%06X zero=#%06X"
                .format(half and 0xFFFFFF, zero and 0xFFFFFF),
            Color.red(zero) < Color.red(half) - 10
        )
        // fade=0 完全等于天空背景色（alpha=0 → 仅 SkyBackground 可见）
        val sky = skyRgbIntAt(40f, 200)
        assertNear(Color.red(sky), Color.red(zero), tolerance = 10)
        assertNear(Color.green(sky), Color.green(zero), tolerance = 10)
        assertNear(Color.blue(sky), Color.blue(zero), tolerance = 10)
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
    // 装饰 LOD（缩放档位防抖——档内微动不重建 chunk）
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

    // ============================================================
    // 运行期 chunk 重建分帧预算
    // 语义：首帧构建全量（整帧完整，淡入遮蔽）；运行期跨档/道路变更触发的
    // 全量失效按单帧预算分帧重烘，未重建 chunk 沿用旧位图合成（渐进刷新，
    // 消除"缩放跨 LOD 阈值单帧 ~1 秒冻结"），并最终收敛到全部有效。
    // ============================================================

    @Test
    fun `runtime rebuild - first frame is full build then frame-budgeted`() {
        // chunk 网格由 config 派生——本用例需要多 chunk 面
        // （分帧语义），用 128²/48px 生产形状（4×4=16 块 1536²，与生产逐位同构）
        val backend = SoftwareCanvasBackend(
            testRenderConfig(worldWidthCells = 128, worldHeightCells = 128, tileSize = 48)
        )
        val td = createDecorTileData(128, 128)
        fun frame(scale: Float) = spiritFieldFrame(td, scale = scale, cols = 128, rows = 128)
        // 首帧构建：预算不生效（hasEverComposedFrame=false）→ 全量 16 块一次完成
        backend.renderFrame(frame(0.8f), atlas, 200, 200)
        assertEquals("首帧必须全量构建 16 块（预算不适用于首建）", 16, backend.chunkRebuildCount)

        // 运行期跨档（0.8 → 0.5）：单帧重建必须 < 16（分帧生效）且 > 0（有进度）
        backend.renderFrame(frame(0.5f), atlas, 200, 200)
        val afterCrossFrame = backend.chunkRebuildCount
        assertTrue(
            "跨档单帧应有重建进度: before=16 after=$afterCrossFrame",
            afterCrossFrame > 16
        )
        assertTrue(
            "运行期重建必须分帧（单帧不得全量重烘）: rebuilt=${afterCrossFrame - 16}",
            afterCrossFrame - 16 < 16
        )

        // 续帧收敛：同参数续渲后重建停止（全部 chunk 回到有效状态）
        repeat(16) { backend.renderFrame(frame(0.5f), atlas, 200, 200) }
        val converged = backend.chunkRebuildCount
        assertTrue("分帧重建应收敛至全量: converged=$converged", converged >= 32)
        backend.renderFrame(frame(0.5f), atlas, 200, 200)
        assertEquals(
            "收敛后同参数渲染不得再触发重建",
            converged, backend.chunkRebuildCount
        )
    }

    @Test
    fun `chunk grid derives from config - not hardcoded 4x4`() {
        // chunk 网格维度从 config 派生（硬编码 4×4 会封死地图扩容）
        // 96²/48px → ceil(96/32)=3 → 3×3=9 块，首帧全量构建
        val backend = SoftwareCanvasBackend(
            testRenderConfig(worldWidthCells = 96, worldHeightCells = 96, tileSize = 48)
        )
        val td = createDecorTileData(96, 96)
        backend.renderFrame(
            spiritFieldFrame(td, scale = 0.8f, cols = 96, rows = 96), atlas, 200, 200
        )
        assertEquals(
            "96² 地图首帧必须构建 9 块（3×3，config 派生）",
            9, backend.chunkRebuildCount
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
