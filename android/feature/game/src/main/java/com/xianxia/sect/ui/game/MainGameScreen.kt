package com.xianxia.sect.ui.game

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.util.BuildingSpatialIndex
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.ui.game.components.messagebar.MessageBarHost
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Collections
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.xianxia.sect.ui.game.leaderboard.LeaderboardViewModel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.xianxia.sect.ui.components.LocalAtlasCache
import com.xianxia.sect.ui.components.LocalItemSpriteCache
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.components.GameButton
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.ui.navigation.toDialogType

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.core.util.TimeProgressUtil
import com.xianxia.sect.ui.game.map.sect.rememberSectCamera
import com.xianxia.sect.core.util.GridSystem

import com.xianxia.sect.core.render.NativeRenderConfig
import com.xianxia.sect.ui.game.sect.NativeSurfaceView
import com.xianxia.sect.ui.game.components.GameActionButtons
import com.xianxia.sect.ui.game.components.LeftSideButtons
import com.xianxia.sect.ui.game.components.GameOverlayHost
import com.xianxia.sect.ui.game.components.OverlayViewModels
import com.xianxia.sect.ui.game.components.OverlayCallbacks
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.ui.game.building.BuildingConstructionBar
import com.xianxia.sect.ui.game.sect.GoldFingerState
import com.xianxia.sect.ui.game.main.DemolishButton
import com.xianxia.sect.ui.game.main.DemolishSelectionOverlay
import com.xianxia.sect.ui.game.main.GoldFingerIcon
import com.xianxia.sect.ui.game.main.GoldFingerSelectionOverlay
import com.xianxia.sect.ui.game.main.GridOverlay
import com.xianxia.sect.ui.game.main.GridPlacement
import com.xianxia.sect.ui.game.main.HerbGardenAuraOverlay
import com.xianxia.sect.ui.game.main.HideUiToggleButton
import com.xianxia.sect.ui.game.main.JadeSymbolBadge
import com.xianxia.sect.ui.game.main.PlacementConfirmButtons
import com.xianxia.sect.ui.game.main.SectInfoCard
import com.xianxia.sect.ui.game.main.SectMapEdgeOverlay
import com.xianxia.sect.ui.game.main.GoldFingerSelection
import com.xianxia.sect.ui.game.main.clampGoldFingerSelection
import com.xianxia.sect.ui.game.main.recomputeGoldFingerState
import com.xianxia.sect.ui.game.main.translateGoldFingerSelection
import com.xianxia.sect.core.touch.LongPressResult
import com.xianxia.sect.core.touch.SectMapTouchEngine
import com.xianxia.sect.core.touch.TouchEngineCallbacks
import com.xianxia.sect.core.touch.TouchEngineConfig
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.core.animation.CameraAnimator
import androidx.compose.runtime.mutableIntStateOf




/**
 * ## MainGameScreen - 主游戏界面 (Compose 重组优化版本)
 *
 * ### [H-07] 性能优化说明
 *
 * **原始问题**:
 * - 在单个 Composable 中收集 30+ 个 StateFlow
 * - 任何 StateFlow 变化都触发整个 MainGameScreen 重组
 * - 高频数据 (cultivation progress, resources) 每秒变化 10 次 (100ms tick)
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

// 瓦片类型常量（与 GameActivity.kt 一致）
private const val TILE_GROUND = 0
private const val TILE_GRASS_SMALL = 1
private const val TILE_GRASS_MEDIUM = 2
private const val TILE_GRASS_LARGE = 3
private const val TILE_TREE1 = 4
private const val TILE_TREE2 = 5
private const val TILE_BUILDING = 6

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
    /** 是否强制使用 Canvas 软件渲染（模拟器/Vulkan 不可用设备） */
    forceSoftwareRendering: Boolean = false,
    /** Vulkan 初始化生命周期监听器（由 GameActivity 注入，驱动 CrashRecoveryEngine） */
    vulkanInitListener: NativeSurfaceView.VulkanInitListener? = null
) {
    // [M7-OPT-1] 高频核心数据收集 - 使用 derivedStateOf 限制重组范围
    // gameData 包含资源、日期等，每 tick (100ms) 都可能变化
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

    // 一键拆除模式状态
    var isDemolishMode by remember { mutableStateOf(false) }
    var demolishSelectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

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

    // D-12（2026-08-06）：movingBuilding 状态单点同步到渲染总线排除通道——
    // 总线不感知 Compose 局部 movingBuilding，不排除会导致拖拽窗口期该建筑
    // 仍在旧位置渲染（双渲染）+ 点不中 + 其格子可叠建（绿色）
    LaunchedEffect(movingBuilding) {
        viewModel.setMovingBuildingInstanceId(movingBuilding?.instanceId)
    }

    val tileSize = mapPreloadData.tileSize
    val worldPixelWidth = mapPreloadData.worldPixelWidth
    val worldPixelHeight = mapPreloadData.worldPixelHeight

    // 统一相机 — 相机在世界空间中移动，screenX = worldX - cameraX
    // 所有设备水平固定显示 24 格（SectCameraState.VISIBLE_COLS），垂直自然适配
    val cameraState = rememberSectCamera(
        worldWidth = worldPixelWidth.toFloat(),
        worldHeight = worldPixelHeight.toFloat(),
        worldWidthCells = mapPreloadData.worldWidthCells
    )

    // 建筑尺寸映射 — 从配置读取，在宗门地图中所占的格数 (宽 × 高)
    val buildingSizes = remember {
        BuildingFeatureRegistry.all.associate { def ->
            val (w, h) = viewModel.getBuildingGridSize(def.displayName)
            def.displayName to GridSnapHelper.BuildingSize(w, h)
        }
    }

    // 建筑精灵比例尺寸映射 — 用于渲染视觉大小（可能大于占地尺寸）
    val buildingSpriteSizes = remember {
        BuildingFeatureRegistry.all.associate { def ->
            val (sw, sh) = viewModel.getBuildingSpriteSize(def.displayName)
            def.displayName to GridSnapHelper.BuildingSize(sw, sh)
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

    // 金手指批量建造状态
    var goldFingerState by remember { mutableStateOf<com.xianxia.sect.ui.game.sect.GoldFingerState>(com.xianxia.sect.ui.game.sect.GoldFingerState()) }
    val goldFingerBuildingCost = remember {
        derivedStateOf {
            val name = goldFingerState.buildingName
            if (name.isNotEmpty()) viewModel.getBuildingCost(name) else 0L
        }
    }
    val goldFingerAvailableStones = remember {
        derivedStateOf { gameData?.spiritStones ?: 0L }
    }

    val worldWidthCells = mapPreloadData.worldWidthCells
    val worldHeightCells = mapPreloadData.worldHeightCells

    // 地图瓦片素材 — 由 GameActivity 预加载，此处同步读取

    val rawTileData = mapPreloadData.rawTileData

    // 纹理将在 NativeSurfaceView 的 onRendererReady 回调中上传

    // 瓦片数据（含建筑占位标记）：装饰物类型 + 建筑占用 → 统一 tileData
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
        GridSystem(tileSize, worldWidthCells, worldHeightCells,
            buildableBorder = GameConfig.SectMap.BORDER_TREE_RING)
    }
    LaunchedEffect(effectivePlacedBuildings) {
        gridSystem.rebuildFrom(effectivePlacedBuildings)
    }

    // 空间索引 — O(1) 触控检测，替代 O(n) 线性查找
    // 2026-08-06 修复：传入精灵视觉尺寸，命中区域扩展为占地 ∪ 精灵包围盒，
    // 高层建筑（塔楼/藏经阁等）悬空上半身可点击
    val buildingIndex = remember { BuildingSpatialIndex() }
    LaunchedEffect(effectivePlacedBuildings) {
        buildingIndex.rebuild(effectivePlacedBuildings, buildingSpriteSizes)
    }

    // 建筑列表及点击回调
    val buildingList = remember {
        BuildingFeatureRegistry.constructible.map { def ->
            val handler: (GridBuildingData?) -> Unit = when (def.key) {
                "spirit_mine" -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogType.SpiritMine(it)) }; Unit }
                "herb_garden" -> { _ -> viewModel.navigateToDialog(DialogType.HerbGarden) }
                "spirit_field" -> { _ -> viewModel.navigateToDialog(DialogType.Planting) }
                "alchemy" -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogType.Alchemy(it)) }; Unit }
                "forge" -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogType.Forge(it)) }; Unit }
                "library" -> { _ -> viewModel.navigateToDialog(DialogType.Library) }
                "wen_dao_peak" -> { _ -> viewModel.navigateToDialog(DialogType.WenDaoPeak) }
                "qingyun_peak" -> { _ -> viewModel.navigateToDialog(DialogType.QingyunPeak) }
                "tianshu_hall" -> { _ -> viewModel.navigateToDialog(DialogType.TianshuHall) }
                "law_enforcement_hall" -> { _ -> viewModel.navigateToDialog(DialogType.LawEnforcementHall) }
                "mission_hall" -> { _ -> viewModel.navigateToDialog(DialogType.MissionHall) }
                "reflection_cliff" -> { _ -> viewModel.navigateToDialog(DialogType.ReflectionCliff) }
                "patrol_tower" -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogType.PatrolTower(it)) }; Unit }
                "blood_refining_pool" -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogType.BloodRefiningPool(it)) }; Unit }
                "single_residence", "multi_residence",
                "single_residence_upgraded", "multi_residence_upgraded" -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogType.Residence(it)) }; Unit }
                "warehouse" -> { b -> b?.instanceId?.let { viewModel.navigateToDialog(DialogType.WarehouseBuilding(it)) }; Unit }
                else -> { _ -> Unit }
            }
            def.displayName to handler
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { route ->
            viewModel.navigateToDialog(route.toDialogType())
        }
    }

    LaunchedEffect(Unit) {
        viewModel.currentDialogType.collect { route ->
            if (route !is DialogType.None) {
                isPlacingBuilding = false
                movingBuilding = null
                buildingBarExpanded = false
                isDemolishMode = false
                demolishSelectedIds = emptySet()
                // 防金手指覆盖层悬浮在非放置模式（预存缺陷：开对话框时漏重置）
                goldFingerState = GoldFingerState()
            }
        }
    }

    // 每日首次进游戏静默上报排行榜战力（节流+未登录自动跳过，不阻塞启动；
    // 打开排行榜界面时另有上报入口）。LeaderboardViewModel 与排行榜对话框共用同一实例。
    val leaderboardViewModel = hiltViewModel<LeaderboardViewModel>()
    LaunchedEffect(Unit) {
        leaderboardViewModel.reportDailyIfDue()
    }

    // 相机视口更新 + 初始居中（只执行一次）
    LaunchedEffect(screenWidthPx, screenHeightPx) {
        if (screenWidthPx > 0 && screenHeightPx > 0) {
            cameraState.updateViewport(screenWidthPx.toInt(), screenHeightPx.toInt())
            cameraState.tryCenterOn(worldPixelWidth / 2f, worldPixelHeight / 2f)
        }
    }

    val isGameOver by viewModel.isGameOver.collectAsStateWithLifecycle()

    LaunchedEffect(isGameOver) {
        if (isGameOver) {
            viewModel.openGameOverDialog()
        }
    }

    // 移动模式/金手指/拆除模式下按返回键取消
    BackHandler(
        enabled = movingBuilding != null || goldFingerState.isActive || isDemolishMode
    ) {
        when {
            goldFingerState.isActive -> goldFingerState = GoldFingerState()
            isDemolishMode -> {
                isDemolishMode = false
                demolishSelectedIds = emptySet()
            }
            else -> movingBuilding = null
        }
    }

    val preloadedItemSprites by saveLoadViewModel.preloadedItemSprites.collectAsStateWithLifecycle()
    val atlasResult by saveLoadViewModel.atlasResult.collectAsStateWithLifecycle()

    CompositionLocalProvider(
        LocalItemSpriteCache provides preloadedItemSprites,
        LocalAtlasCache provides atlasResult
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .onSizeChanged { size ->
                screenWidthPx = size.width.toFloat()
                screenHeightPx = size.height.toFloat()
            }
    ) {
        val context = LocalContext.current

        // 金手指图标位图
        val goldenFingerBmp = remember {
            val resId = SpriteResRegistry.resolve("golden_finger")
            if (resId != null) {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = 1
                }
                android.graphics.BitmapFactory.decodeResource(context.resources, resId, opts)
                    ?.asImageBitmap()
            } else null
        }

        // 宗门大地图层（Vulkan 原生渲染）
        // v4.0.43+ 架构：替换 Compose Canvas 为 Vulkan 原生渲染管线，
        // 实现 GPU 批处理（3 draw calls/帧）、独立渲染线程、VSYNC 对齐。
        // 参见: docs/map-rendering-architecture.md
        val nativeConfig = remember(tileSize) {
            NativeRenderConfig(
                tileSize = tileSize,
                worldWidthCells = worldWidthCells,
                worldHeightCells = worldHeightCells,
                worldPixelWidth = worldPixelWidth,
                worldPixelHeight = worldPixelHeight
            )
        }
        var nativeSurfaceView by remember { mutableStateOf<NativeSurfaceView?>(null) }

        // 普通点击选中格（WP3 选中高亮）：点击建筑时记录其格坐标，点击空地清除。
        // 渲染端经 findBuildingIndex 转换为建筑索引（双后端共用同一命中几何）
        var selectedBuildingGrid by remember { mutableStateOf<Pair<Int, Int>?>(null) }

        // flatTileData — 由 tileData 派生，建筑占位变化时自动重算
        val flatTileData = remember(tileData) {
            tileData.flatMap { it.toList() }.toIntArray()
        }

        // 统一 UV 映射表（来自 SpriteAtlasDef，与 C++ TextureAtlas.h 一致）
        val decorUvMap = SpriteAtlasDef.TILE_UV_MAP

        // ★ 优化：缓存 buildingData FloatArray，仅在建筑列表变化时重建
        // 拖拽时 cameraState 变化触发的重组不重新分配
        val buildingDataArray = remember(effectivePlacedBuildings, buildingSpriteSizes) {
            if (effectivePlacedBuildings.isNotEmpty()) {
                buildBuildingDataArray(effectivePlacedBuildings, buildingSpriteSizes)
            } else null
        }

        // ★ 灵田作物数据（WP6）：灵田建筑 ↔ 种植记录按 buildingInstanceId 映射，
        // progress01 = 游戏时间进度（TimeProgressUtil，与生产结算同源）。
        // 低频变化（种植/收获/逐月生长）走帧率门控 RenderFrame——不新增命令总线槽位。
        // derivedStateOf：游戏时间/种植记录不变时不重建数组（gameData 每 tick 变化
        // 触发的是 recomposition 而非本派生重算）
        val spiritCropData = remember {
            derivedStateOf {
                buildSpiritCropData(
                    buildings = effectivePlacedBuildings,
                    plants = gameData.spiritFieldPlants,
                    currentYear = gameData.gameYear,
                    currentMonth = gameData.gameMonth,
                    sectId = gameData.activeSectId
                )
            }
        }

        // ★ 优化：RenderFrame 推送帧率门控
        // SOFTWARE 路径下限制推送频率（RenderThread 自行读取 currentFrame 原子快照）
        var lastRenderDataSyncNs by remember { mutableLongStateOf(0L) }

        // 缓存 buildingData 哈希值，避免每帧重复分配 FloatArray
        // P-7：地图视口抽离为 SectMapViewport（参数稳定引用——每旬 gameData
        // 变化不触发 AndroidView update；相机/预览/建筑实际变化才重组）
        val viewportParams = remember {
            derivedStateOf {
                SectMapViewportParams(
                    nativeConfig = nativeConfig,
                    cameraState = cameraState,
                    flatTileData = flatTileData,
                    buildingDataArray = buildingDataArray,
                    buildingCount = effectivePlacedBuildings.size,
                    tileSize = tileSize,
                    worldWidthCells = worldWidthCells,
                    worldHeightCells = worldHeightCells,
                    forceSoftwareRendering = forceSoftwareRendering,
                    vulkanInitListener = vulkanInitListener,
                    buildingSpriteSizes = buildingSpriteSizes,
                    selectedGrid = selectedBuildingGrid,
                    spiritCropData = spiritCropData.value
                )
            }
        }
        val previewState = remember {
            derivedStateOf {
                MapPreviewState(
                    isPlacingBuilding = isPlacingBuilding,
                    placingBuildingName = placingBuildingName,
                    placingWorldX = placingWorldX,
                    placingWorldY = placingWorldY,
                    placingBuildingSize = placingBuildingSize,
                    placementValidity = placementValidity,
                    movingBuilding = movingBuilding,
                    movingWorldX = movingWorldX,
                    movingWorldY = movingWorldY,
                    movingBuildingSize = movingBuildingSize,
                    movingValid = movingValid
                )
            }
        }
        SectMapViewport(
            params = viewportParams.value,
            preview = previewState.value,
            commandBus = viewModel.getRenderCommandBus(),
            onViewCreated = { view -> nativeSurfaceView = view }
        )

        // ================================================================
        // 跨平台手势引擎 + 平滑镜头动画
        // ================================================================
        val touchScope = rememberCoroutineScope()
        val cameraAnimator = remember(cameraState, touchScope) {
            CameraAnimator(cameraState, touchScope)
        }
        // 设置动画器引用，使 tryCenterOn 使用平滑动画
        LaunchedEffect(cameraAnimator) {
            cameraState.setAnimator(cameraAnimator)
        }
        // 用户交互时取消动画
        val cancelCameraAnim: () -> Unit = { cameraAnimator.cancel() }

        val touchEngine = remember(cameraState, buildingIndex, gridSystem) {
            SectMapTouchEngine(
                callbacks = object : TouchEngineCallbacks {
                    override fun onPanCamera(dx: Float, dy: Float) {
                        cameraState.pan(dx, dy)
                        cancelCameraAnim()
                        viewModel.onUserInteraction()
                    }

                    override fun onTap(screenX: Float, screenY: Float) {
                        val wx = cameraState.screenToWorldX(screenX)
                        val wy = cameraState.screenToWorldY(screenY)
                        val gx = (wx / tileSize).toInt()
                        val gy = (wy / tileSize).toInt()
                        // 拆除模式：点击建筑切换选中状态，不弹详情
                        if (isDemolishMode) {
                            val b = buildingIndex.findBuildingAt(gx, gy) ?: return
                            if (BuildingFeatureRegistry.findByDisplayName(b.displayName) != null) {
                                demolishSelectedIds = if (b.instanceId in demolishSelectedIds)
                                    demolishSelectedIds - b.instanceId
                                else demolishSelectedIds + b.instanceId
                            }
                            return
                        }
                        val clicked = buildingIndex.findBuildingAt(gx, gy)
                        // 点击空地 → 清除选中高亮（任意模式）
                        if (clicked == null) {
                            selectedBuildingGrid = null
                        }
                        if (clicked != null && !isPlacingBuilding && movingBuilding == null) {
                            // 点击建筑 → 记录选中格（渲染端金色高亮描边），并打开详情
                            selectedBuildingGrid = gx to gy
                            val def = BuildingFeatureRegistry.findByDisplayName(clicked.displayName)
                            when (def?.key) {
                                "spirit_mine" -> viewModel.navigateToDialog(DialogType.SpiritMine(clicked.instanceId))
                                "alchemy" -> viewModel.navigateToDialog(DialogType.Alchemy(clicked.instanceId))
                                "forge" -> viewModel.navigateToDialog(DialogType.Forge(clicked.instanceId))
                                "single_residence", "single_residence_upgraded", "multi_residence", "multi_residence_upgraded" -> {
                                    viewModel.navigateToDialog(DialogType.Residence(clicked.instanceId))
                                }
                                else -> {
                                    // R1 诊断（B1）：displayName 未注册 / 无回调 → 点击被静默吞掉。
                                    // 渲染端会用索引 0 兜底画出该建筑，点击却无任何分支处理——唯一"可见但点不中"确定性路径。
                                    if (def == null) {
                                        DomainLog.w(
                                            BUILDING_TAP_TAG,
                                            "点击建筑 displayName 未注册: name=${clicked.displayName} " +
                                                "sectId=${clicked.sectId} instanceId=${clicked.instanceId} " +
                                                "grid=(${clicked.gridX},${clicked.gridY}) " +
                                                "activeSectId=${gameData.activeSectId} " +
                                                "sectBuildings=${activeSectBuildings.size}"
                                        )
                                    }
                                    val b = buildingList.find { it.first == clicked.displayName }
                                    if (b != null) {
                                        b.second?.invoke(clicked)
                                    } else {
                                        DomainLog.w(
                                            BUILDING_TAP_TAG,
                                            "点击建筑无回调处理: name=${clicked.displayName} " +
                                                "sectId=${clicked.sectId} instanceId=${clicked.instanceId} " +
                                                "grid=(${clicked.gridX},${clicked.gridY}) " +
                                                "activeSectId=${gameData.activeSectId} " +
                                                "sectBuildings=${activeSectBuildings.size}"
                                        )
                                    }
                                }
                            }
                        }
                    }

                    override fun onLongPress(screenX: Float, screenY: Float): LongPressResult {
                        val wx = cameraState.screenToWorldX(screenX)
                        val wy = cameraState.screenToWorldY(screenY)
                        val gx = (wx / tileSize).toInt()
                        val gy = (wy / tileSize).toInt()

                        // 放置模式 → 金手指图标检测（激活 / 已激活时继续框选）
                        if (isPlacingBuilding) {
                            val gfWx = (placingSnappedGridX + placingBuildingSize.width) * tileSize
                            val gfWy = (placingSnappedGridY + placingBuildingSize.height) * tileSize
                            if (wx >= gfWx && wx < gfWx + tileSize &&
                                wy >= gfWy && wy < gfWy + tileSize
                            ) {
                                if (!goldFingerState.isActive) {
                                    // 首次激活：起点锚定预览位置，钳制到可建区后重算状态
                                    val sel = clampGoldFingerSelection(
                                        GoldFingerSelection(
                                            placingSnappedGridX, placingSnappedGridY,
                                            placingSnappedGridX, placingSnappedGridY
                                        ),
                                        worldWidthCells, worldHeightCells,
                                        GameConfig.SectMap.BORDER_TREE_RING
                                    )
                                    goldFingerState = recomputeGoldFingerState(
                                        f = GoldFingerState(
                                            isActive = true,
                                            buildingName = placingBuildingName,
                                            buildingSize = placingBuildingSize,
                                            buildingCost = viewModel.getBuildingCost(placingBuildingName)
                                        ),
                                        sel = sel,
                                        existingBuildings = effectivePlacedBuildings,
                                        worldWidthCells = worldWidthCells,
                                        worldHeightCells = worldHeightCells,
                                        buildableBorder = GameConfig.SectMap.BORDER_TREE_RING,
                                        spiritStones = gameData?.spiritStones ?: 0L
                                    )
                                }
                                // 已激活：不改动选区（等待 MOVE 重新框定，可扩大可缩小），直接重入框选
                                return LongPressResult.GoldFingerDrag
                            }
                            return LongPressResult.NotHandled
                        }

                        // 非放置模式 → 建筑长按 → 移动模式
                        // 注意：movingBuilding 可能非 null（上次拖拽后确认/取消按钮还在显示）
                        // 如果按钮显示期间再次长按同一建筑，应允许继续拖拽
                        // 拆除模式禁止长按移动
                        if (!isPlacingBuilding && !isDemolishMode) {
                            val touched = buildingIndex.findBuildingAt(gx, gy)
                                ?: (if (movingBuilding != null) movingBuilding else null)
                            if (touched != null) {
                                val isResumeDrag = movingBuilding?.instanceId == touched.instanceId
                                if (!isResumeDrag) {
                                    // 新建筑拖拽 → 从该建筑的原始网格坐标开始
                                    movingWorldX = (touched.gridX * tileSize).toFloat()
                                    movingWorldY = (touched.gridY * tileSize).toFloat()
                                    movingSnappedGridX = touched.gridX
                                    movingSnappedGridY = touched.gridY
                                    movingValid = GridSnapHelper.PlacementValidity.Valid
                                }
                                movingBuilding = touched
                                return LongPressResult.BuildingDrag
                            }
                        }
                        return LongPressResult.NotHandled
                    }

                    override fun onBuildingDragUpdate(worldDx: Float, worldDy: Float) {
                        if (isPlacingBuilding) {
                            // 放置模式：更新预览位置
                            placingWorldX += worldDx
                            placingWorldY += worldDy
                            val oldSnappedX = placingSnappedGridX
                            val oldSnappedY = placingSnappedGridY
                            placingSnappedGridX = GridSnapHelper.worldToGrid(placingWorldX, tileSize)
                            placingSnappedGridY = GridSnapHelper.worldToGrid(placingWorldY, tileSize)
                            val dgx = placingSnappedGridX - oldSnappedX
                            val dgy = placingSnappedGridY - oldSnappedY
                            // 金手指激活时选区随预览同增量平移（Bug 2 修复），钳制到可建区后重算
                            if (goldFingerState.isActive && (dgx != 0 || dgy != 0)) {
                                val f = goldFingerState
                                val sel = translateGoldFingerSelection(
                                    GoldFingerSelection(f.startGridX, f.startGridY, f.endGridX, f.endGridY),
                                    dgx, dgy, worldWidthCells, worldHeightCells,
                                    GameConfig.SectMap.BORDER_TREE_RING
                                )
                                goldFingerState = recomputeGoldFingerState(
                                    f = f, sel = sel,
                                    existingBuildings = effectivePlacedBuildings,
                                    worldWidthCells = worldWidthCells,
                                    worldHeightCells = worldHeightCells,
                                    buildableBorder = GameConfig.SectMap.BORDER_TREE_RING,
                                    spiritStones = gameData?.spiritStones ?: 0L
                                )
                            }
                            placementValidity = gridSystem.validatePlacement(
                                placingSnappedGridX, placingSnappedGridY,
                                placingBuildingSize.width, placingBuildingSize.height
                            )
                        } else {
                            // 移动模式：更新被拖建筑位置
                            movingWorldX += worldDx
                            movingWorldY += worldDy
                            movingSnappedGridX = GridSnapHelper.worldToGrid(movingWorldX, tileSize)
                            movingSnappedGridY = GridSnapHelper.worldToGrid(movingWorldY, tileSize)
                            movingValid = gridSystem.validatePlacement(
                                movingSnappedGridX, movingSnappedGridY,
                                movingBuildingSize.width, movingBuildingSize.height
                            )
                        }
                    }

                    override fun onBuildingDragEnd() {
                        // 松手后保持最后位置，显示确认/取消按钮
                        // movingBuilding 保持非 null，确认按钮触发 viewModel.moveBuilding()
                    }

                    override fun onGoldFingerUpdate(screenX: Float, screenY: Float) {
                        if (!goldFingerState.isActive) return
                        val newWx = cameraState.screenToWorldX(screenX)
                        val newWy = cameraState.screenToWorldY(screenY)
                        // 终点格用 GridSnapHelper.worldToGrid（roundToInt，与预览吸附一致），
                        // 并整体钳制到可建区，保证视觉框 == 实际建造区
                        val newGridX = GridSnapHelper.worldToGrid(newWx, tileSize)
                        val newGridY = GridSnapHelper.worldToGrid(newWy, tileSize)
                        val f = goldFingerState
                        val sel = clampGoldFingerSelection(
                            GoldFingerSelection(f.startGridX, f.startGridY, newGridX, newGridY),
                            worldWidthCells, worldHeightCells,
                            GameConfig.SectMap.BORDER_TREE_RING
                        )
                        goldFingerState = recomputeGoldFingerState(
                            f = f, sel = sel,
                            existingBuildings = effectivePlacedBuildings,
                            worldWidthCells = worldWidthCells,
                            worldHeightCells = worldHeightCells,
                            buildableBorder = GameConfig.SectMap.BORDER_TREE_RING,
                            spiritStones = gameData?.spiritStones ?: 0L
                        )
                    }

                    override fun isGoldFingerActive(): Boolean = goldFingerState.isActive
                    override fun getCameraScale(): Float = cameraState.scale

                    /**
                     * [关键] DOWN 时刻检测是否在建筑上。
                     * 引擎据此选择长按超时：建筑上 → 200ms 长按进 BuildingDrag；空地 → 800ms（金手指）。
                     * Slop→Scrolling 判决统一由 touchSlop 决定，与返回值无关；拖动视角不再被建筑吞掉。
                     * 复用 buildingIndex 的 O(1) 空间索引查询。
                     */
                    override fun findBuildingAt(screenX: Float, screenY: Float): Any? {
                        // 拆除模式：不返回建筑 → touch 引擎不会启动 BuildingDrag 定时器，
                        // 短按/滑动正常走 onTap / 平移相机
                        if (isDemolishMode) return null
                        val wx = cameraState.screenToWorldX(screenX)
                        val wy = cameraState.screenToWorldY(screenY)

                        // 放置模式：用世界坐标检测触摸是否在预览区域内（比网格检测更精准）
                        if (isPlacingBuilding) {
                            val previewLeft = placingWorldX
                            val previewTop = placingWorldY
                            val previewRight = previewLeft + placingBuildingSize.width * tileSize
                            val previewBottom = previewTop + placingBuildingSize.height * tileSize
                            if (wx >= previewLeft && wx < previewRight &&
                                wy >= previewTop && wy < previewBottom
                            ) {
                                return Any()
                            }
                            return null
                        }

                        val gx = (wx / tileSize).toInt()
                        val gy = (wy / tileSize).toInt()

                        // buildingIndex 不包含 movingBuilding，手动检查
                        val mb = movingBuilding
                        if (mb != null) {
                            // 用当前拖拽位置（movingSnappedGridX/Y）而非原始位置检查
                            if (gx >= movingSnappedGridX && gx < movingSnappedGridX + mb.width &&
                                gy >= movingSnappedGridY && gy < movingSnappedGridY + mb.height
                            ) {
                                return mb
                            }
                        }
                        return buildingIndex.findBuildingAt(gx, gy)
                    }

                    /**
                     * 是否已在编辑模式（移动或放置中）。
                     * true  → 直接拖拽，无需长按
                     * false → 首次触摸建筑需长按 200ms
                     */
                    override fun isInEditMode(): Boolean = isPlacingBuilding || movingBuilding != null

                    override fun onDragStart() {
                        viewModel.setGameScene(
                            GameEngineCore.GameScene.GAMEPLAY
                        )
                    }

                    override fun onDragEnd() {
                        // 由 idle timeout 自动降帧 (30s → IDLE 10fps)
                    }

                    override fun onFlingStart() {
                        viewModel.setGameScene(
                            GameEngineCore.GameScene.MAP_SCROLL
                        )
                    }

                    override fun onFlingEnd() {
                        // 由 idle timeout 自动降帧 (30s → IDLE 10fps)
                    }
                },
                scope = touchScope,
                config = TouchEngineConfig()
            )
        }

        // 挂载 touchEngine 到 NativeSurfaceView
        LaunchedEffect(nativeSurfaceView) {
            nativeSurfaceView?.touchEngine = touchEngine
        }

        // 将引擎渲染帧率（热控+场景+性能模式综合）接入 NativeSurfaceView
        LaunchedEffect(nativeSurfaceView) {
            val view = nativeSurfaceView ?: return@LaunchedEffect
            viewModel.renderFrameRate.collect { fps ->
                view.targetFps = fps
            }
        }

        // 接通渲染质量/装饰降级流（热控 + 节能模式低画质真实生效）。
        // 经 NativeSurfaceView 转发属性写入——backend 未创建时先存值、
        // 创建后立即应用，防初始发射丢失。
        LaunchedEffect(nativeSurfaceView) {
            val view = nativeSurfaceView ?: return@LaunchedEffect
            combine(
                viewModel.renderingQualityFactor,
                viewModel.decorationsDisabled
            ) { quality, decorations -> quality to decorations }
                .distinctUntilChanged()
                .collect { (quality, decorations) ->
                    view.renderQualityFactor = quality
                    view.renderDecorationsDisabled = decorations
                }
        }

        // 渲染线程实际达成帧率 → 引擎热控（激活帧率驱动降级）
        LaunchedEffect(nativeSurfaceView) {
            nativeSurfaceView?.onObservedFps = { fps ->
                viewModel.gameEngineCore.setObservedRenderFps(fps)
            }
        }

        // 网格线（放置/移动模式时显示）
        val gridPlace = if (isPlacingBuilding) {
            GridPlacement(placingSnappedGridX, placingSnappedGridY,
                placingBuildingSize.width, placingBuildingSize.height)
        } else if (movingBuilding != null) {
            GridPlacement(movingSnappedGridX, movingSnappedGridY,
                movingBuildingSize.width, movingBuildingSize.height)
        } else null
        GridOverlay(
            placement = gridPlace,
            cameraState = cameraState,
            tileSize = tileSize,
            worldWidthCells = worldWidthCells,
            worldHeightCells = worldHeightCells
        )

        // 金手指图标（建筑预览框右下角）— 放置模式始终显示（激活后作为继续框选的可见入口）
        if (isPlacingBuilding && goldenFingerBmp != null) {
            GoldFingerIcon(
                goldenFingerBmp = goldenFingerBmp,
                gridX = placingSnappedGridX + placingBuildingSize.width,
                gridY = placingSnappedGridY + placingBuildingSize.height,
                cameraState = cameraState,
                tileSize = tileSize
            )
        }

        // 金手指框选覆盖层 — 激活时绘制选区方块和边框
        if (goldFingerState.isActive) {
            GoldFingerSelectionOverlay(
                goldFingerState = goldFingerState,
                cameraState = cameraState,
                tileSize = tileSize,
                goldenFingerBmp = goldenFingerBmp
            )
        }

        // 一键拆除覆盖层 — 所有可拆建筑显示绿色占地框，选中变红
        if (isDemolishMode) {
            DemolishSelectionOverlay(
                buildings = activeSectBuildings,
                selectedIds = demolishSelectedIds,
                cameraState = cameraState,
                tileSize = tileSize
            )
        }

        // 灵植阁光环范围 — 放置/移动灵植阁时显示光环范围圈 + 范围内灵田高亮
        val herbGardenDisplayName = "灵植阁"
        val showHerbGardenAura = (isPlacingBuilding && placingBuildingName == herbGardenDisplayName) ||
                (movingBuilding?.displayName == herbGardenDisplayName)
        val auraGridX = if (isPlacingBuilding) placingSnappedGridX else movingSnappedGridX
        val auraGridY = if (isPlacingBuilding) placingSnappedGridY else movingSnappedGridY
        val auraSize = if (isPlacingBuilding) placingBuildingSize else movingBuildingSize
        val spiritFieldDisplayName = "灵田"
        val spiritFieldBuildings = remember(placedBuildings, movingBuilding) {
            placedBuildings.filter { it.displayName == spiritFieldDisplayName }
        }
        HerbGardenAuraOverlay(
            showAura = showHerbGardenAura,
            buildingGridX = auraGridX,
            buildingGridY = auraGridY,
            buildingW = auraSize.width,
            buildingH = auraSize.height,
            spiritFieldBuildings = spiritFieldBuildings,
            cameraState = cameraState,
            tileSize = tileSize
        )

        if (isPlacingBuilding) {
            val isGf = goldFingerState.isActive
            PlacementConfirmButtons(
                snappedGridX = placingSnappedGridX,
                snappedGridY = placingSnappedGridY,
                buildingSize = placingBuildingSize,
                cameraState = cameraState,
                tileSize = tileSize,
                validity = if (isGf && !goldFingerState.canAfford) GridSnapHelper.PlacementValidity.OutOfBounds else placementValidity,
                onConfirm = {
                    if (isGf) {
                        viewModel.batchPlaceBuilding(goldFingerState)
                        goldFingerState = GoldFingerState()
                    } else if (placementValidity == GridSnapHelper.PlacementValidity.Valid) {
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
                    if (isGf) goldFingerState = GoldFingerState()
                    else {
                        isPlacingBuilding = false
                        placingBuildingName = ""
                    }
                }
            )
        }

        // 移动模式确认按钮 + 拆除按钮
        if (movingBuilding != null) {
            val moveScope = rememberCoroutineScope()
            PlacementConfirmButtons(
                snappedGridX = movingSnappedGridX,
                snappedGridY = movingSnappedGridY,
                buildingSize = movingBuildingSize,
                cameraState = cameraState,
                tileSize = tileSize,
                validity = movingValid,
                onConfirm = {
                    val b = movingBuilding
                    if (b != null &&
                        movingValid == GridSnapHelper.PlacementValidity.Valid &&
                        (movingSnappedGridX != b.gridX || movingSnappedGridY != b.gridY)
                    ) {
                        moveScope.launch {
                            viewModel.moveBuilding(b.instanceId, movingSnappedGridX, movingSnappedGridY)
                            // 同步更新空间索引，避免 LaunchedEffect 异步重建前
                            // 第二次长按读到旧坐标导致建筑跳回原位置
                            // 注：add 须传同一 spriteSizes，否则移动中的建筑丢失精灵扩展命中
                            buildingIndex.remove(b.instanceId)
                            buildingIndex.add(
                                b.copy(gridX = movingSnappedGridX, gridY = movingSnappedGridY),
                                buildingSpriteSizes
                            )
                            movingBuilding = null
                        }
                    } else {
                        movingBuilding = null
                    }
                },
                onCancel = { movingBuilding = null }
            )

            val building = checkNotNull(movingBuilding) { "DemolishButton rendered with null building" }
            DemolishButton(
                building = building,
                snappedGridX = movingSnappedGridX,
                snappedGridY = movingSnappedGridY,
                buildingSize = movingBuildingSize,
                cameraState = cameraState,
                tileSize = tileSize,
                onDemolish = {
                    viewModel.demolishBuilding(building.instanceId)
                    movingBuilding = null
                }
            )
        }

        // 宗门地图边缘装饰 — 在世界边界外绘制古风卷轴边缘渐变
        // 位于地图之上、UI 元素之下，对两渲染后端透明
        SectMapEdgeOverlay(
            cameraState = cameraState,
            worldPixelWidth = worldPixelWidth,
            worldPixelHeight = worldPixelHeight
        )

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
                    val currentSectLevel = viewModel.playerSectLevel.collectAsStateWithLifecycle().value
                    val showRewardBadge = viewModel.sectLevelRewardClaimable.collectAsStateWithLifecycle().value
                    SectInfoCard(
                        sectName = gameData?.sectName ?: "青云宗",
                        gameYear = gameData?.gameYear ?: 1,
                        gameMonth = gameData?.gameMonth ?: 1,
                        gamePhase = gameData?.gamePhase ?: 0,
                        lowStones = gameData?.spiritStones ?: 0L,
                        midStones = gameData?.midGradeSpiritStones ?: 0L,
                        highStones = gameData?.highGradeSpiritStones ?: 0L,
                        discipleCount = aliveDisciples.value.size,
                        combatPower = sectCombatPower,
                        sectLevel = currentSectLevel,
                        showRewardBadge = showRewardBadge,
                        onSectIconClick = { viewModel.navigateToSectLevelDetail() },
                        onSectNameClick = { viewModel.navigateToDialog(DialogType.RenameSect) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 隐藏 UI 按钮与玉符货币栏同行（玉符栏位于隐藏按钮正右侧，
                    // 不再与外层 Row 垂直居中、与暂停按钮同列中部）
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HideUiToggleButton(
                            isUiVisible = isUiVisible,
                            onToggle = { isUiVisible = !isUiVisible },
                            modifier = Modifier.size(28.dp)
                        )
                        if (isUiVisible) {
                            Spacer(modifier = Modifier.width(8.dp))
                            // 玉符货币栏（半透明胶囊条 + 图标 + 数量，点击弹说明对话框）
                            JadeSymbolBadge(
                                jadeSymbols = gameData?.jadeSymbols ?: 0,
                                onClick = { viewModel.navigateToDialog(DialogType.JadeSymbol) }
                            )
                        }
                    }
                    // 暂停/继续按钮（根据 isPaused 切换精灵图）
                    val isPaused by saveLoadViewModel.isPaused.collectAsStateWithLifecycle()
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickableWithSound { saveLoadViewModel.togglePause() },
                        contentAlignment = Alignment.Center
                    ) {
                        SpriteImage(
                            name = if (isPaused) "ui_play_button" else "ui_pause_button",
                            contentDescription = if (isPaused) "继续" else "暂停",
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }
                }
            }

            // 仅 UI 可见时显示侧边按钮
            if (isUiVisible) {
                LeftSideButtons(
                    viewModel = viewModel,
                    modifier = Modifier.align(Alignment.CenterStart)
                )

                // 消息栏系统 — 左下角（建造栏展开时自然遮挡消息栏）
                val gameEventRecords by viewModel.gameEventRecords.collectAsStateWithLifecycle()
                MessageBarHost(
                    events = gameEventRecords,
                    isUiVisible = isUiVisible,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 32.dp, bottom = 16.dp)
                )

                GameActionButtons(
                    viewModel = viewModel,
                    buildingBarExpanded = buildingBarExpanded,
                    onToggleBuildingBar = {
                        buildingBarExpanded = !buildingBarExpanded
                        isPlacingBuilding = false
                        movingBuilding = null
                        goldFingerState = GoldFingerState()
                        isDemolishMode = false
                        demolishSelectedIds = emptySet()
                    },
                    onCancelPlacement = {
                        isPlacingBuilding = false
                        movingBuilding = null
                        isDemolishMode = false
                        demolishSelectedIds = emptySet()
                        // 防御性补齐：退出放置时清金手指（toggle 已重置，此处兜底）
                        goldFingerState = GoldFingerState()
                    },
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }

        // 建造栏 — 开关式，展开时显示；拆除模式按钮位于建造栏外部上方最右侧（间距 2dp）
        if (buildingBarExpanded && isUiVisible) {
            val currentSectLevel by viewModel.playerSectLevel.collectAsStateWithLifecycle()
            val constructionBarList = remember {
                buildingList // BuildingRegistry.constructible now includes intermediate buildings
            }
            val buildingCosts = remember {
                constructionBarList.associate { (name, _) -> name to viewModel.getBuildingCost(name) }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                // 按钮行：建造栏外部上方最右侧；拆除模式显示 取消+确认，否则显示 一键拆除
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    if (isDemolishMode) {
                        GameButton(
                            text = "取消拆除",
                            onClick = {
                                isDemolishMode = false
                                demolishSelectedIds = emptySet()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        GameButton(
                            text = "确认拆除",
                            enabled = demolishSelectedIds.isNotEmpty(),
                            onClick = {
                                viewModel.demolishBuildings(demolishSelectedIds.toList())
                                isDemolishMode = false
                                demolishSelectedIds = emptySet()
                            }
                        )
                    } else {
                        GameButton(
                            text = "一键拆除",
                            onClick = {
                                isDemolishMode = true
                                demolishSelectedIds = emptySet()
                                isPlacingBuilding = false
                                placingBuildingName = ""
                                movingBuilding = null
                                goldFingerState = GoldFingerState()
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp)) // 与建造栏距离 2dp
                BuildingConstructionBar(
                    buildingList = constructionBarList,
                    placedBuildings = activeSectBuildings,
                    buildingCosts = buildingCosts,
                    spiritStones = gameData.spiritStones,
                    currentSectLevel = currentSectLevel,
                    onSelectBuildingLevelRequirement = { name ->
                        viewModel.navigateToDialog(DialogType.BuildingSectLevelRequirement(name))
                    },
                    onSelectBuilding = { name ->
                        // 拆除模式下点击建造卡片不进入放置模式
                        if (!isDemolishMode) {
                            val size = buildingSizes[name] ?: GridSnapHelper.BuildingSize(2, 3)
                            isPlacingBuilding = true
                            placingBuildingName = name
                            placingBuildingSize = size
                            placingWorldX = cameraState.screenToWorldX(screenWidthPx / 2f) - size.width * tileSize / 2f
                            placingWorldY = cameraState.screenToWorldY(screenHeightPx / 2f) - size.height * tileSize / 2f
                            placingSnappedGridX = GridSnapHelper.worldToGrid(placingWorldX, tileSize)
                            placingSnappedGridY = GridSnapHelper.worldToGrid(placingWorldY, tileSize)
                            placementValidity = gridSystem.validatePlacement(
                                placingSnappedGridX, placingSnappedGridY,
                                size.width, size.height
                            )
                        }
                    },
                    getBuildingMaxCount = { name ->
                        when {
                            BuildingFeatureRegistry.isResidence(name) || BuildingFeatureRegistry.hasNoLimit(name) -> Int.MAX_VALUE
                            else -> 1
                        }
                    },
                    getBuildingCount = { name ->
                        if (BuildingFeatureRegistry.isGloballyUnique(name)) {
                            gameData.placedBuildings.count { it.displayName == name }
                        } else {
                            activeSectBuildings.count { it.displayName == name }
                        }
                    }
                )
            }
        }

        // Dialog overlay — extracted to GameOverlayHost
        GameOverlayHost(
            vms = OverlayViewModels(
                game = viewModel,
                saveLoad = saveLoadViewModel,
                production = productionViewModel,
                alchemy = alchemyViewModel,
                forge = forgeViewModel,
                herbGarden = herbGardenViewModel,
                spiritMine = spiritMineViewModel,
                patrolTower = patrolTowerViewModel,
                bloodRefining = bloodRefiningViewModel,
                worldMapInteraction = worldMapInteractionViewModel,
                worldMapGarrison = worldMapGarrisonViewModel,
                battle = battleViewModel
            ),
            callbacks = OverlayCallbacks(
                onLogout = onLogout,
                onRestartGame = onRestartGame
            )
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

/**
 * 构建建筑数据数组，供 NativeBridge.drawAllTiles 使用。
 * 格式：[gridX, gridY, spriteWidth, spriteHeight, nameIndex] × buildingCount
 *
 * ## 排序策略：Y-sorting（Painter's Algorithm）
 * 按 gridY 升序排列，使下方（高Y）的建筑最后绘制、覆盖上方（低Y）的建筑。
 * 这是 2D 俯视/斜视角地图渲染的行业标准做法，Godot YSort、Unity Custom Sort Axis、
 * Bevy extol_sprite_layer、RimWorld 等均采用此策略。
 *
 * 注意：数组中传递的是精灵视觉比例尺寸（可能大于占地尺寸），
 * 渲染器如需占地尺寸（如地砖选择），通过 SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX 查找。
 * 调用方须传入已排除移动中建筑的建筑列表，避免原位残留精灵图。
 */

/** B1 渲染端未注册建筑名告警去重集合（仅首次警告，防日志刷屏） */
private val warnedUnregisteredBuildingNames = Collections.synchronizedSet(mutableSetOf<String>())

private const val BUILDING_TAP_TAG = "MainGameScreen"

internal fun buildBuildingDataArray(
    buildings: List<GridBuildingData>,
    spriteSizeMap: Map<String, GridSnapHelper.BuildingSize>
): FloatArray {
    // 按地面接触点(gridY + footprintHeight)升序排列：下方建筑最后绘制→覆盖上方建筑
    val sorted = buildings.sortedBy { it.gridY + it.height }
    val result = FloatArray(sorted.size * 5)
    for ((i, b) in sorted.withIndex()) {
        val idx = i * 5
        val sprite = spriteSizeMap[b.displayName]
        val sw = sprite?.width ?: b.width
        val sh = sprite?.height ?: b.height
        result[idx] = b.gridX.toFloat()
        result[idx + 1] = b.gridY.toFloat()
        result[idx + 2] = sw.toFloat()
        result[idx + 3] = sh.toFloat()
        val nameIndex = BUILDING_NAME_INDEX[b.displayName]
        if (nameIndex == null && warnedUnregisteredBuildingNames.add(b.displayName)) {
            // B1 诊断：displayName 未注册 → 用索引 0 精灵兜底画出（可见），但点击端 findBuildingAt
            // 命中后无任何分支处理（静默吞掉）——与 onTap 日志配套定位"可见但点不中"建筑
            DomainLog.w(BUILDING_TAP_TAG, "渲染建筑 displayName 未注册（索引0兜底）: name=${b.displayName} " +
                "sectId=${b.sectId} instanceId=${b.instanceId} grid=(${b.gridX},${b.gridY})")
        }
        result[idx + 4] = (nameIndex ?: 0).toFloat()
    }
    return result
}

// BUILDING_NAME_INDEX / BUILDING_UV_MAP 已移入 SectMapViewport.kt（P-7，同包 internal）

/** 灵田建筑显示名（与 buildBuildingDataArray 的灵田判定同源） */
private const val SPIRIT_FIELD_NAME = "灵田"

/** 灵田作物数据单条步长（[gx, gy, progress01]） */
private const val CROP_DATA_STRIDE = 3

/**
 * 构建灵田作物渲染数据（WP6）。
 *
 * 输入为已按 sectId 过滤的放置建筑列表与种植记录。仅灵田建筑
 * （displayName == [SPIRIT_FIELD_NAME]）且该田存在种植记录（seedId 非空、同宗门）时
 * 输出 [gx, gy, progress01] 三元组；progress01 = 游戏时间进度
 * （[TimeProgressUtil.calculateProgressFraction]，与生产结算同源）。
 * 无作物时返回 null（后端跳过作物层——渲染零开销）。
 *
 * 注意：与 [buildBuildingDataArray] 不同，本函数不做 Y 排序——作物与灵田建筑同格
 * 绘制（作物绘制在建筑层之后，灵田之间互相遮挡无意义），且数组索引与建筑数组
 * 无关联（后端按三元组独立解析、双端同数学）。
 *
 * @param buildings 已按 sectId 过滤的放置建筑列表
 * @param plants 全部种植记录（内部按 sectId 过滤）
 * @param currentYear 当前游戏年
 * @param currentMonth 当前游戏月
 * @param sectId 当前宗门 ID（跨宗门记录防御性跳过）
 */
internal fun buildSpiritCropData(
    buildings: List<GridBuildingData>,
    plants: List<SpiritFieldPlant>,
    currentYear: Int,
    currentMonth: Int,
    sectId: String
): FloatArray? {
    val plantByBuilding = HashMap<String, SpiritFieldPlant>()
    for (plant in plants) {
        // 跨宗门记录防御性跳过 + 未种植的田无作物（if 包裹避免 continue）
        if (plant.sectId == sectId && plant.seedId.isNotEmpty()) {
            plantByBuilding[plant.buildingInstanceId] = plant
        }
    }

    var count = 0
    val buffer = FloatArray(buildings.size * CROP_DATA_STRIDE)
    for (b in buildings) {
        val plant = plantByBuilding[b.instanceId]
        if (b.displayName == SPIRIT_FIELD_NAME && plant != null) {
            val progress = TimeProgressUtil.calculateProgressFraction(
                startYear = plant.plantYear,
                startMonth = plant.plantMonth,
                duration = plant.growTime,
                currentYear = currentYear,
                currentMonth = currentMonth
            )
            val idx = count * CROP_DATA_STRIDE
            buffer[idx] = b.gridX.toFloat()
            buffer[idx + 1] = b.gridY.toFloat()
            buffer[idx + 2] = progress
            count++
        }
    }
    // 单 return：无种植 → null；全部命中 → 原数组；部分命中 → 截断
    if (count == 0) return null
    return if (count == buildings.size) buffer else buffer.copyOf(count * CROP_DATA_STRIDE)
}



