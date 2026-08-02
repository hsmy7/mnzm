package com.xianxia.sect.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.game.map.sect.SectCameraState
import com.xianxia.sect.ui.game.sect.NativeRenderConfig
import com.xianxia.sect.ui.game.sect.NativeSurfaceView
import com.xianxia.sect.ui.game.sect.RenderCommandBus
import com.xianxia.sect.ui.game.sect.RenderFrame
import com.xianxia.sect.core.render.SpriteAtlasDef

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
    var lastRenderDataSyncNs by remember { mutableLongStateOf(0L) }

    // 缓存 buildingData 哈希值，避免每帧重复分配 FloatArray
    AndroidView(
        factory = { ctx ->
            NativeSurfaceView(ctx, params.nativeConfig).also { view ->
                onViewCreated(view)

                // 强制软件渲染（模拟器/Vulkan 不可用设备）
                if (params.forceSoftwareRendering) {
                    view.useRenderMode = NativeSurfaceView.RenderMode.SOFTWARE
                }

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
                        scale = params.cameraState.scale
                    )
                )
            }
        },
        update = { view ->
            // ★ 优化：RenderFrame 推送帧率门控
            // SOFTWARE 路径限制推送频率（RenderThread 自行读取 currentFrame 原子快照）
            // Vulkan 路径也限制不高于 60fps
            val now = System.nanoTime()
            val minIntervalNs = if (params.forceSoftwareRendering) 33_000_000L else 16_000_000L

            // 始终同步视口到 touchEngine（手势引擎与帧率无关）
            view.touchEngine?.updateViewport(view.width.toFloat(), view.height.toFloat())

            // ★ 在门控外读取相机状态，维持 Compose 订阅活跃。
            // 门控内读取时，若门控未通过则 Compose 移除依赖跟踪，
            // 导致后续 cameraState.pan() 不再触发重组，地图停滞。
            val snapCamX = params.cameraState.cameraX
            val snapCamY = params.cameraState.cameraY
            val snapScale = params.cameraState.scale

            // ★ 独立推送相机（不经过帧率门控），确保拖拽时相机响应无延迟
            view.setCamera(snapCamX, snapCamY, snapScale)

            // ★ 注入渲染命令总线（直达推送通道，在帧率门控外注入引用）
            // 使 RenderThread 可通过 commandBus.buildingData 读取最新建筑数据
            view.commandBus = commandBus

            // ★ 在门控外读取预览相关状态，确保 Compose 订阅活跃。
            // 门控内读取时，若门控未通过则 Compose 移除依赖跟踪，
            // 导致拖拽中精灵图不跟随移动（与 camera 同理）。
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

            // ★ 建筑数据也必须在门控外读取（effectivePlacedBuildings 变化时
            // 若门控关闭则 buildingDataArray 不推送，导致取消后建筑消失）
            val buildingData = params.buildingDataArray
            val effectiveCount = params.buildingCount

            // 帧率门控：低于间隔直接跳过 RenderFrame 推送（不影响 RenderCommandBus 直达通道）
            // buildingData 已通过命令总线独立推送，此处仅用于 tileData + preview
            if (now - lastRenderDataSyncNs >= minIntervalNs) {
                lastRenderDataSyncNs = now

                // Camera + 预览 + 建筑数据通过 RenderFrame 推送
                // 单通道：Vulkan 和 Canvas 两后端均消费同一份 RenderFrame
                view.updateRenderState(
                    RenderFrame(
                        tileData = params.flatTileData,
                        cols = params.worldWidthCells,
                        rows = params.worldHeightCells,
                        camX = snapCamX,
                        camY = snapCamY,
                        scale = snapScale,
                        buildingVisible = true,
                        buildingData = buildingData,
                        buildingCount = effectiveCount,
                        showPreview = hasPreview,
                        previewX = px + previewOffsetX,
                        previewY = py + previewOffsetY,
                        previewW = (previewSW * params.tileSize).toFloat(),
                        previewH = (previewSH * params.tileSize).toFloat(),
                        previewU0 = previewUvs?.get(0) ?: 0f,
                        previewV0 = previewUvs?.get(1) ?: 0f,
                        previewU1 = previewUvs?.get(2) ?: 0f,
                        previewV1 = previewUvs?.get(3) ?: 0f,
                        previewTintRed = 1.0f,
                        previewTintGreen = 1.0f,
                        previewTintBlue = 1.0f,
                        previewAlpha = 0.5f
                    )
                )   // view.updateRenderState()
            }   // if (now - lastRenderDataSyncNs >= minIntervalNs)
        },  // update = { view ->
        modifier = modifier
    )
}

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
    val buildingSpriteSizes: Map<String, GridSnapHelper.BuildingSize>
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
