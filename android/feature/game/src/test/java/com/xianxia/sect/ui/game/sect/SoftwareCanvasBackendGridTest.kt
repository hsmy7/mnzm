package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Color
import com.xianxia.sect.core.render.DemolishHighlightMark
import com.xianxia.sect.core.render.RenderFrame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * SoftwareCanvasBackend 放置/移动模式网格线测试（2026-08-11）。
 *
 * 网格线从 Compose 覆盖层（GridOverlay）迁移至 native 渲染层——范围数学
 * 照抄旧 drawFullGrid（first/last 列行钳制到世界边界），与地图同帧同相机。
 *
 * 相机 camX=0/scale=1/视口 200×200：列线 x=0,64,128,192；行线 y=0,64,128,192。
 * 线宽 1f 中心在整数坐标 → 像素列/行 0 起 50% 抗锯齿覆盖。
 *
 * ⚠️ 帧缓冲复用陷阱：renderFrame 返回内部复用 Bitmap，第二次渲染覆盖第一次
 * 内容——必须用 [renderFrameCopy] 立即拷贝，否则 on/off 比较恒同（假阳性）。
 *
 * @GraphicsMode(NATIVE)：像素断言需要真实 skia 渲染（LEGACY 模式 getPixel 恒 0）
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendGridTest {

    private lateinit var backend: SoftwareCanvasBackend
    private lateinit var atlas: Bitmap

    @Before
    fun setup() {
        backend = SoftwareCanvasBackend(testRenderConfig())
        // 真实精灵图集（地面源灰 100 + 建筑精灵白 255，均可上屏）
        atlas = createSpriteAtlas()
    }

    private fun gridFrame(td: IntArray, visible: Boolean): RenderFrame {
        return spiritFieldFrame(td).copy(gridOverlayVisible = visible)
    }

    /** 渲染并立即拷贝独立 Bitmap（防帧缓冲复用覆盖） */
    private fun renderFrameCopy(frame: RenderFrame): Bitmap {
        val rendered = backend.renderFrame(frame, atlas, 200, 200)!!
        return Bitmap.createBitmap(rendered, 0, 0, 200, 200)
    }

    // ============================================================
    // full 模式画线
    // ============================================================

    /**
     * 放置模式（gridOverlayVisible=true）：视口内 4 列 + 4 行网格线。
     * - 全帧采样（步长 4 恰好命中全部线坐标）差异像素数在 [50, 5000]
     * - 竖线位置验证：像素 (64,8) 地面灰底（建筑外、阴影外）混入网格色，
     *   红通道 96 → ~160（网格色比灰底亮）
     * - 横线位置验证：像素 (32,0) 建筑白精灵上混入网格色，蓝通道
     *   248 → ~232（网格色比白底暗）
     */
    @Test
    fun `renderFrame - grid overlay draws vertical and horizontal lines in full mode`() {
        val td = createFlatTileData(10, 10)
        val on = renderFrameCopy(gridFrame(td, visible = true))
        val off = renderFrameCopy(gridFrame(td, visible = false))

        val diffCount = countDiffPixels(on, off)
        assertTrue(
            "网格线应产生数十级差异像素（实测 $diffCount）",
            diffCount in 50..5000
        )
        // 竖线 col=1 在 x=64：地面灰底（建筑右边界外、阴影 y≥16 外）
        assertTrue(
            "竖线位置 (64,8) 应混入网格色: on=#%06X off=#%06X"
                .format(on.getPixel(64, 8) and 0xFFFFFF, off.getPixel(64, 8) and 0xFFFFFF),
            Color.red(on.getPixel(64, 8)) - Color.red(off.getPixel(64, 8)) > 30
        )
        // 横线 row=0 在 y=0：白色精灵上混入网格色（变暗）
        assertTrue(
            "横线位置 (32,0) 应混入网格色: on=#%06X off=#%06X"
                .format(on.getPixel(32, 0) and 0xFFFFFF, off.getPixel(32, 0) and 0xFFFFFF),
            Color.blue(off.getPixel(32, 0)) - Color.blue(on.getPixel(32, 0)) > 8
        )
    }

    // ============================================================
    // 关闭零侵入
    // ============================================================

    /** gridOverlayVisible=false（非放置/移动模式）→ 与基准帧逐像素一致 */
    @Test
    fun `renderFrame - grid disabled leaves frame pixel-identical to baseline`() {
        val td = createFlatTileData(10, 10)
        val rendered = renderFrameCopy(gridFrame(td, visible = false))
        val baseline = renderFrameCopy(spiritFieldFrame(td))

        assertTrue(
            "网格关闭不得绘制任何像素",
            countDiffPixels(rendered, baseline) == 0
        )
    }

    // ============================================================
    // 相机越界防御
    // ============================================================

    /** 相机完全越出世界左边界：first>last → 空区间不画线，正常出帧 */
    @Test
    fun `renderFrame - camera beyond world left edge draws no lines without crash`() {
        val td = createFlatTileData(10, 10)
        // camX=-500：firstCol=0 > lastCol=-5 → 列区间空；camY 同 → 行区间空
        val frame = spiritFieldFrame(td).copy(
            camX = -500f, camY = -500f,
            gridOverlayVisible = true
        )
        val result = renderFrameCopy(frame)
        val off = renderFrameCopy(spiritFieldFrame(td).copy(camX = -500f, camY = -500f))

        assertNotNull("相机越出世界边界不应 crash", result)
        assertTrue(
            "越界相机空区间不得画线",
            countDiffPixels(result, off) == 0
        )
    }

    /** 相机越出世界右/下边界：区间钳制到 [0, worldCells]，仅残留边界线，不崩溃 */
    @Test
    fun `renderFrame - camera beyond world right edge clamps range without crash`() {
        val td = createFlatTileData(10, 10)
        // camX=600：firstCol=9 → 钳制 lastCol=10 → 列线 x=-24（视口外裁剪）/x=40（可见）
        val frame = spiritFieldFrame(td).copy(
            camX = 600f, camY = 600f,
            gridOverlayVisible = true
        )
        val result = backend.renderFrame(frame, atlas, 200, 200)

        assertNotNull("相机越出世界右边界不应 crash", result)
    }

    // ============================================================
    // 与拆除高亮共存零干扰
    // ============================================================

    /** 拆除模式（高亮 markers 在场）且网格关闭：与无网格帧一致——两图层独立无串扰 */
    @Test
    fun `renderFrame - normal map with demolish markers unchanged when grid off`() {
        val td = createFlatTileData(10, 10)
        val markers = byteArrayOf(DemolishHighlightMark.GREEN.toByte())
        val frame = spiritFieldFrame(td).copy(
            demolishHighlightData = markers,
            gridOverlayVisible = false
        )
        val rendered = renderFrameCopy(frame)
        val baseline = renderFrameCopy(
            spiritFieldFrame(td).copy(demolishHighlightData = markers)
        )

        assertTrue(
            "网格关闭时拆除高亮帧应与自身一致（无网格像素）",
            countDiffPixels(rendered, baseline) == 0
        )
    }

    // ============================================================
    // helpers
    // ============================================================

    /** 步长 4 全帧采样（恰好命中 64px 网格线上的整数坐标），任一通道差 >10 计入 */
    private fun countDiffPixels(a: Bitmap, b: Bitmap): Int {
        var count = 0
        for (y in 0 until 200 step 4) {
            for (x in 0 until 200 step 4) {
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                if (kotlin.math.abs(Color.red(pa) - Color.red(pb)) > 10 ||
                    kotlin.math.abs(Color.green(pa) - Color.green(pb)) > 10 ||
                    kotlin.math.abs(Color.blue(pa) - Color.blue(pb)) > 10
                ) count++
            }
        }
        return count
    }
}
