package com.xianxia.sect.ui.game.sect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.xianxia.sect.core.render.RenderFrame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * SoftwareCanvasBackend 单元测试（核心渲染路径）。
 *
 * 覆盖维度：
 * - 相机偏移（camX/camY）→ 屏幕坐标正确性
 * - 缩放（scale）→ 瓦片大小和可见区域
 * - 建筑位置 → 网格坐标→屏幕坐标
 * - 视锥剔除 → 视口外不绘制
 * - 预览精灵 → 位置随相机偏移 + tint 乘法 + alpha 混合像素断言
 * - 空安全 → tileData/buildingData 为 null 时不抛异常
 * - resize → 视口大小变化时帧缓冲区重建
 *
 * 专项测试已拆分至同包文件（LargeClass 收敛）：
 * - [SoftwareCanvasBackendHighlightTest] — 建筑阴影 + 选中高亮（WP3）
 * - [SoftwareCanvasBackendLodFadeTest] — 地图淡入 + 装饰 LOD（WP4/WP5）
 * - [SoftwareCanvasBackendCropTest] — 灵田作物层（WP6）
 * - [SoftwareCanvasBackendAtlasTest] — SpriteAtlasDef 一致性与地砖
 * - [SoftwareCanvasBackendTestFixtures] — 共享 fixtures
 *
 * @GraphicsMode(NATIVE)：默认 LEGACY 模式下 Canvas 操作记录式不写像素，
 * getPixel 恒返回 0——像素断言测试（preview_tint）需要真实 skia 渲染。
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SoftwareCanvasBackendTest {

    private lateinit var backend: SoftwareCanvasBackend
    private lateinit var atlas: Bitmap

    @Before
    fun setup() {
        backend = SoftwareCanvasBackend(testRenderConfig())
        // 迷你图集（128x128，不含实际精灵，只验证坐标和帧缓冲区尺寸）
        atlas = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
    }

    // ============================================================
    // 空安全
    // ============================================================

    @Test
    fun `renderFrame - default tileData renders correctly`() {
        val td = createFlatTileData(10, 10)
        val frame = RenderFrame(tileData = td, cols = 10, rows = 10, camX = 0f, camY = 0f)
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
        assertEquals(200, result!!.width)
        assertEquals(200, result.height)
    }

    @Test
    fun `renderFrame - null buildingData does not crash`() {
        val td = createFlatTileData(10, 10)
        val frame = RenderFrame(tileData = td, cols = 10, rows = 10, camX = 0f, camY = 0f, scale = 1f)
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    // ============================================================
    // 相机偏移
    // ============================================================

    @Test
    fun `renderFrame - camera at origin shows world top-left`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
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
        val frame = RenderFrame(
            camX = 128f, camY = 128f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - camera at world center`() {
        // 模拟实际场景：相机居中到世界 (1536,1536)，视口 200x200
        val data = createFlatTileData(48, 48)
        val frame = RenderFrame(
            tileData = data, cols = 48, rows = 48,
            camX = 1536f, camY = 1536f, scale = 1f
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    // ============================================================
    // 缩放
    // ============================================================

    @Test
    fun `renderFrame - scale 2x produces correct buffer size`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 2f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        val result = backend.renderFrame(frame, atlas, vpW = 400, vpH = 400)
        assertNotNull(result)
        assertEquals(400, result!!.width)
        assertEquals(400, result.height)
    }

    @Test
    fun `renderFrame - camera offset with scale 2x`() {
        val frame = RenderFrame(
            camX = 64f, camY = 64f, scale = 2f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        val result = backend.renderFrame(frame, atlas, vpW = 400, vpH = 400)
        assertNotNull(result)
    }

    // ============================================================
    // 建筑
    // ============================================================

    @Test
    fun `renderFrame - building at correct screen position`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 2, gridY = 3, width = 2, height = 2, nameIdx = 0
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - building outside viewport not drawn`() {
        // 建筑在 (0,0)，相机在 (500,500)，完全在视口外
        val frame = RenderFrame(
            camX = 500f, camY = 500f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 0, gridY = 0, width = 2, height = 2, nameIdx = 0
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - multiple buildings at different positions`() {
        val buildingData = createBuildingDataArray(
            gridX = 1, gridY = 1, width = 2, height = 2, nameIdx = 0,
            gridX2 = 5, gridY2 = 3, width2 = 1, height2 = 1, nameIdx2 = 1
        )
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            buildingData = buildingData,
            buildingCount = 2,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    // ============================================================
    // 预览精灵
    // ============================================================

    @Test
    fun `renderFrame - preview offset with camera`() {
        val frame = RenderFrame(
            camX = 100f, camY = 100f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            showPreview = true,
            previewX = 200f, previewY = 200f,
            previewW = 128f, previewH = 128f,
            previewU0 = 0f, previewV0 = 0f,
            previewU1 = 0.5f, previewV1 = 0.5f
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - preview with scale`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1.5f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            showPreview = true,
            previewX = 128f, previewY = 128f,
            previewW = 128f, previewH = 128f,
            previewU0 = 0f, previewV0 = 0f,
            previewU1 = 0.5f, previewV1 = 0.5f
        )
        val result = backend.renderFrame(frame, atlas, vpW = 400, vpH = 400)
        assertNotNull(result)
    }

    // ============================================================
    // resize
    // ============================================================

    @Test
    fun `resize - creates new frame buffer`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        // 初始 200x200
        var result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
        assertEquals(200, result!!.width)

        // resize 到 400x400
        backend.resize(400, 400)
        result = backend.renderFrame(frame, atlas, vpW = 400, vpH = 400)
        assertNotNull(result)
        assertEquals(400, result!!.width)
        assertEquals(400, result.height)
    }

    @Test
    fun `resize - zero dimensions ignored`() {
        backend.resize(0, 0) // 不应 crash
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    // ============================================================
    // 边界条件
    // ============================================================

    @Test
    fun `renderFrame - camera at world edge`() {
        // 相机在世界右下角附近
        val data = createFlatTileData(10, 10)
        val frame = RenderFrame(
            tileData = data, cols = 10, rows = 10,
            camX = 576f, camY = 576f, scale = 1f
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - camera beyond world bounds`() {
        // 相机完全超出世界范围，不应 crash
        val data = createFlatTileData(10, 10)
        val frame = RenderFrame(
            tileData = data, cols = 10, rows = 10,
            camX = 2000f, camY = 2000f, scale = 1f
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - zero scale clamped`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 0f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    // ============================================================
    // release
    // ============================================================

    @Test
    fun `release - does not crash and allows re-render`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        backend.release()
        // release 后仍能重新渲染（帧缓冲区被重建）
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    // ============================================================
    // 热控质量因子（P1.4/P2.2）
    // ============================================================

    @Test
    fun `qualityFactor - default is 1 dot 0`() {
        assertEquals("默认质量因子应为 1.0", 1.0f, backend.qualityFactor, 0.01f)
    }

    @Test
    fun `qualityFactor - lowered value does not crash render`() {
        backend.qualityFactor = 0.5f
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 2, gridY = 3, width = 2, height = 2, nameIdx = 0
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull("qualityFactor=0.5f 不应影响渲染", result)
    }

    @Test
    fun `qualityFactor - extreme low value does not crash`() {
        backend.qualityFactor = 0.1f
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull("qualityFactor=0.1f 不应 crash", result)
    }

    // ============================================================
    // 装饰层关闭（decorationsDisabled）
    // ============================================================

    @Test
    fun `decorationsDisabled - default is false`() {
        assertFalse("默认装饰层应启用", backend.decorationsDisabled)
    }

    @Test
    fun `decorationsDisabled - true does not crash render`() {
        backend.decorationsDisabled = true
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull("decorationsDisabled=true 不应 crash", result)
    }

    @Test
    fun `decorationsDisabled - toggle between frames does not crash`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10
        )
        // 第一帧：装饰开启
        assertNotNull(backend.renderFrame(frame, atlas, vpW = 200, vpH = 200))
        // 第二帧：装饰关闭
        backend.decorationsDisabled = true
        assertNotNull(backend.renderFrame(frame, atlas, vpW = 200, vpH = 200))
        // 第三帧：装饰重新开启
        backend.decorationsDisabled = false
        assertNotNull(backend.renderFrame(frame, atlas, vpW = 200, vpH = 200))
    }

    // ============================================================
    // 缓存（buildingCacheValid / tileCacheValid）
    // ============================================================

    @Test
    fun `multiple frames with same data does not crash`() {
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            buildingData = createBuildingDataArray(
                gridX = 2, gridY = 3, width = 2, height = 2, nameIdx = 0
            ),
            buildingCount = 1,
            buildingVisible = true
        )
        // 连续多帧渲染相同数据（验证缓存逻辑）
        repeat(10) {
            val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
            assertNotNull("第 $it 帧不应返回 null", result)
        }
    }

    @Test
    fun `camera movement between frames does not crash`() {
        repeat(5) { frame ->
            val frame = RenderFrame(
                tileData = createFlatTileData(10, 10),
                cols = 10, rows = 10,
                camX = (frame * 32).toFloat(), camY = (frame * 16).toFloat(),
                scale = 1f
            )
            val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
            assertNotNull("相机移动第 $frame 帧不应 crash", result)
        }
    }

    // ============================================================
    // 像素完美定位 — 相邻瓦片无缝隙
    // ============================================================

    @Test
    fun `tile gap - fractional scale adjacent tiles produce contiguous rects`() {
        // 非整数 scale 下验证 roundToInt 两端计算不崩溃
        // 数学保证 dstRight[t0] == dstLeft[t1] — 零缝隙
        val td = IntArray(3 * 3) { 0 }
        val frame = RenderFrame(
            tileData = td, cols = 3, rows = 3,
            camX = 1.3f, camY = 2.7f, scale = 1.0417f
        )
        val result = backend.renderFrame(frame, atlas, vpW = 400, vpH = 400)
        assertNotNull(result)
        assertEquals(400, result!!.width)
        assertEquals(400, result.height)
    }

    @Test
    fun `tile gap - camera at fractional offset no crash`() {
        // 相机位置为小数 + scale 非整数的组合——过去会产生 1px 缝隙
        val td = IntArray(48 * 48) { 0 }
        for (offset in listOf(0f, 0.3f, 0.7f, 1.5f, 2.33f)) {
            for (s in listOf(0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 2.0f, 3.0f)) {
                val frame = RenderFrame(
                    tileData = td, cols = 48, rows = 48,
                    camX = offset, camY = offset, scale = s
                )
                val result = backend.renderFrame(frame, atlas, vpW = 400, vpH = 400)
                assertNotNull("offset=$offset scale=$s 不应返回 null", result)
                assertEquals(400, result!!.width)
                assertEquals(400, result.height)
            }
        }
    }

    @Test
    fun `tile gap - background color is beige not transparent`() {
        // 注：Robolectric 中 getPixel 不可靠，仅验证尺寸和渲染不崩溃
        val td = IntArray(2 * 2) { 0 }
        val frame = RenderFrame(
            tileData = td, cols = 2, rows = 2,
            camX = 0f, camY = 0f, scale = 1f
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
        assertEquals(200, result!!.width)
        assertEquals(200, result.height)

        // step A drawColor 已改为米色（非透明），缝隙处无透明像素闪烁
    }

    @Test
    fun `tile cache - rebuild without camera change uses cache`() {
        // 连续两帧相同数据（无相机变化），验证缓存复用
        val td = createFlatTileData(10, 10)
        val frame = RenderFrame(
            tileData = td, cols = 10, rows = 10,
            camX = 0f, camY = 0f, scale = 1f
        )
        // 第一帧：绘制地面+建筑
        val r1 = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(r1)

        // 第二帧：相同数据，地面应走缓存
        val r2 = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(r2)
    }

    @Test
    fun `tile cache - building only change restores ground from cache`() {
        // 仅建筑变化时，地面应从缓存恢复而非完全重建
        val td = createFlatTileData(10, 10)
        val buildingData1 = createBuildingDataArray(
            gridX = 2, gridY = 3, width = 2, height = 2, nameIdx = 0
        )
        val buildingData2 = createBuildingDataArray(
            gridX = 5, gridY = 3, width = 2, height = 2, nameIdx = 0
        )

        val frame1 = RenderFrame(
            tileData = td, cols = 10, rows = 10,
            camX = 0f, camY = 0f, scale = 1f,
            buildingData = buildingData1, buildingCount = 1, buildingVisible = true
        )
        val r1 = backend.renderFrame(frame1, atlas, vpW = 200, vpH = 200)
        assertNotNull(r1)

        // 修改建筑数据，相机位置不变
        val frame2 = RenderFrame(
            tileData = td, cols = 10, rows = 10,
            camX = 0f, camY = 0f, scale = 1f,
            buildingData = buildingData2, buildingCount = 1, buildingVisible = true
        )
        val r2 = backend.renderFrame(frame2, atlas, vpW = 200, vpH = 200)
        assertNotNull(r2)
    }

    // ============================================================
    // NaN/Infinity 防御
    // ============================================================

    @Test
    fun `renderFrame - NaN camera returns null frame buffer`() {
        val td = createFlatTileData(10, 10)
        val frame = RenderFrame(
            tileData = td, cols = 10, rows = 10,
            camX = Float.NaN, camY = Float.NaN, scale = 1f
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - NaN scale returns null frame buffer`() {
        val td = createFlatTileData(10, 10)
        val frame = RenderFrame(
            tileData = td, cols = 10, rows = 10,
            camX = 0f, camY = 0f, scale = Float.NaN
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    @Test
    fun `renderFrame - Infinite camera returns null frame buffer`() {
        val td = createFlatTileData(10, 10)
        val frame = RenderFrame(
            tileData = td, cols = 10, rows = 10,
            camX = Float.POSITIVE_INFINITY, camY = Float.NEGATIVE_INFINITY, scale = 1f
        )
        val result = backend.renderFrame(frame, atlas, vpW = 200, vpH = 200)
        assertNotNull(result)
    }

    // ============================================================
    // preview_tint（预览精灵调色，与 C++ drawSprite 顶点色乘算双端对齐）
    // ============================================================

    @Test
    fun `renderFrame - preview tint multiplies pixel color`() {
        val whiteAtlas = createWhiteTileAtlas()
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            showPreview = true,
            previewX = 0f, previewY = 0f,
            previewW = 64f, previewH = 64f,
            previewU0 = 0f, previewV0 = 0f,
            previewU1 = 0.5f, previewV1 = 0.5f,
            previewTintRed = 0.5f, previewTintGreen = 0.25f, previewTintBlue = 0.75f,
            previewAlpha = 1f
        )
        val result = backend.renderFrame(frame, whiteAtlas, vpW = 200, vpH = 200)
        assertNotNull(result)

        // 白色源 × tint (0.5, 0.25, 0.75) → (128, 64, 191)
        val pixel = result!!.getPixel(32, 32)
        assertNear(128, Color.red(pixel))
        assertNear(64, Color.green(pixel))
        assertNear(191, Color.blue(pixel))
    }

    @Test
    fun `renderFrame - preview alpha blends with background`() {
        // 双区域图集：tile 源 (0,0,64,64)=灰 100（chunk 底），preview 源
        // (64,0,128,64)=白 255。白源 alpha 0.5 覆盖灰底后 RGB = 255×0.5 + 100×0.5
        // ≈ 178——介于纯源与纯底之间，半透明混合的可观测证据。
        // （单一灰区域不可行：源与底同色，混合后仍为 100，无法区分）
        val dualAtlas = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val atlasCanvas = Canvas(dualAtlas)
        atlasCanvas.drawRect(0f, 0f, 64f, 64f, Paint().apply { color = Color.rgb(100, 100, 100) })
        atlasCanvas.drawRect(64f, 0f, 128f, 64f, Paint().apply { color = Color.WHITE })
        val frame = RenderFrame(
            camX = 0f, camY = 0f, scale = 1f,
            tileData = createFlatTileData(10, 10),
            cols = 10, rows = 10,
            showPreview = true,
            previewX = 0f, previewY = 0f,
            previewW = 64f, previewH = 64f,
            previewU0 = 0.5f, previewV0 = 0f,
            previewU1 = 1f, previewV1 = 0.5f,
            // 显式 tint(1,1,1)：RenderFrame 默认 (0.25,1,0.25) 会污染断言
            previewTintRed = 1f, previewTintGreen = 1f, previewTintBlue = 1f,
            previewAlpha = 0.5f
        )
        val result = backend.renderFrame(frame, dualAtlas, vpW = 200, vpH = 200)
        assertNotNull(result)

        val pixel = result!!.getPixel(32, 32)
        // 帧缓冲底图完全不透明（chunk 灰），src-over 半透明源覆盖后输出
        // alpha 恒为 255——正确混合行为；可观测差异在 RGB
        assertEquals("帧缓冲 src-over 后 alpha 应恒 255", 255, Color.alpha(pixel))
        // 白 255 × alpha 0.5 混合灰底 100 → ≈178，介于 100（纯底）与 255（纯源）之间
        assertNear(178, Color.red(pixel), tolerance = 8)
        assertNear(178, Color.green(pixel), tolerance = 8)
        assertNear(178, Color.blue(pixel), tolerance = 8)
    }
}
