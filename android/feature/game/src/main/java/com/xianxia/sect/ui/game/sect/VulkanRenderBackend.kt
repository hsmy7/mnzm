package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.nativebridge.NativeBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.render.IslandCliffBridge
import com.xianxia.sect.core.render.RenderBackend
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.RenderMetrics
import com.xianxia.sect.core.render.SkyBackgroundConfig

/**
 * Vulkan 渲染后端适配器 — 将 [RenderBackend] 契约翻译为 C++ NativeBridge 调用。
 *
 * ## 职责
 * - [resize] → NativeBridge.resizeRenderer（交换链重建）
 * - [setCamera] → NativeBridge.setCamera（独立相机通道）
 * - [renderFrame] → beginFrame → 天空背景 → 场景绘制（SceneStore 单一路径，
 *   R3.2/R3.3 灰度收口后旧 drawAllTiles 回滚臂已随 B18 删除）：
 *   - [SceneUpdateChannel.push]（场景 + 叠加层状态变化驱动导入 C++
 *     SceneStore，R3.4）+ drawFrame(相机, overlayFlags)——Kotlin 不再
 *     每帧传全量数组，**也不再每帧逐 rect 传叠加层几何**（选中/拆除/预览/
 *     网格线由 C++ 生成）；
 *   绘制核心在 C++ 侧单份（scene_draw.h）——与 Canvas 兜底的像素等价由
 *   构造 + SceneEquivalenceTest / SceneOverlayEquivalenceTest 顶点流锁定。
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

    // ── SceneStore 新路径的脏更新通道（R3.2 场景导入 + R3.3 叠加层状态）──
    // 「变化才跨线」的判定与基线全部收敛在 SceneUpdateChannel（R3.4）：
    // 渲染线程单消费者，与 C++ 导入端口同线程顺序执行；surface 重建 = 新后端
    // 实例 = 新通道实例 ⇒ 基线随实例复位，首帧重推全部场景（C++ 侧
    // shutdownRenderer 已同步清空 SceneStore）。
    private val sceneUpdates = SceneUpdateChannel(nativeSceneUpdateSink)

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

        // 场景绘制（SceneStore 单一路径，R3.3 起叠加层几何亦由 C++ 生成——
        // 四类叠加层的可见性经 overlayFlags 每帧携带，其状态数据变化驱动导入，
        // 本帧不再有任何逐 rect 跨线（旧路径最坏 ~258 次 drawRect，B18 已退役）。
        // 两路等价曾由 SceneEquivalenceTest（地图/崖壁）与
        // SceneOverlayEquivalenceTest（叠加层）顶点流逐位对照锁定
        renderSceneStorePath(
            frame, viewportW, viewportH,
            effectiveBuildingData, effectiveBuildingCount, busWasDirty
        )

        recordMetrics()
        NativeBridge.submitFrame()
        return true
    }

    /**
     * 新路径（R3.2 生产默认，B18 后为唯一路径）：场景数据变化驱动导入 C++
     * SceneStore +
     * [NativeBridge.drawFrame]（相机标量 + overlay 标志，每帧 ≈36B——
     * G3 <200B 达成）。崖壁/地图层序与相机消毒段在 C++ drawFrame 内，
     * 与旧路径共享同一绘制核心；UV 表由 C++ 生成常量消费，
     * Kotlin 不再每帧传 SpriteAtlasDef 数组。
     *
     * R3.3：叠加层（选中/拆除/预览/网格线）同样只导状态不导几何——
     * overlayFlags 位每帧携带可见性与合法性，选中索引/拆除标记/预览几何
     * 与场景六要素一起在 [SceneUpdateChannel.push] 内变化驱动导入（R3.4）。
     */
    private fun renderSceneStorePath(
        frame: RenderFrame,
        viewportW: Int,
        viewportH: Int,
        buildingData: FloatArray?,
        buildingCount: Int,
        busWasDirty: Boolean
    ) {
        RenderMetrics.sceneUpdateFrames.incrementAndGet()
        sceneUpdates.push(
            SceneUpdateInputs(
                frame = frame,
                buildingData = buildingData,
                buildingCount = buildingCount,
                cloudData = host.cloudData,
                atlasTextureId = host.atlasTextureId,
                worldCols = host.renderConfig.worldWidthCells,
                worldRows = host.renderConfig.worldHeightCells,
                tileSize = host.renderConfig.tileSize
            )
        )
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

    companion object {
        /** 浮空岛边缘观测日志标签（渲染端消费锚点） */
        private const val ISLAND_CLIFF_LOG_TAG = "IslandCliff"

        /** 首次绘制日志标志（进程内一次——避免每帧日志） */
        private var islandCliffDrawnLogged = false

        /** 装饰层跳过阈值（qualityFactor < 0.6 时装饰降级——与 Canvas 帧缓冲 RGB_565 阈值同常量） */
        private const val DECOR_QUALITY_THRESHOLD = 0.6f

        /** drawFrame overlayFlags bit0：建筑层可见 */
        private const val OVERLAY_FLAG_BUILDING_VISIBLE = 0x1

        /** bit1–bit6：R3.3 叠加层（网格线/预览精灵/占地框/合法性/选中/拆除高亮） */
        private const val OVERLAY_FLAG_GRID_VISIBLE = 0x2
        private const val OVERLAY_FLAG_PREVIEW_SPRITE = 0x4
        private const val OVERLAY_FLAG_PREVIEW_BOX = 0x8
        private const val OVERLAY_FLAG_PREVIEW_VALID = 0x10
        private const val OVERLAY_FLAG_SELECTION = 0x20
        private const val OVERLAY_FLAG_DEMOLISH = 0x40

    }
}
