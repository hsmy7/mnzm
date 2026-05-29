package com.xianxia.sect.ui.game.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

import com.xianxia.sect.R
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.ui.game.AlchemyViewModel
import com.xianxia.sect.ui.game.BattleViewModel
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.ForgeViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HerbGardenViewModel
import com.xianxia.sect.ui.game.PatrolTowerViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.SaveLoadViewModel
import com.xianxia.sect.ui.game.SpiritMineViewModel
import com.xianxia.sect.ui.game.TopOverlay
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.game.dialogs.AlchemyDialog
import com.xianxia.sect.ui.game.dialogs.BattleLogDetailDialog
import com.xianxia.sect.ui.game.dialogs.BattleLogListDialog
import com.xianxia.sect.ui.game.dialogs.BattleResultDialog
import com.xianxia.sect.ui.game.dialogs.DiplomacyDialog
import com.xianxia.sect.ui.game.dialogs.DiscipleDesertionDialog
import com.xianxia.sect.ui.game.dialogs.DiscipleTheftCaughtDialog
import com.xianxia.sect.ui.game.dialogs.MarriageApprovalDialog
import com.xianxia.sect.ui.game.dialogs.ForgeDialog
import com.xianxia.sect.ui.game.dialogs.HerbGardenDialog
import com.xianxia.sect.ui.game.dialogs.LawEnforcementHallDialog
import com.xianxia.sect.ui.game.dialogs.LibraryDialog
import com.xianxia.sect.ui.game.dialogs.PatrolTowerDialog
import com.xianxia.sect.ui.game.dialogs.MerchantDialog
import com.xianxia.sect.ui.game.dialogs.MissionHallDialog
import com.xianxia.sect.ui.game.dialogs.PlantingDialog
import com.xianxia.sect.ui.game.dialogs.QingyunPeakDialog
import com.xianxia.sect.ui.game.dialogs.RecruitDialog
import com.xianxia.sect.ui.game.dialogs.ReflectionCliffDialog
import com.xianxia.sect.ui.game.dialogs.ResidenceDialog
import com.xianxia.sect.ui.game.dialogs.SalaryConfigDialog
import com.xianxia.sect.ui.game.dialogs.SpiritMineDialog
import com.xianxia.sect.ui.game.dialogs.TianshuHallDialog
import com.xianxia.sect.ui.game.dialogs.WarehouseDialog
import com.xianxia.sect.ui.game.dialogs.WenDaoPeakDialog
import com.xianxia.sect.ui.game.dialogs.WorldMapDialog
import com.xianxia.sect.ui.game.tabs.BuildingsTab
import com.xianxia.sect.ui.game.tabs.DisciplesTab
import com.xianxia.sect.ui.game.tabs.SettingsTab
import com.xianxia.sect.ui.game.tabs.WarehouseTab
import com.xianxia.sect.ui.navigation.GameRoute
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.XianxiaColorScheme
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog

/**
 * GameOverlayHost — 管理所有 Dialog 路由 (NavHost) 和顶层叠加层 (TopOverlay z-order)。
 *
 * 提取自 MainGameScreen，用于降低 MainGameScreen 的复杂度。
 * NavHost 包含所有业务对话框的路由，TopOverlay 管理 BATTLE_RESULT /
 * BATTLE_LOG_DETAIL / DISCIPLE_DETAIL 的 z-order 渲染。
 */
@Composable
fun GameOverlayHost(
    dialogNavController: NavHostController,
    viewModel: GameViewModel,
    gameData: GameData,
    disciples: List<DiscipleAggregate>,
    equipment: List<EquipmentInstance>,
    manuals: List<ManualInstance>,
    manualStacks: List<ManualStack>,
    equipmentStacks: List<EquipmentStack>,
    seeds: List<Seed>,
    activeSectId: String,
    activeSectBuildings: List<GridBuildingData>,
    saveLoadViewModel: SaveLoadViewModel,
    productionViewModel: ProductionViewModel,
    alchemyViewModel: AlchemyViewModel,
    forgeViewModel: ForgeViewModel,
    herbGardenViewModel: HerbGardenViewModel,
    spiritMineViewModel: SpiritMineViewModel,
    patrolTowerViewModel: PatrolTowerViewModel,
    worldMapViewModel: WorldMapViewModel,
    battleViewModel: BattleViewModel,
    battleLogs: List<BattleLog>,
    onLogout: () -> Unit,
    onRestartGame: () -> Unit,
    limitAdTracking: Boolean,
    onLimitAdTrackingChanged: (Boolean) -> Unit
) {
    // TipDialog state — collects error/success messages from BaseViewModel
    var tipDialogMessage by remember { mutableStateOf<String?>(null) }
    var tipDialogIsError by remember { mutableStateOf(false) }

    // Battle result detail overlay
    var detailBattleLog by remember { mutableStateOf<BattleLog?>(null) }

    // Collect additional data from ViewModels
    val pendingNotification by viewModel.pendingNotification.collectAsState()
    val pendingBattleResult by viewModel.pendingBattleResult.collectAsState()
    val mapRenderData by viewModel.worldMapRenderData.collectAsState()
    val alchemySlots by viewModel.alchemySlots.collectAsState()
    val forgeSlots by viewModel.forgeSlots.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val pills by viewModel.pills.collectAsState()

    var showBattleResult by remember { mutableStateOf(false) }

    LaunchedEffect(pendingBattleResult) {
        if (pendingBattleResult != null) {
            showBattleResult = true
        }
    }

    // Sync overlay z-order with visibility state
    LaunchedEffect(showBattleResult) {
        if (showBattleResult) viewModel.pushOverlay(TopOverlay.BATTLE_RESULT)
        else viewModel.popOverlay(TopOverlay.BATTLE_RESULT)
    }

    LaunchedEffect(detailBattleLog) {
        if (detailBattleLog != null) viewModel.pushOverlay(TopOverlay.BATTLE_LOG_DETAIL)
        else viewModel.popOverlay(TopOverlay.BATTLE_LOG_DETAIL)
    }

    LaunchedEffect(Unit) {
        viewModel.errorEvents.collect { message ->
            tipDialogMessage = message
            tipDialogIsError = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.successEvents.collect { message ->
            tipDialogMessage = message
            tipDialogIsError = false
        }
    }

    var showWarehouseFullDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.warehouseFullEvent.collect { showWarehouseFullDialog = true }
    }

    // Dialog overlay via NavHost — no animations, instant open/close
    NavHost(
        navController = dialogNavController,
        startDestination = "empty",
        modifier = Modifier.fillMaxSize(),
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable("empty") {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("OVERVIEW")
                onDispose { }
            }
        }

        // Full-screen overlays (floating button dialogs)
        composable(GameRoute.Disciples.route) {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("DISCIPLES")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlay(title = "弟子", onDismiss = { dialogNavController.popBackStack() }) {
                DisciplesTab(
                    gameData = gameData,
                    disciples = disciples.filter { it.isAlive },
                    equipment = equipment,
                    manuals = manuals,
                    manualStacks = manualStacks,
                    equipmentStacks = equipmentStacks,
                    viewModel = viewModel
                )
            }
        }
        composable(GameRoute.Warehouse.route) {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("WAREHOUSE")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            var showBulkSell by remember { mutableStateOf(false) }
            val warehouseCount = gameData.placedBuildings.count { it.displayName == "仓库" }
            val maxCap = GameConfig.Warehouse.BASE_CAPACITY + warehouseCount * GameConfig.Warehouse.CAPACITY_PER_BUILDING
            val totalItems = equipmentStacks.size + manualStacks.size + pills.size + materials.size + herbs.size + seeds.size
            val isFull = totalItems >= maxCap
            val titleText = buildString {
                append("仓库 ($totalItems/$maxCap)")
                if (isFull) append(" 仓库已满")
            }
            FullScreenOverlay(
                title = titleText,
                onDismiss = { dialogNavController.popBackStack() },
                actions = {
                    GameButton(
                        text = "一键出售",
                        onClick = { showBulkSell = true }
                    )
                }
            ) {
                WarehouseTab(
                    viewModel = viewModel,
                    showBulkSellDialog = showBulkSell,
                    onBulkSellDismiss = { showBulkSell = false },
                    onDismiss = { dialogNavController.popBackStack() }
                )
            }
        }
        composable(GameRoute.Settings.route) {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("SETTINGS")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlay(title = "设置", onDismiss = { dialogNavController.popBackStack() }) {
                SettingsTab(
                    viewModel = viewModel,
                    saveLoadViewModel = saveLoadViewModel,
                    onLogout = onLogout,
                    onDismiss = { dialogNavController.popBackStack() },
                    limitAdTracking = limitAdTracking,
                    onLimitAdTrackingChanged = onLimitAdTrackingChanged
                )
            }
        }
        composable(GameRoute.Buildings.route) {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("BUILDINGS")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlay(title = "建造", onDismiss = { dialogNavController.popBackStack() }) {
                BuildingsTab(
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    alchemyViewModel = alchemyViewModel,
                    forgeViewModel = forgeViewModel,
                    herbGardenViewModel = herbGardenViewModel,
                    spiritMineViewModel = spiritMineViewModel,
                    onDismiss = { dialogNavController.popBackStack() }
                )
            }
        }

        // Business dialogs (DialogStateManager -> NavHost)
        composable(GameRoute.Recruit.route) {
            val recruitList by viewModel.recruitListAggregates.collectAsState()
            RecruitDialog(
                recruitList = recruitList,
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.Diplomacy.route) {
            DiplomacyDialog(
                gameData = gameData,
                viewModel = viewModel,
                worldMapViewModel = worldMapViewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.Planting.route) {
            PlantingDialog(
                seeds = seeds,
                gameData = gameData,
                viewModel = viewModel,
                activeSectId = activeSectId,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.Merchant.route) {
            MerchantDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.SalaryConfig.route) {
            SalaryConfigDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.WorldMap.route) {
            WorldMapDialog(
                worldSects = mapRenderData.worldMapSects,
                mapRenderData = mapRenderData,
                gameData = gameData,
                disciples = disciples,
                viewModel = viewModel,
                worldMapViewModel = worldMapViewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.BattleLog.route) {
            BattleLogListDialog(
                battleLogs = battleLogs,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }

        // Construction dialogs (building click -> NavHost)
        composable(GameRoute.SpiritMine.route) {
            val buildingInstanceId = it.arguments?.getString("buildingInstanceId") ?: ""
            SpiritMineDialog(
                buildingInstanceId = buildingInstanceId,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                spiritMineViewModel = spiritMineViewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.HerbGarden.route) {
            HerbGardenDialog(
                gameData = gameData,
                disciples = disciples.filter { it.isAlive },
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.Alchemy.route) {
            val buildingInstanceId = it.arguments?.getString("buildingInstanceId") ?: ""
            AlchemyDialog(
                buildingInstanceId = buildingInstanceId,
                alchemySlots = alchemySlots,
                materials = materials,
                herbs = herbs,
                gameData = gameData,
                disciples = disciples.filter { it.isAlive },
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                alchemyViewModel = alchemyViewModel,
                colors = XianxiaColorScheme(),
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.Forge.route) {
            val buildingInstanceId = it.arguments?.getString("buildingInstanceId") ?: ""
            ForgeDialog(
                buildingInstanceId = buildingInstanceId,
                forgeSlots = forgeSlots,
                materials = materials,
                gameData = gameData,
                disciples = disciples.filter { it.isAlive },
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                forgeViewModel = forgeViewModel,
                colors = XianxiaColorScheme(),
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.Library.route) {
            LibraryDialog(
                manuals = manuals,
                disciples = disciples.filter { it.isAlive },
                gameData = gameData,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.WenDaoPeak.route) {
            WenDaoPeakDialog(
                disciples = disciples.filter { it.isAlive },
                gameData = gameData,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
            )
        }
        composable(GameRoute.QingyunPeak.route) {
            QingyunPeakDialog(
                disciples = disciples.filter { it.isAlive },
                gameData = gameData,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
            )
        }
        composable(GameRoute.TianshuHall.route) {
            TianshuHallDialog(
                gameData = gameData,
                disciples = disciples.filter { it.isAlive },
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.LawEnforcementHall.route) {
            LawEnforcementHallDialog(
                disciples = disciples.filter { it.isAlive },
                gameData = gameData,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.MissionHall.route) {
            MissionHallDialog(
                gameData = gameData,
                disciples = disciples.filter { it.isAlive },
                viewModel = viewModel,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(GameRoute.ReflectionCliff.route) {
            ReflectionCliffDialog(
                disciples = disciples.filter { it.isAlive },
                gameData = gameData,
                onDismiss = { dialogNavController.popBackStack() },
                onExpelDisciple = { discipleId -> viewModel.expelDisciple(discipleId) }
            )
        }
        composable(GameRoute.PatrolTower.route) {
            val buildingInstanceId = it.arguments?.getString("buildingInstanceId") ?: ""
            PatrolTowerDialog(
                buildingInstanceId = buildingInstanceId,
                viewModel = viewModel,
                patrolTowerViewModel = patrolTowerViewModel,
                gameData = gameData,
                disciples = disciples,
                onDismiss = { dialogNavController.popBackStack() }
            )
        }
        composable(
            route = GameRoute.Residence.route,
            arguments = listOf(navArgument("buildingInstanceId") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("buildingInstanceId") ?: ""
            if (instanceId.isNotEmpty()) {
                ResidenceDialog(
                    buildingInstanceId = instanceId,
                    viewModel = viewModel,
                    disciples = disciples,
                    gameData = gameData,
                    onDismiss = { dialogNavController.popBackStack() }
                )
            }
        }
        composable(
            route = GameRoute.WarehouseBuilding.route,
            arguments = listOf(navArgument("buildingInstanceId") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            val instanceId = backStackEntry.arguments?.getString("buildingInstanceId") ?: ""
            if (instanceId.isNotEmpty()) {
                WarehouseDialog(
                    buildingInstanceId = instanceId,
                    gameData = gameData,
                    disciples = disciples,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    onDismiss = { dialogNavController.popBackStack() }
                )
            }
        }
        composable(GameRoute.GameOver.route) {
            GameOverDialog(
                onRestartGame = {
                    dialogNavController.popBackStack()
                    onRestartGame()
                },
                onReturnToMain = {
                    dialogNavController.popBackStack()
                    onLogout()
                }
            )
        }
    }

    // Platform Dialog() windows — naturally on top, not in overlayOrder
    tipDialogMessage?.let { message ->
        StandardPromptDialog(
            onDismissRequest = { tipDialogMessage = null },
            title = if (tipDialogIsError) "错误" else "提示",
            text = message,
            confirmLabel = "确定"
        )
    }

    if (showWarehouseFullDialog) {
        StandardPromptDialog(
            onDismissRequest = { showWarehouseFullDialog = false },
            title = "仓库已满",
            text = "仓库已满物品无法进入仓库直接遗失",
            confirmLabel = "知道了"
        )
    }

    pendingNotification?.let { notification ->
        val hasPrison = activeSectBuildings.any {
            it.displayName == "监牢" || it.buildingId == "reflection_cliff"
        }
        val currentYear = gameData.gameYear

        when (notification) {
            is GameNotification.DiscipleDesertion -> {
                DiscipleDesertionDialog(
                    disciple = notification.disciple,
                    onDismiss = { viewModel.clearNotification() }
                )
            }
            is GameNotification.DiscipleTheftCaught -> {
                DiscipleTheftCaughtDialog(
                    disciple = notification.disciple,
                    hasPrison = hasPrison,
                    onExpel = { viewModel.expelTheftDisciple(notification.disciple.id) },
                    onImprison = { viewModel.imprisonTheftDisciple(notification.disciple.id, currentYear) },
                    onRelease = { viewModel.releaseTheftDisciple(notification.disciple.id) },
                    onDiscipleClick = { /* opens disciple detail */ },
                    onLoyaltyDismissed = { viewModel.onLoyaltyDialogDismissed() }
                )
            }
            is GameNotification.WarehouseTheft -> {
                StandardPromptDialog(
                    onDismissRequest = { viewModel.clearNotification() },
                    title = "仓库被偷盗",
                    text = "宗门仓库被盗，损失了 ${notification.stolenAmount} 灵石",
                    confirmLabel = "知道了"
                )
            }
            is GameNotification.MarriageRequest -> {
                MarriageApprovalDialog(
                    maleDisciple = notification.maleDisciple,
                    femaleDisciple = notification.femaleDisciple,
                    onApprove = {
                        viewModel.approveMarriage(
                            notification.maleDisciple.id,
                            notification.femaleDisciple.id
                        )
                    },
                    onReject = { viewModel.rejectMarriage() }
                )
            }
        }
    }

    // Top-level inline overlay area (rendered in overlayOrder sequence, last = topmost)
    viewModel.overlayOrder.forEach { overlay ->
        when (overlay) {
            TopOverlay.BATTLE_RESULT -> {
                val result = pendingBattleResult
                if (result != null && showBattleResult) {
                    val log = battleLogs.find { it.id == result.battleLogId }
                    BattleResultDialog(
                        resultData = result,
                        battleLog = log,
                        onConfirm = {
                            viewModel.dismissBattleResult()
                            showBattleResult = false
                        },
                        onViewDetail = { selectedLog ->
                            viewModel.dismissBattleResult()
                            showBattleResult = false
                            detailBattleLog = selectedLog
                        },
                        onDismiss = {
                            viewModel.dismissBattleResult()
                            showBattleResult = false
                        }
                    )
                }
            }

            TopOverlay.BATTLE_LOG_DETAIL -> {
                detailBattleLog?.let { log ->
                    BattleLogDetailDialog(
                        log = log,
                        onDismiss = { detailBattleLog = null }
                    )
                }
            }

            TopOverlay.DISCIPLE_DETAIL -> {
                val request by viewModel.detailDisciple.collectAsState()
                request?.let { req ->
                    val updatedDisciple = disciples
                        .find { it.id == req.disciple.id } ?: req.disciple
                    DiscipleDetailDialog(
                        disciple = updatedDisciple,
                        allDisciples = disciples,
                        gameData = gameData,
                        viewModel = viewModel,
                        onDismiss = { viewModel.dismissDiscipleDetail() },
                        onNavigateToDisciple = req.onNavigateToDisciple
                            ?: { d -> viewModel.navigateDiscipleDetail(d) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FullScreenOverlay(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    BackHandler(onBack = onDismiss)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GameColors.PageBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    actions?.invoke()
                    CloseButton(onClick = onDismiss)
                }
                content()
            }
        }
    }
}

@Composable
private fun GameOverDialog(
    onRestartGame: () -> Unit,
    onReturnToMain: () -> Unit
) {
    BackHandler(enabled = true) { /* no-op: game-over can't be dismissed */ }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "宗门覆灭",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF4444)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "你宗所有领地已被攻占，弟子流离失散，\n宗门就此覆灭于修仙界之中...",
                    fontSize = 14.sp,
                    color = Color(0xFFCCCCCC),
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                GameButton(
                    text = "重开游戏",
                    onClick = onRestartGame,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                GameButton(
                    text = "回到主界面",
                    onClick = onReturnToMain,
                    fontSize = 14.sp
                )
            }
        }
    }
}
