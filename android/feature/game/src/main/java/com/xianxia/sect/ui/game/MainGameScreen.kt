@file:Suppress("TooManyFunctions") // 拆分聚合:提取的私有辅助函数集中在原文件,文件级复杂度为拆分代价
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalResources
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
import kotlinx.coroutines.CoroutineScope

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.core.util.RoadTiling
import com.xianxia.sect.ui.game.map.sect.SectCameraState
import com.xianxia.sect.ui.game.map.sect.rememberSectCamera
import com.xianxia.sect.core.util.GridSystem
import com.xianxia.sect.core.util.FixedSectGateway

import com.xianxia.sect.core.render.DemolishHighlightMark
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
import com.xianxia.sect.ui.game.main.AREA_DEFAULT_DIAMETER
import com.xianxia.sect.ui.game.main.AREA_MAX_DIAMETER
import com.xianxia.sect.ui.game.main.AREA_MIN_DIAMETER
import com.xianxia.sect.ui.game.main.AreaDiameterSlider
import com.xianxia.sect.ui.game.main.AreaSelectButton
import com.xianxia.sect.ui.game.main.DemolishButton
import com.xianxia.sect.ui.game.main.GoldFingerIcon
import com.xianxia.sect.ui.game.main.GoldFingerSelectionOverlay
import com.xianxia.sect.ui.game.main.HerbGardenAuraOverlay
import com.xianxia.sect.ui.game.main.HideUiToggleButton
import com.xianxia.sect.ui.game.main.JadeSymbolBadge
import com.xianxia.sect.ui.game.main.PlacementConfirmButtons
import com.xianxia.sect.ui.game.main.SectInfoCard
import com.xianxia.sect.ui.game.main.SectMapEdgeOverlay
import com.xianxia.sect.core.touch.SectMapTouchEngine
import com.xianxia.sect.core.touch.TouchEngineConfig
import com.xianxia.sect.core.touch.HitSlopPolicy
import com.xianxia.sect.core.animation.CameraAnimator
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity




/** 手势/渲染共用的诊断日志标签 */
internal const val BUILDING_TAP_TAG = "MainGameScreen"

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

// 一键拆除-区域选择模式常量见 AreaSelectControls.kt（internal，含进度条组件与按钮组件）

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
    val state = remember { MainGameScreenState() }
    val data = rememberMainGameScreenData(
        mapPreloadData = mapPreloadData, state = state, viewModel = viewModel,
        forceSoftwareRendering = forceSoftwareRendering,
        vulkanInitListener = vulkanInitListener
    )

    MainGameScreenEffects(state = state, data = data, viewModel = viewModel)
    MainGameScreenRenderEffects(
        state = state, data = data, viewModel = viewModel
    )
    MainGameScreenBackHandler(state = state)

    val preloadedItemSprites by saveLoadViewModel.preloadedItemSprites.collectAsStateWithLifecycle()
    val atlasResult by saveLoadViewModel.atlasResult.collectAsStateWithLifecycle()

    CompositionLocalProvider(
        LocalItemSpriteCache provides preloadedItemSprites,
        LocalAtlasCache provides atlasResult
    ) {
        MainGameScreenContent(
            state = state,
            data = data,
            viewModel = viewModel,
            saveLoadViewModel = saveLoadViewModel,
            vms = OverlayViewModels(
                game = viewModel, saveLoad = saveLoadViewModel,
                production = productionViewModel, alchemy = alchemyViewModel,
                forge = forgeViewModel, herbGarden = herbGardenViewModel,
                spiritMine = spiritMineViewModel,
                patrolTower = patrolTowerViewModel,
                bloodRefining = bloodRefiningViewModel,
                worldMapInteraction = worldMapInteractionViewModel,
                worldMapGarrison = worldMapGarrisonViewModel,
                battle = battleViewModel
            ),
            onLogout = onLogout,
            onRestartGame = onRestartGame
        )
    } // CompositionLocalProvider
}

/** MainGameScreen 编辑交互状态（MainGameScreen 拆分）：放置/移动/拆除/金手指模式状态 */
internal class MainGameScreenState {
    var screenWidthPx by mutableFloatStateOf(0f)
    var screenHeightPx by mutableFloatStateOf(0f)

    // 建筑放置状态
    var isPlacingBuilding by mutableStateOf(false)
    var placingBuildingName by mutableStateOf("")
    var placingWorldX by mutableFloatStateOf(0f)
    var placingWorldY by mutableFloatStateOf(0f)
    var buildingBarExpanded by mutableStateOf(false)
    var isUiVisible by mutableStateOf(true)

    // 一键拆除模式状态
    var isDemolishMode by mutableStateOf(false)
    var demolishSelectedIds by mutableStateOf<Set<String>>(emptySet())
    // 区域选择模式：进入时直径重置为默认值
    var isAreaSelectMode by mutableStateOf(false)
    var areaDiameter by mutableIntStateOf(AREA_DEFAULT_DIAMETER)

    // 建筑移动状态（长按拖动）
    var movingBuilding by mutableStateOf<GridBuildingData?>(null)
    var movingWorldX by mutableFloatStateOf(0f)
    var movingWorldY by mutableFloatStateOf(0f)
    var movingSnappedGridX by mutableIntStateOf(0)
    var movingSnappedGridY by mutableIntStateOf(0)
    var movingValid by mutableStateOf<GridSnapHelper.PlacementValidity>(
        GridSnapHelper.PlacementValidity.Valid
    )

    // 当前放置建筑的尺寸 / 吸附后的网格坐标 / 放置合法性
    var placingBuildingSize by mutableStateOf(GridSnapHelper.BuildingSize(2, 3))
    var placingSnappedGridX by mutableIntStateOf(0)
    var placingSnappedGridY by mutableIntStateOf(0)
    var placementValidity by mutableStateOf<GridSnapHelper.PlacementValidity>(
        GridSnapHelper.PlacementValidity.Valid
    )

    // 金手指批量建造状态
    var goldFingerState by mutableStateOf(GoldFingerState())

    var nativeSurfaceView by mutableStateOf<NativeSurfaceView?>(null)

    // 普通点击选中格（WP3 选中高亮）：点击建筑时记录其格坐标，点击空地清除。
    // 渲染端经 findBuildingIndex 转换为建筑索引（双后端共用同一命中几何）
    var selectedBuildingGrid by mutableStateOf<Pair<Int, Int>?>(null)

    /** 退出全部编辑模式（切 Tab/开对话框/取消放置共用） */
    fun exitAllEditModes() {
        isPlacingBuilding = false
        movingBuilding = null
        goldFingerState = GoldFingerState()
        isDemolishMode = false
        isAreaSelectMode = false
        demolishSelectedIds = emptySet()
    }

    /** 进入一键拆除模式（一键拆除按钮复用） */
    fun enterDemolishMode() {
        isDemolishMode = true
        isAreaSelectMode = false
        demolishSelectedIds = emptySet()
        isPlacingBuilding = false
        placingBuildingName = ""
        movingBuilding = null
        goldFingerState = GoldFingerState()
    }
}

/** MainGameScreen 派生状态（MainGameScreen 拆分）：derivedStateOf 稳定实例（remember 单例，触控回调可读当前值） */
internal class MainGameScreenDerived(
    private val state: MainGameScreenState,
    private val gameDataState: State<GameData>,
    private val disciplesState: State<List<DiscipleAggregate>>
) {
    val gameData: GameData get() = gameDataState.value
    val aliveDisciples by derivedStateOf { disciplesState.value.filter { it.isAlive } }
    // 移动中临时从网格排除正在移动的建筑，避免自身重叠检测
    // 2026-08-16 修复：建筑作用域必须与渲染总线（GameViewModel：gameEngine.gameData 原始
    // StateFlow）同源——activeSectId 与 placedBuildings 都从同一份 gameData 快照读取。
    // 此前 activeSectId 读 gameDataUi、placedBuildings 读 placedBuildings（两条
    // flowOn(Default)+stateIn(WhileSubscribed) 异步管线），enterSect 切换宗门后
    // 点击索引/瓦片标记/渲染帧与总线存在作用域分叉窗口：进入被占宗门 → 总线已切新作用域
    //（或被净化回 ""）而索引仍按旧作用域 → 主宗建筑被渲染出来但点不中、新建建筑叠在旧建筑上。
    val activeSectBuildings by derivedStateOf {
        val gd = gameDataState.value
        buildingsInSectScope(gd.placedBuildings, gd.activeSectId)
    }
    val effectivePlacedBuildings by derivedStateOf {
        val mb = state.movingBuilding
        if (mb != null) activeSectBuildings.filter { it.instanceId != mb.instanceId }
        else activeSectBuildings
    }
    val movingBuildingSize by derivedStateOf {
        state.movingBuilding?.let { GridSnapHelper.BuildingSize(it.width, it.height) }
            ?: GridSnapHelper.BuildingSize(2, 3)
    }
}

/** MainGameScreen 地图静态数据（MainGameScreen 拆分）：尺寸 + 建筑尺寸映射 + 建造列表 */
internal data class MainGameScreenMapData(
    val tileSize: Int,
    val worldPixelWidth: Int,
    val worldPixelHeight: Int,
    val worldWidthCells: Int,
    val worldHeightCells: Int,
    val buildingSizes: Map<String, GridSnapHelper.BuildingSize>,
    val buildingSpriteSizes: Map<String, GridSnapHelper.BuildingSize>,
    val buildingList: List<Pair<String, (GridBuildingData?) -> Unit>>
)

/** MainGameScreen 瓦片/数组数据（MainGameScreen 拆分）：渲染数据源 */
private data class MainGameScreenMapTiles(
    val flatTileData: IntArray,
    val buildingDataArray: FloatArray?,
    val spiritCropData: FloatArray?,
    val demolishHighlightData: ByteArray?,
    val roadData: IntArray?
)

/** MainGameScreen 渲染数据（MainGameScreen 拆分）：索引/网格/精灵/配置 */
internal data class MainGameScreenRenderData(
    val goldenFingerBmp: ImageBitmap?,
    val flatTileData: IntArray,
    val gridSystem: GridSystem,
    val buildingIndex: BuildingSpatialIndex,
    val nativeConfig: NativeRenderConfig,
    val buildingDataArray: FloatArray?,
    val spiritCropData: FloatArray?,
    val demolishHighlightData: ByteArray?,
    val roadData: IntArray?
)

/** MainGameScreen 视口数据（MainGameScreen 拆分）：相机/预览/渲染参数 */
internal data class MainGameScreenViewportData(
    val viewportParams: SectMapViewportParams,
    val previewState: MapPreviewState,
    val cameraState: SectCameraState,
    val touchScope: CoroutineScope,
    val cameraAnimator: CameraAnimator,
    val cancelCameraAnim: () -> Unit
)

/** MainGameScreen 聚合数据（MainGameScreen 拆分）：派生/静态/渲染/视口 + 触控引擎 */
internal class MainGameScreenData(
    val derived: MainGameScreenDerived,
    val mapData: MainGameScreenMapData,
    val renderData: MainGameScreenRenderData,
    val viewportData: MainGameScreenViewportData,
    val touchEngine: SectMapTouchEngine
)

/** MainGameScreen 派生状态计算（MainGameScreen 拆分）：StateFlow 收集 + 稳定派生实例 */
@Composable
private fun rememberMainGameScreenDerived(
    state: MainGameScreenState,
    viewModel: GameViewModel
): MainGameScreenDerived {
    // 2026-08-16 修复：建筑作用域必须与渲染总线同源——总线读 gameEngine.gameData（原始
    // StateFlow），此处也必须读 gameData（原始），不得走 gameDataUi/placedBuildings 的
    // flowOn(Default)+stateIn(WhileSubscribed) 异步管线，否则 enterSect 切换后渲染与
    // 点击索引作用域分叉（进入被占宗门显示主宗建筑但点不中）。
    val gameDataState = viewModel.gameData.collectAsStateWithLifecycle()
    val disciplesState = viewModel.discipleAggregates.collectAsStateWithLifecycle()
    return remember {
        MainGameScreenDerived(state, gameDataState, disciplesState)
    }
}

/** 建造列表构建（MainGameScreen 拆分）：建筑 key → 对话框导航回调 */
// 拆分搬移:分支结构与原函数一致
@Suppress("CyclomaticComplexMethod")
private fun buildMainGameScreenBuildingList(
    viewModel: GameViewModel
): List<Pair<String, (GridBuildingData?) -> Unit>> {
    val buildings = BuildingFeatureRegistry.constructible.map { def ->
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
    // 石板道路（建造栏目标，铺设走 RoadFacade 自动拼接）
    return buildings + (GameConfig.Road.DISPLAY_NAME to { _ -> Unit })
}

/** MainGameScreen 地图静态数据计算（MainGameScreen 拆分） */
@Composable
private fun rememberMainGameScreenMapData(
    mapPreloadData: MapPreloadData,
    viewModel: GameViewModel
): MainGameScreenMapData {
    // 建筑尺寸映射 — 从配置读取，在宗门地图中所占的格数 (宽 × 高)
    val buildingSizes = remember {
        BuildingFeatureRegistry.all.associate { def ->
            val (w, h) = viewModel.getBuildingGridSize(def.displayName)
            def.displayName to GridSnapHelper.BuildingSize(w, h)
        } + (GameConfig.Road.DISPLAY_NAME to GridSnapHelper.BuildingSize(1, 1))
    }
    // 建筑精灵比例尺寸映射 — 用于渲染视觉大小（可能大于占地尺寸）
    val buildingSpriteSizes = remember {
        BuildingFeatureRegistry.all.associate { def ->
            val (sw, sh) = viewModel.getBuildingSpriteSize(def.displayName)
            def.displayName to GridSnapHelper.BuildingSize(sw, sh)
        } + (GameConfig.Road.DISPLAY_NAME to GridSnapHelper.BuildingSize(1, 1))
    }
    // 建筑列表及点击回调
    val buildingList = remember {
        buildMainGameScreenBuildingList(viewModel)
    }
    return MainGameScreenMapData(
        tileSize = mapPreloadData.tileSize,
        worldPixelWidth = mapPreloadData.worldPixelWidth,
        worldPixelHeight = mapPreloadData.worldPixelHeight,
        worldWidthCells = mapPreloadData.worldWidthCells,
        worldHeightCells = mapPreloadData.worldHeightCells,
        buildingSizes = buildingSizes,
        buildingSpriteSizes = buildingSpriteSizes,
        buildingList = buildingList
    )
}

/** MainGameScreen 瓦片/数组数据计算（MainGameScreen 拆分） */
@Composable
private fun rememberMainGameScreenMapTiles(
    mapPreloadData: MapPreloadData,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    state: MainGameScreenState,
    viewModel: GameViewModel
): MainGameScreenMapTiles {
    val gameData by viewModel.gameDataUi.collectAsStateWithLifecycle()

    // 瓦片数据（含建筑占位标记）：装饰物类型 + 建筑占用 → 统一 tileData
    val rawTileData = mapPreloadData.rawTileData
    val tileData = remember(rawTileData, derived.effectivePlacedBuildings) {
        val data = Array(rawTileData.size) { rawTileData[it].copyOf() }
        for (b in derived.effectivePlacedBuildings) {
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
    // flatTileData — 由 tileData 派生，建筑占位变化时自动重算
    val flatTileData = remember(tileData) {
        tileData.flatMap { it.toList() }.toIntArray()
    }
    // ★ 缓存 buildingData：固定结构（门楼/阶梯）追加尾部 ⇒ 建筑层恒最后绘制置顶
    val buildingDataArray = remember(derived.effectivePlacedBuildings, mapData.buildingSpriteSizes) {
        val real = if (derived.effectivePlacedBuildings.isNotEmpty())
            buildBuildingDataArray(derived.effectivePlacedBuildings, mapData.buildingSpriteSizes)
        else FloatArray(0)
        real + FixedSectGateway.renderEntries()
    }
    // ★ 灵田作物数据（WP6）：灵田建筑 ↔ 种植记录按 buildingInstanceId 映射，
    // progress01 = 游戏时间进度（TimeProgressUtil，与生产结算同源）；低频变化走帧率门控 RenderFrame
    val spiritCropData = remember {
        derivedStateOf {
            buildSpiritCropData(
                buildings = derived.effectivePlacedBuildings,
                plants = gameData.spiritFieldPlants,
                currentYear = gameData.gameYear,
                currentMonth = gameData.gameMonth,
                sectId = gameData.activeSectId
            )
        }
    }
    // ★ 拆除模式高亮标记：与 buildingDataArray 同源同序（effectivePlacedBuildings，
    // 拆除模式下 movingBuilding=null 两者内容一致）；null = 非拆除模式，双后端跳过整层
    val demolishHighlightData = remember {
        derivedStateOf {
            if (!state.isDemolishMode) null
            else buildDemolishHighlightData(derived.effectivePlacedBuildings, state.demolishSelectedIds)
        }
    }
    // ★ 石板道路数据（每格邻接位掩码，供双后端合成道路主体/边缘/转角/十字装饰）
    val roadData = remember(gameData.roads) {
        RoadTiling.buildRoadMaskArray(
            roads = gameData.roads,
            cols = mapPreloadData.worldWidthCells,
            rows = mapPreloadData.worldHeightCells
        )
    }
    return MainGameScreenMapTiles(
        flatTileData = flatTileData,
        buildingDataArray = buildingDataArray,
        spiritCropData = spiritCropData.value,
        demolishHighlightData = demolishHighlightData.value,
        roadData = roadData
    )
}

/** MainGameScreen 渲染数据计算（MainGameScreen 拆分）：精灵位图 + 索引 + 渲染配置 */
@Composable
private fun rememberMainGameScreenRenderData(
    mapData: MainGameScreenMapData,
    tiles: MainGameScreenMapTiles
): MainGameScreenRenderData {
    // D-39：LocalResources 替代 context.resources（配置变化时正确更新）
    val resources = LocalResources.current

    // 金手指图标位图
    val goldenFingerBmp = remember {
        val resId = SpriteResRegistry.resolve("golden_finger")
        if (resId != null) {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 1
            }
            android.graphics.BitmapFactory.decodeResource(resources, resId, opts)
                ?.asImageBitmap()
        } else null
    }

    // 网格系统（管理建筑放置与占用格查询）
    val gridSystem = remember(mapData.tileSize, mapData.worldWidthCells, mapData.worldHeightCells) {
        GridSystem(mapData.tileSize, mapData.worldWidthCells, mapData.worldHeightCells,
            buildableBorder = GameConfig.SectMap.BORDER_TREE_RING,
            blockedCells = FixedSectGateway.blockedCells)
    }

    // 空间索引 — O(1) 触控检测，替代 O(n) 线性查找
    // 2026-08-06 修复：传入精灵视觉尺寸，命中区域扩展为占地 ∪ 精灵包围盒，
    // 高层建筑（塔楼/藏经阁等）悬空上半身可点击
    val buildingIndex = remember { BuildingSpatialIndex() }

    // 宗门大地图层（Vulkan 原生渲染）
    // v4.0.43+ 架构：替换 Compose Canvas 为 Vulkan 原生渲染管线，
    // 实现 GPU 批处理（3 draw calls/帧）、独立渲染线程、VSYNC 对齐。
    // 参见: docs/map-rendering-architecture.md
    val nativeConfig = remember(mapData.tileSize) {
        NativeRenderConfig(
            tileSize = mapData.tileSize,
            worldWidthCells = mapData.worldWidthCells,
            worldHeightCells = mapData.worldHeightCells,
            worldPixelWidth = mapData.worldPixelWidth,
            worldPixelHeight = mapData.worldPixelHeight
        )
    }

    return MainGameScreenRenderData(
        goldenFingerBmp = goldenFingerBmp,
        flatTileData = tiles.flatTileData,
        gridSystem = gridSystem,
        buildingIndex = buildingIndex,
        nativeConfig = nativeConfig,
        buildingDataArray = tiles.buildingDataArray,
        spiritCropData = tiles.spiritCropData,
        demolishHighlightData = tiles.demolishHighlightData,
        roadData = tiles.roadData
    )
}

/** MainGameScreen 视口数据计算（MainGameScreen 拆分）：相机/预览/渲染参数 */
@Composable
private fun rememberMainGameScreenViewportData(
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    state: MainGameScreenState,
    viewModel: GameViewModel,
    forceSoftwareRendering: Boolean,
    vulkanInitListener: NativeSurfaceView.VulkanInitListener?
): MainGameScreenViewportData {
    // 统一相机 — 相机在世界空间中移动，screenX = worldX - cameraX
    val cameraState = rememberSectCamera(
        worldWidth = mapData.worldPixelWidth.toFloat(), worldHeight = mapData.worldPixelHeight.toFloat(),
        worldWidthCells = mapData.worldWidthCells
    )
    val touchScope = rememberCoroutineScope()
    val cameraAnimator = remember(cameraState, touchScope) {
        CameraAnimator(cameraState, touchScope)
    }
    // 用户交互时取消动画
    val cancelCameraAnim: () -> Unit = { cameraAnimator.cancel() }
    // P-7：地图视口抽离为 SectMapViewport（参数稳定引用——每旬 gameData 变化不触发
    // AndroidView update；相机/预览/建筑实际变化才重组）
    val viewportParams = remember {
        derivedStateOf {
            SectMapViewportParams(
                nativeConfig = renderData.nativeConfig, cameraState = cameraState,
                flatTileData = renderData.flatTileData, buildingDataArray = renderData.buildingDataArray,
                buildingCount = derived.effectivePlacedBuildings.size + FixedSectGateway.count,
                tileSize = mapData.tileSize, worldWidthCells = mapData.worldWidthCells,
                worldHeightCells = mapData.worldHeightCells,
                forceSoftwareRendering = forceSoftwareRendering, vulkanInitListener = vulkanInitListener,
                surfaceProviderFactory = viewModel.getSurfaceProviderFactory(), gpuTier = viewModel.getGpuTier(),
                buildingSpriteSizes = mapData.buildingSpriteSizes, selectedGrid = state.selectedBuildingGrid,
                spiritCropData = renderData.spiritCropData, demolishHighlightData = renderData.demolishHighlightData,
                roadData = renderData.roadData,
                gridOverlayVisible = state.isPlacingBuilding || state.movingBuilding != null,
                alphaProvider = { viewModel.gameEngineCore.currentAlpha }
            )
        }
    }
    val previewState = remember {
        derivedStateOf {
            MapPreviewState(
                isPlacingBuilding = state.isPlacingBuilding,
                placingBuildingName = state.placingBuildingName,
                placingWorldX = state.placingWorldX,
                placingWorldY = state.placingWorldY,
                placingBuildingSize = state.placingBuildingSize,
                placementValidity = state.placementValidity,
                movingBuilding = state.movingBuilding,
                movingWorldX = state.movingWorldX,
                movingWorldY = state.movingWorldY,
                movingBuildingSize = derived.movingBuildingSize,
                movingValid = state.movingValid
            )
        }
    }
    return MainGameScreenViewportData(
        viewportParams = viewportParams.value, previewState = previewState.value,
        cameraState = cameraState, touchScope = touchScope,
        cameraAnimator = cameraAnimator, cancelCameraAnim = cancelCameraAnim
    )
}

/** MainGameScreen 触控引擎（MainGameScreen 拆分）：跨平台手势引擎 */
@Suppress("LongParameterList")
@Composable
private fun rememberMainGameScreenTouchEngine(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    viewModel: GameViewModel,
    touchConfig: TouchEngineConfig,
    hitSlopPolicy: HitSlopPolicy
): SectMapTouchEngine {
    val cameraState = viewportData.cameraState
    val buildingIndex = renderData.buildingIndex
    val gridSystem = renderData.gridSystem
    return remember(cameraState, buildingIndex, gridSystem, touchConfig, hitSlopPolicy) {
        SectMapTouchEngine(
            callbacks = buildMainGameScreenTouchCallbacks(
                state = state, derived = derived, mapData = mapData,
                renderData = renderData, viewportData = viewportData,
                viewModel = viewModel, config = touchConfig, hitSlopPolicy = hitSlopPolicy
            ),
            scope = viewportData.touchScope,
            config = touchConfig
        )
    }
}

/** MainGameScreen 聚合数据计算（MainGameScreen 拆分） */
@Composable
private fun rememberMainGameScreenData(
    mapPreloadData: MapPreloadData,
    state: MainGameScreenState,
    viewModel: GameViewModel,
    forceSoftwareRendering: Boolean,
    vulkanInitListener: NativeSurfaceView.VulkanInitListener?
): MainGameScreenData {
    val derived = rememberMainGameScreenDerived(state = state, viewModel = viewModel)
    val mapData = rememberMainGameScreenMapData(mapPreloadData = mapPreloadData, viewModel = viewModel)
    val tiles = rememberMainGameScreenMapTiles(
        mapPreloadData = mapPreloadData, derived = derived, mapData = mapData,
        state = state, viewModel = viewModel
    )
    val renderData = rememberMainGameScreenRenderData(mapData = mapData, tiles = tiles)
    val viewportData = rememberMainGameScreenViewportData(
        derived = derived, mapData = mapData, renderData = renderData, state = state,
        viewModel = viewModel, forceSoftwareRendering = forceSoftwareRendering,
        vulkanInitListener = vulkanInitListener
    )
    // 手势配置 + 命中外扩策略（density 按设备注入，跨设备触控目标一致）
    val density = LocalDensity.current.density
    val touchConfig = remember { TouchEngineConfig() }
    val hitSlopPolicy = remember(touchConfig, density) {
        HitSlopPolicy(minHitTargetDp = touchConfig.minHitTargetDp, density = density)
    }
    val touchEngine = rememberMainGameScreenTouchEngine(
        state = state, derived = derived, mapData = mapData, renderData = renderData,
        viewportData = viewportData, viewModel = viewModel,
        touchConfig = touchConfig, hitSlopPolicy = hitSlopPolicy
    )
    return MainGameScreenData(
        derived = derived, mapData = mapData, renderData = renderData,
        viewportData = viewportData, touchEngine = touchEngine
    )
}

/** MainGameScreen 数据副作用（MainGameScreen 拆分）：导航/对话框重置/排行榜/相机视口 */
@Composable
private fun MainGameScreenEffects(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel
) {
    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { route ->
            viewModel.navigateToDialog(route.toDialogType())
        }
    }

    LaunchedEffect(Unit) {
        viewModel.currentDialogType.collect { route ->
            if (route !is DialogType.None) {
                state.exitAllEditModes()
                state.buildingBarExpanded = false
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
    LaunchedEffect(state.screenWidthPx, state.screenHeightPx) {
        if (state.screenWidthPx > 0 && state.screenHeightPx > 0) {
            data.viewportData.cameraState.updateViewport(state.screenWidthPx.toInt(), state.screenHeightPx.toInt())
            data.viewportData.cameraState.tryCenterOn(
                data.mapData.worldPixelWidth / 2f, data.mapData.worldPixelHeight / 2f
            )
        }
    }

    val isGameOver by viewModel.isGameOver.collectAsStateWithLifecycle()

    LaunchedEffect(isGameOver) {
        if (isGameOver) {
            viewModel.openGameOverDialog()
        }
    }
}

/** MainGameScreen 渲染副作用（MainGameScreen 拆分）：索引重建 + 表面接线 */
@Composable
private fun MainGameScreenRenderEffects(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel
) {
    // D-12（2026-08-06）：movingBuilding 状态单点同步到渲染总线排除通道——
    // 总线不感知 Compose 局部 movingBuilding，不排除会导致拖拽窗口期该建筑
    // 仍在旧位置渲染（双渲染）+ 点不中 + 其格子可叠建（绿色）
    LaunchedEffect(state.movingBuilding) {
        viewModel.setMovingBuildingInstanceId(state.movingBuilding?.instanceId)
    }

    // ★ 预览快通道清理（2026-08-30 触控优化）：编辑模式退出（放置确认/取消/移动确认/
    // 切 Tab）时回落 Compose 门控帧——不在拖拽结束（onBuildingDragEnd）时清理，
    // 避免 33ms 帧率门控窗口内预览位置回跳
    LaunchedEffect(state.isPlacingBuilding, state.movingBuilding) {
        if (!state.isPlacingBuilding && state.movingBuilding == null) {
            state.nativeSurfaceView?.fastPreviewChannel?.set(null)
        }
    }

    LaunchedEffect(data.derived.effectivePlacedBuildings) {
        data.renderData.gridSystem.rebuildFrom(data.derived.effectivePlacedBuildings)
    }

    LaunchedEffect(data.derived.effectivePlacedBuildings) {
        data.renderData.buildingIndex.rebuild(data.derived.effectivePlacedBuildings, data.mapData.buildingSpriteSizes)
    }

    // 挂载 touchEngine 到 NativeSurfaceView
    LaunchedEffect(state.nativeSurfaceView) {
        state.nativeSurfaceView?.touchEngine = data.touchEngine
    }

    // 将引擎渲染帧率（热控+场景+性能模式综合）接入 NativeSurfaceView
    LaunchedEffect(state.nativeSurfaceView) {
        val view = state.nativeSurfaceView ?: return@LaunchedEffect
        viewModel.renderFrameRate.collect { fps ->
            view.targetFps = fps
        }
    }

    // 接通渲染质量/装饰降级流（热控 + 节能模式低画质真实生效）。
    // 经 NativeSurfaceView 转发属性写入——backend 未创建时先存值、
    // 创建后立即应用，防初始发射丢失。
    LaunchedEffect(state.nativeSurfaceView) {
        val view = state.nativeSurfaceView ?: return@LaunchedEffect
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
    LaunchedEffect(state.nativeSurfaceView) {
        state.nativeSurfaceView?.onObservedFps = { fps ->
            viewModel.gameEngineCore.setObservedRenderFps(fps)
        }
    }

    // 设置动画器引用，使 tryCenterOn 使用平滑动画
    LaunchedEffect(data.viewportData.cameraAnimator) {
        data.viewportData.cameraState.setAnimator(data.viewportData.cameraAnimator)
    }
}

/** 编辑模式返回键取消（MainGameScreen 拆分）：移动/金手指/拆除模式下按返回键取消 */
@Composable
private fun MainGameScreenBackHandler(state: MainGameScreenState) {
    // 移动模式/金手指/拆除模式下按返回键取消
    BackHandler(
        enabled = state.movingBuilding != null || state.goldFingerState.isActive || state.isDemolishMode
    ) {
        when {
            state.goldFingerState.isActive -> state.goldFingerState = GoldFingerState()
            state.isDemolishMode -> {
                state.isDemolishMode = false
                state.isAreaSelectMode = false
                state.demolishSelectedIds = emptySet()
            }
            else -> state.movingBuilding = null
        }
    }
}

/** MainGameScreen 主体内容（MainGameScreen 拆分）：地图视口 + 覆盖层 + UI 覆盖层 */
@Composable
private fun MainGameScreenContent(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    vms: OverlayViewModels,
    onLogout: () -> Unit,
    onRestartGame: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .onSizeChanged { size ->
                state.screenWidthPx = size.width.toFloat()
                state.screenHeightPx = size.height.toFloat()
            }
    ) {
        SectMapViewport(
            params = data.viewportData.viewportParams,
            preview = data.viewportData.previewState,
            commandBus = viewModel.getRenderCommandBus(),
            onViewCreated = { view -> state.nativeSurfaceView = view }
        )

        MainGameScreenMapOverlays(
            state = state,
            data = data,
            viewModel = viewModel
        )

        // UI overlay — SectInfoCard + toggle + two side button columns
        MainGameScreenUiOverlay(
            state = state,
            data = data,
            viewModel = viewModel,
            saveLoadViewModel = saveLoadViewModel
        )

        // 建造栏 — 开关式，展开时显示；拆除模式按钮位于建造栏外部上方最右侧（间距 2dp）
        MainGameScreenBuildingBar(
            state = state,
            data = data,
            viewModel = viewModel
        )

        // Dialog overlay — extracted to GameOverlayHost
        GameOverlayHost(
            vms = vms,
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

        // 2026-08-16 进入宗门转场 — 全屏覆盖层（最高层）：播放转场视频 + 中央"加载资源中…"，
        // 由 SectMapController 在目标宗门地图就绪且至少播放 1 秒后关闭（实现见 SectTransitionOverlay.kt）
        val sectTransitionActive by viewModel.sectTransitionActive.collectAsStateWithLifecycle()
        SectTransitionOverlay(active = sectTransitionActive)
    }
}

/** 地图覆盖层（MainGameScreen 拆分）：金手指/灵植阁光环/放置确认/移动控制/边缘装饰 */
@Composable
private fun MainGameScreenMapOverlays(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel
) {
    // 金手指图标（建筑预览框右下角）— 仅未激活时显示作入口；
    // 激活后唯一图标由覆盖层承担（跟随 endGrid，即手指位置）
    if (state.isPlacingBuilding && !state.goldFingerState.isActive && data.renderData.goldenFingerBmp != null) {
        GoldFingerIcon(
            goldenFingerBmp = data.renderData.goldenFingerBmp,
            gridX = state.placingSnappedGridX + state.placingBuildingSize.width,
            gridY = state.placingSnappedGridY + state.placingBuildingSize.height,
            cameraState = data.viewportData.cameraState,
            tileSize = data.mapData.tileSize
        )
    }

    // 金手指框选覆盖层 — 激活时绘制选区方块和边框
    if (state.goldFingerState.isActive) {
        GoldFingerSelectionOverlay(
            goldFingerState = state.goldFingerState,
            cameraState = data.viewportData.cameraState,
            tileSize = data.mapData.tileSize,
            goldenFingerBmp = data.renderData.goldenFingerBmp
        )
    }

    // 一键拆除占地高亮已迁移至 native 渲染层（viewportParams.demolishHighlightData，
    // 与建筑精灵同帧同相机快照绘制——消除拖动视角时的相位差错位）

    // 灵植阁光环范围 — 放置/移动灵植阁时显示光环范围圈 + 范围内灵田高亮
    MainGameScreenAuraOverlay(
        state = state,
        data = data,
        viewModel = viewModel
    )

    if (state.isPlacingBuilding) {
        MainGameScreenPlacementConfirm(
            state = state,
            data = data,
            viewModel = viewModel
        )
    }

    // 移动模式确认按钮 + 拆除按钮
    if (state.movingBuilding != null) {
        MainGameScreenMovingControls(
            state = state,
            data = data,
            viewModel = viewModel
        )
    }

    // 宗门地图边缘装饰 — 在世界边界外绘制古风卷轴边缘渐变
    // 位于地图之上、UI 元素之下，对两渲染后端透明
    SectMapEdgeOverlay(
        cameraState = data.viewportData.cameraState,
        worldPixelWidth = data.mapData.worldPixelWidth,
        worldPixelHeight = data.mapData.worldPixelHeight
    )
}

/** 灵植阁光环范围（MainGameScreen 拆分）：放置/移动灵植阁时显示光环 + 灵田高亮 */
@Composable
private fun MainGameScreenAuraOverlay(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel
) {
    val placedBuildings by viewModel.placedBuildings.collectAsStateWithLifecycle()
    val herbGardenDisplayName = "灵植阁"
    val showHerbGardenAura = (state.isPlacingBuilding && state.placingBuildingName == herbGardenDisplayName) ||
        (state.movingBuilding?.displayName == herbGardenDisplayName)
    val auraGridX = if (state.isPlacingBuilding) state.placingSnappedGridX else state.movingSnappedGridX
    val auraGridY = if (state.isPlacingBuilding) state.placingSnappedGridY else state.movingSnappedGridY
    val auraSize = if (state.isPlacingBuilding) state.placingBuildingSize else data.derived.movingBuildingSize
    val spiritFieldDisplayName = "灵田"
    val spiritFieldBuildings = remember(placedBuildings, state.movingBuilding) {
        placedBuildings.filter { it.displayName == spiritFieldDisplayName }
    }
    HerbGardenAuraOverlay(
        showAura = showHerbGardenAura,
        buildingGridX = auraGridX,
        buildingGridY = auraGridY,
        buildingW = auraSize.width,
        buildingH = auraSize.height,
        spiritFieldBuildings = spiritFieldBuildings,
        cameraState = data.viewportData.cameraState,
        tileSize = data.mapData.tileSize
    )
}

/** 放置模式确认按钮（MainGameScreen 拆分） */
@Composable
private fun MainGameScreenPlacementConfirm(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel
) {
    val isGf = state.goldFingerState.isActive
    PlacementConfirmButtons(
        snappedGridX = state.placingSnappedGridX,
        snappedGridY = state.placingSnappedGridY,
        buildingSize = state.placingBuildingSize,
        cameraState = data.viewportData.cameraState,
        tileSize = data.mapData.tileSize,
        validity = if (isGf && !state.goldFingerState.canAfford) {
            GridSnapHelper.PlacementValidity.OutOfBounds
        } else {
            state.placementValidity
        },
        onConfirm = {
            if (isGf) {
                viewModel.batchPlaceBuilding(state.goldFingerState)
                state.goldFingerState = GoldFingerState()
            } else if (state.placementValidity == GridSnapHelper.PlacementValidity.Valid) {
                if (state.placingBuildingName == GameConfig.Road.DISPLAY_NAME) {
                    // 石板道路：走道路系统自动拼接（单格，RoadFacade 内扣 20 灵石/格）
                    viewModel.placeRoad(state.placingSnappedGridX, state.placingSnappedGridY)
                } else {
                    viewModel.placeBuilding(
                        name = state.placingBuildingName,
                        gridX = state.placingSnappedGridX,
                        gridY = state.placingSnappedGridY,
                        width = state.placingBuildingSize.width,
                        height = state.placingBuildingSize.height
                    )
                }
            }
            state.isPlacingBuilding = false
            state.placingBuildingName = ""
        },
        onCancel = {
            if (isGf) state.goldFingerState = GoldFingerState()
            else {
                state.isPlacingBuilding = false
                state.placingBuildingName = ""
            }
        }
    )
}

/** 移动模式确认按钮 + 拆除按钮（MainGameScreen 拆分） */
// 拆分搬移:嵌套/条件结构与原函数一致
@Suppress("ComplexCondition")
@Composable
private fun MainGameScreenMovingControls(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel
) {
    val moveScope = rememberCoroutineScope()
    PlacementConfirmButtons(
        snappedGridX = state.movingSnappedGridX,
        snappedGridY = state.movingSnappedGridY,
        buildingSize = data.derived.movingBuildingSize,
        cameraState = data.viewportData.cameraState,
        tileSize = data.mapData.tileSize,
        validity = state.movingValid,
        onConfirm = {
            val b = state.movingBuilding
            if (b != null &&
                state.movingValid == GridSnapHelper.PlacementValidity.Valid &&
                (state.movingSnappedGridX != b.gridX || state.movingSnappedGridY != b.gridY)
            ) {
                moveScope.launch {
                    viewModel.moveBuilding(b.instanceId, state.movingSnappedGridX, state.movingSnappedGridY)
                    // 同步更新空间索引，避免 LaunchedEffect 异步重建前
                    // 第二次长按读到旧坐标导致建筑跳回原位置
                    // 注：add 须传同一 spriteSizes，否则移动中的建筑丢失精灵扩展命中
                    data.renderData.buildingIndex.remove(b.instanceId)
                    data.renderData.buildingIndex.add(
                        b.copy(gridX = state.movingSnappedGridX, gridY = state.movingSnappedGridY),
                        data.mapData.buildingSpriteSizes
                    )
                    state.movingBuilding = null
                }
            } else {
                state.movingBuilding = null
            }
        },
        onCancel = { state.movingBuilding = null }
    )

    val building = checkNotNull(state.movingBuilding) { "DemolishButton rendered with null building" }
    DemolishButton(
        building = building,
        snappedGridX = state.movingSnappedGridX,
        snappedGridY = state.movingSnappedGridY,
        buildingSize = data.derived.movingBuildingSize,
        cameraState = data.viewportData.cameraState,
        tileSize = data.mapData.tileSize,
        onDemolish = {
            viewModel.demolishBuilding(building.instanceId)
            state.movingBuilding = null
        }
    )
}

/** UI 覆盖层（MainGameScreen 拆分）：顶部栏 + 侧边按钮 */
@Composable
private fun MainGameScreenUiOverlay(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel
) {
    Box(modifier = Modifier.fillMaxSize()) {
        MainGameScreenTopBar(
            state = state,
            data = data,
            viewModel = viewModel,
            saveLoadViewModel = saveLoadViewModel
        )

        // 仅 UI 可见时显示侧边按钮
        if (state.isUiVisible) {
            MainGameScreenSideControls(
                state = state,
                viewModel = viewModel,
                onToggleBuildingBar = {
                    state.buildingBarExpanded = !state.buildingBarExpanded
                    state.exitAllEditModes()
                },
                onCancelPlacement = {
                    // 防御性补齐：退出放置时清金手指（toggle 已重置，此处兜底）
                    state.exitAllEditModes()
                }
            )
        }
    }
}

/** 顶部 UI（MainGameScreen 拆分）：宗门信息卡 + 隐藏 UI/玉符/暂停列 */
@Composable
/** 顶部栏（MainGameScreen 拆分）：宗门信息卡片 + 右侧操作列（隐藏UI/玉符/暂停） */
private fun BoxScope.MainGameScreenTopBar(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel
) {
    // 宗门信息卡片 + 隐藏UI按钮（卡片外部右侧，同一行）
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 32.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MainGameScreenSectInfoSection(state = state, data = data, viewModel = viewModel)
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
                    isUiVisible = state.isUiVisible,
                    onToggle = { state.isUiVisible = !state.isUiVisible },
                    modifier = Modifier.size(28.dp)
                )
                if (state.isUiVisible) {
                    Spacer(modifier = Modifier.width(8.dp))
                    // 玉符货币栏（半透明胶囊条 + 图标 + 数量，点击弹说明对话框，
                    // "+"按钮弹玉符广告确认对话框）
                    JadeSymbolBadge(
                        jadeSymbols = data.derived.gameData?.jadeSymbols ?: 0,
                        onClick = { viewModel.navigateToDialog(DialogType.JadeSymbol) },
                        onAddClick = { viewModel.navigateToDialog(DialogType.JadeSymbolAd) }
                    )
                }
            }
            // 暂停/继续按钮（根据 isPaused 切换精灵图）
            PauseResumeButton(saveLoadViewModel = saveLoadViewModel)
        }
    }
}

/** 宗门信息卡片区（MainGameScreenTopBar 拆分）：仅 UI 可见时显示卡片与右侧间隔 */
@Composable
private fun MainGameScreenSectInfoSection(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel
) {
    if (state.isUiVisible) {
        val currentSectLevel = viewModel.playerSectLevel.collectAsStateWithLifecycle().value
        val showRewardBadge = viewModel.sectLevelRewardClaimable.collectAsStateWithLifecycle().value
        val sectCombatPower by viewModel.sectCombatPower.collectAsStateWithLifecycle()
        // 2026-08-16 修复：卡片标题按当前活跃宗门显示（activeSectId 指向被占宗门时
        // 显示该宗门名与等级，而不是恒显示主宗门名——避免「进入被占宗门地图却显示主宗门」误导）
        val activeSect = data.derived.gameData?.worldMapSects
            ?.find { it.id == data.derived.gameData.activeSectId }
        SectInfoCard(
            sectName = activeSect?.name ?: data.derived.gameData?.sectName ?: "青云宗",
            gameYear = data.derived.gameData?.gameYear ?: 1,
            gameMonth = data.derived.gameData?.gameMonth ?: 1,
            gamePhase = data.derived.gameData?.gamePhase ?: 0,
            lowStones = data.derived.gameData?.spiritStones ?: 0L,
            midStones = data.derived.gameData?.midGradeSpiritStones ?: 0L,
            highStones = data.derived.gameData?.highGradeSpiritStones ?: 0L,
            discipleCount = data.derived.aliveDisciples.size,
            combatPower = sectCombatPower,
            sectLevel = activeSect?.level ?: currentSectLevel,
            showRewardBadge = showRewardBadge,
            onSectIconClick = {
                if (data.derived.gameData.activeSectId.isEmpty()) viewModel.navigateToSectLevelDetail()
            },
            onSectNameClick = {
                // 仅在主宗门（activeSectId=""）时允许改名，被占宗门不可改名
                if (data.derived.gameData.activeSectId.isEmpty()) {
                    viewModel.navigateToDialog(DialogType.RenameSect)
                }
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
}

/** 暂停/继续按钮（MainGameScreen 拆分） */
@Composable
private fun PauseResumeButton(saveLoadViewModel: SaveLoadViewModel) {
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

/** 侧边按钮（MainGameScreen 拆分）：左按钮列 + 消息栏 + 右上动作按钮 */
@Composable
private fun BoxScope.MainGameScreenSideControls(
    state: MainGameScreenState,
    viewModel: GameViewModel,
    onToggleBuildingBar: () -> Unit,
    onCancelPlacement: () -> Unit
) {
    LeftSideButtons(
        viewModel = viewModel,
        modifier = Modifier.align(Alignment.CenterStart)
    )

    // 消息栏系统 — 左下角（建造栏展开时自然遮挡消息栏）
    val gameEventRecords by viewModel.gameEventRecords.collectAsStateWithLifecycle()
    MessageBarHost(
        events = gameEventRecords,
        isUiVisible = state.isUiVisible,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 32.dp, bottom = 16.dp)
    )

    GameActionButtons(
        viewModel = viewModel,
        buildingBarExpanded = state.buildingBarExpanded,
        onToggleBuildingBar = onToggleBuildingBar,
        onCancelPlacement = onCancelPlacement,
        modifier = Modifier.align(Alignment.TopEnd)
    )
}

/** 建造栏（MainGameScreen 拆分）：拆除控制行 + 建筑卡片栏 */
@Composable
private fun BoxScope.MainGameScreenBuildingBar(
    state: MainGameScreenState,
    data: MainGameScreenData,
    viewModel: GameViewModel
) {
    if (state.buildingBarExpanded && state.isUiVisible) {
        val currentSectLevel by viewModel.playerSectLevel.collectAsStateWithLifecycle()
        val gameData by viewModel.gameDataUi.collectAsStateWithLifecycle()
        val buildingCosts = remember {
            data.mapData.buildingList.associate { (name, _) -> name to viewModel.getBuildingCost(name) }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            MainGameScreenDemolishControls(state = state, viewModel = viewModel)
            Spacer(modifier = Modifier.height(2.dp)) // 与建造栏距离 2dp
            BuildingConstructionBar(
                buildingList = data.mapData.buildingList,
                placedBuildings = data.derived.activeSectBuildings,
                buildingCosts = buildingCosts,
                spiritStones = gameData.spiritStones,
                currentSectLevel = currentSectLevel,
                onSelectBuildingLevelRequirement = { name ->
                    viewModel.navigateToDialog(DialogType.BuildingSectLevelRequirement(name))
                },
                onSelectBuilding = { name ->
                    onSelectBuildingFromBar(
                        state = state, mapData = data.mapData,
                        renderData = data.renderData, viewportData = data.viewportData,
                        name = name
                    )
                },
                getBuildingMaxCount = { name ->
                    when {
                        name == GameConfig.Road.DISPLAY_NAME -> Int.MAX_VALUE
                        BuildingFeatureRegistry.isResidence(name) ||
                            BuildingFeatureRegistry.hasNoLimit(name) -> Int.MAX_VALUE
                        else -> 1
                    }
                },
                getBuildingCount = { name ->
                    if (name == GameConfig.Road.DISPLAY_NAME) 0
                    else if (BuildingFeatureRegistry.isGloballyUnique(name)) {
                        gameData.placedBuildings.count { it.displayName == name }
                    } else {
                        data.derived.activeSectBuildings.count { it.displayName == name }
                    }
                }
            )
        }
    }
}

/** 建造栏顶部拆除控制行（MainGameScreen 拆分）：区域选择 + 取消/确认拆除 */
@Composable
private fun MainGameScreenDemolishControls(
    state: MainGameScreenState,
    viewModel: GameViewModel
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.weight(1f))
        if (state.isDemolishMode) {
            DemolishModeButtons(state, viewModel)
        } else {
            QuickActionButtons(state, viewModel)
        }
    }
}

/** 拆除模式按钮行：区域选择按钮（+ 直径调整进度条）+ 取消/确认拆除 */
@Composable
private fun DemolishModeButtons(
    state: MainGameScreenState,
    viewModel: GameViewModel
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.isAreaSelectMode) {
            AreaDiameterSlider(
                diameter = state.areaDiameter,
                onDiameterChange = { state.areaDiameter = it }
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        AreaSelectButton(
            isActive = state.isAreaSelectMode,
            onClick = {
                state.isAreaSelectMode = !state.isAreaSelectMode
                if (state.isAreaSelectMode) state.areaDiameter = AREA_DEFAULT_DIAMETER
            }
        )
    }
    Spacer(modifier = Modifier.width(8.dp))
    GameButton(
        text = "取消拆除",
        onClick = {
            state.isDemolishMode = false
            state.isAreaSelectMode = false
            state.demolishSelectedIds = emptySet()
        }
    )
    Spacer(modifier = Modifier.width(8.dp))
    GameButton(
        text = "确认拆除",
        enabled = state.demolishSelectedIds.isNotEmpty(),
        onClick = {
            viewModel.demolishBuildings(state.demolishSelectedIds.toList())
            state.isDemolishMode = false
            state.isAreaSelectMode = false
            state.demolishSelectedIds = emptySet()
        }
    )
}

/** 非拆除模式快捷按钮：一键升级 + 一键拆除入口 */
@Composable
private fun QuickActionButtons(
    state: MainGameScreenState,
    viewModel: GameViewModel
) {
    Column(horizontalAlignment = Alignment.End) {
        GameButton(
            text = "一键升级",
            onClick = { viewModel.navigateToDialog(DialogType.BuildingUpgrade) }
        )
        Spacer(modifier = Modifier.height(4.dp))
        GameButton(
            text = "一键拆除",
            onClick = { state.enterDemolishMode() }
        )
    }
}


/**
 * 建筑作用域过滤唯一同源谓词（2026-08-16 修复）：
 * 渲染总线（GameViewModel）与点击/瓦片/渲染帧（MainGameScreen）必须使用同一谓词，
 * 只保留 `activeSectId` 作用域内的建筑，杜绝进入被占宗门后渲染主宗建筑但点不中的分叉。
 */
internal fun buildingsInSectScope(
    placedBuildings: List<GridBuildingData>,
    activeSectId: String
): List<GridBuildingData> = placedBuildings.filter { it.sectId == activeSectId }

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
        // 精灵名解析：显示名可能带分级前缀（如「初级单人住所」），经注册表回退到图集精灵名
        //（「单人住所」）；未注册建筑回退用自身 displayName（旧档迁移前仍按原名可渲染）
        val spriteName = BuildingFeatureRegistry.findByDisplayName(b.displayName)
            ?.effectiveSpriteName() ?: b.displayName
        val nameIndex = BUILDING_NAME_INDEX[spriteName]
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

/**
 * 构建一键拆除模式高亮标记数组（每建筑 1 字节，与 [buildBuildingDataArray] **同一排序**：
 * sortedBy { gridY + height } 升序，保证 marker[i] 与 buildingData[i] 是同一建筑——
 * 渲染端从 buildingData 复用占地几何，索引错位会导致高亮画到别的建筑上）。
 *
 * 未注册建筑（[BuildingFeatureRegistry] 查无）标记 [DemolishHighlightMark.NONE]
 * （与旧 DemolishSelectionOverlay 的 filter 行为一致——不可选中不可拆）。
 *
 * @param buildings 已按 sectId 过滤的放置建筑列表（与 buildBuildingDataArray 同一输入）
 * @param selectedIds 当前选中拆除的建筑 instanceId 集合
 * @return 标记数组（空列表返回空数组；null 语义由调用方 isDemolishMode 表达）
 */
internal fun buildDemolishHighlightData(
    buildings: List<GridBuildingData>,
    selectedIds: Set<String>
): ByteArray {
    // 按地面接触点(gridY + height)升序排列：与 buildBuildingDataArray 同一排序不变量
    val sorted = buildings.sortedBy { it.gridY + it.height }
    return ByteArray(sorted.size) { i ->
        val b = sorted[i]
        when {
            BuildingFeatureRegistry.findByDisplayName(b.displayName) == null ->
                DemolishHighlightMark.NONE.toByte()
            b.instanceId in selectedIds ->
                DemolishHighlightMark.SELECTED.toByte()
            else ->
                DemolishHighlightMark.GREEN.toByte()
        }
    }
}

/**
 * 区域选择命中建筑计算（纯函数）。
 *
 * 以 (centerX, centerY) 为中心、diameter 格边长的正方形区域（含中心格），
 * 返回与该区域矩形重叠（含部分重叠）的所有**可拆除**建筑 instanceId。
 *
 * ## 对称性（half = diameter / 2 整数截断）
 * 区域列范围 [centerX-half, centerX+diameter-1-half]，共 diameter 列：
 * 奇数直径中心对称（d=3 → [cx-1, cx+1]）；偶数直径中心偏左半格
 * （d=4 → [cx-2, cx+1]）——整数网格的必然取舍，均包含中心列。
 *
 * ## 矩形重叠（半开区间）
 * 建筑占 [gridX, gridX+width)，区域占 [minX, minX+diameter)；
 * 重叠 ⟺ gridX < minX+diameter && gridX+width > minX（Y 向同理）；
 * 紧贴边界（gridX+width == minX）不算重叠。
 *
 * 排除不可拆除建筑（BuildingFeatureRegistry.findByDisplayName == null，
 * 与 buildDemolishHighlightData 的 NONE 判定同源）。
 * 注意：并集累积语义在调用方（demolishSelectedIds + 返回值），本函数无状态。
 */
internal fun buildingsInSquare(
    buildings: List<GridBuildingData>,
    centerX: Int,
    centerY: Int,
    diameter: Int
): Set<String> {
    val d = diameter.coerceIn(AREA_MIN_DIAMETER, AREA_MAX_DIAMETER)
    val minX = centerX - d / 2
    val minY = centerY - d / 2
    val maxXExclusive = minX + d
    val maxYExclusive = minY + d
    return buildings.asSequence()
        .filter { BuildingFeatureRegistry.findByDisplayName(it.displayName) != null }
        .filter { b ->
            b.gridX < maxXExclusive && b.gridX + b.width > minX &&
                b.gridY < maxYExclusive && b.gridY + b.height > minY
        }
        .map { it.instanceId }
        .toSet()
}

// BUILDING_NAME_INDEX / BUILDING_UV_MAP 已移入 SectMapViewport.kt（P-7，同包 internal）
// 灵田作物渲染数据构建已移入 SpiritCropRenderData.kt（文件行数收敛）



