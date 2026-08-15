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
import com.xianxia.sect.ui.game.sect.RenderCommandBus
import com.xianxia.sect.ui.game.sect.SurfaceProviderFactory

/** 建筑名称 → 精灵图集索引（P-7 从 MainGameScreen 移入——SectMapViewport 与 buildBuildingDataArray 共用） */
internal val BUILDING_NAME_INDEX: Map<String, Int> = SpriteAtlasDef.BUILDING_NAME_INDEX

/** 建筑精灵 UV 映射表（与 C++ TextureAtlas.h 一致） */
internal val BUILDING_UV_MAP: FloatArray = SpriteAtlasDef.BUILDING_UV_MAP

/**
 * 宗门地图 Vulkan/Canvas 双路径渲染视口（P-7 从 MainGameScreen 抽离）。
 *
 * 参数全部为稳定引用（remember/derivedStateOf 产物）——MainGameScreen 每旬
 * gameData 变化重组时，本组件参数引用未变则跳过重组（AndroidView update 不执行，
 * setCamera/updateViewport 不再每旬调用）。相机/预览/建筑数据实际变化时才重组。
 *
 * update 块内"门控外读取"保持原语义（Compose 订阅活跃依赖），逐行搬移自
 * MainGameScreen（2026-08-02），行为逐字节一致。
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
    // ★ 优化：RenderFrame 推送帧率门控
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

/** 地图 SurfaceView 创建（SectMapViewport 拆分）：AndroidView factory 逻辑 */
private fun createSectMapSurfaceView(
    ctx: Context,
    params: SectMapViewportParams,
    onViewCreated: (NativeSurfaceView) -> Unit
): NativeSurfaceView = NativeSurfaceView(ctx, params.nativeConfig).also { view ->
    onViewCreated(view)

    // 平台 surface 提供者替换（Hilt 工厂创建；setter 自动解绑默认实例监听器，
    // 旧实例仍挂在 holder 上但不再派发事件——平台回调翻译归 provider 管理）
    view.surfaceProvider = params.surfaceProviderFactory.create(view.holder)

    // 强制软件渲染（模拟器/Vulkan 不可用设备）
    if (params.forceSoftwareRendering) {
        view.useRenderMode = NativeSurfaceView.RenderMode.SOFTWARE
    }

    // GPU 能力档位（2026-08-14 平板省电：渲染缩放决策输入——
    // 在 surface 初始化事件前设置，recomputeRenderScale 消费）
    view.gpuTier = params.gpuTier

    // 渲染器就绪后上传纹理（地面/装饰/建筑全部在单张图集中）
    view.onRendererReady = {
        view.atlasTextureId = view.buildAtlas(ctx)
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
            currentAlpha = params.alphaProvider()
        )
    )
}

/** 视口同步（SectMapViewport 拆分）：相机/命令总线/预览/建筑数据 → 帧率门控 RenderFrame 推送 */
private fun updateSectMapView(
    view: NativeSurfaceView,
    params: SectMapViewportParams,
    preview: MapPreviewState,
    commandBus: RenderCommandBus,
    lastRenderDataSyncNs: MutableLongState
) {
    // ★ 优化：RenderFrame 推送帧率门控（SOFTWARE 路径限制推送频率，Vulkan 路径不高于 60fps）
    val now = System.nanoTime()
    val minIntervalNs = if (params.forceSoftwareRendering) 33_000_000L else 16_000_000L

    // 始终同步视口到 touchEngine（手势引擎与帧率无关）
    view.touchEngine?.updateViewport(view.width.toFloat(), view.height.toFloat())

    // ★ 在门控外读取相机状态，维持 Compose 订阅活跃（门控内读取会丢失依赖跟踪，地图停滞）
    val snapCamX = params.cameraState.cameraX
    val snapCamY = params.cameraState.cameraY
    val snapScale = params.cameraState.scale

    // ★ 独立推送相机（不经过帧率门控），确保拖拽时相机响应无延迟
    view.setCamera(snapCamX, snapCamY, snapScale)

    // ★ 注入渲染命令总线（直达推送通道，在帧率门控外注入引用）
    view.commandBus = commandBus

    // ★ 在门控外读取预览状态，维持 Compose 订阅活跃（与 camera 同理）
    val previewSnapshot = computeMapPreview(preview = preview, params = params)

    // ★ 建筑数据也必须在门控外读取（取消后建筑消失回归防护）
    val buildingData = params.buildingDataArray
    val effectiveCount = params.buildingCount

    // ★ 选中建筑索引（WP3 高亮）：点击格坐标经 findBuildingIndex 转换（占地 footprint 同源）
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

/** 预览渲染数据计算（SectMapViewport 拆分）：预览状态 → 精灵 UV 与位置（须在帧率门控外调用以维持 Compose 订阅） */
// 拆分搬移:分支结构与原函数一致
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
    val previewNameIdx = BUILDING_NAME_INDEX[previewBuildingName] ?: -1
    val hasPreview = isPreviewActive && previewNameIdx >= 0

    val previewUvs = if (hasPreview) {
        floatArrayOf(
            BUILDING_UV_MAP[previewNameIdx * 4],
            BUILDING_UV_MAP[previewNameIdx * 4 + 1],
            BUILDING_UV_MAP[previewNameIdx * 4 + 2],
            BUILDING_UV_MAP[previewNameIdx * 4 + 3]
        )
    } else null

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
        previewHeight = (previewSH * params.tileSize).toFloat()
    )
}

/** RenderFrame 构建（SectMapViewport 拆分）：单通道推送，Vulkan/Canvas 双后端共用 */
// 拆分聚合:平铺参数搬移自原公共函数
@Suppress("LongParameterList")
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
    previewAlpha = 0.5f,
    // ★ 灵田作物数据（WP6）：低频变化走帧率门控 RenderFrame（不新增命令总线）
    spiritCropData = params.spiritCropData,
    // ★ 拆除模式高亮 + 网格线：低频变化（模式进出/选中切换）走帧率
    // 门控 RenderFrame——与精灵同帧同相机快照，消除 Compose 覆盖层相位差
    demolishHighlightData = params.demolishHighlightData,
    gridOverlayVisible = params.gridOverlayVisible,
    // 逻辑帧插值因子（批次 3 插值消费链——作物进度帧间平滑权重）
    currentAlpha = currentAlpha
)

/** 建筑放置/移动预览渲染快照（SectMapViewport 拆分）：由预览状态派生，供帧构建使用 */
private data class MapPreviewSnapshot(
    val hasPreview: Boolean,
    val previewUvs: FloatArray?,
    val previewX: Float,
    val previewY: Float,
    val previewWidth: Float,
    val previewHeight: Float
)

/**
 * 地图视口渲染参数（P-7 抽离——全部为稳定引用）。
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
    val vulkanInitListener: NativeSurfaceView.VulkanInitListener?,
    /** 平台 surface 提供者工厂（Hilt 注入；替换默认 provider，iOS 化替换点） */
    val surfaceProviderFactory: SurfaceProviderFactory,
    /** GPU 能力档位（2026-08-14 平板省电：RenderScalePolicy 决策输入，GameViewModel 注入） */
    val gpuTier: com.xianxia.sect.core.perf.GpuTier,
    val buildingSpriteSizes: Map<String, GridSnapHelper.BuildingSize>,
    /** 点击选中格坐标（null=无选中，渲染端经 findBuildingIndex 转换为建筑索引） */
    val selectedGrid: Pair<Int, Int>? = null,
    /** 灵田作物数据 [gx, gy, progress01] × N（WP6；null=无作物，双后端跳过作物层） */
    val spiritCropData: FloatArray? = null,
    /** 拆除模式高亮标记（与 buildingDataArray 同序；null=非拆除模式，双后端跳过整层） */
    val demolishHighlightData: ByteArray? = null,
    /** 放置/移动模式全视口网格线开关（true=双后端画视口内网格线） */
    val gridOverlayVisible: Boolean = false,
    /**
     * 逻辑帧插值因子提供者（2026-08-13 批次 3：读 GameEngineCore.currentAlpha
     * 快照——渲染端对连续量做帧间平滑的权重；仅渲染契约，不写任何游戏状态）
     */
    val alphaProvider: () -> Float = { 0f }
)

/**
 * 建筑放置/移动预览状态（P-7 抽离）。
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
