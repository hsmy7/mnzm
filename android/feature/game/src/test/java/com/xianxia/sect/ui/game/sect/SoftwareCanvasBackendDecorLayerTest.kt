package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.SpriteAtlasDef
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * SoftwareCanvasBackend 装饰分层/越界渲染测试。
 *
 * 覆盖渲染不变量（双端同源契约 = gamecore/map/draw_order.h）：
 * 1. 立体层装饰（树）与建筑**同一画家序**——树在建筑前方（更大地面接触点）时
 *    覆盖建筑，在后方时被建筑覆盖（旧实现把树留在地面层，北侧建筑会把树冠无脑压掉）。
 * 2. 装饰遍历范围外扩——chunk 顶行的树，其树冠越界进入上一 chunk 的部分必须被绘制
 *    （旧实现只遍历本 chunk 的格 → 树冠在 chunk 缝处被整块裁掉）。
 * 3. 装饰 LOD/热控跳过（decorSkip）时立体层装饰同样不绘制。
 *
 * @GraphicsMode(NATIVE)：像素断言需要真实 skia 渲染（与 SoftwareCanvasBackendTest 同约定）。
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendDecorLayerTest {

    private companion object {
        /** 树瓦片 index（layers：object） */
        val TREE_TILE = SpriteAtlasDef.TileType.TREE1.index

        /** 建筑精灵索引（灵田：占地 1×1——测试帧直接给精灵尺寸，占地只用于底部对齐） */
        const val BUILDING_NAME_IDX = 2

        val GROUND_COLOR = Color.rgb(100, 100, 100)
        val TREE_COLOR = Color.rgb(0, 200, 0)
        val BUILDING_COLOR = Color.rgb(255, 0, 0)

        /** 生产格尺寸（树 2 × 3.291 格 = 96×158px 的锚点契约按此常量验证） */
        const val TILE = 48
    }

    private lateinit var atlas: Bitmap

    @Before
    fun setup() {
        atlas = createDecorAtlas()
    }

    /** 测试图集：地面=灰、树槽位=绿、建筑槽位=红（与 SpriteAtlasDef 源矩形对齐缩放） */
    private fun createDecorAtlas(): Bitmap {
        val w = 1024
        val bmp = createBitmap(w, w, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(
            scaledFixtureRect(w, SpriteAtlasDef.TileType.GROUND.rect),
            Paint().apply { color = GROUND_COLOR }
        )
        c.drawRect(
            scaledFixtureRect(w, SpriteAtlasDef.TileType.TREE1.rect),
            Paint().apply { color = TREE_COLOR }
        )
        c.drawRect(
            scaledFixtureRect(w, SpriteAtlasDef.buildingRect(BUILDING_NAME_IDX)),
            Paint().apply { color = BUILDING_COLOR }
        )
        return bmp
    }

    private fun tileDataWithTreeAt(cols: Int, rows: Int, treeCol: Int, treeRow: Int): IntArray =
        IntArray(cols * rows) { idx ->
            val c = idx % cols
            val r = idx / cols
            if (c == treeCol && r == treeRow) TREE_TILE else SpriteAtlasDef.TileType.GROUND.index
        }

    /**
     * 像素颜色断言（通道容差）。
     *
     * 不能逐位相等：chunk 位图为 RGB_565 且合成帧时按 TOPDOWN_Y_SCALE 做双线性缩放，
     * 采样点会与相邻行插值（实测偏差 ≤3/255）；三种测试色（地面灰/树绿/建筑红）
     * 两两差异 ≥100，容差 8 足以区分"谁盖住谁"。
     */
    private fun assertColorNear(expected: Int, actual: Int, message: String) {
        val tolerance = 8
        val delta = maxOf(
            kotlin.math.abs(Color.red(expected) - Color.red(actual)),
            kotlin.math.abs(Color.green(expected) - Color.green(actual)),
            kotlin.math.abs(Color.blue(expected) - Color.blue(actual))
        )
        assertTrue(
            "$message（期望 rgb(${Color.red(expected)},${Color.green(expected)},${Color.blue(expected)})" +
                "，实到 rgb(${Color.red(actual)},${Color.green(actual)},${Color.blue(actual)})）",
            delta <= tolerance
        )
    }

    private fun render(tileData: IntArray, cols: Int, rows: Int, camY: Float = 0f): Bitmap {
        val backend = SoftwareCanvasBackend(
            testRenderConfig(tileSize = TILE, worldWidthCells = cols, worldHeightCells = rows)
        )
        val frame = RenderFrame(
            camX = 0f, camY = camY, scale = 1f,
            tileData = tileData, cols = cols, rows = rows,
            buildingData = createBuildingDataArray(
                gridX = 2, gridY = 2, width = 2, height = 2, nameIdx = BUILDING_NAME_IDX
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 220)
        assertNotNull("renderFrame 不应返回 null", result)
        return result ?: error("renderFrame 返回 null")
    }

    @Test
    fun `立体层装饰在建筑前方时覆盖建筑 - 画家序按地面接触点`() {
        // 树在 (2,4)：底边 240 > 建筑底边 (2+1)×48=144 → 树在后绘（前方），应覆盖建筑
        // 世界→屏幕：x 不压缩、y × TOPDOWN_Y_SCALE(0.75)
        // 建筑世界 y 48..144 → 屏幕 36..108；树世界 y 82..240 → 屏幕 61..180
        val bmp = render(tileDataWithTreeAt(10, 10, treeCol = 2, treeRow = 4), cols = 10, rows = 10)
        // 重叠区间（屏幕 y 61..108）内取点：树色而非建筑色
        assertColorNear(TREE_COLOR, bmp.getPixel(120, 85), "树（前方）应覆盖建筑精灵")
        // 建筑专属区（屏幕 y 36..61）仍为建筑色（未被树覆盖）
        assertColorNear(BUILDING_COLOR, bmp.getPixel(120, 48), "树冠之外的建筑区应为建筑色")
    }

    @Test
    fun `立体层装饰在建筑后方时被建筑覆盖`() {
        // 树在 (2,1)：底边 96 < 建筑底边 144 → 树先绘（后方），建筑覆盖其下半段
        // 树世界 y −62..96 → 屏幕 −46..72；建筑世界 y 48..144 → 屏幕 36..108
        val bmp = render(tileDataWithTreeAt(10, 10, treeCol = 2, treeRow = 1), cols = 10, rows = 10)
        // 重叠区间（屏幕 y 36..72）内取点：建筑色而非树色
        assertColorNear(BUILDING_COLOR, bmp.getPixel(120, 55), "建筑（前方）应覆盖后方树的下半段")
        // 树冠上部（屏幕 y < 36）仍在建筑之上可见
        assertColorNear(TREE_COLOR, bmp.getPixel(120, 15), "树冠上部应可见")
    }

    @Test
    fun `chunk 顶行树的树冠不被裁切 - 装饰遍历范围外扩`() {
        // 世界 40×40 格（chunk 0 = 行 0..31、chunk 1 = 行 32..39）；树在 (1,32)
        //（chunk 1 的首行），其树冠向上越界进入 chunk 0——chunk 0 必须补绘越界段
        val bmp = render(
            tileDataWithTreeAt(40, 40, treeCol = 1, treeRow = 32),
            cols = 40, rows = 40, camY = 1400f
        )
        // 树世界 y 1426..1584（屏幕 19..138）；chunk 缝在屏幕 y=(1536−1400)×0.75=102
        // 取 chunk 0 范围内的树冠段（屏幕 y 19..102）
        assertColorNear(TREE_COLOR, bmp.getPixel(72, 60), "chunk 缝内的树冠应被绘制")
        // 树冠顶之上仍是地面（外扩范围不越界多画）
        assertColorNear(GROUND_COLOR, bmp.getPixel(72, 8), "树冠上方应为地面")
    }

    @Test
    fun `装饰 LOD 跳过时立体层装饰不绘制`() {
        val backend = SoftwareCanvasBackend(
            testRenderConfig(tileSize = TILE, worldWidthCells = 10, worldHeightCells = 10)
        )
        backend.decorationsDisabled = true
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = tileDataWithTreeAt(10, 10, treeCol = 2, treeRow = 4), cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 2, gridY = 2, width = 2, height = 2, nameIdx = BUILDING_NAME_IDX
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val bmp = backend.renderFrame(frame, atlas, vpW = 200, vpH = 220)
        assertNotNull(bmp)
        // 装饰关闭：树原本覆盖区（屏幕 y 61..180 / 世界 y 82..240）回到地面色
        val pixel = bmp?.getPixel(120, 130) ?: error("renderFrame 返回 null")
        assertColorNear(GROUND_COLOR, pixel, "装饰关闭时不应绘制立体层装饰")
    }
}
