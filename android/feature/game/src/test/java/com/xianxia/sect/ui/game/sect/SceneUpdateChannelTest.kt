package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.render.DemolishHighlightMark
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.RenderMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SceneUpdateChannel 脏更新协议测试（重构方案 2026-09-17 R3.4/B11）。
 *
 * 用计数替身汇（[CountingSink]）驱动十路导入端口，逐路验证「变化才跨线」：
 * 首帧全量 → 之后同帧零跨线 → 单路变化恰一次 → 同引用内容复用不重复推。
 * 并把**放置模式每帧 JNI 次数**算成可复跑数字（G4 的 Kotlin 侧口径）：
 * 新路径 = 帧固定端口（beginFrame/drawSky/drawFrame/submitFrame）+ 本通道触线数；
 * 旧路径 = 同族固定端口 + 逐 rect 调用点算术（每 rect 一次 JNI）。
 * 叠加层几何侧的 draw call 实测在 C++ `SceneOverlayEquivalenceTest`
 * （同一最坏档：旧 276 → 新 3），两份口径互为旁证。
 */
class SceneUpdateChannelTest {

    /** 计数替身：按调用序记录触线端口名 + 末次入参 */
    private class CountingSink : SceneUpdateChannel.Sink {
        val calls = mutableListOf<String>()
        var lastBuildings: FloatArray? = null
        var lastBuildingCount = -1
        var lastMarkers: ByteArray? = null
        var lastPreview: FloatArray? = null
        var lastSelection = -2

        override fun setTerrain(tiles: IntArray, cols: Int, rows: Int, tileSize: Int) {
            calls += "terrain"
        }

        override fun updateBuildings(data: FloatArray?, count: Int) {
            calls += "buildings"
            lastBuildings = data
            lastBuildingCount = count
        }

        override fun updateCrops(data: FloatArray?, count: Int) {
            calls += "crops"
        }

        override fun updateRoads(data: IntArray?, cellCount: Int) {
            calls += "roads"
        }

        override fun updateClouds(data: FloatArray?, count: Int) {
            calls += "clouds"
        }

        override fun setCliffLayout(data: FloatArray?, pieceCount: Int) {
            calls += "cliffs"
        }

        override fun setGroundBoundary(data: FloatArray?) {
            calls += "boundary"
        }

        override fun setAtlasTexture(texId: Int) {
            calls += "atlas"
        }

        override fun setSelection(index: Int) {
            calls += "selection"
            lastSelection = index
        }

        override fun setDemolishMarkers(markers: ByteArray?, count: Int) {
            calls += "markers"
            lastMarkers = markers
        }

        override fun setPreview(values: FloatArray?) {
            calls += "preview"
            lastPreview = values?.copyOf()
        }

        fun countOf(port: String): Int = calls.count { it == port }
    }

    // ── 稳定引用夹具（同引用 ⇒ 同内容：与生产端 remember/derivedStateOf 同契约）──
    private lateinit var tileData: IntArray
    private lateinit var altTileData: IntArray
    private lateinit var buildingArr: FloatArray
    private lateinit var altBuildingArr: FloatArray
    private lateinit var cropArr: FloatArray
    private lateinit var altCropArr: FloatArray
    private lateinit var roadArr: IntArray
    private lateinit var altRoadArr: IntArray
    private lateinit var cloudArr: FloatArray
    private lateinit var altCloudArr: FloatArray
    private lateinit var cliffArr: FloatArray
    private lateinit var altCliffArr: FloatArray
    private lateinit var boundaryArr: FloatArray
    private lateinit var altBoundaryArr: FloatArray
    private lateinit var markerArr: ByteArray
    private lateinit var altMarkerArr: ByteArray

    private lateinit var sink: CountingSink
    private lateinit var channel: SceneUpdateChannel

    @Before
    fun setUp() {
        tileData = IntArray(16) { it % 3 }
        altTileData = IntArray(16) { 1 }
        buildingArr = floatArrayOf(3f, 2f, 1f, 1f, 2f, 7f, 5f, 4f, 4f, 0f)
        altBuildingArr = floatArrayOf(1f, 1f, 1f, 1f, 2f)
        cropArr = floatArrayOf(8f, 3f, 0.4f)
        altCropArr = floatArrayOf(8f, 3f, 0.6f)
        roadArr = IntArray(16) { if (it % 5 == 0) 1 else 0 }
        altRoadArr = IntArray(16)
        cloudArr = floatArrayOf(10f, 20f, 30f, 40f, 0f, 0.8f)
        altCloudArr = floatArrayOf(11f, 20f, 30f, 40f, 0f, 0.8f)
        cliffArr = FloatArray(10)
        altCliffArr = FloatArray(10) { 1f }
        boundaryArr = FloatArray(32)
        altBoundaryArr = FloatArray(32) { 1f }
        markerArr = byteArrayOf(DemolishHighlightMark.GREEN.toByte())
        altMarkerArr = byteArrayOf(DemolishHighlightMark.SELECTED.toByte())

        sink = CountingSink()
        channel = SceneUpdateChannel(sink)
        RenderMetrics.resetForTest()
    }

    private fun frame(
        tiles: IntArray = tileData,
        crops: FloatArray? = cropArr,
        roads: IntArray? = roadArr,
        cliffs: FloatArray? = cliffArr,
        groundBoundary: FloatArray? = boundaryArr,
        markers: ByteArray? = markerArr,
        selection: Int = -1,
        previewBoxX: Float = 240f,
        buildingData: FloatArray? = buildingArr
    ): RenderFrame = RenderFrame(
        tileData = tiles,
        cols = 4,
        rows = 4,
        roadData = roads,
        islandCliffData = cliffs,
        groundBoundaryData = groundBoundary,
        buildingData = buildingData,
        buildingCount = if (buildingData == null) 0 else 2,
        selectedBuildingIndex = selection,
        spiritCropData = crops,
        demolishHighlightData = markers,
        previewBoxX = previewBoxX
    )

    private fun inputs(
        f: RenderFrame = frame(),
        buildingData: FloatArray? = buildingArr,
        buildingCount: Int = 2,
        cloudData: FloatArray? = cloudArr,
        atlasTextureId: Int = 42
    ) = SceneUpdateInputs(
        frame = f,
        buildingData = buildingData,
        buildingCount = buildingCount,
        cloudData = cloudData,
        atlasTextureId = atlasTextureId,
        worldCols = 4,
        worldRows = 4,
        tileSize = 48
    )

    @Test
    fun `first frame - every populated channel is imported once in protocol order`() {
        val calls = channel.push(inputs())

        assertEquals(11, calls)
        assertEquals(
            listOf(
                "terrain", "buildings", "crops", "roads", "clouds", "cliffs",
                "boundary", "atlas", "selection", "markers", "preview"
            ),
            sink.calls
        )
        assertEquals("遥测计数与实际触线一致", 11L, RenderMetrics.sceneUpdatePushes.get())
    }

    @Test
    fun `steady frame - zero cross-line calls`() {
        val first = inputs()
        channel.push(first)
        sink.calls.clear()

        assertEquals("同帧引用未变 ⇒ 零跨线", 0, channel.push(first))
        assertEquals(0, channel.push(inputs()))
        assertEquals("重复构造的同值输入同样零跨线", 0, channel.push(inputs()))
        assertTrue(sink.calls.isEmpty())
        assertEquals(11L, RenderMetrics.sceneUpdatePushes.get())
    }

    @Test
    fun `camera-only change costs no scene import call`() {
        val first = inputs()
        channel.push(first)
        sink.calls.clear()

        // 相机只经 drawFrame 标量携带（本通道输入不含相机）——相机移动帧零导入
        assertEquals(0, channel.push(first))
        assertEquals(0, channel.push(inputs()))
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `each single channel change costs exactly one port call`() {
        val changes = listOf(
            "terrain" to inputs(f = frame(tiles = altTileData)),
            "buildings" to inputs(buildingData = altBuildingArr),
            "crops" to inputs(f = frame(crops = altCropArr)),
            "roads" to inputs(f = frame(roads = altRoadArr)),
            "clouds" to inputs(cloudData = altCloudArr),
            "cliffs" to inputs(f = frame(cliffs = altCliffArr)),
            "boundary" to inputs(f = frame(groundBoundary = altBoundaryArr)),
            "atlas" to inputs(atlasTextureId = 43),
            "selection" to inputs(f = frame(selection = 1)),
            "markers" to inputs(f = frame(markers = altMarkerArr)),
            "preview" to inputs(f = frame(previewBoxX = 300f))
        )
        assertEquals("用例表须覆盖十一路", 11, changes.size)
        for ((port, next) in changes) {
            // 每例独立通道：先建立全量基线，再只改一路——避免用例间基线串扰
            val localSink = CountingSink()
            val localChannel = SceneUpdateChannel(localSink)
            localChannel.push(inputs())
            localSink.calls.clear()

            assertEquals("$port 变化应恰一次跨线", 1, localChannel.push(next))
            assertEquals("$port 变化只触该路端口", listOf(port), localSink.calls)
            assertEquals("$port 变化后同帧零跨线", 0, localChannel.push(next))
        }
        assertTrue(
            "遥测计数随十一路触线累加",
            RenderMetrics.sceneUpdatePushes.get() >= 10L
        )
    }

    @Test
    fun `building count change with same array reference still pushes buildings`() {
        channel.push(inputs())
        sink.calls.clear()

        val calls = channel.push(inputs(buildingCount = 1))

        assertEquals(1, calls)
        assertEquals(1, sink.countOf("buildings"))
        assertEquals("推送同一引用与新的计数", buildingArr, sink.lastBuildings)
        assertEquals(1, sink.lastBuildingCount)
    }

    @Test
    fun `preview value change pushes the whole preview block once`() {
        channel.push(inputs())
        sink.calls.clear()

        val moved = inputs(f = frame(previewBoxX = 300f))
        assertEquals(1, channel.push(moved))
        assertEquals(1, sink.countOf("preview"))
        assertEquals(16, sink.lastPreview?.size)
        assertEquals("占地框 X 落在协议首字段", 300f, sink.lastPreview?.get(0))
        assertEquals("同值重推零跨线", 0, channel.push(moved))
    }

    @Test
    fun `empty channels do not re-push every frame`() {
        val emptyFrame = frame(
            crops = null, roads = null, cliffs = null, groundBoundary = null,
            markers = null, buildingData = null
        )
        val first = channel.push(
            inputs(f = emptyFrame, buildingData = null, buildingCount = 0, cloudData = null)
        )
        // 建筑数 0 也需导入一次（C++ 侧清空语义与哨兵基线不同值），此后该路静默；
        // 作物/道路/云/崖壁/标记保持 null ⇒ 全程零跨线
        assertEquals(
            listOf("terrain", "buildings", "atlas", "selection", "preview"),
            sink.calls
        )
        assertEquals(5, first)
        sink.calls.clear()
        assertEquals(0, channel.push(inputs(f = emptyFrame, buildingData = null, buildingCount = 0, cloudData = null)))
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `overlay field change yields a new frame so dirty skip cannot swallow it`() {
        val base = frame()
        // 叠加层数据全在 RenderFrame 契约内：任一变化 ⇒ 新实例 ⇒ 渲染循环的
        // frameChanged 守卫不会把叠加层变更吞进脏帧跳过
        assertTrue(base !== base.copy(selectedBuildingIndex = 2))
        assertTrue(base !== base.copy(previewBoxX = 300f))
        assertTrue(base !== base.copy(gridOverlayVisible = true))
        assertTrue(base !== base.copy(demolishHighlightData = altMarkerArr))
        assertFalse(
            "帧引用变化必须渲染（R3.4 不得退化早退语义）",
            FrameSkipPolicy.shouldSkipFrame(
                FrameSkipInputs(
                    cameraDirty = false,
                    frameChanged = true,
                    buildingBusDirty = false,
                    fadeActive = false,
                    scaleChanged = false,
                    cloudDirty = false,
                    previewDirty = false,
                    skyDirty = false
                )
            )
        )
    }

    /**
     * G4 的 Kotlin 侧口径：放置模式每帧 JNI 次数（当前路径真实计数 +
     * 旧路径算术对照，B18 基线冻结）。
     *
     * 当前路径 = 帧固定端口（beginFrame + drawSky + drawFrame + submitFrame）
     * + [SceneUpdateChannel.push] 返回值（本测试实测）；
     * 旧路径（B18 已删除的回滚臂，此处保留为**冻结对照基线**）= 帧固定端口
     * + setFadeAlpha + drawIslandCliffs + drawAllTiles + drawSprite
     * + 逐 rect（占地框 5 + 选中 5 + 拆除逐建筑 + 网格线根数）。
     * 网格线根数按真实地图（128×128 格）+ 1080×1920 视口 + 整岛缩放档
     * = (128+1)×2 = 258（与方案 §1 病灶计数同口径）。
     */
    @Test
    fun `placement mode per-frame JNI calls stay under ten on the current path`() {
        channel.push(inputs())
        sink.calls.clear()

        val steadyPushes = channel.push(inputs())
        val steadyFrame = FIXED_PATH_PORTS + steadyPushes + cameraPort(moved = false)
        val cameraFrame = FIXED_PATH_PORTS + channel.push(inputs()) + cameraPort(moved = true)
        val dragPushes = channel.push(inputs(f = frame(previewBoxX = 300f)))
        val dragFrame = FIXED_PATH_PORTS + dragPushes + cameraPort(moved = false)

        assertEquals("稳态放置帧零导入跨线", 0, steadyPushes)
        assertEquals(1, dragPushes)
        assertTrue("稳态放置帧 JNI=$steadyFrame 必须 <$G4_JNI_TARGET", steadyFrame < G4_JNI_TARGET)
        assertTrue("相机移动帧 JNI=$cameraFrame 必须 <$G4_JNI_TARGET", cameraFrame < G4_JNI_TARGET)
        assertTrue("拖拽预览帧 JNI=$dragFrame 必须 <$G4_JNI_TARGET", dragFrame < G4_JNI_TARGET)

        val gridLines = (WORLD_CELLS + 1) * 2
        val demolishRects = 5 + 1 + 1  // 一栋红（填充+四边）+ 两栋绿填充
        // 旧路径（B18 已删除）固定端口 = beginFrame/drawSky/submitFrame +
        // setFadeAlpha/drawIslandCliffs/drawAllTiles（无 drawFrame——旧路径逐层各自跨线）
        val legacyFrame = LEGACY_FIXED_PORTS_FROZEN + 1 +
            PREVIEW_BOX_RECTS + SELECTION_RECTS + demolishRects + gridLines
        assertTrue("旧路径同帧应处于数百量级（实测口径=$legacyFrame）", legacyFrame > 250)
        println(
            "[G4 JNI] 放置模式每帧：旧（B18 前基线）$legacyFrame → 当前 $steadyFrame（稳态）/ " +
                "$cameraFrame（相机移动）/ $dragFrame（拖拽预览），目标 <$G4_JNI_TARGET"
        )
    }

    /** 相机脏帧的独立端口（setCamera 只在 cameraDirty 时触线一次） */
    private fun cameraPort(moved: Boolean): Int = if (moved) 1 else 0

    private companion object {
        /** 当前路径每帧固定端口：beginFrame / drawSky / drawFrame / submitFrame */
        const val FIXED_PATH_PORTS = 4

        /** B18 前旧路径每帧固定端口：beginFrame / drawSky / submitFrame +
         *  setFadeAlpha / drawIslandCliffs / drawAllTiles（回滚臂已删除，
         *  此值作为对照基线冻结——使"当前路径 < 目标"与"旧路径数百量级"
         *  两个陈述可同帧比较） */
        const val LEGACY_FIXED_PORTS_FROZEN = 6

        /** 占地框矩形数（填充 + 四边） */
        const val PREVIEW_BOX_RECTS = 5

        /** 选中高亮矩形数（填充 + 四边） */
        const val SELECTION_RECTS = 5

        /** 真实世界格数（GameConfig.SectMap.WORLD_WIDTH_CELLS） */
        const val WORLD_CELLS = 128

        /** 方案 §0.2 G4 目标：放置模式每帧 JNI 次数上限 */
        const val G4_JNI_TARGET = 10
    }
}
