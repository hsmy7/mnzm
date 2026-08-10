package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Color
import com.xianxia.sect.core.render.RenderFlags
import com.xianxia.sect.core.render.RenderFrame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * SoftwareCanvasBackend 建筑阴影 + 选中高亮测试（WP3）。
 *
 * - 阴影：与 C++ drawAllTiles (A2) 段同数学（右下偏移 0.25 格 + alpha 0.2 半透明黑）
 * - 高亮：金色描边动态叠加（不烘焙 chunk，选中变化零重建成本）
 *
 * @GraphicsMode(NATIVE)：像素断言需要真实 skia 渲染（LEGACY 模式 getPixel 恒 0）
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendHighlightTest {

    private lateinit var backend: SoftwareCanvasBackend
    private lateinit var atlas: Bitmap

    @Before
    fun setup() {
        backend = SoftwareCanvasBackend(testRenderConfig())
        // 迷你图集（128x128，不含实际精灵，只验证坐标和帧缓冲区尺寸）
        atlas = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    }

    // ============================================================
    // 建筑阴影（WP3，与 C++ drawAllTiles (A2) 段同数学）
    // ============================================================

    @Test
    fun `renderFrame - building shadow darkens strip beyond sprite and respects flag`() {
        val td = createFlatTileData(10, 10)
        // 阴影条带内采样点（世界坐标 = 屏幕坐标，camX=0 scale=1）
        val sampleX = 72
        val sampleY = 40

        // 阴影开启：像素明显暗于底色（米色 × 0.8 ≈ (194,190,182)）
        val onBackend = SoftwareCanvasBackend(testRenderConfig())
        val onPx = onBackend.renderFrame(spiritFieldFrame(td), atlas, 200, 200)!!
            .getPixel(sampleX, sampleY)

        // 阴影关闭（RenderFlags.buildingShadows=false）：同点 = 纯底色
        val offBackend = SoftwareCanvasBackend(
            testRenderConfig(renderFlags = RenderFlags(buildingShadows = false))
        )
        val offPx = offBackend.renderFrame(spiritFieldFrame(td), atlas, 200, 200)!!
            .getPixel(sampleX, sampleY)

        // 差异断言（不依赖绝对色值——灵田精灵源 rect 在测试迷你图集外，绘制结果不确定）
        assertTrue(
            "阴影应使采样点变暗: on=#%06X off=#%06X".format(onPx and 0xFFFFFF, offPx and 0xFFFFFF),
            Color.red(onPx) < Color.red(offPx) - 10
        )
        assertTrue(
            "flags off 时应无阴影（与开启帧像素不同）",
            onPx != offPx
        )
    }

    @Test
    fun `renderFrame - shadow rebuilds when building moves (chunk invalidation)`() {
        val td = createFlatTileData(10, 10)
        // 帧1：建筑 (0,0) → 阴影条带在 (64,16)-(80,80)
        val frame1 = spiritFieldFrame(td)
        val r1 = backend.renderFrame(frame1, atlas, 200, 200)!!
        val px1 = r1.getPixel(72, 40)

        // 帧2：建筑移动到 (1,0) → 原 (0,0) 阴影区恢复底色（阴影随建筑失效重建）
        val frame2 = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = td,
            cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 1, gridY = 0, width = 1, height = 1, nameIdx = 2
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val r2 = backend.renderFrame(frame2, atlas, 200, 200)!!
        val px2 = r2.getPixel(72, 40)

        assertTrue(
            "建筑移动后原阴影区应恢复底色: before=#%06X after=#%06X".format(px1 and 0xFFFFFF, px2 and 0xFFFFFF),
            Color.red(px2) > Color.red(px1) + 10
        )
    }

    // ============================================================
    // 选中高亮（WP3，金色描边——动态叠加不烘焙 chunk）
    // ============================================================

    @Test
    fun `renderFrame - selection highlight draws gold border`() {
        val td = createFlatTileData(10, 10)
        // 高亮矩形 (0,0)-(64,64)；上描边 y∈[0, max(2, 64×0.06)=3.84)
        // 采样点 (32,2)：金色 alpha 0.9 混合底 → r-b 差值大（金色 R=255 B=0）
        val selectedPx = backend.renderFrame(spiritFieldFrame(td, selectedIndex = 0), atlas, 200, 200)!!
            .getPixel(32, 2)
        val unselectedPx = backend.renderFrame(spiritFieldFrame(td, selectedIndex = -1), atlas, 200, 200)!!
            .getPixel(32, 2)

        assertTrue(
            "选中应改变描边点像素: sel=#%06X unsel=#%06X".format(selectedPx and 0xFFFFFF, unselectedPx and 0xFFFFFF),
            selectedPx != unselectedPx
        )
        // 金色特征：red 明显高于 blue（底色米色 r-b≈14，远低于金色混合后的差值）
        assertTrue(
            "描边应为金色: px=#%06X".format(selectedPx and 0xFFFFFF),
            Color.red(selectedPx) - Color.blue(selectedPx) > 150
        )
        assertTrue(
            "未选中时不应有金色描边: px=#%06X".format(unselectedPx and 0xFFFFFF),
            Color.red(unselectedPx) - Color.blue(unselectedPx) < 150
        )
    }

    @Test
    fun `renderFrame - selection highlight respects flag`() {
        val td = createFlatTileData(10, 10)
        val noHighlightBackend = SoftwareCanvasBackend(
            testRenderConfig(renderFlags = RenderFlags(selectionHighlight = false))
        )
        val px = noHighlightBackend.renderFrame(spiritFieldFrame(td, selectedIndex = 0), atlas, 200, 200)!!
            .getPixel(32, 2)
        // flags off：选中但无描边（像素保持底色特征，非金色）
        assertTrue(
            "selectionHighlight=false 时选中不应画金色: px=#%06X".format(px and 0xFFFFFF),
            Color.red(px) - Color.blue(px) < 150
        )
    }

    @Test
    fun `renderFrame - out of range selection index does not crash`() {
        val td = createFlatTileData(10, 10)
        // 防御路径：索引越界（>= buildingCount）→ 跳过高亮绘制
        val frame = spiritFieldFrame(td, selectedIndex = 5)
        val result = backend.renderFrame(frame, atlas, 200, 200)
        assertNotNull("越界选中索引不应 crash", result)
    }
}
