package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.render.DemolishHighlightMark
import com.xianxia.sect.core.render.RenderBackend
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.RenderMetrics
import com.xianxia.sect.core.render.SpriteAtlasDef

/**
 * Vulkan 渲染后端适配器 — 将 [RenderBackend] 契约翻译为 C++ NativeBridge 调用。
 *
 * ## 职责
 * - [resize] → NativeBridge.resizeRenderer（交换链重建）
 * - [setCamera] → NativeBridge.setCamera（独立相机通道）
 * - [renderFrame] → beginFrame → drawAllTiles（含命令总线建筑快照）→
 *   drawSprite（预览覆盖层）→ 指标 → submitFrame
 * - [release] → NativeBridge.shutdownRenderer（surface 销毁时由宿主调用）
 *
 * ## 数据流
 * 宿主（[NativeSurfaceView]）持有帧契约与图集状态，本适配器只读消费：
 * - 当前帧 [RenderFrame]（帧率门控写入）+ 命令总线建筑快照（即时通道）
 * - 图集 GPU 纹理 ID（buildAtlas 后注入）
 * - 热控状态（qualityFactor/decorationsDisabled，指标用）
 *
 * 渲染线程调用（与宿主 [NativeSurfaceView.RenderThread] 同线程），
 * 异常由渲染循环统一捕获（见 RenderThread.run）。
 */
class VulkanRenderBackend(private val host: NativeSurfaceView) : RenderBackend {

    /** 最新相机缓存（setCamera 转发时自留，供网格线范围计算——渲染线程每帧先于 renderFrame 更新） */
    @Volatile
    private var cachedCamX = 0f
    @Volatile
    private var cachedCamY = 0f
    @Volatile
    private var cachedScale = 1f

    init {
        // 渲染特性开关推送：surface 重建后 C++ globals 已重置为默认全开，
        // 此处重放当前配置值（仿 pushRenderQuality 重放语义）
        NativeBridge.setRenderFlags(
            buildingShadows = host.renderConfig.renderFlags.buildingShadows,
            selectionHighlight = host.renderConfig.renderFlags.selectionHighlight,
            decorLod = host.renderConfig.renderFlags.decorLod
        )
    }

    override fun resize(width: Int, height: Int) {
        NativeBridge.resizeRenderer(width, height)
    }

    override fun setCamera(camX: Float, camY: Float, scale: Float, viewportW: Int, viewportH: Int) {
        // 自留一份最新相机缓存（转发之外）：网格线范围计算用——渲染线程每帧
        // setCamera 先于 renderFrame 调用（NativeSurfaceView.renderTick），
        // 与 g_projMatrix 同源，杜绝帧率门控旧相机值错位
        cachedCamX = camX
        cachedCamY = camY
        cachedScale = scale
        NativeBridge.setCamera(camX, camY, scale, viewportW, viewportH)
    }

    override fun renderFrame(frame: RenderFrame, viewportW: Int, viewportH: Int): Boolean {
        NativeBridge.beginFrame()

        // ★ 地图淡入 alpha 推送（WP4）：渲染线程每帧计算（EaseOutCubic 纯时钟驱动），
        // C++ g_fadeAlpha 乘算 drawAllTiles 全部 quad——预览/高亮 drawRect 不受影响
        NativeBridge.setFadeAlpha(host.fadeAlpha)

        // ★ 从命令总线读取建筑数据快照（一次性读取，消除 TOCTOU 竞态）
        // 对标 UE ENQUEUE_RENDER_COMMAND：建筑变更即时送达，不依赖 Compose 重组时序
        // busWasDirty：建筑数据本次刚被推送——frame.selectedBuildingIndex 是
        // Compose 帧率门控旧值，与新数据可能错位，本帧跳过高亮（下帧自动恢复）
        val bus = host.commandBus
        val busWasDirty = bus?.buildingDirty?.get() ?: false
        val busSnapshot = bus?.consumeBuildingData()
        val effectiveBuildingData = busSnapshot?.data ?: frame.buildingData
        val effectiveBuildingCount = if (busSnapshot != null) {
            busSnapshot.count.coerceAtMost((busSnapshot.data?.size ?: 0) / 5)
        } else {
            // 对抗性审查修复：回退路径同样 clamp（防上游 buildingCount 与数组长度
            // 不一致时 C++ 越界读——bus 直达通道有 clamp，双端路径行为一致）
            frame.buildingCount.coerceAtMost((frame.buildingData?.size ?: 0) / 5)
        }

        // 从 RenderFrame 读取瓦片数据 + SpriteAtlasDef 编译时常量
        if (host.atlasTextureId != 0) {
            NativeBridge.drawAllTiles(
                tileData = frame.tileData,
                cols = host.renderConfig.worldWidthCells,
                rows = host.renderConfig.worldHeightCells,
                buildingData = effectiveBuildingData,
                buildingCount = effectiveBuildingCount,
                buildingVisible = frame.buildingVisible,
                tileSize = host.renderConfig.tileSize,
                atlasTexId = host.atlasTextureId,
                uvMap = SpriteAtlasDef.TILE_UV_MAP,
                buildingUVMap = SpriteAtlasDef.BUILDING_UV_MAP,
                floorTileUVMap = SpriteAtlasDef.FLOOR_TILE_UV_MAP,
                // ★ 灵田作物数据（WP6）：低频变化走帧率门控 RenderFrame，
                // C++ 侧按进度计算阶段索引 + 淡化 alpha（与 Kotlin SpiritCropRender 同数学）
                cropData = frame.spiritCropData,
                cropUVMap = SpriteAtlasDef.CROP_UV_MAP,
                // 批次 3 插值消费链：作物进度帧间平滑权重（仅渲染契约）
                frameAlpha = frame.currentAlpha
            )
        }

        // ★ 普通选中高亮（选中建筑金色描边——动态叠加，独立 draw calls，不烘焙进瓦片层）
        // 用同一份 effectiveBuildingData 快照计算，杜绝命令总线消费后索引错位
        drawSelectionHighlight(frame, effectiveBuildingData, effectiveBuildingCount, busWasDirty)

        // ★ 一键拆除模式占地高亮（绿/红半透明填充——与精灵同帧同相机，
        // 与选中高亮同一份建筑快照；总线脏帧跳帧防索引错位）
        drawDemolishHighlight(frame, effectiveBuildingData, effectiveBuildingCount, busWasDirty)

        if (frame.showPreview && host.atlasTextureId != 0) {
            NativeBridge.drawSprite(
                frame.previewX, frame.previewY,
                frame.previewW, frame.previewH,
                host.atlasTextureId,
                frame.previewU0, frame.previewV0,
                frame.previewU1, frame.previewV1,
                frame.previewTintRed, frame.previewTintGreen,
                frame.previewTintBlue, frame.previewAlpha
            )
        }

        // ★ 放置/移动模式网格线（预览精灵之上——与旧 Compose 覆盖层层叠顺序一致；
        // 范围按缓存最新相机计算，与 g_projMatrix 同源零错位）
        drawGridOverlay(frame, viewportW, viewportH)

        // ★ 热控降级可观测性：装饰层被跳过（decorationsDisabled || qualityFactor < 0.6）
        //   的 Vulkan 帧计数——与 C++ drawAllTiles 的 skipDecor 判定同语义（阈值同 0.6）
        if (host.renderDecorationsDisabled || host.renderQualityFactor < DECOR_QUALITY_THRESHOLD) {
            RenderMetrics.vulkanDecorSkippedFrames.incrementAndGet()
        }

        RenderMetrics.vulkanFrames.incrementAndGet()
        RenderMetrics.totalFrames.incrementAndGet()
        RenderMetrics.recordFrame()
        NativeBridge.submitFrame()
        return true
    }

    override fun release() {
        NativeBridge.shutdownRenderer()
    }

    /**
     * 绘制选中建筑高亮（金色描边 + 半透明填充，drawRect×5）。
     *
     * 框选**占地矩形**（与点击命中判定 [com.xianxia.sect.core.render.BuildingRenderGeometry.findBuildingIndex]
     * 同一几何来源），精灵超出占地的透明像素不计入高亮区域。
     * 线宽按相机缩放折算：屏幕线宽恒定 max(2px, tileSize×0.06)。
     *
     * @param frame 当前帧（含 selectedBuildingIndex）
     * @param buildingData 建筑数据快照（与瓦片绘制同一份，防索引错位）
     * @param buildingCount 建筑数量
     * @param busWasDirty 建筑数据本帧刚被命令总线推送（旧索引可能错位，跳过本次）
     */
    private fun drawSelectionHighlight(
        frame: RenderFrame,
        buildingData: FloatArray?,
        buildingCount: Int,
        busWasDirty: Boolean
    ) {
        val index = frame.selectedBuildingIndex
        val base = index * SELECTED_DATA_STRIDE
        val flagOk = !busWasDirty && host.renderConfig.renderFlags.selectionHighlight
        val dataOk = buildingData != null && index in 0 until buildingCount &&
            base + SELECTED_DATA_STRIDE - 1 < buildingData.size // 防御：数组截断
        if (!flagOk || !dataOk) return

        val gx = buildingData[base].toInt()
        val gy = buildingData[base + 1].toInt()
        val nameIdx = buildingData[base + 4].toInt()
        val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.getOrElse(nameIdx) { 2 to 2 }
        val tileSize = host.renderConfig.tileSize

        val x = gx * tileSize.toFloat()
        val y = gy * tileSize.toFloat()
        val w = fpW * tileSize.toFloat()
        val h = fpH * tileSize.toFloat()
        val scale = frame.scale.coerceAtLeast(MIN_SCALE)
        // 目标屏幕线宽 max(2px, tileSize×0.06×scale)，换算回世界坐标除以 scale
        val lineWidth = maxOf(2f, tileSize * HIGHLIGHT_LINE_WIDTH_TILES * scale) / scale

        // 填充 → 上边 → 下边 → 左边 → 右边（描边盖住填充边缘，避免颜色叠加发亮）
        NativeBridge.drawRect(x, y, w, h, GOLD_R, GOLD_G, GOLD_B, HIGHLIGHT_FILL_ALPHA)
        NativeBridge.drawRect(x, y, w, lineWidth, GOLD_R, GOLD_G, GOLD_B, HIGHLIGHT_EDGE_ALPHA)
        NativeBridge.drawRect(x, y + h - lineWidth, w, lineWidth, GOLD_R, GOLD_G, GOLD_B, HIGHLIGHT_EDGE_ALPHA)
        NativeBridge.drawRect(x, y, lineWidth, h, GOLD_R, GOLD_G, GOLD_B, HIGHLIGHT_EDGE_ALPHA)
        NativeBridge.drawRect(x + w - lineWidth, y, lineWidth, h, GOLD_R, GOLD_G, GOLD_B, HIGHLIGHT_EDGE_ALPHA)
    }

    /**
     * 绘制一键拆除模式占地高亮（绿/红半透明填充 + 选中红描边，drawRect×5/建筑）。
     *
     * 数据驱动：markers 与 buildingData **同序同长**（Compose 侧
     * buildDemolishHighlightData 按与 buildBuildingDataArray 同一排序构建），
     * 每建筑 1 字节：[DemolishHighlightMark.NONE] 跳过、GREEN 绿填充、
     * SELECTED 红填充 + 红描边。世界坐标直传 drawRect——C++ 侧投影矩阵
     * （g_projMatrix 来自 setCamera）做相机变换，与精灵同相机零错位。
     *
     * @param frame 当前帧（含 demolishHighlightData）
     * @param buildingData 建筑数据快照（与瓦片绘制同一份，防索引错位）
     * @param buildingCount 建筑数量
     * @param busWasDirty 建筑数据本帧刚被命令总线推送（旧 markers 可能错位，跳过本次）
     */
    private fun drawDemolishHighlight(
        frame: RenderFrame,
        buildingData: FloatArray?,
        buildingCount: Int,
        busWasDirty: Boolean
    ) {
        val markers = frame.demolishHighlightData
        if (busWasDirty || markers == null || buildingData == null) return
        val count = minOf(buildingCount, markers.size)
            .coerceAtMost(buildingData.size / SELECTED_DATA_STRIDE)

        for (i in 0 until count) {
            val base = i * SELECTED_DATA_STRIDE
            if (base + SELECTED_DATA_STRIDE - 1 >= buildingData.size) return // 截断防御
            drawDemolishMarker(frame, buildingData, base, markers[i])
        }
    }

    /**
     * 绘制单个建筑的高亮矩形（NONE 跳过 / GREEN 绿填充 / SELECTED 红填充 + 红描边）。
     * 世界坐标直传 drawRect——投影矩阵（g_projMatrix 来自 setCamera）做相机变换。
     */
    private fun drawDemolishMarker(
        frame: RenderFrame,
        buildingData: FloatArray,
        base: Int,
        marker: Byte
    ) {
        if (marker == DemolishHighlightMark.NONE.toByte()) return
        val tileSize = host.renderConfig.tileSize
        val gx = buildingData[base].toInt()
        val gy = buildingData[base + 1].toInt()
        val nameIdx = buildingData[base + 4].toInt()
        val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.getOrElse(nameIdx) { 2 to 2 }
        val x = gx * tileSize.toFloat()
        val y = gy * tileSize.toFloat()
        val w = fpW * tileSize.toFloat()
        val h = fpH * tileSize.toFloat()
        val scale = frame.scale.coerceAtLeast(MIN_SCALE)
        // 目标屏幕线宽 max(2px, tileSize×0.06×scale)，换算回世界坐标除以 scale
        val lineWidth = maxOf(2f, tileSize * HIGHLIGHT_LINE_WIDTH_TILES * scale) / scale

        if (marker == DemolishHighlightMark.SELECTED.toByte()) {
            NativeBridge.drawRect(
                x, y, w, h, DEMOLISH_RED_R, DEMOLISH_RED_G, DEMOLISH_RED_B, DEMOLISH_FILL_ALPHA
            )
            // 填充 → 上边 → 下边 → 左边 → 右边（描边盖住填充边缘，避免颜色叠加发亮）
            NativeBridge.drawRect(
                x, y, w, lineWidth, DEMOLISH_RED_R, DEMOLISH_RED_G, DEMOLISH_RED_B, DEMOLISH_EDGE_ALPHA
            )
            NativeBridge.drawRect(
                x, y + h - lineWidth, w, lineWidth,
                DEMOLISH_RED_R, DEMOLISH_RED_G, DEMOLISH_RED_B, DEMOLISH_EDGE_ALPHA
            )
            NativeBridge.drawRect(
                x, y, lineWidth, h, DEMOLISH_RED_R, DEMOLISH_RED_G, DEMOLISH_RED_B, DEMOLISH_EDGE_ALPHA
            )
            NativeBridge.drawRect(
                x + w - lineWidth, y, lineWidth, h,
                DEMOLISH_RED_R, DEMOLISH_RED_G, DEMOLISH_RED_B, DEMOLISH_EDGE_ALPHA
            )
        } else {
            NativeBridge.drawRect(
                x, y, w, h, DEMOLISH_GREEN_R, DEMOLISH_GREEN_G, DEMOLISH_GREEN_B, DEMOLISH_FILL_ALPHA
            )
        }
    }

    /**
     * 绘制放置/移动模式全视口网格线（世界坐标薄矩形，drawRect×视口线数）。
     *
     * 范围数学与旧 Compose GridOverlay.drawFullGrid 同式：按缓存最新相机
     * （setCamera 自留份，与 g_projMatrix 同源）计算视口内行列区间并钳制到
     * 世界边界；列线 x = col×tileSize、行线 y = row×tileSize，全高/全宽延伸
     * （投影矩阵自动裁剪视口外部分）。线宽换算为世界坐标（屏幕 1px）。
     *
     * @param frame 当前帧（gridOverlayVisible 开关）
     * @param viewportW 视口宽（px）
     * @param viewportH 视口高（px）
     */
    private fun drawGridOverlay(frame: RenderFrame, viewportW: Int, viewportH: Int) {
        if (!frame.gridOverlayVisible) return
        val tileSize = host.renderConfig.tileSize
        val scale = cachedScale.coerceAtLeast(MIN_SCALE)
        if (viewportW <= 0 || viewportH <= 0) return

        val worldW = host.renderConfig.worldWidthCells * tileSize
        val worldH = host.renderConfig.worldHeightCells * tileSize
        val firstCol = (cachedCamX / tileSize).toInt().coerceAtLeast(0)
        val lastCol = ((cachedCamX + viewportW / scale) / tileSize).toInt()
            .coerceAtMost(host.renderConfig.worldWidthCells)
        val firstRow = (cachedCamY / tileSize).toInt().coerceAtLeast(0)
        val lastRow = ((cachedCamY + viewportH / scale) / tileSize).toInt()
            .coerceAtMost(host.renderConfig.worldHeightCells)

        // 目标屏幕线宽 1px，换算回世界坐标（除 scale）；下限 0.5 世界单位防退化 quad
        val lineWidth = maxOf(0.5f, 1f / scale)
        for (col in firstCol..lastCol) {
            val x = col * tileSize.toFloat()
            NativeBridge.drawRect(x, 0f, lineWidth, worldH.toFloat(), GRID_R, GRID_G, GRID_B, GRID_ALPHA)
        }
        for (row in firstRow..lastRow) {
            val y = row * tileSize.toFloat()
            NativeBridge.drawRect(0f, y, worldW.toFloat(), lineWidth, GRID_R, GRID_G, GRID_B, GRID_ALPHA)
        }
    }

    companion object {
        /** 装饰层跳过阈值（qualityFactor < 0.6 时装饰降级——与 Canvas 帧缓冲 RGB_565 阈值同常量） */
        private const val DECOR_QUALITY_THRESHOLD = 0.6f

        /** 建筑数据单条步长（[gx, gy, sw, sh, nameIdx]） */
        private const val SELECTED_DATA_STRIDE = 5

        /** 缩放下限（防御除零） */
        private const val MIN_SCALE = 0.001f

        /** 高亮线宽（格数）：max(2px, tileSize×0.06) 的格数分量 */
        private const val HIGHLIGHT_LINE_WIDTH_TILES = 0.06f

        /** 高亮填充不透明度（金色半透明填充） */
        private const val HIGHLIGHT_FILL_ALPHA = 0.15f

        /** 高亮描边不透明度 */
        private const val HIGHLIGHT_EDGE_ALPHA = 0.9f

        /** 金色 #FFD700 */
        private const val GOLD_R = 1.0f
        private const val GOLD_G = 0.843f
        private const val GOLD_B = 0.0f

        // ── 拆除模式占地高亮（与旧 Compose 覆盖层同色 #4CAF50 / #F44336） ──

        /** 未选中绿 #4CAF50（R=76/255） */
        private const val DEMOLISH_GREEN_R = 0.298f
        /** 未选中绿 #4CAF50（G=175/255） */
        private const val DEMOLISH_GREEN_G = 0.686f
        /** 未选中绿 #4CAF50（B=80/255） */
        private const val DEMOLISH_GREEN_B = 0.314f
        /** 选中红 #F44336（R=244/255） */
        private const val DEMOLISH_RED_R = 0.957f
        /** 选中红 #F44336（G=68/255） */
        private const val DEMOLISH_RED_G = 0.267f
        /** 选中红 #F44336（B=54/255） */
        private const val DEMOLISH_RED_B = 0.212f
        /** 拆除填充不透明度（0x66 = 40% 半透明） */
        private const val DEMOLISH_FILL_ALPHA = 0.4f
        /** 拆除描边不透明度 */
        private const val DEMOLISH_EDGE_ALPHA = 1.0f

        // ── 放置/移动模式网格线（与旧 Compose GridOverlay 同色 #E4DDD0） ──

        /** 网格线 #E4DDD0（R=228/255） */
        private const val GRID_R = 0.894f
        /** 网格线 #E4DDD0（G=221/255） */
        private const val GRID_G = 0.867f
        /** 网格线 #E4DDD0（B=208/255） */
        private const val GRID_B = 0.816f
        /** 网格线不透明度 */
        private const val GRID_ALPHA = 1.0f
    }
}
