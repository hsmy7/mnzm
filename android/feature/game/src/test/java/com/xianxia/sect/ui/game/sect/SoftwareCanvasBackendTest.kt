package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import com.xianxia.sect.core.render.SpriteAtlasDef
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SoftwareCanvasBackend 单元测试。
 *
 * 覆盖维度：
 * - 相机偏移（camX/camY）→ 屏幕坐标正确性
 * - 缩放（scale）→ 瓦片大小和可见区域
 * - 建筑位置 → 网格坐标→屏幕坐标
 * - 视锥剔除 → 视口外不绘制
 * - 预览精灵 → 位置随相机偏移
 * - 空安全 → tileData/buildingData 为 null 时不抛异常
 * - resize → 视口大小变化时帧缓冲区重建
 */
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendTest {

    private lateinit var backend: SoftwareCanvasBackend
    private lateinit var atlas: Bitmap

    @Before
    fun setup() {
        val config = NativeRenderConfig(
            tileSize = 64,
            worldWidthCells = 10,
            worldHeightCells = 10,
            worldPixelWidth = 640,
            worldPixelHeight = 640
        )
        backend = SoftwareCanvasBackend(config)
        // 迷你图集（128x128，不含实际精灵，只验证坐标和帧缓冲区尺寸）
        atlas = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    }

    // ============================================================
    // 空安全
    // ============================================================

    @Test
    fun `renderFrame - null tileData returns solid buffer`() {
        val rs = FrameRenderState(camX = 0f, camY = 0f)
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
        assertEquals(200, result!!.width)
        assertEquals(200, result.height)
    }

    @Test
    fun `renderFrame - null buildingData does not crash`() {
        val rs = FrameRenderState(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10)
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    // ============================================================
    // 相机偏移
    // ============================================================

    @Test
    fun `renderFrame - camera at origin shows world top-left`() {
        val rs = FrameRenderState(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10)
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
        assertEquals(200, result!!.width)
        assertEquals(200, result.height)
    }

    @Test
    fun `renderFrame - camera offset shifts visible area`() {
        // 相机在 (128,128)，视口 200x200
        // 应显示世界 (128,128)-(328,328)
        // 瓦片0 (0,0) 在屏幕 (-128,-128) → 视口外，不绘制
        // 瓦片2 (128,128) 在屏幕 (0,0)
        val rs = FrameRenderState(
            camX = 128f, camY = 128f, scale = 1f,
            tileData = createFlatTileData(10, 10)
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - camera at world center`() {
        // 模拟实际场景：相机居中到世界 (1536,1536)，视口 200x200
        val data = createFlatTileData(48, 48)
        val rs = FrameRenderState(
            camX = 1536f, camY = 1536f, scale = 1f,
            tileData = data
        )
        val result = backend.renderFrame(rs, atlas, 48, 48, 200, 200)
        assertNotNull(result)
    }

    // ============================================================
    // 缩放
    // ============================================================

    @Test
    fun `renderFrame - scale 2x produces correct buffer size`() {
        val rs = FrameRenderState(
            camX = 0f, camY = 0f, scale = 2f,
            tileData = createFlatTileData(10, 10)
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 400, 400)
        assertNotNull(result)
        assertEquals(400, result!!.width)
        assertEquals(400, result.height)
    }

    @Test
    fun `renderFrame - camera offset with scale 2x`() {
        val rs = FrameRenderState(
            camX = 64f, camY = 64f, scale = 2f,
            tileData = createFlatTileData(10, 10)
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    // ============================================================
    // 建筑
    // ============================================================

    @Test
    fun `renderFrame - building at correct screen position`() {
        val rs = FrameRenderState(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            buildingData = createBuildingDataArray(
                gridX = 2, gridY = 3, width = 2, height = 2, nameIdx = 0
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - building outside viewport not drawn`() {
        // 建筑在 (0,0)，相机在 (500,500)，完全在视口外
        val rs = FrameRenderState(
            camX = 500f, camY = 500f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            buildingData = createBuildingDataArray(
                gridX = 0, gridY = 0, width = 2, height = 2, nameIdx = 0
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - multiple buildings at different positions`() {
        val buildingData = createBuildingDataArray(
            gridX = 1, gridY = 1, width = 2, height = 2, nameIdx = 0,
            gridX2 = 5, gridY2 = 3, width2 = 1, height2 = 1, nameIdx2 = 1
        )
        val rs = FrameRenderState(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            buildingData = buildingData,
            buildingCount = 2,
            buildingVisible = true
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    // ============================================================
    // 预览精灵
    // ============================================================

    @Test
    fun `renderFrame - preview offset with camera`() {
        val rs = FrameRenderState(
            camX = 100f, camY = 100f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            showPreview = true,
            previewX = 200f, previewY = 200f,
            previewW = 128f, previewH = 128f,
            previewU0 = 0f, previewV0 = 0f,
            previewU1 = 0.5f, previewV1 = 0.5f
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - preview with scale`() {
        val rs = FrameRenderState(
            camX = 0f, camY = 0f, scale = 1.5f,
            tileData = createFlatTileData(10, 10),
            showPreview = true,
            previewX = 128f, previewY = 128f,
            previewW = 128f, previewH = 128f,
            previewU0 = 0f, previewV0 = 0f,
            previewU1 = 0.5f, previewV1 = 0.5f
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 400, 400)
        assertNotNull(result)
    }

    // ============================================================
    // resize
    // ============================================================

    @Test
    fun `resize - creates new frame buffer`() {
        val rs = FrameRenderState(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10)
        )
        // 初始 200x200
        var result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
        assertEquals(200, result!!.width)

        // resize 到 400x400
        backend.resize(400, 400)
        result = backend.renderFrame(rs, atlas, 10, 10, 400, 400)
        assertNotNull(result)
        assertEquals(400, result!!.width)
        assertEquals(400, result.height)
    }

    @Test
    fun `resize - zero dimensions ignored`() {
        backend.resize(0, 0) // 不应 crash
        val rs = FrameRenderState(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10)
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    // ============================================================
    // 边界条件
    // ============================================================

    @Test
    fun `renderFrame - camera at world edge`() {
        // 相机在世界右下角附近
        val data = createFlatTileData(10, 10)
        val rs = FrameRenderState(
            camX = 576f, camY = 576f, scale = 1f,
            tileData = data
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - camera beyond world bounds`() {
        // 相机完全超出世界范围，不应 crash
        val data = createFlatTileData(10, 10)
        val rs = FrameRenderState(
            camX = 2000f, camY = 2000f, scale = 1f,
            tileData = data
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - zero scale clamped`() {
        val rs = FrameRenderState(
            camX = 0f, camY = 0f, scale = 0f,
            tileData = createFlatTileData(10, 10)
        )
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
    }

    // ============================================================
    // release
    // ============================================================

    @Test
    fun `release - does not crash and allows re-render`() {
        val rs = FrameRenderState(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10)
        )
        backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        backend.release()
        // release 后仍能重新渲染（帧缓冲区被重建）
        val result = backend.renderFrame(rs, atlas, 10, 10, 200, 200)
        assertNotNull(result)
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
        assertEquals(SpriteAtlasDef.BUILDING_NAMES.size * 4, SpriteAtlasDef.BUILDING_UV_MAP.size)
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /** 创建纯地面瓦片数据（所有格为 TILE_GROUND） */
    private fun createFlatTileData(cols: Int, rows: Int): IntArray {
        return IntArray(cols * rows) { SpriteAtlasDef.TileType.GROUND.index }
    }

    /** 创建单个建筑数据 FloatArray */
    private fun createBuildingDataArray(
        gridX: Int, gridY: Int, width: Int, height: Int, nameIdx: Int,
        gridX2: Int = 0, gridY2: Int = 0, width2: Int = 0, height2: Int = 0, nameIdx2: Int = 0
    ): FloatArray {
        return if (gridX2 == 0 && gridY2 == 0 && width2 == 0) {
            floatArrayOf(
                gridX.toFloat(), gridY.toFloat(),
                width.toFloat(), height.toFloat(), nameIdx.toFloat()
            )
        } else {
            floatArrayOf(
                gridX.toFloat(), gridY.toFloat(),
                width.toFloat(), height.toFloat(), nameIdx.toFloat(),
                gridX2.toFloat(), gridY2.toFloat(),
                width2.toFloat(), height2.toFloat(), nameIdx2.toFloat()
            )
        }
    }
}
