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
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.core.util.TimeProgressUtil
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
import com.xianxia.sect.ui.game.main.GoldFingerSelection
import com.xianxia.sect.ui.game.main.clampGoldFingerSelection
import com.xianxia.sect.ui.game.main.recomputeGoldFingerState
import com.xianxia.sect.ui.game.main.translateGoldFingerSelection
import com.xianxia.sect.core.touch.LongPressResult
import com.xianxia.sect.core.touch.SectMapTouchEngine
import com.xianxia.sect.core.touch.TouchEngineCallbacks
import com.xianxia.sect.core.touch.TouchEngineConfig
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
private class MainGameScreenState {
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
private class MainGameScreenDerived(
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
private data class MainGameScreenMapData(
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
    val demolishHighlightData: ByteArray?
)

/** MainGameScreen 渲染数据（MainGameScreen 拆分）：索引/网格/精灵/配置 */
private data class MainGameScreenRenderData(
    val goldenFingerBmp: ImageBitmap?,
    val flatTileData: IntArray,
    val gridSystem: GridSystem,
    val buildingIndex: BuildingSpatialIndex,
    val nativeConfig: NativeRenderConfig,
    val buildingDataArray: FloatArray?,
    val spiritCropData: FloatArray?,
    val demolishHighlightData: ByteArray?
)

/** MainGameScreen 视口数据（MainGameScreen 拆分）：相机/预览/渲染参数 */
private data class MainGameScreenViewportData(
    val viewportParams: SectMapViewportParams,
    val previewState: MapPreviewState,
    val cameraState: SectCameraState,
    val touchScope: CoroutineScope,
    val cameraAnimator: CameraAnimator,
    val cancelCameraAnim: () -> Unit
)

/** MainGameScreen 聚合数据（MainGameScreen 拆分）：派生/静态/渲染/视口 + 触控引擎 */
private class MainGameScreenData(
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
    return BuildingFeatureRegistry.constructible.map { def ->
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
        }
    }
    // 建筑精灵比例尺寸映射 — 用于渲染视觉大小（可能大于占地尺寸）
    val buildingSpriteSizes = remember {
        BuildingFeatureRegistry.all.associate { def ->
            val (sw, sh) = viewModel.getBuildingSpriteSize(def.displayName)
            def.displayName to GridSnapHelper.BuildingSize(sw, sh)
        }
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
    return MainGameScreenMapTiles(
        flatTileData = flatTileData,
        buildingDataArray = buildingDataArray,
        spiritCropData = spiritCropData.value,
        demolishHighlightData = demolishHighlightData.value
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
        demolishHighlightData = tiles.demolishHighlightData
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
@Composable
private fun rememberMainGameScreenTouchEngine(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    viewModel: GameViewModel
): SectMapTouchEngine {
    val cameraState = viewportData.cameraState
    val buildingIndex = renderData.buildingIndex
    val gridSystem = renderData.gridSystem
    return remember(cameraState, buildingIndex, gridSystem) {
        SectMapTouchEngine(
            callbacks = buildMainGameScreenTouchCallbacks(
                state = state, derived = derived, mapData = mapData,
                renderData = renderData, viewportData = viewportData,
                viewModel = viewModel
            ),
            scope = viewportData.touchScope,
            config = TouchEngineConfig()
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
    val touchEngine = rememberMainGameScreenTouchEngine(
        state = state, derived = derived, mapData = mapData, renderData = renderData,
        viewportData = viewportData, viewModel = viewModel
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

/** MainGameScreen 触控回调（MainGameScreen 拆分）：跨平台手势引擎回调 */
private fun buildMainGameScreenTouchCallbacks(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    viewModel: GameViewModel
): TouchEngineCallbacks = object : TouchEngineCallbacks {
    override fun onPanCamera(dx: Float, dy: Float) {
        viewportData.cameraState.pan(dx, dy)
        viewportData.cancelCameraAnim()
        viewModel.onUserInteraction()
    }
    override fun onPinchZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        viewportData.cameraState.zoom(scaleFactor, focusX, focusY)
        viewportData.cancelCameraAnim()
        viewModel.onUserInteraction()
    }
    override fun onTap(screenX: Float, screenY: Float) {
        handleMainGameScreenTap(
            state = state, derived = derived, mapData = mapData,
            renderData = renderData, viewportData = viewportData,
            viewModel = viewModel, screenX = screenX, screenY = screenY
        )
    }
    override fun onLongPress(screenX: Float, screenY: Float): LongPressResult {
        return handleMainGameScreenLongPress(
            state = state, derived = derived, mapData = mapData,
            renderData = renderData, viewportData = viewportData,
            viewModel = viewModel, screenX = screenX, screenY = screenY
        )
    }
    override fun onBuildingDragUpdate(worldDx: Float, worldDy: Float) {
        handleMainGameScreenDragUpdate(
            state = state, derived = derived, mapData = mapData,
            renderData = renderData,
            worldDx = worldDx, worldDy = worldDy
        )
    }
    override fun onBuildingDragEnd() { /* 松手后保持最后位置，显示确认/取消按钮 */ }
    override fun onGoldFingerUpdate(screenX: Float, screenY: Float) {
        handleMainGameScreenGoldFingerUpdate(
            state = state, derived = derived, mapData = mapData,
            viewportData = viewportData,
            screenX = screenX, screenY = screenY
        )
    }
    override fun isGoldFingerActive(): Boolean = state.goldFingerState.isActive
    override fun getCameraScale(): Float = viewportData.cameraState.scale
    override fun findBuildingAt(screenX: Float, screenY: Float): Any? {
        return findMainGameScreenBuildingAt(
            state = state, mapData = mapData,
            renderData = renderData, viewportData = viewportData,
            screenX = screenX, screenY = screenY
        )
    }
    override fun isInEditMode(): Boolean = state.isPlacingBuilding || state.movingBuilding != null
    override fun onDragStart() { viewModel.setGameScene(GameEngineCore.GameScene.GAMEPLAY) }
    override fun onDragEnd() { /* 由 idle timeout 自动降帧 (30s → IDLE 10fps) */ }
    override fun onFlingStart() { viewModel.setGameScene(GameEngineCore.GameScene.MAP_SCROLL) }
    override fun onFlingEnd() { /* 由 idle timeout 自动降帧 (30s → IDLE 10fps) */ }
}

/** 点击处理（MainGameScreen 拆分）：拆除选中 / 建筑详情打开 */
// 拆分聚合:平铺参数搬移自原公共函数
@Suppress("LongParameterList")
private fun handleMainGameScreenTap(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    viewModel: GameViewModel,
    screenX: Float,
    screenY: Float
) {
    val wx = viewportData.cameraState.screenToWorldX(screenX)
    val wy = viewportData.cameraState.screenToWorldY(screenY)
    val gx = (wx / mapData.tileSize).toInt()
    val gy = (wy / mapData.tileSize).toInt()
    // 拆除模式：单点切换选中 / 区域模式范围选中，不弹详情
    if (state.isDemolishMode) {
        handleDemolishTap(
            state = state, derived = derived, renderData = renderData, gx = gx, gy = gy
        )
        return
    }
    val clicked = renderData.buildingIndex.findBuildingAt(gx, gy)
    // 点击空地 → 清除选中高亮（任意模式）
    if (clicked == null) {
        state.selectedBuildingGrid = null
    }
    if (clicked != null && !state.isPlacingBuilding && state.movingBuilding == null) {
        // 点击建筑 → 记录选中格（渲染端金色高亮描边），并打开详情
        state.selectedBuildingGrid = gx to gy
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
                            "activeSectId=${derived.gameData.activeSectId} " +
                            "sectBuildings=${derived.activeSectBuildings.size}"
                    )
                }
                val b = mapData.buildingList.find { it.first == clicked.displayName }
                if (b != null) {
                    b.second?.invoke(clicked)
                } else {
                    DomainLog.w(
                        BUILDING_TAP_TAG,
                        "点击建筑无回调处理: name=${clicked.displayName} " +
                            "sectId=${clicked.sectId} instanceId=${clicked.instanceId} " +
                            "grid=(${clicked.gridX},${clicked.gridY}) " +
                            "activeSectId=${derived.gameData.activeSectId} " +
                            "sectBuildings=${derived.activeSectBuildings.size}"
                    )
                }
            }
        }
    }
}

/** 长按处理（MainGameScreen 拆分）：金手指入口检测 / 建筑移动模式 */
// 拆分聚合:平铺参数搬移自原公共函数
// 拆分搬移:多出口与原函数一致
@Suppress("LongParameterList", "ReturnCount")
private fun handleMainGameScreenLongPress(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    viewModel: GameViewModel,
    screenX: Float,
    screenY: Float
): LongPressResult {
    val wx = viewportData.cameraState.screenToWorldX(screenX)
    val wy = viewportData.cameraState.screenToWorldY(screenY)
    val gx = (wx / mapData.tileSize).toInt()
    val gy = (wy / mapData.tileSize).toInt()

    // 放置模式 → 金手指图标检测：唯一图标随激活状态移动
    // （未激活在预览角作入口；激活后跟随 endGrid，按住它即可重入框选）
    if (state.isPlacingBuilding) {
        return handleGoldFingerLongPress(
            state = state, derived = derived, mapData = mapData,
            viewModel = viewModel, wx = wx, wy = wy
        )
    }

    // 非放置模式 → 建筑长按 → 移动模式
    // 注意：movingBuilding 可能非 null（上次拖拽后确认/取消按钮还在显示）
    // 如果按钮显示期间再次长按同一建筑，应允许继续拖拽
    // 拆除模式禁止长按移动
    if (!state.isPlacingBuilding && !state.isDemolishMode) {
        val touched = renderData.buildingIndex.findBuildingAt(gx, gy)
            ?: (if (state.movingBuilding != null) state.movingBuilding else null)
        if (touched != null) {
            val isResumeDrag = state.movingBuilding?.instanceId == touched.instanceId
            if (!isResumeDrag) {
                // 新建筑拖拽 → 从该建筑的原始网格坐标开始
                state.movingWorldX = (touched.gridX * mapData.tileSize).toFloat()
                state.movingWorldY = (touched.gridY * mapData.tileSize).toFloat()
                state.movingSnappedGridX = touched.gridX
                state.movingSnappedGridY = touched.gridY
                state.movingValid = GridSnapHelper.PlacementValidity.Valid
            }
            state.movingBuilding = touched
            return LongPressResult.BuildingDrag
        }
    }
    return LongPressResult.NotHandled
}

/** 金手指图标长按（MainGameScreen 拆分）：首次激活锚定预览位置并重算状态 */
// 拆分搬移:嵌套/条件结构与原函数一致
@Suppress("ComplexCondition")
private fun handleGoldFingerLongPress(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    viewModel: GameViewModel,
    wx: Float,
    wy: Float
): LongPressResult {
    val gfWx = (if (state.goldFingerState.isActive) state.goldFingerState.endGridX
        else state.placingSnappedGridX + state.placingBuildingSize.width) * mapData.tileSize
    val gfWy = (if (state.goldFingerState.isActive) state.goldFingerState.endGridY
        else state.placingSnappedGridY + state.placingBuildingSize.height) * mapData.tileSize
    if (wx >= gfWx && wx < gfWx + mapData.tileSize &&
        wy >= gfWy && wy < gfWy + mapData.tileSize
    ) {
        if (!state.goldFingerState.isActive) {
            // 首次激活：起点锚定预览位置，钳制到可建区后重算状态
            val sel = clampGoldFingerSelection(
                GoldFingerSelection(
                    state.placingSnappedGridX, state.placingSnappedGridY,
                    state.placingSnappedGridX, state.placingSnappedGridY
                ),
                mapData.worldWidthCells, mapData.worldHeightCells,
                GameConfig.SectMap.BORDER_TREE_RING
            )
            state.goldFingerState = recomputeGoldFingerState(
                f = GoldFingerState(
                    isActive = true,
                    buildingName = state.placingBuildingName,
                    buildingSize = state.placingBuildingSize,
                    buildingCost = viewModel.getBuildingCost(state.placingBuildingName)
                ),
                sel = sel,
                existingBuildings = derived.effectivePlacedBuildings,
                worldWidthCells = mapData.worldWidthCells,
                worldHeightCells = mapData.worldHeightCells,
                buildableBorder = GameConfig.SectMap.BORDER_TREE_RING,
                spiritStones = derived.gameData?.spiritStones ?: 0L
            )
        }
        // 已激活：不改动选区（等待 MOVE 重新框定，可扩大可缩小），直接重入框选
        return LongPressResult.GoldFingerDrag
    }
    return LongPressResult.NotHandled
}

/** 拖拽更新（MainGameScreen 拆分）：放置预览 / 移动建筑位置实时更新 */
private fun handleMainGameScreenDragUpdate(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    worldDx: Float,
    worldDy: Float
) {
    if (state.isPlacingBuilding) {
        // 放置模式：更新预览位置
        state.placingWorldX += worldDx
        state.placingWorldY += worldDy
        val oldSnappedX = state.placingSnappedGridX
        val oldSnappedY = state.placingSnappedGridY
        state.placingSnappedGridX = GridSnapHelper.worldToGrid(state.placingWorldX, mapData.tileSize)
        state.placingSnappedGridY = GridSnapHelper.worldToGrid(state.placingWorldY, mapData.tileSize)
        val dgx = state.placingSnappedGridX - oldSnappedX
        val dgy = state.placingSnappedGridY - oldSnappedY
        // 金手指激活时选区随预览同增量平移（Bug 2 修复），钳制到可建区后重算
        if (state.goldFingerState.isActive && (dgx != 0 || dgy != 0)) {
            val f = state.goldFingerState
            val sel = translateGoldFingerSelection(
                GoldFingerSelection(f.startGridX, f.startGridY, f.endGridX, f.endGridY),
                dgx, dgy, mapData.worldWidthCells, mapData.worldHeightCells,
                GameConfig.SectMap.BORDER_TREE_RING
            )
            state.goldFingerState = recomputeGoldFingerState(
                f = f, sel = sel,
                existingBuildings = derived.effectivePlacedBuildings,
                worldWidthCells = mapData.worldWidthCells,
                worldHeightCells = mapData.worldHeightCells,
                buildableBorder = GameConfig.SectMap.BORDER_TREE_RING,
                spiritStones = derived.gameData?.spiritStones ?: 0L
            )
        }
        state.placementValidity = renderData.gridSystem.validatePlacement(
            state.placingSnappedGridX, state.placingSnappedGridY,
            state.placingBuildingSize.width, state.placingBuildingSize.height
        )
    } else {
        // 移动模式：更新被拖建筑位置
        state.movingWorldX += worldDx
        state.movingWorldY += worldDy
        state.movingSnappedGridX = GridSnapHelper.worldToGrid(state.movingWorldX, mapData.tileSize)
        state.movingSnappedGridY = GridSnapHelper.worldToGrid(state.movingWorldY, mapData.tileSize)
        state.movingValid = renderData.gridSystem.validatePlacement(
            state.movingSnappedGridX, state.movingSnappedGridY,
            derived.movingBuildingSize.width, derived.movingBuildingSize.height
        )
    }
}

/** 金手指拖拽更新（MainGameScreen 拆分）：终点格吸附 + 钳制可建区 + 重算状态 */
private fun handleMainGameScreenGoldFingerUpdate(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    mapData: MainGameScreenMapData,
    viewportData: MainGameScreenViewportData,
    screenX: Float,
    screenY: Float
) {
    if (!state.goldFingerState.isActive) return
    val newWx = viewportData.cameraState.screenToWorldX(screenX)
    val newWy = viewportData.cameraState.screenToWorldY(screenY)
    // 终点格用 GridSnapHelper.worldToGrid（roundToInt，与预览吸附一致），
    // 并整体钳制到可建区，保证视觉框 == 实际建造区
    val newGridX = GridSnapHelper.worldToGrid(newWx, mapData.tileSize)
    val newGridY = GridSnapHelper.worldToGrid(newWy, mapData.tileSize)
    val f = state.goldFingerState
    val sel = clampGoldFingerSelection(
        GoldFingerSelection(f.startGridX, f.startGridY, newGridX, newGridY),
        mapData.worldWidthCells, mapData.worldHeightCells,
        GameConfig.SectMap.BORDER_TREE_RING
    )
    state.goldFingerState = recomputeGoldFingerState(
        f = f, sel = sel,
        existingBuildings = derived.effectivePlacedBuildings,
        worldWidthCells = mapData.worldWidthCells,
        worldHeightCells = mapData.worldHeightCells,
        buildableBorder = GameConfig.SectMap.BORDER_TREE_RING,
        spiritStones = derived.gameData?.spiritStones ?: 0L
    )
}

/** 触控命中建筑检测（MainGameScreen 拆分）：拆除/放置/移动模式分支 */
// 拆分搬移:多出口与原函数一致
// 拆分搬移:嵌套/条件结构与原函数一致
@Suppress("ReturnCount", "ComplexCondition")
private fun findMainGameScreenBuildingAt(
    state: MainGameScreenState,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    screenX: Float,
    screenY: Float
): Any? {
    // 拆除模式：不返回建筑 → touch 引擎不会启动 BuildingDrag 定时器，
    // 短按/滑动正常走 onTap / 平移相机
    if (state.isDemolishMode) return null
    val wx = viewportData.cameraState.screenToWorldX(screenX)
    val wy = viewportData.cameraState.screenToWorldY(screenY)

    // 放置模式：用世界坐标检测触摸是否在预览区域内（比网格检测更精准）
    if (state.isPlacingBuilding) {
        val previewLeft = state.placingWorldX
        val previewTop = state.placingWorldY
        val previewRight = previewLeft + state.placingBuildingSize.width * mapData.tileSize
        val previewBottom = previewTop + state.placingBuildingSize.height * mapData.tileSize
        if (wx >= previewLeft && wx < previewRight &&
            wy >= previewTop && wy < previewBottom
        ) {
            return Any()
        }
        return null
    }

    val gx = (wx / mapData.tileSize).toInt()
    val gy = (wy / mapData.tileSize).toInt()

    // buildingIndex 不包含 movingBuilding，手动检查
    val mb = state.movingBuilding
    if (mb != null) {
        // 用当前拖拽位置（movingSnappedGridX/Y）而非原始位置检查
        if (gx >= state.movingSnappedGridX && gx < state.movingSnappedGridX + mb.width &&
            gy >= state.movingSnappedGridY && gy < state.movingSnappedGridY + mb.height
        ) {
            return mb
        }
    }
    return renderData.buildingIndex.findBuildingAt(gx, gy)
}

/** 拆除模式点击处理（MainGameScreen 拆分）：区域模式范围选中 / 单点切换选中 */
private fun handleDemolishTap(
    state: MainGameScreenState,
    derived: MainGameScreenDerived,
    renderData: MainGameScreenRenderData,
    gx: Int,
    gy: Int
) {
    if (state.isAreaSelectMode) {
        // 区域模式：以点击格为中心做正方形范围选中（并集累积 + Set 幂等——
        // 新区域内已选中的建筑保持选中，重复框选不取消；点击任意格都触发，
        // 不要求格上有建筑——越界格纯几何计算天然安全）。
        state.demolishSelectedIds = state.demolishSelectedIds + buildingsInSquare(
            buildings = derived.effectivePlacedBuildings,
            centerX = gx,
            centerY = gy,
            diameter = state.areaDiameter
        )
    } else {
        // 单点模式：点击建筑切换选中状态
        val b = renderData.buildingIndex.findBuildingAt(gx, gy) ?: return
        if (BuildingFeatureRegistry.findByDisplayName(b.displayName) != null) {
            state.demolishSelectedIds = if (b.instanceId in state.demolishSelectedIds)
                state.demolishSelectedIds - b.instanceId
            else state.demolishSelectedIds + b.instanceId
        }
    }
}

/** 建造卡片点击（MainGameScreen 拆分）：进入放置模式（拆除模式下忽略） */
private fun onSelectBuildingFromBar(
    state: MainGameScreenState,
    mapData: MainGameScreenMapData,
    renderData: MainGameScreenRenderData,
    viewportData: MainGameScreenViewportData,
    name: String
) {
    // 拆除模式下点击建造卡片不进入放置模式
    if (state.isDemolishMode) return
    val size = mapData.buildingSizes[name] ?: GridSnapHelper.BuildingSize(2, 3)
    state.isPlacingBuilding = true
    state.placingBuildingName = name
    state.placingBuildingSize = size
    state.placingWorldX = viewportData.cameraState.screenToWorldX(state.screenWidthPx / 2f) -
        size.width * mapData.tileSize / 2f
    state.placingWorldY = viewportData.cameraState.screenToWorldY(state.screenHeightPx / 2f) -
        size.height * mapData.tileSize / 2f
    state.placingSnappedGridX = GridSnapHelper.worldToGrid(state.placingWorldX, mapData.tileSize)
    state.placingSnappedGridY = GridSnapHelper.worldToGrid(state.placingWorldY, mapData.tileSize)
    state.placementValidity = renderData.gridSystem.validatePlacement(
        state.placingSnappedGridX, state.placingSnappedGridY,
        size.width, size.height
    )
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
                viewModel.placeBuilding(
                    name = state.placingBuildingName,
                    gridX = state.placingSnappedGridX,
                    gridY = state.placingSnappedGridY,
                    width = state.placingBuildingSize.width,
                    height = state.placingBuildingSize.height
                )
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
                        BuildingFeatureRegistry.isResidence(name) ||
                            BuildingFeatureRegistry.hasNoLimit(name) -> Int.MAX_VALUE
                        else -> 1
                    }
                },
                getBuildingCount = { name ->
                    if (BuildingFeatureRegistry.isGloballyUnique(name)) {
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
            // 区域选择按钮 + 正上方的直径调整进度条（仅区域模式激活时显示）
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
        } else {
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



