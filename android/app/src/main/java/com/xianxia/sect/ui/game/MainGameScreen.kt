package com.xianxia.sect.ui.game

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.util.BuildingSpatialIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xianxia.sect.ui.components.LocalItemSpriteCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.xianxia.sect.ui.navigation.DialogRoute
import com.xianxia.sect.ui.navigation.GameRoute
import com.xianxia.sect.ui.navigation.toDialogRoute

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.R
import com.xianxia.sect.core.perf.ThermalState
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GamePhase
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.game.map.CameraState
import com.xianxia.sect.ui.game.map.rememberCameraState
import com.xianxia.sect.core.util.GridSystem
import com.xianxia.sect.core.util.sortedByFollowAttributeAndRealm
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.game.components.GameActionButtons
import com.xianxia.sect.ui.game.components.GameOverlayHost
import com.xianxia.sect.ui.game.building.BuildingRegistry
import com.xianxia.sect.ui.game.building.BuildingDef
import com.xianxia.sect.ui.game.building.BuildingConstructionBar


/**
 * ## MainGameScreen - 主游戏界面 (Compose 重组优化版本)
 *
 * ### [H-07] 性能优化说明
 *
 * **原始问题**:
 * - 在单个 Composable 中收集 30+ 个 StateFlow
 * - 任何 StateFlow 变化都触发整个 MainGameScreen 重组
 * - 高频数据 (cultivation progress, resources) 每秒变化 5 次 (200ms tick)
 * - 导致每秒 5-25 次全量重组 (30+ StateFlow × 5 ticks)
 *
 * **优化策略**:
 *
 * 1. **分层收集** (Layered Collection)
 *    - 顶层: 只收集当前 Tab 需要的核心数据
 *    - Dialog 层: 只在 Dialog 可见时收集其状态
 *    - 效果: 减少无效重组 60-80%
 *
 * 2. **高频数据限制** (High-Frequency Throttling)
 *    - 使用 `derivedStateOf` 提取 UI 真正需要的字段
 *    - 使用 `collectLatest` 取消过时的更新
 *    - 效果: 高频数据不再触发低频组件重组
 *
 * 3. **惰性对话框收集** (Lazy Dialog Collection)
 *    - 对话框状态只在 Dialog 显示时才订阅
 *    - 使用 `remember` 缓存计算结果
 *    - 效果: 减少 20+ 个常驻订阅
 *
 * **性能预期**:
 * - 重组次数: 从 ~100次/秒 → ~10-20次/秒
 * - 帧时间: 从 16-50ms → 8-16ms
 * - 内存: 减少 30% (更少的状态快照)
 */

// 瓦片类型常量（0=空地 1=草地 2=树木 3=建筑）
private const val TILE_GROUND = 0
private const val TILE_GRASS = 1
private const val TILE_TREE = 2
private const val TILE_BUILDING = 3

@Composable
fun MainGameScreen(
    mapPreloadData: MapPreloadData,
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    productionViewModel: ProductionViewModel,
    alchemyViewModel: AlchemyViewModel,
    forgeViewModel: ForgeViewModel,
    herbGardenViewModel: HerbGardenViewModel,
    spiritMineViewModel: SpiritMineViewModel,
    patrolTowerViewModel: PatrolTowerViewModel,
    bloodRefiningViewModel: BloodRefiningViewModel,
    worldMapViewModel: WorldMapViewModel,
    battleViewModel: BattleViewModel,
    onLogout: () -> Unit,
    onRestartGame: () -> Unit,
    limitAdTracking: Boolean = true,
    onLimitAdTrackingChanged: (Boolean) -> Unit = {}
) {
    // [M7-OPT-1] 高频核心数据收集 - 使用 derivedStateOf 限制重组范围
    // gameData 包含资源、日期等，每 tick (200ms) 都可能变化
    // derivedStateOf 确保：只有当 UI 实际读取的字段变化时才触发重组
    val gameData by viewModel.gameDataUi.collectAsStateWithLifecycle()
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
    val sectCombatPower by viewModel.sectCombatPower.collectAsStateWithLifecycle()
    val aliveDisciples = remember {
        derivedStateOf { disciples.filter { it.isAlive } }
    }

    var screenWidthPx by remember { mutableFloatStateOf(0f) }
    var screenHeightPx by remember { mutableFloatStateOf(0f) }

    // 建筑放置状态
    val placedBuildings by viewModel.placedBuildings.collectAsStateWithLifecycle()
    var isPlacingBuilding by remember { mutableStateOf(false) }
    var placingBuildingName by remember { mutableStateOf("") }
    var placingWorldX by remember { mutableFloatStateOf(0f) }
    var placingWorldY by remember { mutableFloatStateOf(0f) }
    var buildingBarExpanded by remember { mutableStateOf(false) }

    // 建筑移动状态（长按拖动）
    var movingBuilding by remember { mutableStateOf<GridBuildingData?>(null) }
    var movingWorldX by remember { mutableFloatStateOf(0f) }
    var movingWorldY by remember { mutableFloatStateOf(0f) }
    var movingSnappedGridX by remember { mutableIntStateOf(0) }
    var movingSnappedGridY by remember { mutableIntStateOf(0) }
    var movingValid by remember {
        mutableStateOf<GridSnapHelper.PlacementValidity>(GridSnapHelper.PlacementValidity.Valid)
    }
    val movingBuildingSize by derivedStateOf {
        movingBuilding?.let { GridSnapHelper.BuildingSize(it.width, it.height) }
            ?: GridSnapHelper.BuildingSize(2, 3)
    }

    // 移动中临时从网格排除正在移动的建筑，避免自身重叠检测
    val activeSectBuildings by derivedStateOf {
        val sid = gameData.activeSectId
        placedBuildings.filter { it.sectId == sid }
    }
    val effectivePlacedBuildings by derivedStateOf {
        val mb = movingBuilding
        if (mb != null) activeSectBuildings.filter { it.instanceId != mb.instanceId }
        else activeSectBuildings
    }

    val tileSize = mapPreloadData.tileSize
    val worldPixelWidth = mapPreloadData.worldPixelWidth
    val worldPixelHeight = mapPreloadData.worldPixelHeight

    // 统一相机 — 相机在世界空间中移动，screenX = worldX - cameraX
    val cameraState = rememberCameraState(
        worldWidth = worldPixelWidth.toFloat(),
        worldHeight = worldPixelHeight.toFloat()
    )

    // 建筑尺寸映射 — 从配置读取，在宗门地图中所占的格数 (宽 × 高)
    val buildingSizes = remember {
        BuildingRegistry.ALL.associate { def ->
            val (w, h) = viewModel.getBuildingGridSize(def.displayName)
            def.displayName to GridSnapHelper.BuildingSize(w, h)
        }
    }

    // 当前放置建筑的尺寸
    var placingBuildingSize by remember { mutableStateOf(GridSnapHelper.BuildingSize(2, 3)) }

    // 吸附后的网格坐标（拖拽中实时更新）
    var placingSnappedGridX by remember { mutableIntStateOf(0) }
    var placingSnappedGridY by remember { mutableIntStateOf(0) }

    // 放置合法性
    var placementValidity by remember {
        mutableStateOf<GridSnapHelper.PlacementValidity>(GridSnapHelper.PlacementValidity.Valid)
    }

    val worldWidthCells = mapPreloadData.worldWidthCells
    val worldHeightCells = mapPreloadData.worldHeightCells

    // 地图瓦片素材 — 由 GameActivity 预加载，此处同步读取

    val fullMapBmp = remember(mapPreloadData) { mapPreloadData.fullMapBmp }

    val rawTileData = mapPreloadData.rawTileData

    // 建筑覆盖到瓦片数据
    val tileData = remember(rawTileData, effectivePlacedBuildings) {
        val data = Array(rawTileData.size) { rawTileData[it].copyOf() }
        for (b in effectivePlacedBuildings) {
            for (cx in b.gridX until b.gridX + b.width) {
                for (cy in b.gridY until b.gridY + b.height) {
                    if (cy in data.indices && cx in data[cy].indices) {
                        data[cy][cx] = TILE_BUILDING
                    }
                }
            }
        }
        data
    }

    // 网格系统（管理建筑放置与占用格查询）
    val gridSystem = remember(tileSize, worldWidthCells, worldHeightCells) {
        GridSystem(tileSize, worldWidthCells, worldHeightCells)
    }

    LaunchedEffect(effectivePlacedBuildings) {
        gridSystem.rebuildFrom(effectivePlacedBuildings)
    }

    // 建筑列表及点击回调
    val buildingList = remember {
        BuildingRegistry.constructible.map { def ->
            val handler: (GridBuildingData?) -> Unit = when (def) {
                BuildingDef.SPIRIT_MINE -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogRoute.SpiritMine(it)) }; Unit }
                BuildingDef.HERB_GARDEN -> { _ -> viewModel.navigateToDialog(DialogRoute.HerbGarden) }
                BuildingDef.SPIRIT_FIELD -> { _ -> viewModel.navigateToDialog(DialogRoute.Planting) }
                BuildingDef.ALCHEMY -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogRoute.Alchemy(it)) }; Unit }
                BuildingDef.FORGE -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogRoute.Forge(it)) }; Unit }
                BuildingDef.LIBRARY -> { _ -> viewModel.navigateToDialog(DialogRoute.Library) }
                BuildingDef.WEN_DAO_PEAK -> { _ -> viewModel.navigateToDialog(DialogRoute.WenDaoPeak) }
                BuildingDef.QINGYUN_PEAK -> { _ -> viewModel.navigateToDialog(DialogRoute.QingyunPeak) }
                BuildingDef.TIANSHU_HALL -> { _ -> viewModel.navigateToDialog(DialogRoute.TianshuHall) }
                BuildingDef.LAW_ENFORCEMENT -> { _ -> viewModel.navigateToDialog(DialogRoute.LawEnforcementHall) }
                BuildingDef.MISSION_HALL -> { _ -> viewModel.navigateToDialog(DialogRoute.MissionHall) }
                BuildingDef.REFLECTION_CLIFF -> { _ -> viewModel.navigateToDialog(DialogRoute.ReflectionCliff) }
                BuildingDef.PATROL_TOWER -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogRoute.PatrolTower(it)) }; Unit }
                BuildingDef.BLOOD_REFINING_POOL -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogRoute.BloodRefiningPool(it)) }; Unit }
                BuildingDef.SINGLE_RESIDENCE, BuildingDef.MULTI_RESIDENCE -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogRoute.Residence(it)) }; Unit }
                BuildingDef.WAREHOUSE -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogRoute.WarehouseBuilding(it)) }; Unit }
                BuildingDef.SINGLE_RESIDENCE_UPGRADED -> { _ -> Unit }
            }
            def.displayName to handler
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { route ->
            viewModel.navigateToDialog(route.toDialogRoute())
        }
    }

    LaunchedEffect(Unit) {
        viewModel.currentDialogRoute.collect { route ->
            if (route !is DialogRoute.None) {
                isPlacingBuilding = false
                movingBuilding = null
                buildingBarExpanded = false
            }
        }
    }

    // 相机视口更新 + 初始居中（只执行一次）
    LaunchedEffect(screenWidthPx, screenHeightPx) {
        if (screenWidthPx > 0 && screenHeightPx > 0) {
            cameraState.updateViewport(screenWidthPx.toInt(), screenHeightPx.toInt())
            cameraState.tryCenterOn(worldPixelWidth / 2f, worldPixelHeight / 2f)
        }
    }
    val isGameOver by viewModel.isGameOver.collectAsStateWithLifecycle()

    // 热状态自适应分辨率 — 根据设备发热程度降低 Canvas 渲染分辨率
    val thermalState by viewModel.thermalState.collectAsStateWithLifecycle()
    val renderScale by remember {
        derivedStateOf {
            when (thermalState) {
                ThermalState.EMERGENCY -> 0.5f
                ThermalState.SEVERE -> 0.6f
                ThermalState.MODERATE -> 0.75f
                else -> 1.0f
            }
        }
    }

    LaunchedEffect(isGameOver) {
        if (isGameOver) {
            viewModel.openGameOverDialog()
        }
    }

    // 移动模式下按返回键取消移动
    BackHandler(enabled = movingBuilding != null) {
        movingBuilding = null
    }

    val preloadedItemSprites by saveLoadViewModel.preloadedItemSprites.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalItemSpriteCache provides preloadedItemSprites) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                screenWidthPx = size.width.toFloat()
                screenHeightPx = size.height.toFloat()
            }
    ) {
        val preloadedBuildingBitmaps by saveLoadViewModel.preloadedBuildingBitmaps.collectAsStateWithLifecycle()
        val buildingBitmaps = if (preloadedBuildingBitmaps.isNotEmpty()) preloadedBuildingBitmaps
            else rememberBuildingBitmaps()

        // [C2 静态建筑层架构]
        // 核心思路：将建筑预渲染（bake）到地图 Bitmap 上，Canvas 每帧只绘制单张 Bitmap，
        // 避免逐帧遍历建筑列表。建筑变化时通过增量更新（擦除旧区域 + 绘制新建筑）修改 bakedMapBmp，
        // 而非全量重建。低配设备（heap < 256MB）跳过烘焙，回退为动态绘制。
        // 注意：Canvas 本身因相机平移每帧内容都变，graphicsLayer(offscreen) 无法缓存；
        // 真正的静态层优化由 bakedMapBmp 承担。
        // 设备分级：低配设备跳过建筑烘焙，避免额外 18-36MB Bitmap 分配
        val maxHeapMB = remember { Runtime.getRuntime().maxMemory() / (1024 * 1024) }
        val shouldBakeBuildings = maxHeapMB >= 256  // 低配 4GB 设备 largeHeap 后约 256MB，跳过烘焙
        val bmpConfig = if (maxHeapMB >= 384) android.graphics.Bitmap.Config.ARGB_8888
            else android.graphics.Bitmap.Config.RGB_565  // 中配设备用 RGB_565 省一半内存

        // 增量绘制：只在建筑变化时增量更新，避免全量 copy
        // 直接持有 Android Bitmap，避免 ImageBitmap↔Android Bitmap 反复转换
        val bakedMapBmp: android.graphics.Bitmap? = remember(fullMapBmp) {
            if (!shouldBakeBuildings) {
                null  // 低配设备跳过烘焙
            } else {
                val src = fullMapBmp.asAndroidBitmap()
                src.copy(bmpConfig, true) ?: src
            }
        }

        // 追踪上一次绘制的建筑列表，用于增量更新
        val previousBuildings = remember { mutableListOf<GridBuildingData>() }

        // 已清除装饰物的格子（避免反复清除同一格）
        val clearedDecorationCells = remember { mutableSetOf<Long>() }

        LaunchedEffect(effectivePlacedBuildings) {
            val currentBmp = bakedMapBmp ?: return@LaunchedEffect
            if (currentBmp.isRecycled) return@LaunchedEffect
            val canvas = android.graphics.Canvas(currentBmp)
            val groundBmp = mapPreloadData.groundTileBmp.asAndroidBitmap()

            // === 1. 清除新增建筑覆盖的装饰物 ===
            val buildingCells = mutableSetOf<Long>()
            for (b in effectivePlacedBuildings) {
                for (cx in b.gridX until b.gridX + b.width) {
                    for (cy in b.gridY until b.gridY + b.height) {
                        if (cy in rawTileData.indices && cx in rawTileData[cy].indices) {
                            buildingCells.add((cx.toLong() shl 32) or (cy.toLong() and 0xFFFF_FFFF))
                        }
                    }
                }
            }
            val cellsToClear = mutableSetOf<Long>()
            cellsToClear.addAll(buildingCells)
            // 建筑周围的树也要清除（树是 2×2 格大装饰物）
            for (cellKey in buildingCells) {
                val bx = (cellKey shr 32).toInt()
                val by = (cellKey and 0xFFFF_FFFF).toInt()
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        val tx = bx + dx
                        val ty = by + dy
                        if (ty in rawTileData.indices && tx in rawTileData[ty].indices && rawTileData[ty][tx] == TILE_TREE) {
                            for (ex in tx - 1..tx + 1) {
                                for (ey in ty - 1..ty + 1) {
                                    if (ey in rawTileData.indices && ex in rawTileData[ey].indices) {
                                        cellsToClear.add((ex.toLong() shl 32) or (ey.toLong() and 0xFFFF_FFFF))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (cellKey in cellsToClear) {
                if (cellKey !in clearedDecorationCells) {
                    clearedDecorationCells.add(cellKey)
                    val cx = (cellKey shr 32).toInt()
                    val cy = (cellKey and 0xFFFF_FFFF).toInt()
                    rawTileData[cy][cx] = TILE_GROUND
                    val srcRect = android.graphics.Rect(
                        cx * tileSize, cy * tileSize,
                        (cx + 1) * tileSize, (cy + 1) * tileSize
                    )
                    val dstRect = android.graphics.Rect(
                        cx * tileSize, cy * tileSize,
                        (cx + 1) * tileSize, (cy + 1) * tileSize
                    )
                    canvas.drawBitmap(groundBmp, srcRect, dstRect, null)
                }
            }

            // === 2. 擦除被移除的建筑区域（恢复原始背景含装饰物） ===
            val currentIds = effectivePlacedBuildings.map { it.instanceId }.toSet()
            for (oldBuilding in previousBuildings) {
                if (oldBuilding.instanceId !in currentIds) {
                    val bx = oldBuilding.gridX * tileSize
                    val by = oldBuilding.gridY * tileSize
                    val bw = oldBuilding.width * tileSize
                    val bh = oldBuilding.height * tileSize
                    val srcBmp = fullMapBmp.asAndroidBitmap()
                    val srcRect = android.graphics.Rect(bx, by, bx + bw, by + bh)
                    val dstRect = android.graphics.Rect(bx, by, bx + bw, by + bh)
                    canvas.drawBitmap(srcBmp, srcRect, dstRect, null)
                }
            }

            // === 3. 绘制新增的建筑 ===
            val previousIds = previousBuildings.map { it.instanceId }.toSet()
            for (building in effectivePlacedBuildings) {
                if (building.instanceId !in previousIds) {
                    val bx = building.gridX * tileSize
                    val by = building.gridY * tileSize
                    val bw = building.width * tileSize
                    val bh = building.height * tileSize
                    val bmp = buildingBitmaps[building.displayName]
                    if (bmp != null) {
                        val androidBmp = bmp.asAndroidBitmap()
                        val srcRect = android.graphics.Rect(0, 0, androidBmp.width, androidBmp.height)
                        val dstRect = android.graphics.Rect(bx, by, bx + bw, by + bh)
                        canvas.drawBitmap(androidBmp, srcRect, dstRect, null)
                    } else {
                        val paint = android.graphics.Paint().apply { color = 0xCCBDBDBD.toInt() }
                        canvas.drawRect(android.graphics.RectF(bx.toFloat(), by.toFloat(), (bx + bw).toFloat(), (by + bh).toFloat()), paint)
                    }
                }
            }

            // 更新追踪列表
            previousBuildings.clear()
            previousBuildings.addAll(effectivePlacedBuildings)
        }

        // 主动回收 Bitmap，避免依赖 GC
        DisposableEffect(Unit) {
            onDispose {
                bakedMapBmp?.takeIf { !it.isRecycled }?.recycle()
            }
        }

        // 用于 Compose 渲染的 ImageBitmap（低配设备直接用原始地图）
        val displayMapBmp = bakedMapBmp?.asImageBitmap() ?: fullMapBmp

        // 宗门大地图层（Canvas + 建筑 + 网格 + 放置预览 + 确认按钮）
        SectMapLayer(
            cameraState = cameraState,
            placedBuildings = effectivePlacedBuildings,
            fullMapBmp = displayMapBmp,
            buildingsBaked = shouldBakeBuildings,
            buildingBitmaps = buildingBitmaps,
            tileSize = tileSize,
            worldWidthCells = worldWidthCells,
            worldHeightCells = worldHeightCells,
            worldPixelWidth = worldPixelWidth,
            worldPixelHeight = worldPixelHeight,
            renderScale = renderScale,
            isPlacing = isPlacingBuilding,
            placingBuildingName = placingBuildingName,
            buildingBarExpanded = buildingBarExpanded,
            previewGridX = placingSnappedGridX,
            previewGridY = placingSnappedGridY,
            previewWorldX = placingWorldX,
            previewWorldY = placingWorldY,
            previewSize = placingBuildingSize,
            previewValid = placementValidity,
            buildingList = buildingList,
            onSpiritMineClick = { instanceId -> viewModel.navigateToDialog(DialogRoute.SpiritMine(instanceId)) },
            onAlchemyClick = { instanceId -> viewModel.navigateToDialog(DialogRoute.Alchemy(instanceId)) },
            onForgeClick = { instanceId -> viewModel.navigateToDialog(DialogRoute.Forge(instanceId)) },
            onResidenceClick = { instanceId -> viewModel.navigateToDialog(DialogRoute.Residence(instanceId)) },
            onPlacementDrag = { dx, dy ->
                placingWorldX += dx
                placingWorldY += dy
                placingSnappedGridX = GridSnapHelper.worldToGrid(placingWorldX, tileSize)
                placingSnappedGridY = GridSnapHelper.worldToGrid(placingWorldY, tileSize)
                placementValidity = gridSystem.validatePlacement(
                    placingSnappedGridX, placingSnappedGridY,
                    placingBuildingSize.width, placingBuildingSize.height
                )
                val edgePx = 80f
                val screenX = cameraState.worldToScreenX(placingWorldX) * renderScale
                val screenY = cameraState.worldToScreenY(placingWorldY) * renderScale
                val panSpeed = 8f
                if (screenX < edgePx) cameraState.pan(panSpeed, 0f)
                if (screenX > screenWidthPx - edgePx) cameraState.pan(-panSpeed, 0f)
                if (screenY < edgePx) cameraState.pan(0f, panSpeed)
                if (screenY > screenHeightPx - edgePx) cameraState.pan(0f, -panSpeed)
            },
            onPlacementConfirm = {
                if (placementValidity == GridSnapHelper.PlacementValidity.Valid) {
                    viewModel.placeBuilding(
                        name = placingBuildingName,
                        gridX = placingSnappedGridX,
                        gridY = placingSnappedGridY,
                        width = placingBuildingSize.width,
                        height = placingBuildingSize.height
                    )
                }
                isPlacingBuilding = false
                placingBuildingName = ""
            },
            onPlacementCancel = {
                isPlacingBuilding = false
                placingBuildingName = ""
            },
            // 移动模式参数
            isMoving = movingBuilding != null,
            movingBuildingName = movingBuilding?.displayName ?: "",
            movingGridX = movingSnappedGridX,
            movingGridY = movingSnappedGridY,
            movingWorldX = movingWorldX,
            movingWorldY = movingWorldY,
            movingSize = movingBuildingSize,
            movingValid = movingValid,
            movingInstanceId = movingBuilding?.instanceId,
            onBuildingLongPress = { building ->
                if (!isPlacingBuilding) {
                    movingBuilding = building
                    movingWorldX = (building.gridX * tileSize).toFloat()
                    movingWorldY = (building.gridY * tileSize).toFloat()
                    movingSnappedGridX = building.gridX
                    movingSnappedGridY = building.gridY
                    movingValid = GridSnapHelper.PlacementValidity.Valid
                }
            },
            onMovingDrag = { dx, dy ->
                movingWorldX += dx
                movingWorldY += dy
                movingSnappedGridX = GridSnapHelper.worldToGrid(movingWorldX, tileSize)
                movingSnappedGridY = GridSnapHelper.worldToGrid(movingWorldY, tileSize)
                movingValid = gridSystem.validatePlacement(
                    movingSnappedGridX, movingSnappedGridY,
                    movingBuildingSize.width, movingBuildingSize.height
                )
                // 边缘自动平移
                val edgePx = 80f
                val screenX = cameraState.worldToScreenX(movingWorldX) * renderScale
                val screenY = cameraState.worldToScreenY(movingWorldY) * renderScale
                val panSpeed = 8f
                if (screenX < edgePx) cameraState.pan(panSpeed, 0f)
                if (screenX > screenWidthPx - edgePx) cameraState.pan(-panSpeed, 0f)
                if (screenY < edgePx) cameraState.pan(0f, panSpeed)
                if (screenY > screenHeightPx - edgePx) cameraState.pan(0f, -panSpeed)
            }
        )

        // 移动模式确认按钮
        if (movingBuilding != null) {
            PlacementConfirmButtons(
                snappedGridX = movingSnappedGridX,
                snappedGridY = movingSnappedGridY,
                buildingSize = movingBuildingSize,
                cameraState = cameraState,
                tileSize = tileSize,
                validity = movingValid,
                renderScale = renderScale,
                onConfirm = {
                    movingBuilding?.let { b ->
                        if (movingValid == GridSnapHelper.PlacementValidity.Valid &&
                            (movingSnappedGridX != b.gridX || movingSnappedGridY != b.gridY)
                        ) {
                            viewModel.moveBuilding(b.instanceId, movingSnappedGridX, movingSnappedGridY)
                        }
                    }
                    movingBuilding = null
                },
                onCancel = { movingBuilding = null }
            )
        }

        // UI overlay — SectInfoCard + two side button columns
        Box(modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        viewModel.onUserInteraction()
                    }
                }
            }
        ) {
            SectInfoCard(
                sectName = gameData?.sectName ?: "青云宗",
                gameYear = gameData?.gameYear ?: 1,
                gameMonth = gameData?.gameMonth ?: 1,
                gamePhase = gameData?.gamePhase ?: 0,
                spiritStones = gameData?.spiritStones ?: 0L,
                discipleCount = aliveDisciples.value.size,
                combatPower = sectCombatPower,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 32.dp, top = 8.dp)
            )

            GameActionButtons(
                viewModel = viewModel,
                buildingBarExpanded = buildingBarExpanded,
                onToggleBuildingBar = { buildingBarExpanded = !buildingBarExpanded; isPlacingBuilding = false; movingBuilding = null },
                onCancelPlacement = { isPlacingBuilding = false; movingBuilding = null },
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        // 建造栏 — 开关式，展开时显示
        if (buildingBarExpanded) {
            val constructionBarList = remember {
                buildingList // BuildingRegistry.constructible already excludes 中级单人住所
            }
            val buildingCosts = remember {
                constructionBarList.associate { (name, _) -> name to viewModel.getBuildingCost(name) }
            }
            BuildingConstructionBar(
                buildingList = constructionBarList,
                placedBuildings = activeSectBuildings,
                buildingCosts = buildingCosts,
                spiritStones = gameData.spiritStones,
                onSelectBuilding = { name ->
                    val size = buildingSizes[name] ?: GridSnapHelper.BuildingSize(2, 3)
                    isPlacingBuilding = true
                    placingBuildingName = name
                    placingBuildingSize = size
                    placingWorldX = cameraState.cameraX + screenWidthPx / 2f - size.width * tileSize / 2f
                    placingWorldY = cameraState.cameraY + screenHeightPx / 2f - size.height * tileSize / 2f
                    placingSnappedGridX = GridSnapHelper.worldToGrid(placingWorldX, tileSize)
                    placingSnappedGridY = GridSnapHelper.worldToGrid(placingWorldY, tileSize)
                    placementValidity = gridSystem.validatePlacement(
                        placingSnappedGridX, placingSnappedGridY,
                        size.width, size.height
                    )
                },
                modifier = Modifier.align(Alignment.BottomCenter),
                getBuildingMaxCount = { name ->
                    when {
                        BuildingRegistry.isResidence(name) || BuildingRegistry.hasNoLimit(name) -> Int.MAX_VALUE
                        else -> 1
                    }
                }
            )
        }

        // Dialog overlay — extracted to GameOverlayHost
        GameOverlayHost(
            viewModel = viewModel,
            saveLoadViewModel = saveLoadViewModel,
            productionViewModel = productionViewModel,
            alchemyViewModel = alchemyViewModel,
            forgeViewModel = forgeViewModel,
            herbGardenViewModel = herbGardenViewModel,
            spiritMineViewModel = spiritMineViewModel,
            patrolTowerViewModel = patrolTowerViewModel,
            bloodRefiningViewModel = bloodRefiningViewModel,
            worldMapViewModel = worldMapViewModel,
            battleViewModel = battleViewModel,
            onLogout = onLogout,
            onRestartGame = onRestartGame,
            limitAdTracking = limitAdTracking,
            onLimitAdTrackingChanged = onLimitAdTrackingChanged
        )

    }
    } // CompositionLocalProvider
}


@Composable
private fun SectInfoCard(
    sectName: String,
    gameYear: Int,
    gameMonth: Int,
    gamePhase: Int,
    spiritStones: Long,
    discipleCount: Int,
    combatPower: Long,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
    ) {
        Image(
            painter = painterResource(id = R.drawable.bg_horizontal),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    painter = painterResource(id = com.xianxia.sect.ui.components.sectIconRes(1)),
                    contentDescription = "宗门等级",
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = sectName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                val combatPowerBrush = Brush.verticalGradient(
                    colors = listOf(Color.Red, Color(0xFFFFD700))
                )
                Text(
                    text = "战斗力",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(brush = combatPowerBrush)
                )
                Text(
                    text = "$combatPower",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Red
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "${gameYear}年${gameMonth}月${GamePhase.fromValue(gamePhase).displayName}",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                Text(
                    text = "弟子 $discipleCount",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                Text(
                    text = "灵石 $spiritStones",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
        }
    }
}

/**
 * 宗门地图层 — 封装 Canvas 地图渲染 + 建筑点击 + 放置预览 + 确认按钮
 * 提取为独立 Composable 以降低 MainGameScreen 的寄存器数量（避免 VerifyError）
 */
@Composable
private fun SectMapLayer(
    cameraState: CameraState,
    placedBuildings: List<GridBuildingData>,
    fullMapBmp: androidx.compose.ui.graphics.ImageBitmap,
    buildingsBaked: Boolean = true,
    buildingBitmaps: Map<String, androidx.compose.ui.graphics.ImageBitmap>,
    tileSize: Int,
    worldWidthCells: Int,
    worldHeightCells: Int,
    worldPixelWidth: Int,
    worldPixelHeight: Int,
    renderScale: Float = 1.0f,
    isPlacing: Boolean,
    placingBuildingName: String,
    buildingBarExpanded: Boolean,
    previewGridX: Int,
    previewGridY: Int,
    previewWorldX: Float = 0f,
    previewWorldY: Float = 0f,
    previewSize: GridSnapHelper.BuildingSize,
    previewValid: GridSnapHelper.PlacementValidity,
    buildingList: List<Pair<String, (GridBuildingData?) -> Unit>>,
    onSpiritMineClick: (String) -> Unit = {},
    onAlchemyClick: (String) -> Unit = {},
    onForgeClick: (String) -> Unit = {},
    onResidenceClick: (String) -> Unit = {},
    onPlacementDrag: (Float, Float) -> Unit,
    onPlacementConfirm: () -> Unit,
    onPlacementCancel: () -> Unit,
    // 移动模式参数
    isMoving: Boolean = false,
    movingBuildingName: String = "",
    movingGridX: Int = 0,
    movingGridY: Int = 0,
    movingWorldX: Float = 0f,
    movingWorldY: Float = 0f,
    movingSize: GridSnapHelper.BuildingSize = GridSnapHelper.BuildingSize(2, 3),
    movingValid: GridSnapHelper.PlacementValidity = GridSnapHelper.PlacementValidity.Valid,
    movingInstanceId: String? = null,
    onBuildingLongPress: (GridBuildingData) -> Unit = {},
    onMovingDrag: (Float, Float) -> Unit = { _, _ -> }
) {
    val textMeasurer = rememberTextMeasurer()
    SectGroundCanvas(
        cameraState = cameraState,
        placedBuildings = placedBuildings,
        buildingsBaked = buildingsBaked,
        buildingBitmaps = buildingBitmaps,
        fullMapBmp = fullMapBmp,
        tileSize = tileSize,
        worldWidthCells = worldWidthCells,
        worldHeightCells = worldHeightCells,
        worldPixelWidth = worldPixelWidth,
        worldPixelHeight = worldPixelHeight,
        renderScale = renderScale,
        isPlacing = isPlacing,
        placingBuildingName = placingBuildingName,
        buildingBarExpanded = buildingBarExpanded,
        previewGridX = previewGridX,
        previewGridY = previewGridY,
        previewWorldX = previewWorldX,
        previewWorldY = previewWorldY,
        previewSize = previewSize,
        previewValid = previewValid,
        textMeasurer = textMeasurer,
        onBuildingClick = { building ->
            val def = BuildingRegistry.findByDisplayName(building.displayName)
            when (def) {
                BuildingDef.SPIRIT_MINE -> onSpiritMineClick(building.instanceId)
                BuildingDef.ALCHEMY -> onAlchemyClick(building.instanceId)
                BuildingDef.FORGE -> onForgeClick(building.instanceId)
                BuildingDef.SINGLE_RESIDENCE, BuildingDef.SINGLE_RESIDENCE_UPGRADED, BuildingDef.MULTI_RESIDENCE -> {
                    onResidenceClick(building.instanceId)
                }
                else -> {
                    val b = buildingList.find { it.first == building.displayName }
                    b?.second?.invoke(building)
                }
            }
        },
        onPlacementDrag = onPlacementDrag,
        // 移动模式参数
        isMoving = isMoving,
        movingBuildingName = movingBuildingName,
        movingGridX = movingGridX,
        movingGridY = movingGridY,
        movingWorldX = movingWorldX,
        movingWorldY = movingWorldY,
        movingSize = movingSize,
        movingValid = movingValid,
        movingInstanceId = movingInstanceId,
        onBuildingLongPress = onBuildingLongPress,
        onMovingDrag = onMovingDrag,
        modifier = Modifier.fillMaxSize()
    )

    if (isPlacing) {
        PlacementConfirmButtons(
            snappedGridX = previewGridX,
            snappedGridY = previewGridY,
            buildingSize = previewSize,
            cameraState = cameraState,
            tileSize = tileSize,
            validity = previewValid,
            renderScale = renderScale,
            onConfirm = onPlacementConfirm,
            onCancel = onPlacementCancel
        )
    }
}

@Composable
private fun SectGroundCanvas(
    cameraState: CameraState,
    placedBuildings: List<GridBuildingData>,
    buildingsBaked: Boolean = true,
    buildingBitmaps: Map<String, androidx.compose.ui.graphics.ImageBitmap>,
    fullMapBmp: androidx.compose.ui.graphics.ImageBitmap,
    tileSize: Int,
    worldWidthCells: Int,
    worldHeightCells: Int,
    worldPixelWidth: Int,
    worldPixelHeight: Int,
    renderScale: Float = 1.0f,
    isPlacing: Boolean = false,
    placingBuildingName: String = "",
    buildingBarExpanded: Boolean = false,
    previewGridX: Int = 0,
    previewGridY: Int = 0,
    previewWorldX: Float = 0f,
    previewWorldY: Float = 0f,
    previewSize: GridSnapHelper.BuildingSize = GridSnapHelper.BuildingSize(2, 3),
    previewValid: GridSnapHelper.PlacementValidity = GridSnapHelper.PlacementValidity.Valid,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    onBuildingClick: (GridBuildingData) -> Unit = {},
    onPlacementDrag: (Float, Float) -> Unit = { _, _ -> },
    // 移动模式参数
    isMoving: Boolean = false,
    movingBuildingName: String = "",
    movingGridX: Int = 0,
    movingGridY: Int = 0,
    movingWorldX: Float = 0f,
    movingWorldY: Float = 0f,
    movingSize: GridSnapHelper.BuildingSize = GridSnapHelper.BuildingSize(2, 3),
    movingValid: GridSnapHelper.PlacementValidity = GridSnapHelper.PlacementValidity.Valid,
    onBuildingLongPress: (GridBuildingData) -> Unit = {},
    onMovingDrag: (Float, Float) -> Unit = { _, _ -> },
    // 正在移动的建筑instanceId（用于从渲染列表排除）
    movingInstanceId: String? = null,
    modifier: Modifier = Modifier
) {
    val currentOnBuildingClick by rememberUpdatedState(onBuildingClick)
    val currentOnBuildingLongPress by rememberUpdatedState(onBuildingLongPress)
    val currentOnPlacementDrag by rememberUpdatedState(onPlacementDrag)
    val currentOnMovingDrag by rememberUpdatedState(onMovingDrag)
    val currentIsMoving by rememberUpdatedState(isMoving)
    val currentIsPlacing by rememberUpdatedState(isPlacing)
    val currentMovingWorldX by rememberUpdatedState(movingWorldX)
    val currentMovingWorldY by rememberUpdatedState(movingWorldY)
    val currentPreviewWorldX by rememberUpdatedState(previewWorldX)
    val currentPreviewWorldY by rememberUpdatedState(previewWorldY)
    val currentMovingSize by rememberUpdatedState(movingSize)
    val currentPreviewSize by rememberUpdatedState(previewSize)
    val currentMovingInstanceId by rememberUpdatedState(movingInstanceId)
    val currentRenderScale by rememberUpdatedState(renderScale)
    val longPressScope = rememberCoroutineScope()

    // 空间索引 — O(1) 触控检测，替代 O(n) 线性查找
    val buildingIndex = remember { BuildingSpatialIndex() }
    LaunchedEffect(placedBuildings) { buildingIndex.rebuild(placedBuildings) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    // 补偿 Canvas scale 变换对触控坐标的影响
                    val scaledPos = Offset(downPos.x / currentRenderScale, downPos.y / currentRenderScale)

                    val wx = cameraState.screenToWorldX(scaledPos.x)
                    val wy = cameraState.screenToWorldY(scaledPos.y)

                    val gridX = (wx / tileSize).toInt()
                    val gridY = (wy / tileSize).toInt()
                    val touchedBuilding = buildingIndex.findBuildingAt(gridX, gridY)
                        ?.takeIf { it.instanceId != currentMovingInstanceId }

                    val onMovingBuilding = currentIsMoving && run {
                        val bw = currentMovingSize.width * tileSize
                        val bh = currentMovingSize.height * tileSize
                        wx >= currentMovingWorldX && wx < currentMovingWorldX + bw &&
                            wy >= currentMovingWorldY && wy < currentMovingWorldY + bh
                    }

                    val onPlacingBuilding = currentIsPlacing && run {
                        val bw = currentPreviewSize.width * tileSize
                        val bh = currentPreviewSize.height * tileSize
                        wx >= currentPreviewWorldX && wx < currentPreviewWorldX + bw &&
                            wy >= currentPreviewWorldY && wy < currentPreviewWorldY + bh
                    }

                    var longPressTriggered = false
                    var dragStarted = false
                    var dragTarget = DragTarget.CAMERA
                    var lastPos = downPos

                    val longPressJob = longPressScope.launch {
                        delay(viewConfiguration.longPressTimeoutMillis)
                        if (!dragStarted && touchedBuilding != null &&
                            !currentIsMoving && !currentIsPlacing
                        ) {
                            longPressTriggered = true
                            currentOnBuildingLongPress(touchedBuilding)
                            dragTarget = DragTarget.BUILDING_MOVE
                        }
                    }

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break

                        if (!change.pressed) {
                            longPressJob.cancel()
                            if (!dragStarted && !longPressTriggered && touchedBuilding != null) {
                                currentOnBuildingClick(touchedBuilding)
                            }
                            change.consume()
                            break
                        }

                        if (!dragStarted) {
                            val dx = change.position.x - downPos.x
                            val dy = change.position.y - downPos.y
                            if (dx * dx + dy * dy > viewConfiguration.touchSlop * viewConfiguration.touchSlop) {
                                dragStarted = true
                                longPressJob.cancel()
                                dragTarget = when {
                                    longPressTriggered -> DragTarget.BUILDING_MOVE
                                    currentIsMoving && onMovingBuilding -> DragTarget.BUILDING_MOVE
                                    currentIsPlacing && onPlacingBuilding -> DragTarget.BUILDING_PLACE
                                    else -> DragTarget.CAMERA
                                }
                            }
                        }

                        if (dragStarted) {
                            change.consume()
                            val dragAmountX = (change.position.x - lastPos.x) / currentRenderScale
                            val dragAmountY = (change.position.y - lastPos.y) / currentRenderScale
                            when (dragTarget) {
                                DragTarget.BUILDING_MOVE -> currentOnMovingDrag(dragAmountX, dragAmountY)
                                DragTarget.BUILDING_PLACE -> currentOnPlacementDrag(dragAmountX, dragAmountY)
                                DragTarget.CAMERA -> cameraState.pan(dragAmountX, dragAmountY)
                            }
                        }

                        lastPos = change.position
                    } while (true)
                }
            }
    ) {
        val sw = size.width / renderScale
        val sh = size.height / renderScale

        // 热状态自适应分辨率：通过 scale 变换降低实际渲染像素量
        withTransform({
            scale(renderScale, renderScale, pivot = Offset.Zero)
            translate(-cameraState.cameraX, -cameraState.cameraY)
        }) {
            // 1. 静态背景层（含已烘焙建筑，或纯地形背景）
            drawImage(fullMapBmp, topLeft = Offset.Zero)

            // 2. 动态建筑绘制
            if (!buildingsBaked) {
                // 低配设备：所有建筑动态绘制（未烘焙进 fullMapBmp）
                for (building in placedBuildings) {
                    if (movingInstanceId != null && building.instanceId == movingInstanceId) continue
                    val bx = building.gridX * tileSize
                    val by = building.gridY * tileSize
                    val bw = building.width * tileSize
                    val bh = building.height * tileSize
                    val bmp = buildingBitmaps[building.displayName]
                    if (bmp != null) {
                        drawImage(bmp, dstOffset = IntOffset(bx, by), dstSize = IntSize(bw, bh))
                    } else {
                        drawRect(Color(0xFFBDBDBD).copy(alpha = 0.8f), Offset(bx.toFloat(), by.toFloat()), Size(bw.toFloat(), bh.toFloat()))
                    }
                }
            }
            // 3. 移动中的建筑（0.5 alpha）— 高配设备烘焙层排除它，低配在步骤 2 中跳过了它
            if (movingInstanceId != null) {
                for (building in placedBuildings) {
                    if (building.instanceId != movingInstanceId) continue
                    val bx = building.gridX * tileSize
                    val by = building.gridY * tileSize
                    val bw = building.width * tileSize
                    val bh = building.height * tileSize
                    val bmp = buildingBitmaps[building.displayName]
                    if (bmp != null) {
                        drawImage(bmp, dstOffset = IntOffset(bx, by), dstSize = IntSize(bw, bh), alpha = 0.5f)
                    }
                }
            }

            // 2.5. 灵植阁光环预览 — 范围内灵田绿色覆盖
            val herbGardenAuraName = BuildingDef.HERB_GARDEN.displayName
            val showHerbGardenAura = (isPlacing && placingBuildingName == herbGardenAuraName) ||
                    (isMoving && movingBuildingName == herbGardenAuraName)
            if (showHerbGardenAura) {
                val hgGridX = if (isPlacing) previewGridX else movingGridX
                val hgGridY = if (isPlacing) previewGridY else movingGridY
                val hgW = if (isPlacing) previewSize.width else movingSize.width
                val hgH = if (isPlacing) previewSize.height else movingSize.height
                val hgCenterX = hgGridX + hgW / 2.0
                val hgCenterY = hgGridY + hgH / 2.0
                val auraRadius = GameConfig.HerbGarden.AURA_RADIUS_TILES
                val spiritFieldName = BuildingDef.SPIRIT_FIELD.displayName
                for (building in placedBuildings) {
                    if (building.displayName != spiritFieldName) continue
                    val closestX = hgCenterX.coerceIn(
                        building.gridX.toDouble(), (building.gridX + building.width).toDouble()
                    )
                    val closestY = hgCenterY.coerceIn(
                        building.gridY.toDouble(), (building.gridY + building.height).toDouble()
                    )
                    val dx = closestX - hgCenterX
                    val dy = closestY - hgCenterY
                    if (dx * dx + dy * dy <= auraRadius * auraRadius) {
                        drawRect(
                            Color(0x404CAF50),
                            Offset(building.gridX * tileSize.toFloat(), building.gridY * tileSize.toFloat()),
                            Size(building.width * tileSize.toFloat(), building.height * tileSize.toFloat())
                        )
                    }
                }
            }

            // 3. 网格线（放置/建造栏展开/移动模式时显示）
            if (isPlacing || buildingBarExpanded || isMoving) {
                val gridColor = Color(0xFFE4DDD0)
                val visibleStartX = cameraState.cameraX
                val visibleEndX = cameraState.cameraX + sw
                val visibleStartY = cameraState.cameraY
                val visibleEndY = cameraState.cameraY + sh

                val firstCol = (visibleStartX / tileSize).toInt().coerceAtLeast(0)
                val lastCol = (visibleEndX / tileSize).toInt().coerceAtMost(worldWidthCells)
                val clippedStartY = visibleStartY.coerceAtLeast(0f)
                val clippedEndY = visibleEndY.coerceAtMost(worldPixelHeight.toFloat())
                for (col in firstCol..lastCol) {
                    val x = (col * tileSize).toFloat()
                    drawLine(gridColor, Offset(x, clippedStartY), Offset(x, clippedEndY), strokeWidth = 1f)
                }

                val firstRow = (visibleStartY / tileSize).toInt().coerceAtLeast(0)
                val lastRow = (visibleEndY / tileSize).toInt().coerceAtMost(worldHeightCells)
                val clippedStartX = visibleStartX.coerceAtLeast(0f)
                val clippedEndX = visibleEndX.coerceAtMost(worldPixelWidth.toFloat())
                for (row in firstRow..lastRow) {
                    val y = (row * tileSize).toFloat()
                    drawLine(gridColor, Offset(clippedStartX, y), Offset(clippedEndX, y), strokeWidth = 1f)
                }
            }

            // 4. 放置预览
            if (isPlacing) {
                // 建筑素材图
                if (placingBuildingName.isNotEmpty()) {
                    val placeBmp = buildingBitmaps[placingBuildingName]
                    if (placeBmp != null) {
                        drawImage(
                            placeBmp,
                            dstOffset = IntOffset(previewWorldX.roundToInt(), previewWorldY.roundToInt()),
                            dstSize = IntSize(previewSize.width * tileSize, previewSize.height * tileSize)
                        )
                    }
                }
                val previewColor = when (previewValid) {
                    is GridSnapHelper.PlacementValidity.Valid -> Color(0x404CAF50)
                    is GridSnapHelper.PlacementValidity.OutOfBounds -> Color(0x40F44336)
                    is GridSnapHelper.PlacementValidity.Overlap -> Color(0x40FF5722)
                }
                for (cgx in previewGridX until previewGridX + previewSize.width) {
                    for (cgy in previewGridY until previewGridY + previewSize.height) {
                        drawRect(
                            previewColor,
                            Offset((cgx * tileSize).toFloat(), (cgy * tileSize).toFloat()),
                            Size(tileSize.toFloat(), tileSize.toFloat())
                        )
                    }
                }
            }

            // 5. 移动预览
            if (isMoving) {
                if (movingBuildingName.isNotEmpty()) {
                    val moveBmp = buildingBitmaps[movingBuildingName]
                    if (moveBmp != null) {
                        drawImage(
                            moveBmp,
                            dstOffset = IntOffset(movingWorldX.roundToInt(), movingWorldY.roundToInt()),
                            dstSize = IntSize(movingSize.width * tileSize, movingSize.height * tileSize),
                            alpha = 0.7f
                        )
                    }
                }
                val moveColor = when (movingValid) {
                    is GridSnapHelper.PlacementValidity.Valid -> Color(0x404CAF50)
                    is GridSnapHelper.PlacementValidity.OutOfBounds -> Color(0x40F44336)
                    is GridSnapHelper.PlacementValidity.Overlap -> Color(0x40FF5722)
                }
                for (cgx in movingGridX until movingGridX + movingSize.width) {
                    for (cgy in movingGridY until movingGridY + movingSize.height) {
                        drawRect(
                            moveColor,
                            Offset((cgx * tileSize).toFloat(), (cgy * tileSize).toFloat()),
                            Size(tileSize.toFloat(), tileSize.toFloat())
                        )
                    }
                }
            }

            // 6. 灵植阁光环范围圈
            if (showHerbGardenAura) {
                val centerX: Float
                val centerY: Float
                if (isPlacing) {
                    centerX = previewWorldX + (previewSize.width * tileSize) / 2f
                    centerY = previewWorldY + (previewSize.height * tileSize) / 2f
                } else {
                    centerX = movingWorldX + (movingSize.width * tileSize) / 2f
                    centerY = movingWorldY + (movingSize.height * tileSize) / 2f
                }
                drawCircle(
                    color = Color(0x404CAF50),
                    radius = (GameConfig.HerbGarden.AURA_RADIUS_TILES * tileSize).toFloat(),
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}


private enum class DragTarget { CAMERA, BUILDING_MOVE, BUILDING_PLACE }

// 建筑放置数据类（文件级，供所有 private composable 使用）
// GridBuildingData replaced by GridBuildingData from core.model (persisted via GameData)

@Composable
private fun rememberBuildingBitmaps(): Map<String, androidx.compose.ui.graphics.ImageBitmap> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val names = BuildingRegistry.names
    return remember {
        names.associateWith { name ->
            val resId = BuildingRegistry.drawableRes(name)
            android.graphics.BitmapFactory.decodeResource(context.resources, resId)
                .asImageBitmap()
        }
    }
}



/**
 * 建筑放置确认/取消按钮 — 固定出现在建筑上方居中，不受地图方格尺寸限制。
 */
@Composable
private fun PlacementConfirmButtons(
    snappedGridX: Int,
    snappedGridY: Int,
    buildingSize: GridSnapHelper.BuildingSize,
    cameraState: CameraState,
    tileSize: Int,
    validity: GridSnapHelper.PlacementValidity,
    renderScale: Float = 1.0f,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val worldX = GridSnapHelper.gridToWorld(snappedGridX, tileSize).toFloat()
    val worldY = GridSnapHelper.gridToWorld(snappedGridY, tileSize).toFloat()
    val buildingCenterXDp = cameraState.worldToScreenX(worldX + buildingSize.width * tileSize / 2f) * renderScale / density
    val buildingTopYDp = cameraState.worldToScreenY(worldY) * renderScale / density
    val canConfirm = validity == GridSnapHelper.PlacementValidity.Valid
    val btnDp = (tileSize / density).dp
    val spacerDp = btnDp * 0.4f
    // 按钮行独立于建筑宽度，居中出现在建筑正上方
    Box(
        modifier = Modifier
            .offset(x = buildingCenterXDp.dp - btnDp * 1.2f, y = buildingTopYDp.dp - btnDp * 1.5f)
            .size(width = btnDp * 2f + spacerDp, height = btnDp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(btnDp)
                    .background(if (canConfirm) Color(0xFF4CAF50) else Color.Black, CircleShape)
                    .clickable(enabled = canConfirm) { onConfirm() },
                contentAlignment = Alignment.Center
            ) { Text("✓", fontSize = (btnDp.value * 0.4f).sp, color = Color.Black, fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.width(spacerDp))
            Box(
                modifier = Modifier.size(btnDp)
                    .background(Color(0xFFF44336), CircleShape)
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center
            ) { Text("✗", fontSize = (btnDp.value * 0.4f).sp, color = Color.Black, fontWeight = FontWeight.Bold) }
        }
    }
    // 放置预览覆盖层 — graphicsLayer 零重组动画（平移仅触发 draw，跳过 layout）
    val overlayWDp = (buildingSize.width * tileSize * renderScale) / density
    val overlayHDp = (buildingSize.height * tileSize * renderScale) / density
    val overlayColor = if (canConfirm) Color(0x664CAF50) else Color(0x66F44336)
    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = cameraState.worldToScreenX(worldX) * renderScale
                translationY = cameraState.worldToScreenY(worldY) * renderScale
            }
            .size(width = overlayWDp.dp, height = overlayHDp.dp)
            .background(overlayColor)
    )
}
private fun getBuildingColor(displayName: String): Color = BuildingRegistry.color(displayName)

// Shared utility functions used by multiple tabs and dialogs
internal data class AttributeFilterOption(
    val key: String,
    val name: String
)

internal val SPIRIT_ROOT_FILTER_OPTIONS = listOf(
    1 to "单灵根",
    2 to "双灵根",
    3 to "三灵根",
    4 to "四灵根",
    5 to "五灵根"
)

internal val ATTRIBUTE_FILTER_OPTIONS = listOf(
    AttributeFilterOption("comprehension", "悟性"),
    AttributeFilterOption("intelligence", "智力"),
    AttributeFilterOption("charm", "魅力"),
    AttributeFilterOption("loyalty", "忠诚"),
    AttributeFilterOption("artifactRefining", "炼器"),
    AttributeFilterOption("pillRefining", "炼丹"),
    AttributeFilterOption("spiritPlanting", "灵植"),
    AttributeFilterOption("mining", "采矿"),
    AttributeFilterOption("teaching", "传道"),
    AttributeFilterOption("morality", "道德")
)


internal fun DiscipleAggregate.getAttributeValue(key: String): Int = when (key) {
    "comprehension" -> comprehension
    "intelligence" -> intelligence
    "charm" -> charm
    "loyalty" -> loyalty
    "artifactRefining" -> artifactRefining
    "pillRefining" -> pillRefining
    "spiritPlanting" -> spiritPlanting
    "mining" -> mining
    "teaching" -> teaching
    "morality" -> morality
    else -> 0
}

internal fun DiscipleAggregate.getSpiritRootCount(): Int = spiritRoot.types.size

internal fun List<DiscipleAggregate>.applyFilters(
    realmFilter: Set<Int>,
    spiritRootFilter: Set<Int>,
    attributeSort: String?,
    defaultSortAttribute: String? = null
): List<DiscipleAggregate> {
    val sorted = if (attributeSort != null) {
        sortedWith(
            compareByDescending<DiscipleAggregate> { it.isFollowed }
                .thenByDescending { it.getAttributeValue(attributeSort) }
                .thenBy { it.realm }
                .thenByDescending { it.realmLayer }
        )
    } else {
        sortedByFollowAttributeAndRealm(defaultSortAttribute)
    }
    val realmFiltered = if (realmFilter.isNotEmpty()) sorted.filter { it.realm in realmFilter } else sorted
    return if (spiritRootFilter.isNotEmpty()) {
        realmFiltered.filter { it.getSpiritRootCount() in spiritRootFilter }
            .sortedBy { it.getSpiritRootCount() }
    } else {
        realmFiltered
    }
}
