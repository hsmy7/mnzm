package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Color
import com.xianxia.sect.core.render.DemolishHighlightMark
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * SoftwareCanvasBackend 拆除模式占地高亮测试（2026-08-11）。
 *
 * 一键拆除模式高亮从 Compose 覆盖层迁移至 native 渲染层——与建筑精灵
 * 同帧同相机快照绘制，消除拖拽视角时的双时钟相位差。本测试验证：
 * - 绿色/红色填充与红色描边的像素特征（与旧 Compose 覆盖层同色值）
 * - null/NONE 的零侵入语义（非拆除模式与未注册建筑不得绘制）
 * - 防御链：markers 短于 buildingCount / 越界 / 视口外建筑不崩溃
 *
 * @GraphicsMode(NATIVE)：像素断言需要真实 skia 渲染（LEGACY 模式 getPixel 恒 0）
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendDemolishTest {

    private lateinit var backend: SoftwareCanvasBackend
    private lateinit var atlas: Bitmap

    @Before
    fun setup() {
        backend = SoftwareCanvasBackend(testRenderConfig())
        // 真实精灵图集：灵田精灵源 (256,128,384,256)=白 255 上屏，
        // 高亮混合特征可观测（迷你图集源矩形越界则精灵不可见，断言假阳性）
        atlas = createSpriteAtlas()
    }

    private fun greenMarker(): ByteArray = byteArrayOf(DemolishHighlightMark.GREEN.toByte())

    private fun selectedMarker(): ByteArray = byteArrayOf(DemolishHighlightMark.SELECTED.toByte())

    // ============================================================
    // 绿色填充（未选中建筑）
    // ============================================================

    /**
     * 绿色填充 #4CAF50 @ alpha 0.4 画在白色精灵上：
     * r≈183/g≈223/b≈185（RGB_565）→ g-r 差 ≈40。
     * 白色精灵 g-r=0——>20 阈值同时排除"高亮未绘制"与"精灵未上屏"两种假阳性。
     */
    @Test
    fun `renderFrame - demolish mode paints green fill on unselected building`() {
        val td = createFlatTileData(10, 10)
        val highlighted = backend.renderFrame(demolishFrame(td, greenMarker()), atlas, 200, 200)!!
            .getPixel(32, 32)
        val baseline = backend.renderFrame(spiritFieldFrame(td), atlas, 200, 200)!!
            .getPixel(32, 32)

        assertTrue(
            "拆除模式未选中建筑应为绿色填充: px=#%06X".format(highlighted and 0xFFFFFF),
            Color.green(highlighted) - Color.red(highlighted) > 20
        )
        assertTrue(
            "基准帧同点不应有绿特征（白色精灵）: px=#%06X".format(baseline and 0xFFFFFF),
            Color.green(baseline) - Color.red(baseline) < 20
        )
    }

    // ============================================================
    // 红色填充 + 红色描边（选中建筑）
    // ============================================================

    /**
     * 选中建筑：中心 = 红填充 #F44336 @ 0.4 混合白精灵（r≈250/g≈180 → r-g≈68）；
     * 上边 (32,2) = 不透明红边 #F44336 直接覆盖（g≈68）——g 通道显著低于中心，
     * 且边缘 r-g 差 >100（红特征更强），验证描边盖住填充边缘。
     */
    @Test
    fun `renderFrame - selected building paints red fill plus opaque red edge`() {
        val td = createFlatTileData(10, 10)
        val rendered = backend.renderFrame(demolishFrame(td, selectedMarker()), atlas, 200, 200)!!
        val centerPx = rendered.getPixel(32, 32)
        val edgePx = rendered.getPixel(32, 2)

        assertTrue(
            "选中建筑中心应为红色填充: px=#%06X".format(centerPx and 0xFFFFFF),
            Color.red(centerPx) - Color.green(centerPx) > 20
        )
        assertTrue(
            "描边为不透明红，g 通道应显著低于半透明填充: edge=#%06X center=#%06X"
                .format(edgePx and 0xFFFFFF, centerPx and 0xFFFFFF),
            Color.green(edgePx) < Color.green(centerPx) - 40
        )
        assertTrue(
            "描边应具强红特征: edge=#%06X".format(edgePx and 0xFFFFFF),
            Color.red(edgePx) - Color.green(edgePx) > 100
        )
    }

    // ============================================================
    // 零侵入语义（null / NONE）
    // ============================================================

    /** null markers = 非拆除模式 → 与基准帧（无高亮层）逐像素一致 */
    @Test
    fun `renderFrame - null markers leave frame pixel-identical to baseline`() {
        val td = createFlatTileData(10, 10)
        // ⚠️ 帧缓冲复用：渲染后立即拷贝，否则第二次渲染覆盖第一次内容（恒等假阳性）
        val rendered = Bitmap.createBitmap(
            backend.renderFrame(demolishFrame(td, null), atlas, 200, 200)!!, 0, 0, 200, 200
        )
        val baseline = Bitmap.createBitmap(
            backend.renderFrame(spiritFieldFrame(td), atlas, 200, 200)!!, 0, 0, 200, 200
        )

        assertTrue(
            "非拆除模式（markers=null）不得绘制任何高亮像素",
            pixelsEqual(rendered, baseline)
        )
    }

    /** marker=NONE（未注册建筑/不参与拆除）→ 同点与基准帧一致 */
    @Test
    fun `renderFrame - NONE marker does not paint`() {
        val td = createFlatTileData(10, 10)
        val nonePx = backend.renderFrame(
            demolishFrame(td, byteArrayOf(DemolishHighlightMark.NONE.toByte())), atlas, 200, 200
        )!!.getPixel(32, 32)
        val baselinePx = backend.renderFrame(spiritFieldFrame(td), atlas, 200, 200)!!
            .getPixel(32, 32)

        assertTrue(
            "NONE 标记不得绘制: px=#%06X".format(nonePx and 0xFFFFFF),
            Color.green(nonePx) - Color.red(nonePx) < 20
        )
        assertTrue(
            "NONE 标记像素应与基准一致: none=#%06X base=#%06X"
                .format(nonePx and 0xFFFFFF, baselinePx and 0xFFFFFF),
            nonePx == baselinePx
        )
    }

    // ============================================================
    // 防御链（双后端同 clamp 语义）
    // ============================================================

    /** markers 短于 buildingCount：count 双重 clamp 到可用容量，多余建筑不高亮 */
    @Test
    fun `renderFrame - markers shorter than buildingCount clamps without crash`() {
        val td = createFlatTileData(10, 10)
        // 建筑0 (0,0) 标记 GREEN；建筑1 (2,0) 无标记 → count=min(2,1)=1
        val rendered = backend.renderFrame(twoBuildingFrame(td, greenMarker()), atlas, 200, 200)!!
        val firstPx = rendered.getPixel(32, 32)
        val secondPx = rendered.getPixel(160, 32)
        val secondBaseline = backend.renderFrame(twoBuildingFrame(td, null), atlas, 200, 200)!!
            .getPixel(160, 32)

        assertTrue(
            "有标记的建筑应画绿: px=#%06X".format(firstPx and 0xFFFFFF),
            Color.green(firstPx) - Color.red(firstPx) > 20
        )
        assertTrue(
            "无标记建筑不得绘制，且与基准一致: px=#%06X base=#%06X"
                .format(secondPx and 0xFFFFFF, secondBaseline and 0xFFFFFF),
            secondPx == secondBaseline
        )
    }

    /** 视口外建筑（世界右边界外）→ offScreen 剔除，正常出帧不崩溃 */
    @Test
    fun `renderFrame - building outside viewport is culled without crash`() {
        val td = createFlatTileData(10, 10)
        // 建筑 (10,0)：left=640px ≥ 视口宽 200 → offScreenX 剔除
        val frame = spiritFieldFrame(td).copy(
            buildingData = createBuildingDataArray(
                gridX = 10, gridY = 0, width = 1, height = 1, nameIdx = 2
            ),
            demolishHighlightData = greenMarker()
        )
        val result = backend.renderFrame(frame, atlas, 200, 200)
        assertNotNull("视口外建筑不应 crash", result)
    }

    /** markers 越界（长于 buildingData 容量）：count clamp + break 截断防御 */
    @Test
    fun `renderFrame - markers longer than building capacity truncates without crash`() {
        val td = createFlatTileData(10, 10)
        // buildingData 仅 1 建筑（容量 1），markers 长 5、buildingCount=3：
        // count = min(3,5) coerceAtMost(5/5) = 1 → 只绘制真实存在的建筑
        val frame = spiritFieldFrame(td).copy(
            buildingCount = 3,
            demolishHighlightData = byteArrayOf(
                DemolishHighlightMark.GREEN.toByte(),
                DemolishHighlightMark.SELECTED.toByte(),
                DemolishHighlightMark.GREEN.toByte(),
                DemolishHighlightMark.GREEN.toByte(),
                DemolishHighlightMark.GREEN.toByte()
            )
        )
        val px = backend.renderFrame(frame, atlas, 200, 200)!!
            .getPixel(32, 32)

        assertTrue(
            "clamp 后真实建筑应正常画绿: px=#%06X".format(px and 0xFFFFFF),
            Color.green(px) - Color.red(px) > 20
        )
    }

    // ============================================================
    // helpers
    // ============================================================

    private fun pixelsEqual(a: Bitmap, b: Bitmap): Boolean {
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return false
            }
        }
        return true
    }
}
