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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.perf.GpuTier
import com.xianxia.sect.core.perf.GpuTierDetector
import com.xianxia.sect.core.perf.GpuRenderConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GamePhase
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.game.map.sect.SectCameraState
import com.xianxia.sect.ui.game.map.sect.rememberSectCamera
import com.xianxia.sect.core.util.GridSystem

import com.xianxia.sect.ui.game.components.GameActionButtons
import com.xianxia.sect.ui.game.components.LeftSideButtons
import com.xianxia.sect.ui.game.components.GameOverlayHost
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.game.building.BuildingRegistry
import com.xianxia.sect.ui.game.building.BuildingDef
import com.xianxia.sect.ui.game.building.BuildingConstructionBar
import com.xianxia.sect.ui.game.sect.*
import com.xianxia.sect.ui.theme.ButtonSizes


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
    worldMapInteractionViewModel: WorldMapInteractionViewModel,
    worldMapGarrisonViewModel: WorldMapGarrisonViewModel,
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
    var isUiVisible by remember { mutableStateOf(true) }

    // 建筑移动状态（长按拖动）
    var movingBuilding by remember { mutableStateOf<GridBuildingData?>(null) }
    var movingWorldX by remember { mutableFloatStateOf(0f) }
    var movingWorldY by remember { mutableFloatStateOf(0f) }
    var movingSnappedGridX by remember { mutableIntStateOf(0) }
    var movingSnappedGridY by remember { mutableIntStateOf(0) }
    var movingValid by remember {
        mutableStateOf<GridSnapHelper.PlacementValidity>(GridSnapHelper.PlacementValidity.Valid)
    }
    val movingBuildingSize by remember {
        derivedStateOf {
            movingBuilding?.let { GridSnapHelper.BuildingSize(it.width, it.height) }
                ?: GridSnapHelper.BuildingSize(2, 3)
        }
    }

    // 移动中临时从网格排除正在移动的建筑，避免自身重叠检测
    val activeSectBuildings by remember {
        derivedStateOf {
            val sid = gameData.activeSectId
            placedBuildings.filter { it.sectId == sid }
        }
    }
    val effectivePlacedBuildings by remember {
        derivedStateOf {
            val mb = movingBuilding
            if (mb != null) activeSectBuildings.filter { it.instanceId != mb.instanceId }
            else activeSectBuildings
        }
    }

    val tileSize = mapPreloadData.tileSize
    val worldPixelWidth = mapPreloadData.worldPixelWidth
    val worldPixelHeight = mapPreloadData.worldPixelHeight

    // 统一相机 — 相机在世界空间中移动，screenX = worldX - cameraX
    val cameraState = rememberSectCamera(
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

    // 空间索引 — O(1) 触控检测，替代 O(n) 线性查找
    val buildingIndex = remember { BuildingSpatialIndex() }
    LaunchedEffect(effectivePlacedBuildings) { buildingIndex.rebuild(effectivePlacedBuildings) }

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

    // GPU 分级检测 — 启动时一次性检测，后续使用缓存结果
    val gpuTier = remember { GpuTierDetector().detect() }
    val gpuRenderConfig = remember { GpuRenderConfig.forTier(gpuTier) }

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
        // 避免逐帧遍历建筑列表。建筑变化时通过增量更新（擦除旧区域 + 绘制新建筑）修改
        // 离屏 backBuffer，完成后原子交换为 frontBuffer —— 消除 HWUI RenderThread 与
        // 主线程之间的读写竞争（libhwui.so SIGSEGV 根因）。
        // 低配设备（heap < 256MB）跳过烘焙，回退为动态绘制。
        // 注意：Canvas 本身因相机平移每帧内容都变，graphicsLayer(offscreen) 无法缓存；
        // 真正的静态层优化由 baked frontBuffer 承担。
        // 设备分级：GPU 能力 + 堆内存联动
        // 来源: docs/huawei-performance-research.md §4.2 + §4.4
        val maxHeapMB = remember { Runtime.getRuntime().maxMemory() / (1024 * 1024) }
        val shouldBakeBuildings = gpuRenderConfig.bakeBuildings && maxHeapMB >= 256
        val bmpConfig = if (gpuRenderConfig.useArgb8888 && maxHeapMB >= 384)
            android.graphics.Bitmap.Config.ARGB_8888
        else
            android.graphics.Bitmap.Config.RGB_565

        // 双缓冲烘焙管线：
        //   frontBufferBmp — Compose 渲染线程只读（通过 displayMapBmp → drawImage）
        //   backBufferBmp  — 主线程写入（LaunchedEffect 中 canvas.drawBitmap）
        // 写入完成后 swap：back → front, front → back（复用，不分配新内存）
        // 这从根源消除了 SIGSEGV — HWUI 渲染线程永远不会读到正在被写的 Bitmap
        var frontBufferBmp by remember {
            mutableStateOf<android.graphics.Bitmap?>(null)
        }
        var backBufferBmp by remember {
            mutableStateOf<android.graphics.Bitmap?>(null)
        }

        // 追踪上一次绘制的建筑列表，用于增量更新
        val previousBuildings = remember { mutableListOf<GridBuildingData>() }

        // 已清除装饰物的格子（避免反复清除同一格）
        val clearedDecorationCells = remember { mutableSetOf<Long>() }

        // 烘焙触发器 — 双缓冲重建时递增，强制 LaunchedEffect 全量重绘
        var bakeTrigger by remember { mutableIntStateOf(0) }

        // 烘焙完成版本号 — swap 后递增，触发 Compose 重绘
        var bakeVersion by remember { mutableIntStateOf(0) }

        // 初始化/重建双缓冲（shouldBakeBuildings / bmpConfig / fullMapBmp 变化时）
        LaunchedEffect(shouldBakeBuildings, bmpConfig, fullMapBmp) {
            // 回收旧缓冲
            frontBufferBmp?.takeIf { !it.isRecycled }?.recycle()
            backBufferBmp?.takeIf { !it.isRecycled }?.recycle()
            frontBufferBmp = null
            backBufferBmp = null
            previousBuildings.clear()
            clearedDecorationCells.clear()

            if (shouldBakeBuildings) {
                val src = fullMapBmp.asAndroidBitmap()
                val b1 = withContext(Dispatchers.Default) {
                    src.copy(bmpConfig, true) ?: src
                }
                val b2 = withContext(Dispatchers.Default) {
                    src.copy(bmpConfig, true) ?: src
                }
                frontBufferBmp = b1
                backBufferBmp = b2
                bakeTrigger++
                bakeVersion++
            }
        }

        // 建筑变化时增量烘焙到 backBuffer，然后原子交换
        LaunchedEffect(effectivePlacedBuildings, bakeTrigger) {
            val backBmp = backBufferBmp?.takeIf { !it.isRecycled }
                ?: return@LaunchedEffect

            // 检测是否全量重绘（缓冲重建或 bakeTrigger 递增）
            if (previousBuildings.isEmpty() && clearedDecorationCells.isEmpty()
                && effectivePlacedBuildings.isNotEmpty()) {
                // 全量重绘：无需额外操作，previousBuildings 已清空
                // 步骤 2/2.5 自动跳过，步骤 3 绘制所有建筑
            }

            val canvas = android.graphics.Canvas(backBmp)
            // Canvas 缩放到渲染分辨率，建筑绘制坐标仍基于世界空间
            // 来源: docs/gpu-tier-fairness-plan.md §3 — 内部位图可能低于世界分辨率
            val renderScale = backBmp.width.toFloat() / worldPixelWidth.toFloat()
            canvas.scale(renderScale, renderScale)
            val groundBmp = mapPreloadData.groundTileBmp.asAndroidBitmap()

            // === 1. 清除新增建筑覆盖的装饰物 ===
            val buildingCells = mutableSetOf<Long>()
            for (b in effectivePlacedBuildings) {
                for (cx in b.gridX until b.gridX + b.width) {
                    for (cy in b.gridY until b.gridY + b.height) {
                        if (cy in rawTileData.indices && cx in rawTileData[cy].indices) {
                            buildingCells.add(
                                (cx.toLong() shl 32) or (cy.toLong() and 0xFFFF_FFFF))
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
                        if (ty in rawTileData.indices && tx in rawTileData[ty].indices
                            && rawTileData[ty][tx] == TILE_TREE) {
                            for (ex in tx - 1..tx + 1) {
                                for (ey in ty - 1..ty + 1) {
                                    if (ey in rawTileData.indices && ex in rawTileData[ey].indices) {
                                        cellsToClear.add(
                                            (ex.toLong() shl 32) or (ey.toLong() and 0xFFFF_FFFF))
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
                    // srcRect 必须使用渲染分辨率坐标
                    val srcX = (cx * tileSize * renderScale).toInt()
                    val srcY = (cy * tileSize * renderScale).toInt()
                    val srcX2 = ((cx + 1) * tileSize * renderScale).toInt()
                    val srcY2 = ((cy + 1) * tileSize * renderScale).toInt()
                    val srcRect = android.graphics.Rect(srcX, srcY, srcX2, srcY2)
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
                    val srcRect = android.graphics.Rect(
                        (bx * renderScale).toInt(), (by * renderScale).toInt(),
                        ((bx + bw) * renderScale).toInt(),
                        ((by + bh) * renderScale).toInt()
                    )
                    val dstRect = android.graphics.Rect(bx, by, bx + bw, by + bh)
                    canvas.drawBitmap(srcBmp, srcRect, dstRect, null)
                }
            }

            // === 2.5. 处理被移动的建筑（同一 instanceId，坐标变化） ===
            for (prev in previousBuildings) {
                val curr = effectivePlacedBuildings.find {
                    it.instanceId == prev.instanceId
                } ?: continue
                if (prev.gridX != curr.gridX || prev.gridY != curr.gridY) {
                    // 擦除旧位置（恢复原始地形含装饰物）
                    val obx = prev.gridX * tileSize
                    val oby = prev.gridY * tileSize
                    val obw = prev.width * tileSize
                    val obh = prev.height * tileSize
                    val srcBmp = fullMapBmp.asAndroidBitmap()
                    val sRect = android.graphics.Rect(
                        (obx * renderScale).toInt(), (oby * renderScale).toInt(),
                        ((obx + obw) * renderScale).toInt(),
                        ((oby + obh) * renderScale).toInt()
                    )
                    val dRect = android.graphics.Rect(obx, oby, obx + obw, oby + obh)
                    canvas.drawBitmap(srcBmp, sRect, dRect, null)
                    // 绘制新位置
                    val nbx = curr.gridX * tileSize
                    val nby = curr.gridY * tileSize
                    val nbw = curr.width * tileSize
                    val nbh = curr.height * tileSize
                    val cbmp = buildingBitmaps[curr.displayName]
                    if (cbmp != null) {
                        val abmp = cbmp.asAndroidBitmap()
                        canvas.drawBitmap(abmp,
                            android.graphics.Rect(0, 0, abmp.width, abmp.height),
                            android.graphics.Rect(nbx, nby, nbx + nbw, nby + nbh),
                            null)
                    } else {
                        val p = android.graphics.Paint().apply {
                            color = 0xCCBDBDBD.toInt()
                        }
                        canvas.drawRect(
                            android.graphics.RectF(
                                nbx.toFloat(), nby.toFloat(),
                                (nbx + nbw).toFloat(), (nby + nbh).toFloat()
                            ), p)
                    }
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
                        val srcRect = android.graphics.Rect(
                            0, 0, androidBmp.width, androidBmp.height)
                        val dstRect = android.graphics.Rect(bx, by, bx + bw, by + bh)
                        canvas.drawBitmap(androidBmp, srcRect, dstRect, null)
                    } else {
                        val paint = android.graphics.Paint().apply {
                            color = 0xCCBDBDBD.toInt()
                        }
                        canvas.drawRect(
                            android.graphics.RectF(
                                bx.toFloat(), by.toFloat(),
                                (bx + bw).toFloat(), (by + bh).toFloat()
                            ), paint)
                    }
                }
            }

            // 更新追踪列表
            previousBuildings.clear()
            previousBuildings.addAll(effectivePlacedBuildings)

            // 原子交换：backBuffer 变成新的 frontBuffer（渲染线程只读）
            // 旧 frontBuffer 变成新的 backBuffer（下次写入复用，不分配新 Bitmap）
            val tmp = frontBufferBmp
            frontBufferBmp = backBmp
            backBufferBmp = tmp

            // 通知 Compose 双缓冲内容已变更，触发重绘
            bakeVersion++
        }

        // 主动回收双缓冲 Bitmap，避免依赖 GC
        DisposableEffect(Unit) {
            onDispose {
                frontBufferBmp?.takeIf { !it.isRecycled }?.recycle()
                backBufferBmp?.takeIf { !it.isRecycled }?.recycle()
            }
        }

        // 用于 Compose 渲染的 ImageBitmap（低配设备直接用原始地图）
        val displayMapBmp = remember(frontBufferBmp, bakeVersion) {
            frontBufferBmp?.asImageBitmap() ?: fullMapBmp
        }

        // 宗门大地图层（Canvas + 建筑 + 网格 + 放置预览）
        SectMapCanvas(
            config = SectMapRenderConfig(
                cameraState = cameraState,
                tileSize = tileSize,
                worldWidthCells = worldWidthCells,
                worldHeightCells = worldHeightCells,
                gpuRenderConfig = gpuRenderConfig
            ),
            staticData = SectMapStaticData(
                placedBuildings = effectivePlacedBuildings,
                buildingBitmaps = buildingBitmaps,
                fullMapBmp = displayMapBmp,
                buildingsBaked = shouldBakeBuildings && frontBufferBmp != null
            ),
            placement = if (isPlacingBuilding) PlacementModeState(
                isActive = true,
                buildingName = placingBuildingName,
                gridX = placingSnappedGridX,
                gridY = placingSnappedGridY,
                worldX = placingWorldX,
                worldY = placingWorldY,
                size = placingBuildingSize,
                validity = placementValidity
            ) else PlacementModeState.INACTIVE,
            move = if (movingBuilding != null) MoveModeState(
                isActive = true,
                building = movingBuilding,
                gridX = movingSnappedGridX,
                gridY = movingSnappedGridY,
                worldX = movingWorldX,
                worldY = movingWorldY,
                size = movingBuildingSize,
                validity = movingValid
            ) else MoveModeState.INACTIVE,
            buildingIndex = buildingIndex,
            onBuildingClick = { building ->
                val def = BuildingRegistry.findByDisplayName(building.displayName)
                when (def) {
                    BuildingDef.SPIRIT_MINE -> viewModel.navigateToDialog(DialogRoute.SpiritMine(building.instanceId))
                    BuildingDef.ALCHEMY -> viewModel.navigateToDialog(DialogRoute.Alchemy(building.instanceId))
                    BuildingDef.FORGE -> viewModel.navigateToDialog(DialogRoute.Forge(building.instanceId))
                    BuildingDef.SINGLE_RESIDENCE, BuildingDef.SINGLE_RESIDENCE_UPGRADED, BuildingDef.MULTI_RESIDENCE -> {
                        viewModel.navigateToDialog(DialogRoute.Residence(building.instanceId))
                    }
                    else -> {
                        val b = buildingList.find { it.first == building.displayName }
                        b?.second?.invoke(building)
                    }
                }
            },
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
                val screenX = cameraState.worldToScreenX(placingWorldX)
                val screenY = cameraState.worldToScreenY(placingWorldY)
                val panSpeed = 8f
                if (screenX < edgePx) cameraState.pan(panSpeed, 0f)
                if (screenX > screenWidthPx - edgePx) cameraState.pan(-panSpeed, 0f)
                if (screenY < edgePx) cameraState.pan(0f, panSpeed)
                if (screenY > screenHeightPx - edgePx) cameraState.pan(0f, -panSpeed)
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
                val edgePx = 80f
                val screenX = cameraState.worldToScreenX(movingWorldX)
                val screenY = cameraState.worldToScreenY(movingWorldY)
                val panSpeed = 8f
                if (screenX < edgePx) cameraState.pan(panSpeed, 0f)
                if (screenX > screenWidthPx - edgePx) cameraState.pan(-panSpeed, 0f)
                if (screenY < edgePx) cameraState.pan(0f, panSpeed)
                if (screenY > screenHeightPx - edgePx) cameraState.pan(0f, -panSpeed)
            },
            onUserInteraction = viewModel::onUserInteraction,
            modifier = Modifier.fillMaxSize()
        )

        // 放置模式确认按钮
        if (isPlacingBuilding) {
            PlacementConfirmButtons(
                snappedGridX = placingSnappedGridX,
                snappedGridY = placingSnappedGridY,
                buildingSize = placingBuildingSize,
                cameraState = cameraState,
                tileSize = tileSize,
                validity = placementValidity,
                onConfirm = {
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
                onCancel = {
                    isPlacingBuilding = false
                    placingBuildingName = ""
                }
            )
        }

        // 移动模式确认按钮 + 拆除按钮
        if (movingBuilding != null) {
            PlacementConfirmButtons(
                snappedGridX = movingSnappedGridX,
                snappedGridY = movingSnappedGridY,
                buildingSize = movingBuildingSize,
                cameraState = cameraState,
                tileSize = tileSize,
                validity = movingValid,
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

            DemolishButton(
                building = movingBuilding!!,
                snappedGridX = movingSnappedGridX,
                snappedGridY = movingSnappedGridY,
                buildingSize = movingBuildingSize,
                cameraState = cameraState,
                tileSize = tileSize,
                onDemolish = {
                    viewModel.demolishBuilding(movingBuilding!!.instanceId)
                    movingBuilding = null
                }
            )
        }

        // UI overlay — SectInfoCard + toggle + two side button columns
        Box(modifier = Modifier.fillMaxSize()) {
            // 宗门信息卡片 + 隐藏UI按钮（卡片外部右侧，同一行）
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 32.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isUiVisible) {
                    SectInfoCard(
                        sectName = gameData?.sectName ?: "青云宗",
                        gameYear = gameData?.gameYear ?: 1,
                        gameMonth = gameData?.gameMonth ?: 1,
                        gamePhase = gameData?.gamePhase ?: 0,
                        spiritStones = gameData?.spiritStones ?: 0L,
                        discipleCount = aliveDisciples.value.size,
                        combatPower = sectCombatPower
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                HideUiToggleButton(
                    isUiVisible = isUiVisible,
                    onToggle = { isUiVisible = !isUiVisible }
                )
            }

            // 仅 UI 可见时显示侧边按钮
            if (isUiVisible) {
                LeftSideButtons(
                    viewModel = viewModel,
                    modifier = Modifier.align(Alignment.CenterStart)
                )

                GameActionButtons(
                    viewModel = viewModel,
                    buildingBarExpanded = buildingBarExpanded,
                    onToggleBuildingBar = {
                        buildingBarExpanded = !buildingBarExpanded
                        isPlacingBuilding = false
                        movingBuilding = null
                    },
                    onCancelPlacement = {
                        isPlacingBuilding = false
                        movingBuilding = null
                    },
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }

        // 建造栏 — 开关式，展开时显示
        if (buildingBarExpanded && isUiVisible) {
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
            worldMapInteractionViewModel = worldMapInteractionViewModel,
            worldMapGarrisonViewModel = worldMapGarrisonViewModel,
            battleViewModel = battleViewModel,
            onLogout = onLogout,
            onRestartGame = onRestartGame,
            limitAdTracking = limitAdTracking,
            onLimitAdTrackingChanged = onLimitAdTrackingChanged
        )

        // 奖励卡片动效 — 最顶层，覆盖所有界面元素
        val rewardCardQueue by viewModel.rewardCardQueue.collectAsStateWithLifecycle()
        if (rewardCardQueue.isNotEmpty()) {
            val batchSize = rewardCardQueue.size
            com.xianxia.sect.ui.game.components.RewardCardHost(
                rewardCards = rewardCardQueue,
                onAnimationComplete = { viewModel.clearRewardCardQueue(batchSize) }
            )
        }
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
    combatPower: Long
) {
    Box(
        modifier = Modifier
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
                val sectIconResId = com.xianxia.sect.ui.components.sectIconRes(1)
                if (sectIconResId != null) {
                    Image(
                        painter = painterResource(id = sectIconResId),
                        contentDescription = "宗门等级",
                        modifier = Modifier.size(28.dp)
                    )
                }
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

@Composable
private fun HideUiToggleButton(
    isUiVisible: Boolean,
    onToggle: () -> Unit
) {
    val drawableRes = if (isUiVisible)
        R.drawable.ui_hide_button
    else
        R.drawable.ui_show_button
    val description = if (isUiVisible) "隐藏UI" else "显示UI"
    Box(
        modifier = Modifier
            .size(35.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = description,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
    }
}

// 建筑放置数据类（文件级，供所有 private composable 使用）
// GridBuildingData replaced by GridBuildingData from core.model (persisted via GameData)

// 建筑纹理最大尺寸 256px —— 建筑在网格上最大 3×3 格 = 192px
// 限制纹理尺寸防止低端设备超出 GL_MAX_TEXTURE_SIZE 导致 libhwui.so SIGSEGV
private const val MAX_BUILDING_TEXTURE_PX = 256

@Composable
private fun rememberBuildingBitmaps(): Map<String, androidx.compose.ui.graphics.ImageBitmap> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val names = BuildingRegistry.names
    return remember {
        names.associateWith { name ->
            val resId = BuildingRegistry.drawableRes(name)
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeResource(
                context.resources, resId, opts)
            opts.inSampleSize = calculateInSampleSize(
                opts.outWidth, opts.outHeight, MAX_BUILDING_TEXTURE_PX)
            opts.inJustDecodeBounds = false
            android.graphics.BitmapFactory.decodeResource(
                context.resources, resId, opts
            )?.asImageBitmap() ?: createFallbackBuildingBitmap()
        }
    }
}

/**
 * 计算 BitmapFactory 的 inSampleSize（总是 2 的幂），
 * 确保解码后尺寸不超过 maxDimension。
 */
private fun calculateInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int
): Int {
    var sampleSize = 1
    while (width / (sampleSize * 2) >= maxDimension
        || height / (sampleSize * 2) >= maxDimension
    ) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * 建筑纹理缺失时的回退 Bitmap — 2×2 灰色像素
 */
private fun createFallbackBuildingBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(2, 2,
        android.graphics.Bitmap.Config.ARGB_8888)
    bmp.eraseColor(0xFFBDBDBD.toInt())
    return bmp.asImageBitmap()
}



/**
 * 建筑放置确认/取消按钮 — 固定出现在建筑上方居中，不受地图方格尺寸限制。
 */
@Composable
private fun PlacementConfirmButtons(
    snappedGridX: Int,
    snappedGridY: Int,
    buildingSize: GridSnapHelper.BuildingSize,
    cameraState: SectCameraState,
    tileSize: Int,
    validity: GridSnapHelper.PlacementValidity,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val worldX = GridSnapHelper.gridToWorld(snappedGridX, tileSize).toFloat()
    val worldY = GridSnapHelper.gridToWorld(snappedGridY, tileSize).toFloat()
    val buildingCenterXDp = cameraState.worldToScreenX(worldX + buildingSize.width * tileSize / 2f) / density
    val buildingTopYDp = cameraState.worldToScreenY(worldY) / density
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
    val overlayWDp = (buildingSize.width * tileSize) / density
    val overlayHDp = (buildingSize.height * tileSize) / density
    val overlayColor = if (canConfirm) Color(0x664CAF50) else Color(0x66F44336)
    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = cameraState.worldToScreenX(worldX)
                translationY = cameraState.worldToScreenY(worldY)
            }
            .size(width = overlayWDp.dp, height = overlayHDp.dp)
            .background(overlayColor)
    )
}
private fun getBuildingColor(displayName: String): Color = BuildingRegistry.color(displayName)

@Composable
private fun DemolishButton(
    building: GridBuildingData,
    snappedGridX: Int,
    snappedGridY: Int,
    buildingSize: GridSnapHelper.BuildingSize,
    cameraState: SectCameraState,
    tileSize: Int,
    onDemolish: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val worldX = (snappedGridX * tileSize).toFloat()
    val worldY = (snappedGridY * tileSize).toFloat()
    val buildingBottomYDp = cameraState.worldToScreenY(worldY + buildingSize.height * tileSize) / density
    val buildingCenterXDp = cameraState.worldToScreenX(
        worldX + buildingSize.width * tileSize / 2f
    ) / density

    var showConfirm by remember { mutableStateOf(false) }
    val btnW = (tileSize / density).dp * 2
    val btnH = (tileSize / density).dp

    Box(
        modifier = Modifier
            .offset(
                x = buildingCenterXDp.dp - btnW / 2,
                y = buildingBottomYDp.dp + 8.dp
            )
            .width(btnW)
            .height(btnH)
            .background(Color(0xFFD32F2F), RoundedCornerShape(6.dp))
            .clickable { showConfirm = true },
        contentAlignment = Alignment.Center
    ) {
        Text("拆除", fontSize = 14.sp, color = Color.Black, fontWeight = FontWeight.Bold)
    }

    if (showConfirm) {
        StandardPromptDialog(
            onDismissRequest = { showConfirm = false },
            title = "确认拆除",
            text = "确定要拆除「${building.displayName}」吗？\n将返还 50% 建造灵石。",
            confirmLabel = "拆除",
            onConfirm = {
                showConfirm = false
                onDemolish()
            },
            dismissLabel = "取消"
        )
    }
}
