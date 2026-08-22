package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.SpriteAtlasDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * SoftwareCanvasBackend SpriteAtlasDef 一致性与地砖绘制测试。
 *
 * - SpriteAtlasDef：BUILDING_NAMES/UV 映射/地砖 rect 等静态数据自洽
 * - 地砖：floorTileIndex 尺寸映射 + 灵田跳过/灵矿专属地皮等绘制路径
 *
 * @GraphicsMode(NATIVE)：像素断言需要真实 skia 渲染（LEGACY 模式 getPixel 恒 0）
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendAtlasTest {

    private lateinit var backend: SoftwareCanvasBackend
    private lateinit var atlas: Bitmap

    @Before
    fun setup() {
        backend = SoftwareCanvasBackend(testRenderConfig())
        // 迷你图集（128x128，不含实际精灵，只验证坐标和帧缓冲区尺寸）
        atlas = createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    }

    // ============================================================
    // SpriteAtlasDef 一致性
    // ============================================================

    @Test
    fun `spriteAtlasDef - BUILDING_NAMES map matches index`() {
        SpriteAtlasDef.BUILDING_NAMES.forEachIndexed { idx, name ->
            val mapIdx = SpriteAtlasDef.BUILDING_NAME_INDEX[name]
            assertEquals("$name 索引不一致", idx, mapIdx)
        }
    }

    @Test
    fun `spriteAtlasDef - buildingRect matches BUILDING_NAMES size`() {
        for (i in SpriteAtlasDef.BUILDING_NAMES.indices) {
            val rect = SpriteAtlasDef.buildingRect(i)
            assertTrue("buildingRect $i: w=${rect.w}", rect.w > 0)
            assertTrue("buildingRect $i: h=${rect.h}", rect.h > 0)
            assertEquals(SpriteAtlasDef.BUILDING_SIZE, rect.w)
            assertEquals(SpriteAtlasDef.BUILDING_SIZE, rect.h)
        }
    }

    @Test
    fun `spriteAtlasDef - TILE_UV_MAP has correct size`() {
        val expectedEntries = SpriteAtlasDef.TileType.values().size
        assertEquals(expectedEntries * 4, SpriteAtlasDef.TILE_UV_MAP.size)
    }

    @Test
    fun `spriteAtlasDef - BUILDING_UV_MAP has correct size`() {
        // 建筑 UV + 固定结构 UV（结构追加在尾部，nameIdx = BUILDING_NAMES.size + index）
        assertEquals(
            (SpriteAtlasDef.BUILDING_NAMES.size + SpriteAtlasDef.STRUCTURES.size) * 4,
            SpriteAtlasDef.BUILDING_UV_MAP.size
        )
    }

    // ============================================================
    // 地砖（Floor Tile）
    // ============================================================

    @Test
    fun `renderFrame - 2x2 building draws floor tile without crash`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 2, gridY = 2, width = 2, height = 2, nameIdx = 0
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull("2x2 建筑 + 地砖不应 crash", result)
    }

    @Test
    fun `renderFrame - spirit field does not draw floor tile`() {
        // 灵田 nameIdx=2，应跳过地砖绘制
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 2, gridY = 2, width = 1, height = 1, nameIdx = 2
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull("灵田跳过地砖不应 crash", result)
    }

    @Test
    fun `renderFrame - spirit mine uses custom ground cover`() {
        // 灵矿场 nameIdx=0，应使用专属地皮覆盖（ftIdx=4）而非通用地砖
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 2, gridY = 2, width = 4, height = 4, nameIdx = 0
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull("灵矿场地皮覆盖不应 crash", result)
    }

    @Test
    fun `renderFrame - 3x2 building uses floor tile index 2 without crash`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 1, gridY = 1, width = 3, height = 2, nameIdx = 5
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull("3x2 建筑 + 地砖不应 crash", result)
    }

    @Test
    fun `renderFrame - 2x3 building uses floor tile index 1 without crash`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 1, gridY = 1, width = 2, height = 3, nameIdx = 7
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull("2x3 建筑 + 地砖不应 crash", result)
    }

    @Test
    fun `floorTileIndex - returns correct index for each size`() {
        assertEquals(0, SpriteAtlasDef.floorTileIndex(2, 2))
        assertEquals(1, SpriteAtlasDef.floorTileIndex(2, 3))
        assertEquals(2, SpriteAtlasDef.floorTileIndex(3, 2))
        assertEquals(3, SpriteAtlasDef.floorTileIndex(3, 3))
    }

    @Test
    fun `floorTileIndex - spirit field 1x1 returns -1`() {
        assertEquals(-1, SpriteAtlasDef.floorTileIndex(1, 1))
    }

    @Test
    fun `floorTileIndex - new footprint sizes map to closest tile`() {
        assertEquals(3, SpriteAtlasDef.floorTileIndex(4, 4))  // 方形 → 3x3
        assertEquals(2, SpriteAtlasDef.floorTileIndex(6, 4))  // 宽扁 → 3x2
        assertEquals(1, SpriteAtlasDef.floorTileIndex(4, 6))  // 窄高 → 2x3
        assertEquals(3, SpriteAtlasDef.floorTileIndex(6, 6))  // 大方 → 3x3
        assertEquals(1, SpriteAtlasDef.floorTileIndex(4, 8))  // 瘦高 → 2x3
        assertEquals(1, SpriteAtlasDef.floorTileIndex(2, 4))  // 窄高 → 2x3
        assertEquals(2, SpriteAtlasDef.floorTileIndex(4, 3))  // 宽扁 → 3x2
        assertEquals(2, SpriteAtlasDef.floorTileIndex(6, 5))  // 宽扁 → 3x2
        assertEquals(2, SpriteAtlasDef.floorTileIndex(6, 2))  // 门楼占地 6x2 → 3x2
        assertEquals(2, SpriteAtlasDef.floorTileIndex(12, 6))  // 天枢殿占地 12x6 → 3x2（拉伸）
    }

    @Test
    fun `spriteAtlasDef - FOOTPRINT_BY_NAME_INDEX matches BUILDING_NAMES size`() {
        assertEquals(
            SpriteAtlasDef.BUILDING_NAMES.size,
            SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.size
        )
    }

    @Test
    fun `renderFrame - 固定结构门楼阶梯绘制不 crash`() {
        // 结构条目（nameIdx = BUILDING_NAMES.size + index），渲染走建筑层
        val base = SpriteAtlasDef.BUILDING_NAMES.size
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(20, 20),
            cols = 20, rows = 20,
            buildingData = createBuildingDataArray(
                gridX = 2, gridY = 16, width = 6, height = 4, nameIdx = base
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull("固定结构（建筑层渲染）不应 crash", result)
    }

    @Test
    fun `renderFrame - 固定结构精灵实际可见（像素级）`() {
        // 全尺寸图集：全灰地面 + 门楼源矩形涂白（STRUCTURES[0].rect）
        val fullAtlas = createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val c = Canvas(fullAtlas)
        c.drawRect(0f, 0f, 2048f, 2048f, Paint().apply { color = Color.rgb(100, 100, 100) })
        val gateRect = SpriteAtlasDef.STRUCTURES[0].rect
        c.drawRect(
            gateRect.x.toFloat(), gateRect.y.toFloat(),
            (gateRect.x + gateRect.w).toFloat(), (gateRect.y + gateRect.h).toFloat(),
            Paint().apply { color = Color.WHITE }
        )

        // 门楼精灵 6×4 底部对齐占地 6×2，位于 (0,2) → 世界 y = 2*64 - (4-2)*64 = 0..256
        val base = SpriteAtlasDef.BUILDING_NAMES.size
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(20, 20),
            cols = 20, rows = 20,
            buildingData = createBuildingDataArray(
                gridX = 0, gridY = 2, width = 6, height = 4, nameIdx = base
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, fullAtlas, vpW = 400, vpH = 200)
        assertNotNull(result)
        val fb = result!!
        // (200,100) 在门楼精灵内 → 白；(395,190) 在门楼外 → 地面灰
        val gatePx = fb.getPixel(200, 100)
        val groundPx = fb.getPixel(395, 190)
        assertTrue(
            "门楼精灵应真实上屏（gate=${Color.red(gatePx)}, ground=${Color.red(groundPx)}）",
            Color.red(gatePx) > Color.red(groundPx) + 100
        )
    }

    @Test
    fun `spriteAtlasDef - FLOOR_TILE_UV_MAP has correct size`() {
        assertEquals(5 * 4, SpriteAtlasDef.FLOOR_TILE_UV_MAP.size)
    }

    @Test
    fun `spriteAtlasDef - floorTileRect returns valid rect for all indices`() {
        for (i in 0 until 5) {
            val rect = SpriteAtlasDef.floorTileRect(i)
            assertTrue("floorTileRect $i: w=${rect.w}", rect.w > 0)
            assertTrue("floorTileRect $i: h=${rect.h}", rect.h > 0)
        }
    }
}
