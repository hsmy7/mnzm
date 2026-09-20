package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.render.IslandCliffBridge
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.RenderMetrics

/**
 * SceneUpdateChannel — C++ 场景更新侧的**脏更新协议**（重构方案 2026-09-17 R3.4/B11）。
 *
 * ## 这条协议说什么
 * 「游戏状态变化才推 diff」——把 [RenderCommandBus] 早已确立的"变化才跨线"语义
 * （单槽覆盖 + 脏标记，此前只管建筑一条通道）平移到**全部**场景与叠加层通道：
 * Kotlin 只负责判定"变了什么"并推**最小增量**（相机变化只传 drawFrame 的标量，
 * 场景内容变化只推该变化的那一路），C++ SceneStore 持有真相。
 *
 * ## 哪些变化推什么（每行 = 一路端口，判据 = 与上次推送基线比较）
 * | 变化 | 端口 | 判据 | 频率 |
 * |---|---|---|---|
 * | 地形瓦片（地图切换/建筑占位变化） | [Sink.setTerrain] | 数组**引用**变化 | 低频 |
 * | 建筑集 | [Sink.updateBuildings] | 数组引用变化 或 建筑数变化 | 放置/拆除 |
 * | 灵田作物进度 | [Sink.updateCrops] | 数组引用变化 | 每旬级 |
 * | 石板道路掩码 | [Sink.updateRoads] | 数组引用变化（生产者内容等价早退） | 铺路 |
 * | 云实例快照 | [Sink.updateClouds] | 数组引用变化（脏帧才刷新） | 云运动帧 |
 * | 崖壁布局 | [Sink.setCliffLayout] | 数组引用变化（一次性预计算） | 地图/掩码变化 |
 * | 图集纹理 ID | [Sink.setAtlasTexture] | 值变化 | 上传完成时 |
 * | 选中建筑索引 | [Sink.setSelection] | 值变化 | 点选时 |
 * | 拆除标记 | [Sink.setDemolishMarkers] | 数组引用变化 | 拆除模式/勾选 |
 * | 预览几何（占地框+精灵） | [Sink.setPreview] | 16 个浮点逐项**值**变化 | 拖拽帧 |
 * | 相机 / overlayFlags / 淡入与插值 alpha | [NativeBridge.drawFrame]（既有帧入口） | 每帧必发（标量，非几何） | 每帧 1 次 |
 *
 * ## 稳态跨线数
 * 上述十路**全部未变**时本通道触线 0 次；一帧的 JNI 总量 = beginFrame + drawSky
 * + drawFrame + submitFrame = **4 次**（拖拽预览帧 +1，场景变化帧 + 变化路数）。
 * 相机静止且帧未变时整帧由渲染循环的脏帧跳过（[FrameSkipPolicy]）早退，
 * 跨线 0 次——本通道只做"判定 + 最小推送"，不引入任何每帧无条件跨线。
 *
 * ## 引用比较为何等价于内容比较
 * 十路数据的生产者全部产出**稳定引用**（同名引用 ⇒ 同内容）：
 * `RenderCommandBus.postBuildingData` 每次 post 做 `copyOf`；
 * `RoadMaskTracker.syncTo` 内容等价时早退返回旧引用；`CloudLayerAnimator.snapshot`
 * 脏帧才刷新；`spiritCropData`/`demolishHighlightData`/`islandCliffData`/
 * `flatTileData` 走 `remember`/`derivedStateOf` 重算才产新数组；预览与选中索引是
 * 值类型。故"引用变了"⇔"内容变了"，无需每帧逐元素比对（零每帧分配）。
 * 预览几何是触控驱动的连续值（每拖拽帧都变），因此按**值**比较而非引用。
 *
 * ## 纪元复位
 * 基线是本对象的字段：surface 重建 ⇒ 新 [VulkanRenderBackend] 实例 ⇒ 新通道实例
 * ⇒ 首帧重推全部场景（与 C++ `shutdownRenderer` 清空 SceneStore 严格配对）。
 *
 * 线程契约：渲染线程单消费者（与 drawFrame 同线程顺序执行，无锁）。
 *
 * @param sink 端口汇——生产 = [nativeSceneUpdateSink]，测试 = 计数替身
 */
class SceneUpdateChannel(private val sink: Sink) {

    // ── 已推送基线（引用/值比较用；null 与 0/-1 等"无数据"态也参与比较，
    //    故"上游恒无某路数据"时该路零跨线）──
    private var pushedTerrain: IntArray? = null
    private var pushedTerrainCols = 0
    private var pushedTerrainRows = 0
    private var pushedTerrainTileSize = 0
    private var pushedBuildings: FloatArray? = null
    private var pushedBuildingCount = -1
    private var pushedCrops: FloatArray? = null
    private var pushedRoads: IntArray? = null
    private var pushedClouds: FloatArray? = null
    private var pushedCliffs: FloatArray? = null
    private var pushedAtlasTexId = -1
    private var pushedSelection = SENTINEL_NO_SELECTION
    private var pushedMarkers: ByteArray? = null

    /** 预览几何复用缓冲（逐帧填写比对；跨线时 C++ 侧按值拷贝，故可复用零分配） */
    private val previewScratch = FloatArray(PREVIEW_DATA_STRIDE)

    /** 已推送预览基线（NaN 初值 ⇒ 首帧恒判脏） */
    private val pushedPreview = FloatArray(PREVIEW_DATA_STRIDE) { Float.NaN }

    /**
     * 推本帧的场景/叠加层变化。
     *
     * 推送序固定为「地形 → 建筑 → 作物 → 道路 → 云 → 崖壁 → 图集 →
     * 选中 → 拆除标记 → 预览」，与 drawFrame 之前的同帧装配时序一致
     * （建筑先于其叠加层状态，保证 C++ 侧读到的建筑数与标记序不成对错位）。
     *
     * @return 本帧实际触线（JNI）次数——遥测与守卫观测面
     */
    fun push(inputs: SceneUpdateInputs): Int {
        var calls = 0
        calls += pushTerrain(inputs)
        calls += pushBuildings(inputs)
        calls += pushCrops(inputs.frame)
        calls += pushRoads(inputs.frame)
        calls += pushClouds(inputs)
        calls += pushCliffs(inputs.frame)
        calls += pushAtlas(inputs.atlasTextureId)
        calls += pushSelection(inputs.frame.selectedBuildingIndex)
        calls += pushMarkers(inputs.frame.demolishHighlightData)
        calls += pushPreview(inputs.frame)
        if (calls > 0) RenderMetrics.sceneUpdatePushes.addAndGet(calls.toLong())
        return calls
    }

    /** 地形（引用 + 网格尺寸/格像素协议字段任一变化才重导整表） */
    private fun pushTerrain(inputs: SceneUpdateInputs): Int {
        val frame = inputs.frame
        val shapeChanged = inputs.worldCols != pushedTerrainCols ||
            inputs.worldRows != pushedTerrainRows || inputs.tileSize != pushedTerrainTileSize
        if (frame.tileData !== pushedTerrain || shapeChanged) {
            sink.setTerrain(frame.tileData, inputs.worldCols, inputs.worldRows, inputs.tileSize)
            pushedTerrain = frame.tileData
            pushedTerrainCols = inputs.worldCols
            pushedTerrainRows = inputs.worldRows
            pushedTerrainTileSize = inputs.tileSize
            return 1
        }
        return 0
    }

    /** 建筑集（引用或建筑数变化才推——总线 copyOf 保证引用变化 ⇔ 内容变化） */
    private fun pushBuildings(inputs: SceneUpdateInputs): Int {
        if (inputs.buildingData === pushedBuildings && inputs.buildingCount == pushedBuildingCount) return 0
        sink.updateBuildings(inputs.buildingData, inputs.buildingCount)
        pushedBuildings = inputs.buildingData
        pushedBuildingCount = inputs.buildingCount
        return 1
    }

    /** 灵田作物（progress 的帧间平滑在 C++ 绘制核心，这里只推进度快照） */
    private fun pushCrops(frame: RenderFrame): Int {
        if (frame.spiritCropData === pushedCrops) return 0
        sink.updateCrops(frame.spiritCropData, entriesOf(frame.spiritCropData, CROP_DATA_STRIDE))
        pushedCrops = frame.spiritCropData
        return 1
    }

    /** 石板道路掩码（RoadMaskTracker 内容等价早退 ⇒ 引用变化即内容变化） */
    private fun pushRoads(frame: RenderFrame): Int {
        if (frame.roadData === pushedRoads) return 0
        sink.updateRoads(frame.roadData, frame.roadData?.size ?: 0)
        pushedRoads = frame.roadData
        return 1
    }

    /** 云实例（CloudLayerAnimator 脏帧才刷新快照） */
    private fun pushClouds(inputs: SceneUpdateInputs): Int {
        if (inputs.cloudData === pushedClouds) return 0
        sink.updateClouds(inputs.cloudData, entriesOf(inputs.cloudData, CLOUD_DATA_STRIDE))
        pushedClouds = inputs.cloudData
        return 1
    }

    /** 崖壁布局（IslandCliffBridge 一次性预计算的稳定引用） */
    private fun pushCliffs(frame: RenderFrame): Int {
        if (frame.islandCliffData === pushedCliffs) return 0
        sink.setCliffLayout(
            frame.islandCliffData,
            entriesOf(frame.islandCliffData, IslandCliffBridge.PIECE_STRIDE)
        )
        pushedCliffs = frame.islandCliffData
        return 1
    }

    /** 图集纹理 ID（上传完成时一次） */
    private fun pushAtlas(atlasTextureId: Int): Int {
        if (atlasTextureId == pushedAtlasTexId) return 0
        sink.setAtlasTexture(atlasTextureId)
        pushedAtlasTexId = atlasTextureId
        return 1
    }

    /** 选中建筑索引（点选时一次） */
    private fun pushSelection(selectedBuildingIndex: Int): Int {
        if (selectedBuildingIndex == pushedSelection) return 0
        sink.setSelection(selectedBuildingIndex)
        pushedSelection = selectedBuildingIndex
        return 1
    }

    /** 拆除标记（derivedStateOf 仅模式/勾选集变化时产新数组） */
    private fun pushMarkers(demolishHighlightData: ByteArray?): Int {
        if (demolishHighlightData === pushedMarkers) return 0
        sink.setDemolishMarkers(demolishHighlightData, demolishHighlightData?.size ?: 0)
        pushedMarkers = demolishHighlightData
        return 1
    }

    /**
     * 预览几何（占地框 + 精灵 + UV + 调色）——连续触控值，按 16 个浮点逐项
     * **值**比较；跨线时 C++ 侧按值拷贝，故复用缓冲零分配。
     */
    private fun pushPreview(frame: RenderFrame): Int {
        val p = previewScratch
        p[0] = frame.previewBoxX
        p[1] = frame.previewBoxY
        p[2] = frame.previewBoxW
        p[3] = frame.previewBoxH
        p[4] = frame.previewX
        p[5] = frame.previewY
        p[6] = frame.previewW
        p[7] = frame.previewH
        p[8] = frame.previewU0
        p[9] = frame.previewV0
        p[10] = frame.previewU1
        p[11] = frame.previewV1
        p[12] = frame.previewTintRed
        p[13] = frame.previewTintGreen
        p[14] = frame.previewTintBlue
        p[15] = frame.previewAlpha
        var changed = false
        for (i in p.indices) {
            // 基线初值 NaN（任何值都与 NaN 不等）⇒ 首帧恒判脏，无需额外标志位
            if (p[i] != pushedPreview[i]) changed = true
        }
        if (!changed) return 0
        sink.setPreview(p)
        p.copyInto(pushedPreview)
        return 1
    }

    /** 步长数组 → 条目数（null = 0） */
    private fun entriesOf(data: FloatArray?, stride: Int): Int {
        if (data == null) return 0
        return data.size / stride
    }

    companion object {
        /** 预览数据步长（与 C++ `scene::kPreviewStride` 同值，双端守卫锁定） */
        private const val PREVIEW_DATA_STRIDE = 16

        /** 灵田作物数据步长（与 C++ `scene::kCropStride` 同值） */
        private const val CROP_DATA_STRIDE = 3

        /** 云实例数据步长（与 `CloudLayerAnimator.CLOUD_DATA_STRIDE` 同值） */
        private const val CLOUD_DATA_STRIDE = 6

        /**
         * "尚未推送"哨兵——-1 是合法的"无选中"值，不可复用作基线初值
         * （否则首帧的无选中状态不会触线，C++ 侧留下复位前的默认值）
         */
        private const val SENTINEL_NO_SELECTION = Int.MIN_VALUE
    }

    /**
     * 场景/叠加层导入端口汇（每次调用 = 一次 JNI 跨线）。
     *
     * 独立接口使"哪些变化推什么"可在 JVM 单测里被计数与断言（生产实现
     * [nativeSceneUpdateSink] 逐端口转发到 [NativeBridge]）。
     */
    interface Sink {
        /** 地形一次性导入（展平瓦片 + 网格尺寸 + 格像素） */
        fun setTerrain(tiles: IntArray, cols: Int, rows: Int, tileSize: Int)

        /** 建筑集更新（[gx, gy, spriteW, spriteH, nameIdx] × count） */
        fun updateBuildings(data: FloatArray?, count: Int)

        /** 灵田作物更新（[gx, gy, progress01] × count） */
        fun updateCrops(data: FloatArray?, count: Int)

        /** 石板道路掩码更新（展平 1-based 编码，0 = 非道路） */
        fun updateRoads(data: IntArray?, cellCount: Int)

        /** 云实例快照更新（[x, y, w, h, spriteIndex, alpha] × count） */
        fun updateClouds(data: FloatArray?, count: Int)

        /** 崖壁布局导入（[texIdx,x,y,w,h,u0,v0,u1,v1,flags] × pieceCount） */
        fun setCliffLayout(data: FloatArray?, pieceCount: Int)

        /** 图集纹理 ID（0 = 未就绪，C++ 侧跳过地图层） */
        fun setAtlasTexture(texId: Int)

        /** 选中建筑索引（-1 = 无选中） */
        fun setSelection(index: Int)

        /** 拆除标记（逐建筑 1 字节，与建筑集同序；null = 非拆除模式） */
        fun setDemolishMarkers(markers: ByteArray?, count: Int)

        /** 预览几何（16 浮点，字段序见 [SceneUpdateChannel.pushPreview] 与 C++ kPreviewStride） */
        fun setPreview(values: FloatArray?)
    }
}

/**
 * 一帧的场景更新输入（[RenderFrame] + 宿主状态的只读快照；仿 [FrameSkipInputs]
 * 的纯函数输入形态，使脏更新判定可脱离 JNI 单测）。
 *
 * @param frame 当前帧（地形/作物/道路/崖壁/选中/拆除/预览来源）
 * @param buildingData 建筑快照（与地图层绘制消费同一份：总线优先）
 * @param buildingCount 建筑数（已按数组容量钳制）
 * @param cloudData 云实例快照（宿主渲染线程生成，非帧数据）
 * @param atlasTextureId 图集 GPU 纹理 ID（宿主上传后持有）
 * @param worldCols 世界列数（地形导入协议字段）
 * @param worldRows 世界行数
 * @param tileSize 格像素
 */
data class SceneUpdateInputs(
    val frame: RenderFrame,
    val buildingData: FloatArray?,
    val buildingCount: Int,
    val cloudData: FloatArray?,
    val atlasTextureId: Int,
    val worldCols: Int,
    val worldRows: Int,
    val tileSize: Int
)

/**
 * 生产汇：逐端口转发到 [NativeBridge]（每次调用即一次 JNI 跨线）。
 *
 * 顶层对象而非类成员：无状态、单一实现，[SceneUpdateChannel] 因此可被
 * 计数替身完整单测。
 */
internal val nativeSceneUpdateSink = object : SceneUpdateChannel.Sink {
    override fun setTerrain(tiles: IntArray, cols: Int, rows: Int, tileSize: Int) {
        NativeBridge.sceneSetTerrain(tiles, cols, rows, tileSize)
    }

    override fun updateBuildings(data: FloatArray?, count: Int) {
        NativeBridge.sceneUpdateBuildings(data, count)
    }

    override fun updateCrops(data: FloatArray?, count: Int) {
        NativeBridge.sceneUpdateCrops(data, count)
    }

    override fun updateRoads(data: IntArray?, cellCount: Int) {
        NativeBridge.sceneUpdateRoads(data, cellCount)
    }

    override fun updateClouds(data: FloatArray?, count: Int) {
        NativeBridge.sceneUpdateClouds(data, count)
    }

    override fun setCliffLayout(data: FloatArray?, pieceCount: Int) {
        NativeBridge.sceneSetCliffLayout(data, pieceCount)
    }

    override fun setAtlasTexture(texId: Int) {
        NativeBridge.sceneSetAtlasTexture(texId)
    }

    override fun setSelection(index: Int) {
        NativeBridge.sceneSetSelection(index)
    }

    override fun setDemolishMarkers(markers: ByteArray?, count: Int) {
        NativeBridge.sceneSetDemolishMarkers(markers, count)
    }

    override fun setPreview(values: FloatArray?) {
        NativeBridge.sceneSetPreview(values)
    }
}
