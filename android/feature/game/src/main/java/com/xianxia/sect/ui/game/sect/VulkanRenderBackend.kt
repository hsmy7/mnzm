package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.render.DemolishHighlightMark
import com.xianxia.sect.core.render.IslandCliffBridge
import com.xianxia.sect.core.render.RenderBackend
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.RenderMetrics
import com.xianxia.sect.core.render.SkyBackgroundConfig
import com.xianxia.sect.core.render.SpriteAtlasDef

/**
 * Vulkan 渲染后端适配器 — 将 [RenderBackend] 契约翻译为 C++ NativeBridge 调用。
 *
 * ## 职责
 * - [resize] → NativeBridge.resizeRenderer（交换链重建）
 * - [setCamera] → NativeBridge.setCamera（独立相机通道）
 * - [renderFrame] → beginFrame → 天空背景 → 场景绘制（按
 *   [NativeEngineFlag.sceneStoreRender] 双路，R3.2/R3.3 灰度共存）：
 *   - **新路径（默认）**：pushSceneUpdates（场景 + 叠加层状态变化驱动导入 C++
 *     SceneStore）+ drawFrame(相机, overlayFlags)——Kotlin 不再每帧传全量数组，
 *     **也不再每帧逐 rect 传叠加层几何**（选中/拆除/预览/网格线由 C++ 生成）；
 *   - **旧路径（回滚臂）**：setFadeAlpha + drawIslandCliffs + drawAllTiles
 *     （17 参数全量数组）+ drawSprite/drawRect 逐条叠加层，
 *     行为 = 本批开工前现状，各保留一个版本周期；
 *   两路消费 C++ 同一份绘制核心（scene_draw.h）——像素等价由构造保证。
 *   → 指标 → submitFrame
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
open class VulkanRenderBackend(private val host: NativeSurfaceView) : RenderBackend {

    /** 最新相机缓存（setCamera 转发时自留，供网格线范围计算——渲染线程每帧先于 renderFrame 更新） */
    @Volatile
    private var cachedCamX = 0f
    @Volatile
    private var cachedCamY = 0f
    @Volatile
    private var cachedScale = 1f

    // ── SceneStore 新路径的场景引用追踪（R3.2）──
    // 渲染线程单消费者：与 C++ SceneStore 导入端口同线程顺序执行；
    // 引用变化即推送（场景数据全部为 remember/快照稳定引用——
    // RenderCommandBus.postBuildingData copyOf / RoadMaskTracker 等价早退 /
    // CloudLayerAnimator.snapshot / derivedStateOf 重算均产出新引用），
    // 内容未变时零 JNI。surface 重建 = 新后端实例，追踪基线随实例复位，
    // 首帧重推全部场景（C++ 侧 shutdownRenderer 已同步清空 SceneStore）。
    private var pushedTerrain: IntArray? = null
    private var pushedBuildings: FloatArray? = null
    private var pushedBuildingCount: Int = -1
    private var pushedCrops: FloatArray? = null
    private var pushedRoads: IntArray? = null
    private var pushedClouds: FloatArray? = null
    private var pushedCliffs: FloatArray? = null
    private var pushedAtlasTexId: Int = -1

    // ── SceneStore 新路径的叠加层状态追踪（R3.3）──
    // 同一脏更新协议：值/引用变化才触 JNI（几何生成在 C++，见 scene_draw.h）。
    // surface 重建 = 新后端实例 ⇒ 基线随实例复位，首帧重推（C++ 侧
    // shutdownRenderer 已同步清空 SceneStore 叠加层状态）。
    private var pushedSelectionIndex: Int = SENTINEL_SELECTION_UNSET
    private var pushedMarkers: ByteArray? = null

    /** 预览几何暂存缓冲（逐帧填写比对，跨线时随 [NativeBridge.sceneSetPreview]
     *  拷贝进 C++，故复用零分配） */
    private val previewScratch = FloatArray(PREVIEW_DATA_STRIDE)

    /** 已推送预览基线（NaN 初值 ⇒ 首帧恒判脏） */
    private val pushedPreviewValues = FloatArray(PREVIEW_DATA_STRIDE) { Float.NaN }

    init {
        // 渲染特性开关推送：surface 重建后 C++ globals 已重置为默认全开，
        // 此处重放当前配置值（仿 pushRenderQuality 重放语义）
        NativeBridge.setRenderFlags(
            buildingShadows = host.renderConfig.renderFlags.buildingShadows,
            selectionHighlight = host.renderConfig.renderFlags.selectionHighlight,
            decorLod = host.renderConfig.renderFlags.decorLod
        )
        // SkyBackground：surface 重建后 C++ g_sky 已 resetToDefault，此处重放当前
        // skyConfig（仿 pushRenderQuality/重放语义，防降级链切换后天空配置残留）。
        // 重置 lastPushedSkyConfig 强制重放——backend 新实例 = surface 重建，C++ 侧已恢复默认，
        // 即使 skyConfig 与上次相同也必须重新推送，否则沿用 C++ 默认色。
        host.lastPushedSkyConfig = null
        pushSkyConfigIfChanged(host.skyConfig)
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

        // SkyBackground 屏幕空间渐变背景（帧首、最底图层，先于地图/建筑/特效绘制）：
        // 配置变化推送到 C++（天气/时间切换），随后 drawSky 以屏幕正交投影绘制——
        // Camera 平移/缩放不影响背景（Screen Space / Background Layer）。
        drawSkyBackground()

        // 从命令总线读取建筑数据快照（一次性读取，消除 TOCTOU 竞态）
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
            // 回退路径同样 clamp（防上游 buildingCount 与数组长度不一致时
            // C++ 越界读——bus 直达通道有 clamp，双端路径行为一致）
            frame.buildingCount.coerceAtMost((frame.buildingData?.size ?: 0) / 5)
        }

        // 场景绘制按灰度旗标双路（R3.2 灰度共存——两路消费 C++ 同一份绘制核心，
        // 像素等价由构造保证 + SceneEquivalenceTest 顶点流对照锁定）
        if (NativeEngineFlag.sceneStoreRender) {
            // R3.3：叠加层（选中/拆除/预览/网格线）几何亦由 C++ 生成——
            // 四类叠加层的可见性经 overlayFlags 每帧携带，其状态数据变化驱动
            // 导入，本帧不再有任何逐 rect 跨线（旧路径最坏 ~258 次 drawRect）。
            renderSceneStorePath(
                frame, viewportW, viewportH,
                effectiveBuildingData, effectiveBuildingCount, busWasDirty
            )
        } else {
            renderLegacyDrawAllTilesPath(frame, effectiveBuildingData, effectiveBuildingCount)
            renderLegacyOverlayPath(
                frame, effectiveBuildingData, effectiveBuildingCount, busWasDirty,
                viewportW, viewportH
            )
        }

        recordMetrics()
        NativeBridge.submitFrame()
        return true
    }

    /**
     * 旧叠加层路径（[NativeEngineFlag.sceneStoreRender]=false 的回滚臂，R3.3 红线：
     * 逐 rect 每帧跨线，代码与行为 = R3.3 开工前现状，可即时回退）：
     * 选中高亮 → 拆除高亮 → 预览精灵 → 占地框 → 网格线。
     *
     * 新路径下这五段整体由 C++ 单份生成核心产出（同层序、同几何、
     * 同常量——SceneOverlayEquivalenceTest 顶点流逐位对照锁定）。
     */
    private fun renderLegacyOverlayPath(
        frame: RenderFrame,
        buildingData: FloatArray?,
        buildingCount: Int,
        busWasDirty: Boolean,
        viewportW: Int,
        viewportH: Int
    ) {
        // 普通选中高亮（选中建筑金色描边——动态叠加，独立 draw calls，不烘焙进瓦片层）
        // 用同一份 effectiveBuildingData 快照计算，杜绝命令总线消费后索引错位
        drawSelectionHighlight(frame, buildingData, buildingCount, busWasDirty)

        // 一键拆除模式占地高亮（绿/红半透明填充——与精灵同帧同相机，
        // 与选中高亮同一份建筑快照；总线脏帧跳帧防索引错位）
        drawDemolishHighlight(frame, buildingData, buildingCount, busWasDirty)

        if (frame.showPreview && host.atlasTextureId != 0) {
            // 绘制顺序：精灵先画、占地框（填充+描边）后画——填充绿纱罩于
            //   精灵之上（标准放置 UI），且精灵透明区不透出填充色
            NativeBridge.drawSprite(
                frame.previewX, frame.previewY,
                frame.previewW, frame.previewH,
                host.atlasTextureId,
                frame.previewU0, frame.previewV0,
                frame.previewU1, frame.previewV1,
                frame.previewTintRed, frame.previewTintGreen,
                frame.previewTintBlue, frame.previewAlpha
            )
            if (frame.previewBoxVisible) {
                drawPreviewHighlight(frame)
            }
        }

        // 放置/移动模式网格线（预览精灵之上）；
        // 范围按缓存最新相机计算，与 g_projMatrix 同源零错位
        drawGridOverlay(frame, viewportW, viewportH)
    }

    /**
     * 新路径（R3.2 生产默认）：场景数据变化驱动导入 C++ SceneStore +
     * [NativeBridge.drawFrame]（相机标量 + overlay 标志，每帧 ≈36B——
     * G3 <200B 达成）。崖壁/地图层序与相机消毒段在 C++ drawFrame 内，
     * 与旧路径共享同一绘制核心；UV 表由 C++ 生成常量消费，
     * Kotlin 不再每帧传 SpriteAtlasDef 数组。
     *
     * R3.3：叠加层（选中/拆除/预览/网格线）同样只导状态不导几何——
     * overlayFlags 位每帧携带可见性与合法性，选中索引/拆除标记/预览几何
     * 在 [pushSceneUpdates] 内变化驱动导入。
     */
    private fun renderSceneStorePath(
        frame: RenderFrame,
        viewportW: Int,
        viewportH: Int,
        buildingData: FloatArray?,
        buildingCount: Int,
        busWasDirty: Boolean
    ) {
        pushSceneUpdates(frame, buildingData, buildingCount)
        var flags = 0
        if (frame.buildingVisible) flags = flags or OVERLAY_FLAG_BUILDING_VISIBLE
        if (frame.gridOverlayVisible) flags = flags or OVERLAY_FLAG_GRID_VISIBLE
        if (frame.showPreview) flags = flags or OVERLAY_FLAG_PREVIEW_SPRITE
        if (frame.previewBoxVisible) flags = flags or OVERLAY_FLAG_PREVIEW_BOX
        if (frame.previewBoxValid) flags = flags or OVERLAY_FLAG_PREVIEW_VALID
        // 总线脏帧：buildingData 刚被即时通道替换，Compose 门控的选中索引与
        // 拆除标记可能与新数据错位——本帧抑制两处高亮层（旧路径同语义跳帧，
        // 下帧自动恢复）
        if (!busWasDirty) {
            flags = flags or OVERLAY_FLAG_SELECTION or OVERLAY_FLAG_DEMOLISH
        }
        NativeBridge.drawFrame(
            camX = cachedCamX,
            camY = cachedCamY,
            scale = cachedScale,
            viewportW = viewportW,
            viewportH = viewportH,
            overlayFlags = flags,
            fadeAlpha = host.fadeAlpha,
            frameAlpha = frame.currentAlpha
        )
    }

    /**
     * 旧路径（灰度回滚臂，[NativeEngineFlag.sceneStoreRender]=false）：
     * setFadeAlpha + drawIslandCliffs + drawAllTiles（17 参数全量数组每帧跨线）。
     * 行为 = R3.2 前现状——崖壁层与瓦片层共用同帧淡入 alpha（零相位差），
     * 不依赖图集纹理（崖壁走独立纹理，图集未就绪时崖壁仍可绘制）。
     */
    private fun renderLegacyDrawAllTilesPath(
        frame: RenderFrame,
        buildingData: FloatArray?,
        buildingCount: Int
    ) {
        // 地图淡入 alpha 推送：渲染线程每帧计算（EaseOutCubic 纯时钟驱动），
        // C++ g_fadeAlpha 乘算 drawAllTiles 全部 quad——预览/高亮 drawRect 不受影响
        NativeBridge.setFadeAlpha(host.fadeAlpha)

        // 浮空岛崖壁层（世界空间；z 序：天空 → 崖壁 → 地面——先于瓦片/建筑绘制，
        // 地面层覆盖内缘接缝）。布局数据由 IslandCliffBridge 一次性预计算
        //（地图尺寸/种子变化时重建；Camera 平移/缩放不重建）。
        if (frame.islandCliffData != null) {
            drawIslandCliffs(frame)
        }

        // 从 RenderFrame 读取瓦片数据 + SpriteAtlasDef 编译时常量
        if (host.atlasTextureId != 0) {
            @Suppress("DEPRECATION") // 灰度回滚臂：旧路径保留一个版本周期（R3.2 红线）
            NativeBridge.drawAllTiles(
                tileData = frame.tileData,
                cols = host.renderConfig.worldWidthCells,
                rows = host.renderConfig.worldHeightCells,
                buildingData = buildingData,
                buildingCount = buildingCount,
                buildingVisible = frame.buildingVisible,
                tileSize = host.renderConfig.tileSize,
                atlasTexId = host.atlasTextureId,
                uvMap = SpriteAtlasDef.TILE_UV_MAP,
                buildingUVMap = SpriteAtlasDef.BUILDING_UV_MAP,
                // 灵田作物数据：低频变化走帧率门控 RenderFrame，
                // C++ 侧按进度计算阶段索引 + 淡化 alpha（与 Kotlin SpiritCropRender 同数学）
                cropData = frame.spiritCropData,
                cropUVMap = SpriteAtlasDef.CROP_UV_MAP,
                // 逻辑帧插值：作物进度帧间平滑权重（仅渲染契约）
                frameAlpha = frame.currentAlpha,
                // 云层实例数据（渲染线程逐帧生成快照——双后端共享同一份 host.cloudData，
                // 与 C++ 侧同一快照保证像素级一致；cloudUVMap 与 SpriteAtlasDef 同源）
                cloudData = host.cloudData,
                cloudUVMap = SpriteAtlasDef.CLOUD_UV_MAP,
                // 石板道路每格位掩码 + UV（双后端按位掩码合成主体/边缘/转角/十字装饰）
                roadData = frame.roadData,
                roadUVMap = SpriteAtlasDef.ROAD_UV_MAP
            )
        }
    }

    /**
     * SceneStore 场景数据推送（R3.2 新路径；渲染线程调用）。
     *
     * 变化驱动：仅当数据引用（或建筑数）相对上次推送变化时才触 JNI——
     * 场景数据生产者全部产出稳定引用：
     * - tileData：remember(基座, 建筑集) 占位副本（建筑变动一次 copyOf）；
     * - buildingData：RenderCommandBus.postBuildingData copyOf（每 post 一新引用）
     *   或帧率门控 RenderFrame 的 remember 数组；
     * - spiritCropData：derivedStateOf 重算（作物进度变化 = 每旬级）；
     * - roadData：RoadMaskTracker.syncTo 内容等价早退返回稳定引用；
     * - cloudData：CloudLayerAnimator.snapshot（脏帧才刷新）；
     * - islandCliffData：remember(尺寸/种子/掩码) 一次性预计算。
     * 内容未变时本函数零 JNI 调用；surface 重建后新实例基线为空 → 首帧全量重推
     * （C++ SceneStore 已由 shutdownRenderer 清空，两侧一致）。
     *
     * @param frame 当前帧（地形/作物/道路/云/崖壁来源）
     * @param buildingData 建筑快照（与旧路径 drawAllTiles 传入同一份：总线优先）
     * @param buildingCount 建筑数（已钳制）
     */
    private fun pushSceneUpdates(frame: RenderFrame, buildingData: FloatArray?, buildingCount: Int) {
        if (frame.tileData !== pushedTerrain) {
            NativeBridge.sceneSetTerrain(
                frame.tileData,
                host.renderConfig.worldWidthCells,
                host.renderConfig.worldHeightCells,
                host.renderConfig.tileSize
            )
            pushedTerrain = frame.tileData
        }
        if (buildingData !== pushedBuildings || buildingCount != pushedBuildingCount) {
            NativeBridge.sceneUpdateBuildings(buildingData, buildingCount)
            pushedBuildings = buildingData
            pushedBuildingCount = buildingCount
        }
        if (frame.spiritCropData !== pushedCrops) {
            NativeBridge.sceneUpdateCrops(frame.spiritCropData, countOf(frame.spiritCropData, CROP_DATA_STRIDE))
            pushedCrops = frame.spiritCropData
        }
        if (frame.roadData !== pushedRoads) {
            NativeBridge.sceneUpdateRoads(frame.roadData, frame.roadData?.size ?: 0)
            pushedRoads = frame.roadData
        }
        val clouds = host.cloudData
        if (clouds !== pushedClouds) {
            NativeBridge.sceneUpdateClouds(clouds, countOf(clouds, CLOUD_DATA_STRIDE))
            pushedClouds = clouds
        }
        if (frame.islandCliffData !== pushedCliffs) {
            NativeBridge.sceneSetCliffLayout(
                frame.islandCliffData,
                countOf(frame.islandCliffData, IslandCliffBridge.PIECE_STRIDE)
            )
            pushedCliffs = frame.islandCliffData
        }
        if (host.atlasTextureId != pushedAtlasTexId) {
            NativeBridge.sceneSetAtlasTexture(host.atlasTextureId)
            pushedAtlasTexId = host.atlasTextureId
        }
        pushOverlayUpdates(frame)
    }

    /**
     * 叠加层状态推送（R3.3；渲染线程调用）——只导状态、不导几何。
     *
     * 与场景六要素同一脏更新协议：**值/引用比较**后才触 JNI——
     * - 选中索引：Int 值比较（选中切换那一帧才触线）；
     * - 拆除标记：引用比较（`derivedStateOf` 仅在拆除模式/勾选集变化时产新数组）；
     * - 预览几何：16 个浮点逐项值比较（拖拽帧才触线；精灵与占地框同源一帧，
     *   与旧路径 `frame.showPreview && previewBoxVisible` 同帧携带一致）。
     *
     * 网格线/占地框/高亮**本帧可见性与合法性**不经本函数——每帧由
     * [renderSceneStorePath] 的 overlayFlags 位携带（drawFrame 已有一次跨线，零新增）。
     */
    private fun pushOverlayUpdates(frame: RenderFrame) {
        if (frame.selectedBuildingIndex != pushedSelectionIndex) {
            NativeBridge.sceneSetSelection(frame.selectedBuildingIndex)
            pushedSelectionIndex = frame.selectedBuildingIndex
        }
        val markers = frame.demolishHighlightData
        if (markers !== pushedMarkers) {
            NativeBridge.sceneSetDemolishMarkers(markers, markers?.size ?: 0)
            pushedMarkers = markers
        }
        // 预览几何写进复用缓冲后与已推送基线逐项比较（基线初值 NaN ⇒ 首帧恒判脏；
        // 跨线时 C++ 侧按值拷贝，故缓冲可复用零分配）
        val preview = previewScratch
        preview[0] = frame.previewBoxX
        preview[1] = frame.previewBoxY
        preview[2] = frame.previewBoxW
        preview[3] = frame.previewBoxH
        preview[4] = frame.previewX
        preview[5] = frame.previewY
        preview[6] = frame.previewW
        preview[7] = frame.previewH
        preview[8] = frame.previewU0
        preview[9] = frame.previewV0
        preview[10] = frame.previewU1
        preview[11] = frame.previewV1
        preview[12] = frame.previewTintRed
        preview[13] = frame.previewTintGreen
        preview[14] = frame.previewTintBlue
        preview[15] = frame.previewAlpha
        var previewChanged = false
        for (i in preview.indices) {
            if (preview[i] != pushedPreviewValues[i]) previewChanged = true
        }
        if (previewChanged) {
            NativeBridge.sceneSetPreview(preview)
            preview.copyInto(pushedPreviewValues)
        }
    }

    /** 步长数组 → 条目数（null = 0） */
    private fun countOf(data: FloatArray?, stride: Int): Int {
        if (data == null) return 0
        return data.size / stride
    }

    /** 帧指标记录（热控降级可观测 + 帧计数——提取以收敛 renderFrame 行数） */
    private fun recordMetrics() {
        // 热控降级可观测性：装饰层被跳过（decorationsDisabled || qualityFactor < 0.6）
        //   的 Vulkan 帧计数——与 C++ drawAllTiles 的 skipDecor 判定同语义（阈值同 0.6）
        if (host.renderDecorationsDisabled || host.renderQualityFactor < DECOR_QUALITY_THRESHOLD) {
            RenderMetrics.vulkanDecorSkippedFrames.incrementAndGet()
        }
        RenderMetrics.vulkanFrames.incrementAndGet()
        RenderMetrics.totalFrames.incrementAndGet()
        RenderMetrics.recordFrame()
    }

    override fun release() {
        NativeBridge.shutdownRenderer()
    }

    /**
     * 绘制屏幕空间天空背景：先把当前 skyConfig 推送到 C++（仅变化时，避免每帧 JNI 开销），
     * 再触发 drawSky（以屏幕正交投影绘制，Camera 平移/缩放不影响）。
     */
    private fun drawSkyBackground() {
        pushSkyConfigIfChanged(host.skyConfig)
        NativeBridge.drawSky()
    }

    /**
     * 推送天空配置到 C++（仅配置变化时调用，避免每帧 JNI 开销）。
     * 与 C++ g_sky.resetToDefault() 配合：surface 重建/降级链切换后由 [init] 重放当前配置。
     */
    private fun pushSkyConfigIfChanged(config: SkyBackgroundConfig) {
        if (host.lastPushedSkyConfig == config) return
        NativeBridge.setSkyConfig(
            config.topColor.r, config.topColor.g, config.topColor.b,
            config.secondColor.r, config.secondColor.g, config.secondColor.b,
            config.thirdColor.r, config.thirdColor.g, config.thirdColor.b,
            config.bottomColor.r, config.bottomColor.g, config.bottomColor.b,
            config.secondT, config.thirdT, config.strength
        )
        host.lastPushedSkyConfig = config
    }

    /**
     * 浮空岛崖壁层绘制（世界空间；z 序：天空 → 崖壁 → 地面）。
     * 只消费 [RenderFrame.islandCliffData] 布局引用（一次性预计算，稳定）——
     * 可见性剔除与按纹理分批在 C++ 侧 NativeBridge.drawIslandCliffs 完成。
     *
     * 崖壁走**独立纹理**（超出图集容量），纹理 ID 由宿主经
     * [NativeBridge.setIslandCliffTextures] 一次性注入；此处只判「是否已有任一张
     * 可用」——全不可用则整层跳过（C++ 侧亦会逐条目跳过不可用纹理）。
     * 观测锚点（保留级，进程内一次）：确认渲染端消费到布局数据。
     */
    private fun drawIslandCliffs(frame: RenderFrame) {
        val layout = frame.islandCliffData ?: return
        if (!host.hasAnyCliffTexture) return
        if (!islandCliffDrawnLogged) {
            islandCliffDrawnLogged = true
            android.util.Log.d(
                ISLAND_CLIFF_LOG_TAG,
                "drawIslandCliffs ${layout.size / IslandCliffBridge.PIECE_STRIDE} pieces " +
                    "(cliffTextures=${host.cliffTextureCount})"
            )
        }
        NativeBridge.drawIslandCliffs(cliffData = layout)
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
     * 绘制放置/移动模式占地框（预览框）：与建筑精灵同帧同源（绿=可放置 / 红=不可放置提示）。
     * 世界坐标直传 drawRect（投影矩阵 g_projMatrix 做相机变换，与精灵同相机零错位）；
     * 精灵居中+底部对齐绘于其内——两者共享同一份预览快照，物理上永不同步脱节。
     *
     * @param frame 当前帧（previewBoxVisible 开关 + 几何/合法性）
     */
    private fun drawPreviewHighlight(frame: RenderFrame) {
        if (!frame.previewBoxVisible) return
        val x = frame.previewBoxX
        val y = frame.previewBoxY
        val w = frame.previewBoxW
        val h = frame.previewBoxH
        val tileSize = host.renderConfig.tileSize
        val scale = cachedScale.coerceAtLeast(MIN_SCALE)
        // 目标屏幕线宽 max(2px, tileSize×0.06×scale)，换算回世界坐标除以 scale
        val lineWidth = maxOf(2f, tileSize * HIGHLIGHT_LINE_WIDTH_TILES * scale) / scale
        val valid = frame.previewBoxValid
        val fillR = if (valid) PREVIEW_GREEN_R else PREVIEW_RED_R
        val fillG = if (valid) PREVIEW_GREEN_G else PREVIEW_RED_G
        val fillB = if (valid) PREVIEW_GREEN_B else PREVIEW_RED_B
        // 填充（绿/红半透明，可放置提示）→ 上边 → 下边 → 左边 → 右边
        //（描边盖住填充边缘，避免颜色叠加发亮）
        NativeBridge.drawRect(x, y, w, h, fillR, fillG, fillB, PREVIEW_BOX_FILL_ALPHA)
        NativeBridge.drawRect(x, y, w, lineWidth, fillR, fillG, fillB, PREVIEW_BOX_EDGE_ALPHA)
        NativeBridge.drawRect(x, y + h - lineWidth, w, lineWidth, fillR, fillG, fillB, PREVIEW_BOX_EDGE_ALPHA)
        NativeBridge.drawRect(x, y, lineWidth, h, fillR, fillG, fillB, PREVIEW_BOX_EDGE_ALPHA)
        NativeBridge.drawRect(x + w - lineWidth, y, lineWidth, h, fillR, fillG, fillB, PREVIEW_BOX_EDGE_ALPHA)
    }

    /**
     * 绘制放置/移动模式全视口网格线（世界坐标薄矩形，drawRect×视口线数）。
     *
     * 范围数学与 Canvas 侧 `SoftwareCanvasBackend.drawGridOverlay` 同式：按缓存
     * 最新相机（setCamera 自留份，与 g_projMatrix 同源）计算视口内行列区间并
     * 钳制到世界边界，**行范围含俯视 Y 轴压缩**（视口高 ÷ (scale × 0.75)）——
     * 与投影可见带严格一致；列线 x = col×tileSize、行线 y = row×tileSize，
     * 全高/全宽延伸（投影矩阵自动裁剪视口外部分）。线宽换算为世界坐标
     * （目标 2 物理屏像素，下限 0.5 世界单位）。
     *
     * 新路径（[NativeEngineFlag.sceneStoreRender]=true）下本函数不参与渲染——
     * 同一几何由 C++ `scene_draw.h::buildOverlayLayers` 以同式产出（含本行范围
     * 口径），两路一致由 SceneOverlayProtocolGuardTest 的公式守卫与
     * SceneOverlayEquivalenceTest 的顶点流对照共同锁定。
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
        // 行范围按投影可见带：世界可见高度 = 视口高 / (scale × 俯视 Y 压缩系数)
        // ——与 C++ g_viewBottom / Canvas 侧 drawGridOverlay 同式（B11 前置缺陷 A
        // 修复：旧写法漏乘 TOPDOWN_Y_SCALE，导致 Vulkan/GLES 放置模式视口底部
        // 缺横线、与 Canvas 兜底路径不一致）
        val lastRow = ((cachedCamY + viewportH / (scale * SpriteAtlasDef.TOPDOWN_Y_SCALE)) / tileSize)
            .toInt()
            .coerceAtMost(host.renderConfig.worldHeightCells)

        // 目标屏幕线宽 1px，换算回世界坐标（除 scale）；下限 0.5 世界单位防退化 quad。
        //    离屏降采样渲染下 1 物理屏像素 = 0.5 离屏像素，细线光栅化会被整条丢弃
        //    （放置模式方格不完整/单线根因）——线宽按 2 物理屏像素起
        val lineWidth = maxOf(0.5f, 2f / scale)
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
        /** 浮空岛边缘观测日志标签（渲染端消费锚点） */
        private const val ISLAND_CLIFF_LOG_TAG = "IslandCliff"

        /** 首次绘制日志标志（进程内一次——避免每帧日志） */
        private var islandCliffDrawnLogged = false

        /** 装饰层跳过阈值（qualityFactor < 0.6 时装饰降级——与 Canvas 帧缓冲 RGB_565 阈值同常量） */
        private const val DECOR_QUALITY_THRESHOLD = 0.6f

        /** 建筑数据单条步长（[gx, gy, sw, sh, nameIdx]） */
        private const val SELECTED_DATA_STRIDE = 5

        /** drawFrame overlayFlags bit0：建筑层可见 */
        private const val OVERLAY_FLAG_BUILDING_VISIBLE = 0x1

        /** bit1–bit6：R3.3 叠加层（网格线/预览精灵/占地框/合法性/选中/拆除高亮） */
        private const val OVERLAY_FLAG_GRID_VISIBLE = 0x2
        private const val OVERLAY_FLAG_PREVIEW_SPRITE = 0x4
        private const val OVERLAY_FLAG_PREVIEW_BOX = 0x8
        private const val OVERLAY_FLAG_PREVIEW_VALID = 0x10
        private const val OVERLAY_FLAG_SELECTION = 0x20
        private const val OVERLAY_FLAG_DEMOLISH = 0x40

        /** 预览数据单条步长（与 C++ `scene::kPreviewStride` 同值，见 sceneSetPreview） */
        private const val PREVIEW_DATA_STRIDE = 16

        /** 叠加层推送基线的"尚未推送"哨兵（-1 是合法的"无选中"值，不可复用） */
        private const val SENTINEL_SELECTION_UNSET = Int.MIN_VALUE

        /** 灵田作物数据单条步长（[gx, gy, progress01]，与 C++ scene::kCropStride 同值） */
        private const val CROP_DATA_STRIDE = 3

        /** 云实例数据单条步长（[x, y, w, h, spriteIndex, alpha]，与 CloudLayerAnimator 同值） */
        private const val CLOUD_DATA_STRIDE = 6

        // ── 叠加层视觉常量（R3.3/B11）──
        // 单一权威 = build-atlas.mjs 的 LAYOUT.overlay，双端生成物
        // （Kotlin SpriteAtlasDef / C++ scene_uv_tables.h）同值——旧路径（本类
        // 逐 rect）与新路径（C++ 几何生成）共用一份数据源，两路同值由构造保证。
        // 本段为引用别名（值与 R3.3 前逐位一致），语义见 SpriteAtlasDef 同名常量。

        /** 缩放下限（防御除零） */
        private const val MIN_SCALE = SpriteAtlasDef.OVERLAY_MIN_SCALE

        /** 高亮线宽（格数）：max(2px, tileSize×0.06) 的格数分量 */
        private const val HIGHLIGHT_LINE_WIDTH_TILES = SpriteAtlasDef.HIGHLIGHT_LINE_WIDTH_TILES

        /** 高亮填充不透明度（金色半透明填充） */
        private const val HIGHLIGHT_FILL_ALPHA = SpriteAtlasDef.HIGHLIGHT_FILL_ALPHA

        /** 高亮描边不透明度 */
        private const val HIGHLIGHT_EDGE_ALPHA = SpriteAtlasDef.HIGHLIGHT_EDGE_ALPHA

        /** 金色 #FFD700 */
        private const val GOLD_R = SpriteAtlasDef.GOLD_R
        private const val GOLD_G = SpriteAtlasDef.GOLD_G
        private const val GOLD_B = SpriteAtlasDef.GOLD_B

        // ── 拆除模式占地高亮（与旧 Compose 覆盖层同色 #4CAF50 / #F44336） ──

        /** 未选中绿 #4CAF50 */
        private const val DEMOLISH_GREEN_R = SpriteAtlasDef.DEMOLISH_GREEN_R
        private const val DEMOLISH_GREEN_G = SpriteAtlasDef.DEMOLISH_GREEN_G
        private const val DEMOLISH_GREEN_B = SpriteAtlasDef.DEMOLISH_GREEN_B
        /** 选中红 #F44336 */
        private const val DEMOLISH_RED_R = SpriteAtlasDef.DEMOLISH_RED_R
        private const val DEMOLISH_RED_G = SpriteAtlasDef.DEMOLISH_RED_G
        private const val DEMOLISH_RED_B = SpriteAtlasDef.DEMOLISH_RED_B
        /** 拆除填充不透明度（0x66 = 40% 半透明） */
        private const val DEMOLISH_FILL_ALPHA = SpriteAtlasDef.DEMOLISH_FILL_ALPHA
        /** 拆除描边不透明度 */
        private const val DEMOLISH_EDGE_ALPHA = SpriteAtlasDef.DEMOLISH_EDGE_ALPHA

        // ── 放置/移动模式网格线（与旧 Compose GridOverlay 同色 #E4DDD0） ──

        /** 网格线 #E4DDD0 */
        private const val GRID_R = SpriteAtlasDef.GRID_R
        private const val GRID_G = SpriteAtlasDef.GRID_G
        private const val GRID_B = SpriteAtlasDef.GRID_B
        /** 网格线不透明度 */
        private const val GRID_ALPHA = SpriteAtlasDef.GRID_ALPHA

        // ── 放置/移动模式占地框（预览框）：绿/红提示可放置/不可放置（与拆除色系一致） ──

        /** 可放置 #4CAF50 */
        private const val PREVIEW_GREEN_R = SpriteAtlasDef.PREVIEW_GREEN_R
        private const val PREVIEW_GREEN_G = SpriteAtlasDef.PREVIEW_GREEN_G
        private const val PREVIEW_GREEN_B = SpriteAtlasDef.PREVIEW_GREEN_B
        /** 不可放置 #F44336 */
        private const val PREVIEW_RED_R = SpriteAtlasDef.PREVIEW_RED_R
        private const val PREVIEW_RED_G = SpriteAtlasDef.PREVIEW_RED_G
        private const val PREVIEW_RED_B = SpriteAtlasDef.PREVIEW_RED_B
        /** 占地框填充不透明度（0x59 ≈ 35% 半透明） */
        private const val PREVIEW_BOX_FILL_ALPHA = SpriteAtlasDef.PREVIEW_BOX_FILL_ALPHA
        /** 占地框描边不透明度（0xE6 ≈ 90%） */
        private const val PREVIEW_BOX_EDGE_ALPHA = SpriteAtlasDef.PREVIEW_BOX_EDGE_ALPHA
    }
}
