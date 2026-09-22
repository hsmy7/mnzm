package com.xianxia.sect.ui.game

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.render.BuildingRenderGeometry
import com.xianxia.sect.core.render.NativeRenderConfig
import com.xianxia.sect.core.render.RenderFrame
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.game.map.sect.SectCameraState
import com.xianxia.sect.ui.game.sect.NativeSurfaceView
import com.xianxia.sect.ui.game.sect.setCamera
import com.xianxia.sect.ui.game.sect.updateRenderState
import com.xianxia.sect.ui.game.sect.RenderCommandBus
import com.xianxia.sect.ui.game.sect.SurfaceProviderFactory

/** 建筑名称 → 精灵图集索引（SectMapViewport 与 buildBuildingDataArray 共用） */
internal val BUILDING_NAME_INDEX: Map<String, Int> = SpriteAtlasDef.BUILDING_NAME_INDEX

/** 建筑精灵 UV 映射表（与 C++ TextureAtlas.h 一致） */
internal val BUILDING_UV_MAP: FloatArray = SpriteAtlasDef.BUILDING_UV_MAP

/**
 * 宗门地图 Vulkan/Canvas 双路径渲染视口。
 *
 * 参数全部为稳定引用（remember/derivedStateOf 产物）——MainGameScreen 每旬
 * gameData 变化重组时，本组件参数引用未变则跳过重组（AndroidView update 不执行，
 * setCamera/updateViewport 不再每旬调用）。相机/预览/建筑数据实际变化时才重组。
 *
 * update 块内读取均在帧率门控外（维持 Compose 订阅活跃依赖）。
 *
 * @param params 渲染参数聚合（含相机/瓦片/建筑/渲染配置——全部稳定引用）
 * @param preview 建筑放置/移动预览状态聚合（mutableState 变化时重建）
 * @param commandBus 渲染命令直达通道（RenderCommandBus 单例）
 * @param onViewCreated NativeSurfaceView 创建回调（挂载 touchEngine/帧率订阅）
 * @param modifier 修饰符
 */
@Composable
internal fun SectMapViewport(
    params: SectMapViewportParams,
    preview: MapPreviewState,
    commandBus: RenderCommandBus,
    onViewCreated: (NativeSurfaceView) -> Unit,
    modifier: Modifier = Modifier
) {
    // RenderFrame 推送帧率门控
    // SOFTWARE 路径下限制推送频率（RenderThread 自行读取 currentFrame 原子快照）
    val lastRenderDataSyncNs = remember { mutableLongStateOf(0L) }

    AndroidView(
        factory = { ctx ->
            createSectMapSurfaceView(
                ctx = ctx,
                params = params,
                onViewCreated = onViewCreated
            )
        },
        update = { view ->
            updateSectMapView(
                view = view,
                params = params,
                preview = preview,
                commandBus = commandBus,
                lastRenderDataSyncNs = lastRenderDataSyncNs
            )
        },
        modifier = modifier
    )
}

/** 地图 SurfaceView 创建：AndroidView factory 逻辑 */
private fun createSectMapSurfaceView(
    ctx: Context,
    params: SectMapViewportParams,
    onViewCreated: (NativeSurfaceView) -> Unit
): NativeSurfaceView = NativeSurfaceView(ctx, params.nativeConfig).also { view ->
    onViewCreated(view)

    // 平台 surface 提供者替换（Hilt 工厂创建；setter 自动解绑默认实例监听器，
    // 旧实例仍挂在 holder 上但不再派发事件——平台回调翻译归 provider 管理）
    view.surfaceProvider = params.surfaceProviderFactory.create(view.holder)

    // 强制软件渲染（模拟器/Vulkan 崩溃自愈安全模式）
    if (params.forceSoftwareRendering) {
        view.useRenderMode = NativeSurfaceView.RenderMode.SOFTWARE
    } else if (params.glesRendering) {
        // GPU OpenGL ES 中间层（Vulkan 不可靠但 GPU 可用）：直接走 GPU GLES，跳过 Vulkan
        view.useRenderMode = NativeSurfaceView.RenderMode.GLES
    }

    // GPU 能力档位（渲染缩放决策输入——
    // 在 surface 初始化事件前设置，recomputeRenderScale 消费）
    view.gpuTier = params.gpuTier

    // 渲染器就绪后上传纹理（地面/装饰/建筑全部在单张图集中）
    //
    // 图集拼装（逐精灵解码 + Canvas 绘制 + ARGB→RGBA 转换，2048² 在
    //   低端机可达数百毫秒）绝不在主线程执行——buildAtlasAsync 把重活放后台
    //   线程，只把最后一次 GPU 上传 post 回主线程（C++ g_renderer 无锁，
    //   上传不能与渲染线程并发）。纹理 ID 在回调里赋值，期间 atlasTextureId
    //   仍为 0，渲染线程按既有守卫跳过瓦片层（地图淡入遮蔽）。
    view.onRendererReady = {
        view.buildAtlasAsync(ctx) { texId -> view.atlasTextureId = texId }
        // 崖壁独立纹理（超出图集容量）：与图集同纪律——重活在后台、上传在主线程，
        // 掩码写入后 Compose 层据以重建崖壁布局（部分降级由掩码表达）
        view.loadIslandCliffTextures()
    }

    // Vulkan 初始化生命周期监听（由 GameActivity 驱动 CrashRecoveryEngine）
    view.vulkanInitListener = params.vulkanInitListener

    // 初始设置 camera + 瓦片数据（通过 RenderFrame 单通道传递）
    view.updateRenderState(
        RenderFrame(
            tileData = params.flatTileData,
            cols = params.worldWidthCells,
            rows = params.worldHeightCells,
            camX = params.cameraState.cameraX,
            camY = params.cameraState.cameraY,
            scale = params.cameraState.scale,
            spiritCropData = params.spiritCropData,
            currentAlpha = params.alphaProvider(),
            // 弯曲地皮轮廓（地图边缘 v2；一次性预计算稳定引用——Camera 平移/缩放不重建）
            groundBoundaryData = params.groundBoundaryData
        )
    )
}

/** 视口同步：相机/命令总线/预览/建筑数据 → 帧率门控 RenderFrame 推送 */
private fun updateSectMapView(
    view: NativeSurfaceView,
    params: SectMapViewportParams,
    preview: MapPreviewState,
    commandBus: RenderCommandBus,
    lastRenderDataSyncNs: MutableLongState
) {
    // RenderFrame 推送帧率门控（SOFTWARE 路径限制推送频率，Vulkan 路径不高于 60fps）
    val now = System.nanoTime()
    val minIntervalNs = if (params.forceSoftwareRendering) 33_000_000L else 16_000_000L

    // 始终同步视口到 touchEngine（手势引擎与帧率无关）
    view.touchEngine?.updateViewport(view.width.toFloat(), view.height.toFloat())

    // 在门控外读取相机状态，维持 Compose 订阅活跃（门控内读取会丢失依赖跟踪，地图停滞）
    val snapCamX = params.cameraState.cameraX
    val snapCamY = params.cameraState.cameraY
    val snapScale = params.cameraState.scale

    // 独立推送相机（不经过帧率门控），确保拖拽时相机响应无延迟
    view.setCamera(snapCamX, snapCamY, snapScale)

    // 注入渲染命令总线（直达推送通道，在帧率门控外注入引用）
    view.commandBus = commandBus

    // 在门控外读取预览状态，维持 Compose 订阅活跃（与 camera 同理）
    val previewSnapshot = computeMapPreview(preview = preview, params = params)

    // 建筑数据也必须在门控外读取（取消后建筑消失回归防护）
    val buildingData = params.buildingDataArray
    val effectiveCount = params.buildingCount

    // 选中建筑索引（高亮）：点击格坐标经 findBuildingIndex 转换（占地 footprint 同源）
    val selectedGrid = params.selectedGrid
    val selectedBuildingIndex = if (selectedGrid != null && buildingData != null) {
        BuildingRenderGeometry.findBuildingIndex(
            selectedGrid.first, selectedGrid.second,
            buildingData, effectiveCount
        )
    } else -1

    // 帧率门控：低于间隔跳过 RenderFrame 推送（不影响 RenderCommandBus 直达通道）
    if (now - lastRenderDataSyncNs.longValue >= minIntervalNs) {
        lastRenderDataSyncNs.longValue = now
        // Camera + 预览 + 建筑数据通过 RenderFrame 单通道推送（Vulkan/Canvas 双后端共用）
        view.updateRenderState(
            buildSectRenderFrame(
                params = params,
                snapCamX = snapCamX,
                snapCamY = snapCamY,
                snapScale = snapScale,
                buildingData = buildingData,
                effectiveCount = effectiveCount,
                selectedBuildingIndex = selectedBuildingIndex,
                preview = previewSnapshot,
                currentAlpha = params.alphaProvider()
            )
        )
    }
}

/** 预览渲染数据计算：预览状态 → 精灵 UV 与位置（须在帧率门控外调用以维持 Compose 订阅） */
@Suppress("CyclomaticComplexMethod")
private fun computeMapPreview(
    preview: MapPreviewState,
    params: SectMapViewportParams
): MapPreviewSnapshot {
    val mb = preview.movingBuilding
    val isPreviewActive = preview.isPlacingBuilding || mb != null
    val previewBuildingName = when {
        preview.isPlacingBuilding -> preview.placingBuildingName
        mb != null -> mb.displayName
        else -> ""
    }
    // 石板道路：非建筑精灵，预览用 ROAD_UV_MAP[0]（road_body 石板纹理，1×1 格）
    val isRoadPreview = previewBuildingName == com.xianxia.sect.core.GameConfig.Road.DISPLAY_NAME
    val previewNameIdx = if (isRoadPreview) {
        -1
    } else {
        BUILDING_NAME_INDEX[
            com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
                .findByDisplayName(previewBuildingName)?.effectiveSpriteName() ?: previewBuildingName
        ] ?: -1
    }
    val hasPreview = isPreviewActive && (isRoadPreview || previewNameIdx >= 0)

    val previewUvs = when {
        isRoadPreview -> floatArrayOf(
            SpriteAtlasDef.ROAD_UV_MAP[0],
            SpriteAtlasDef.ROAD_UV_MAP[1],
            SpriteAtlasDef.ROAD_UV_MAP[2],
            SpriteAtlasDef.ROAD_UV_MAP[3]
        )
        hasPreview -> floatArrayOf(
            BUILDING_UV_MAP[previewNameIdx * 4],
            BUILDING_UV_MAP[previewNameIdx * 4 + 1],
            BUILDING_UV_MAP[previewNameIdx * 4 + 2],
            BUILDING_UV_MAP[previewNameIdx * 4 + 3]
        )
        else -> null
    }

    val px = if (mb != null) preview.movingWorldX else preview.placingWorldX
    val py = if (mb != null) preview.movingWorldY else preview.placingWorldY
    val pSize = if (mb != null) preview.movingBuildingSize else preview.placingBuildingSize
    val pValid = if (mb != null) preview.movingValid else preview.placementValidity

    // 视觉比例居中偏移：精灵居中于占地网格
    val (previewSW, previewSH) = if (previewBuildingName.isNotEmpty()) {
        val s = params.buildingSpriteSizes[previewBuildingName]
        (s?.width ?: pSize.width) to (s?.height ?: pSize.height)
    } else (pSize.width to pSize.height)
    val previewOffsetX = (pSize.width - previewSW) * params.tileSize * 0.5f
    val previewOffsetY = (pSize.height - previewSH) * params.tileSize.toFloat() // 底部对齐

    return MapPreviewSnapshot(
        hasPreview = hasPreview,
        previewUvs = previewUvs,
        previewX = px + previewOffsetX,
        previewY = py + previewOffsetY,
        previewWidth = (previewSW * params.tileSize).toFloat(),
        previewHeight = (previewSH * params.tileSize).toFloat(),
        // 占地框（预览框）：网格对齐覆盖建筑占地格；颜色由放置/移动合法性驱动
        boxX = px,
        boxY = py,
        boxW = (pSize.width * params.tileSize).toFloat(),
        boxH = (pSize.height * params.tileSize).toFloat(),
        boxValid = pValid == GridSnapHelper.PlacementValidity.Valid
    )
}

/** RenderFrame 构建：单通道推送，Vulkan/Canvas 双后端共用 */
@Suppress("LongParameterList")
/** 边界复合数据空实例（data class 数组字段按引用比较——默认值必须共享单例） */
private val EMPTY_GROUND_BOUNDARY = FloatArray(0)

private fun buildSectRenderFrame(
    params: SectMapViewportParams,
    snapCamX: Float,
    snapCamY: Float,
    snapScale: Float,
    buildingData: FloatArray?,
    effectiveCount: Int,
    selectedBuildingIndex: Int,
    preview: MapPreviewSnapshot,
    currentAlpha: Float
): RenderFrame = RenderFrame(
    tileData = params.flatTileData,
    cols = params.worldWidthCells,
    rows = params.worldHeightCells,
    camX = snapCamX,
    camY = snapCamY,
    scale = snapScale,
    buildingVisible = true,
    buildingData = buildingData,
    buildingCount = effectiveCount,
    selectedBuildingIndex = selectedBuildingIndex,
    showPreview = preview.hasPreview,
    previewX = preview.previewX,
    previewY = preview.previewY,
    previewW = preview.previewWidth,
    previewH = preview.previewHeight,
    previewU0 = preview.previewUvs?.get(0) ?: 0f,
    previewV0 = preview.previewUvs?.get(1) ?: 0f,
    previewU1 = preview.previewUvs?.get(2) ?: 0f,
    previewV1 = preview.previewUvs?.get(3) ?: 0f,
    previewTintRed = 1.0f,
    previewTintGreen = 1.0f,
    previewTintBlue = 1.0f,
    // 精灵不透明显示（半透明会与半透明预览框
    //   叠成"两个绿色半透明背景"，观感混乱——精灵全显、提示框仍半透明）
    previewAlpha = 1.0f,
    // 占地框（预览框）：与预览精灵同帧同源（绿/红提示可放置/不可放置）
    previewBoxVisible = preview.hasPreview,
    previewBoxValid = preview.boxValid,
    previewBoxX = preview.boxX,
    previewBoxY = preview.boxY,
    previewBoxW = preview.boxW,
    previewBoxH = preview.boxH,
    // 灵田作物数据：低频变化走帧率门控 RenderFrame（不新增命令总线）
    spiritCropData = params.spiritCropData,
    // 拆除模式高亮 + 网格线：低频变化（模式进出/选中切换）走帧率
    // 门控 RenderFrame——与精灵同帧同相机快照，消除 Compose 覆盖层相位差
    demolishHighlightData = params.demolishHighlightData,
    gridOverlayVisible = params.gridOverlayVisible,
    // 石板道路每格位掩码（双后端按其合成道路主体/边缘/转角/十字装饰）
    roadData = params.roadData,
    // 浮空岛边缘布局（C++ 单一权威合成器一次性预计算——稳定引用）
    groundBoundaryData = params.groundBoundaryData,
    // 逻辑帧插值因子（作物进度帧间平滑权重）
    currentAlpha = currentAlpha
)

/** 建筑放置/移动预览渲染快照：由预览状态派生，供帧构建使用 */
private data class MapPreviewSnapshot(
    val hasPreview: Boolean,
    val previewUvs: FloatArray?,
    val previewX: Float,
    val previewY: Float,
    val previewWidth: Float,
    val previewHeight: Float,
    // 占地框（预览框）：与预览精灵同帧同源（绿/红提示可放置/不可放置）
    val boxX: Float,
    val boxY: Float,
    val boxW: Float,
    val boxH: Float,
    val boxValid: Boolean
)

/**
 * 地图视口渲染参数（全部为稳定引用）。
 *
 * 由 MainGameScreen 的 derivedStateOf 构建：任一依赖变化才重建引用，
 * 否则 SectMapViewport 跳过重组（每旬 gameData 变化不触发 AndroidView update）。
 */
internal data class SectMapViewportParams(
    val nativeConfig: NativeRenderConfig,
    val cameraState: SectCameraState,
    val flatTileData: IntArray,
    val buildingDataArray: FloatArray?,
    val buildingCount: Int,
    val tileSize: Int,
    val worldWidthCells: Int,
    val worldHeightCells: Int,
    val forceSoftwareRendering: Boolean,
    /** GPU OpenGL ES 中间层（Vulkan 不可靠但 GPU 可用设备 —— 直接走 GLES 而非 CPU 软件） */
    val glesRendering: Boolean = false,
    val vulkanInitListener: NativeSurfaceView.VulkanInitListener?,
    /** 平台 surface 提供者工厂（Hilt 注入；替换默认 provider，iOS 化替换点） */
    val surfaceProviderFactory: SurfaceProviderFactory,
    /** GPU 能力档位（RenderScalePolicy 决策输入，GameViewModel 注入） */
    val gpuTier: com.xianxia.sect.core.perf.GpuTier,
    val buildingSpriteSizes: Map<String, GridSnapHelper.BuildingSize>,
    /** 点击选中格坐标（null=无选中，渲染端经 findBuildingIndex 转换为建筑索引） */
    val selectedGrid: Pair<Int, Int>? = null,
    /** 灵田作物数据 [gx, gy, progress01] × N（null=无作物，双后端跳过作物层） */
    val spiritCropData: FloatArray? = null,
    /** 拆除模式高亮标记（与 buildingDataArray 同序；null=非拆除模式，双后端跳过整层） */
    val demolishHighlightData: ByteArray? = null,
    /** 石板道路每格位掩码（展平；null=无道路，双后端跳过道路层） */
    val roadData: IntArray? = null,
    /**
     * 浮空岛边缘布局数据 [sprite, x, y, w, h] × N（世界像素；由 IslandCliffBridge
     * 一次性预计算，地图尺寸/种子变化才重建；null=无边缘层，双后端跳过）。
     * 稳定引用——Camera 平移/缩放不触发重建（与 flatTileData 同生命周期模式）。
     */
    val groundBoundaryData: FloatArray = EMPTY_GROUND_BOUNDARY,
    /** 放置/移动模式全视口网格线开关（true=双后端画视口内网格线） */
    val gridOverlayVisible: Boolean = false,
    /**
     * 逻辑帧插值因子提供者（读 GameEngineCore.currentAlpha
     * 快照——渲染端对连续量做帧间平滑的权重；仅渲染契约，不写任何游戏状态）
     */
    val alphaProvider: () -> Float = { 0f }
)

/**
 * 建筑放置/移动预览状态。
 *
 * 由 MainGameScreen 的 derivedStateOf 聚合全部 mutableState——
 * 任一预览状态变化时重建引用（触发 SectMapViewport 重组），无变化时复用。
 */
internal data class MapPreviewState(
    val isPlacingBuilding: Boolean,
    val placingBuildingName: String,
    val placingWorldX: Float,
    val placingWorldY: Float,
    val placingBuildingSize: GridSnapHelper.BuildingSize,
    val placementValidity: GridSnapHelper.PlacementValidity,
    val movingBuilding: GridBuildingData?,
    val movingWorldX: Float,
    val movingWorldY: Float,
    val movingBuildingSize: GridSnapHelper.BuildingSize,
    val movingValid: GridSnapHelper.PlacementValidity
)
