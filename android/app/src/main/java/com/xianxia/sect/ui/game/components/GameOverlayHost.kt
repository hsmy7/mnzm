package com.xianxia.sect.ui.game.components

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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

import com.xianxia.sect.R
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.ui.game.AlchemyViewModel
import com.xianxia.sect.ui.game.BattleViewModel
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import com.xianxia.sect.ui.game.ForgeViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HerbGardenViewModel
import com.xianxia.sect.ui.game.PatrolTowerViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.SaveLoadViewModel
import com.xianxia.sect.ui.game.SpiritMineViewModel
import com.xianxia.sect.ui.game.TopOverlay
import com.xianxia.sect.ui.game.WorldMapViewModel
import com.xianxia.sect.ui.game.dialogs.*
import com.xianxia.sect.ui.game.tabs.BuildingsTab
import com.xianxia.sect.ui.game.tabs.DisciplesTab
import com.xianxia.sect.ui.game.tabs.SettingsTab
import com.xianxia.sect.ui.game.tabs.WarehouseTab
import com.xianxia.sect.ui.navigation.DialogRoute
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.XianxiaColorScheme
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog

private val CachedColorScheme = XianxiaColorScheme()

@Composable
fun GameOverlayHost(
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    productionViewModel: ProductionViewModel,
    alchemyViewModel: AlchemyViewModel,
    forgeViewModel: ForgeViewModel,
    herbGardenViewModel: HerbGardenViewModel,
    spiritMineViewModel: SpiritMineViewModel,
    patrolTowerViewModel: PatrolTowerViewModel,
    worldMapViewModel: WorldMapViewModel,
    battleViewModel: BattleViewModel,
    onLogout: () -> Unit,
    onRestartGame: () -> Unit,
    limitAdTracking: Boolean,
    onLimitAdTrackingChanged: (Boolean) -> Unit
) {
    var tipDialogMessage by remember { mutableStateOf<String?>(null) }
    var tipDialogIsError by remember { mutableStateOf(false) }

    var detailBattleLog by remember { mutableStateOf<BattleLog?>(null) }

    val currentDialogRoute by viewModel.currentDialogRoute.collectAsState()

    val pendingNotification by viewModel.pendingNotification.collectAsState()
    val pendingBattleResult by viewModel.pendingBattleResult.collectAsState()
    Log.w("GameOverlayHost", "pendingBattleResult collected: ${pendingBattleResult?.battleLogId}")

    var showBattleResult by remember { mutableStateOf(false) }

    LaunchedEffect(pendingBattleResult) {
        Log.w("GameOverlayHost", "LaunchedEffect(pendingBattleResult): value=${pendingBattleResult?.battleLogId}")
        if (pendingBattleResult != null) {
            showBattleResult = true
        }
    }

    LaunchedEffect(showBattleResult) {
        Log.w("GameOverlayHost", "showBattleResult changed: $showBattleResult")
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

    LaunchedEffect(Unit) {
        viewModel.popBackEvents.collect { target ->
            if (target == null || target == "empty") {
                viewModel.dismissDialog()
            }
        }
    }

    val onDismiss: () -> Unit = { viewModel.dismissDialog() }

    when (val route = currentDialogRoute) {
        is DialogRoute.None -> { }

        is DialogRoute.Disciples -> {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("DISCIPLES")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlay(title = "弟子", onDismiss = onDismiss) {
                DisciplesTabContent(viewModel = viewModel)
            }
        }
        is DialogRoute.Warehouse -> {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("WAREHOUSE")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlayWarehouse(viewModel = viewModel, onDismiss = onDismiss)
        }
        is DialogRoute.Settings -> {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("SETTINGS")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlay(title = "设置", onDismiss = onDismiss) {
                SettingsTab(
                    viewModel = viewModel,
                    saveLoadViewModel = saveLoadViewModel,
                    onLogout = onLogout,
                    onDismiss = onDismiss,
                    limitAdTracking = limitAdTracking,
                    onLimitAdTrackingChanged = onLimitAdTrackingChanged
                )
            }
        }
        is DialogRoute.Buildings -> {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("BUILDINGS")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlay(title = "建造", onDismiss = onDismiss) {
                BuildingsTab(
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    alchemyViewModel = alchemyViewModel,
                    forgeViewModel = forgeViewModel,
                    herbGardenViewModel = herbGardenViewModel,
                    spiritMineViewModel = spiritMineViewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogRoute.Recruit -> {
            val recruitList by viewModel.recruitListAggregates.collectAsState()
            val gameData by viewModel.gameDataUi.collectAsState()
            RecruitDialog(
                recruitList = recruitList,
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.Diplomacy -> {
            val gameData by viewModel.gameDataUi.collectAsState()
            DiplomacyDialog(
                gameData = gameData,
                viewModel = viewModel,
                worldMapViewModel = worldMapViewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.Planting -> {
            val seeds by viewModel.seeds.collectAsState()
            val gameData by viewModel.gameDataUi.collectAsState()
            PlantingDialog(
                seeds = seeds,
                gameData = gameData,
                viewModel = viewModel,
                activeSectId = gameData.activeSectId,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.Merchant -> {
            val gameData by viewModel.gameDataUi.collectAsState()
            MerchantDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.SalaryConfig -> {
            val gameData by viewModel.gameDataUi.collectAsState()
            SalaryConfigDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.WorldMap -> {
            val mapRenderData by viewModel.worldMapRenderData.collectAsState()
            val gameData by viewModel.gameDataUi.collectAsState()
            val disciples by viewModel.discipleAggregates.collectAsState()
            WorldMapDialog(
                worldSects = mapRenderData.worldMapSects,
                mapRenderData = mapRenderData,
                gameData = gameData,
                disciples = disciples,
                viewModel = viewModel,
                worldMapViewModel = worldMapViewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.BattleLog -> {
            val battleLogs by viewModel.battleLogs.collectAsState()
            BattleLogListDialog(
                battleLogs = battleLogs,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.SpiritMine -> {
            SpiritMineDialog(
                buildingInstanceId = route.buildingInstanceId,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                spiritMineViewModel = spiritMineViewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.HerbGarden -> {
            val gameData by viewModel.gameDataUi.collectAsState()
            val disciples by viewModel.discipleAggregates.collectAsState()
            val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
            HerbGardenDialog(
                gameData = gameData,
                disciples = aliveDisciples.value,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.Alchemy -> {
            val alchemySlots by viewModel.alchemySlots.collectAsState()
            val materials by viewModel.materials.collectAsState()
            val herbs by viewModel.herbs.collectAsState()
            val gameData by viewModel.gameDataUi.collectAsState()
            val disciples by viewModel.discipleAggregates.collectAsState()
            val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
            AlchemyDialog(
                buildingInstanceId = route.buildingInstanceId,
                alchemySlots = alchemySlots,
                materials = materials,
                herbs = herbs,
                gameData = gameData,
                disciples = aliveDisciples.value,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                alchemyViewModel = alchemyViewModel,
                colors = CachedColorScheme,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.Forge -> {
            val forgeSlots by viewModel.forgeSlots.collectAsState()
            val materials by viewModel.materials.collectAsState()
            val gameData by viewModel.gameDataUi.collectAsState()
            val disciples by viewModel.discipleAggregates.collectAsState()
            val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
            ForgeDialog(
                buildingInstanceId = route.buildingInstanceId,
                forgeSlots = forgeSlots,
                materials = materials,
                gameData = gameData,
                disciples = aliveDisciples.value,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                forgeViewModel = forgeViewModel,
                colors = CachedColorScheme,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.Library -> {
            val manuals by viewModel.manuals.collectAsState()
            val disciples by viewModel.discipleAggregates.collectAsState()
            val gameData by viewModel.gameDataUi.collectAsState()
            val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
            LibraryDialog(
                manuals = manuals,
                disciples = aliveDisciples.value,
                gameData = gameData,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.WenDaoPeak -> {
            val disciples by viewModel.discipleAggregates.collectAsState()
            val gameData by viewModel.gameDataUi.collectAsState()
            val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
            WenDaoPeakDialog(
                disciples = aliveDisciples.value,
                gameData = gameData,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
            )
        }
        is DialogRoute.QingyunPeak -> {
            val disciples by viewModel.discipleAggregates.collectAsState()
            val gameData by viewModel.gameDataUi.collectAsState()
            val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
            QingyunPeakDialog(
                disciples = aliveDisciples.value,
                gameData = gameData,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
            )
        }
        is DialogRoute.TianshuHall -> {
            val gameData by viewModel.gameDataUi.collectAsState()
            val disciples by viewModel.discipleAggregates.collectAsState()
            val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
            TianshuHallDialog(
                gameData = gameData,
                disciples = aliveDisciples.value,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.LawEnforcementHall -> {
            val disciples by viewModel.discipleAggregates.collectAsState()
            val gameData by viewModel.gameDataUi.collectAsState()
            val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
            LawEnforcementHallDialog(
                disciples = aliveDisciples.value,
                gameData = gameData,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.MissionHall -> {
            val gameData by viewModel.gameDataUi.collectAsState()
            val disciples by viewModel.discipleAggregates.collectAsState()
            val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
            MissionHallDialog(
                gameData = gameData,
                disciples = aliveDisciples.value,
                viewModel = viewModel,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.ReflectionCliff -> {
            val disciples by viewModel.discipleAggregates.collectAsState()
            val gameData by viewModel.gameDataUi.collectAsState()
            val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
            ReflectionCliffDialog(
                disciples = aliveDisciples.value,
                gameData = gameData,
                onDismiss = onDismiss,
                onExpelDisciple = { discipleId -> viewModel.expelDisciple(discipleId) }
            )
        }
        is DialogRoute.PatrolTower -> {
            val gameData by viewModel.gameDataUi.collectAsState()
            val disciples by viewModel.discipleAggregates.collectAsState()
            PatrolTowerDialog(
                buildingInstanceId = route.buildingInstanceId,
                viewModel = viewModel,
                patrolTowerViewModel = patrolTowerViewModel,
                gameData = gameData,
                disciples = disciples,
                onDismiss = onDismiss
            )
        }
        is DialogRoute.Residence -> {
            if (route.buildingInstanceId.isNotEmpty()) {
                val gameData by viewModel.gameDataUi.collectAsState()
                val disciples by viewModel.discipleAggregates.collectAsState()
                ResidenceDialog(
                    buildingInstanceId = route.buildingInstanceId,
                    viewModel = viewModel,
                    disciples = disciples,
                    gameData = gameData,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogRoute.WarehouseBuilding -> {
            if (route.buildingInstanceId.isNotEmpty()) {
                val gameData by viewModel.gameDataUi.collectAsState()
                val disciples by viewModel.discipleAggregates.collectAsState()
                WarehouseDialog(
                    buildingInstanceId = route.buildingInstanceId,
                    gameData = gameData,
                    disciples = disciples,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogRoute.GameOver -> {
            GameOverDialog(
                onRestartGame = {
                    viewModel.dismissDialog()
                    onRestartGame()
                },
                onReturnToMain = {
                    viewModel.dismissDialog()
                    onLogout()
                }
            )
        }
    }

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

    val gameData by viewModel.gameDataUi.collectAsState()
    val placedBuildings by viewModel.placedBuildings.collectAsState()
    val activeSectBuildings = remember(placedBuildings, gameData.activeSectId) {
        derivedStateOf { placedBuildings.filter { it.sectId == gameData.activeSectId } }
    }

    pendingNotification?.let { notification ->
        val hasPrison = activeSectBuildings.value.any {
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
                    onDiscipleClick = { },
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

    val battleLogs by viewModel.battleLogs.collectAsState()
    val disciples by viewModel.discipleAggregates.collectAsState()

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
private fun DisciplesTabContent(viewModel: GameViewModel) {
    val gameData by viewModel.gameDataUi.collectAsState()
    val disciples by viewModel.discipleAggregates.collectAsState()
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val manualStacks by viewModel.manualStacks.collectAsState()
    val equipmentStacks by viewModel.equipmentStacks.collectAsState()
    val aliveDisciples = remember(disciples) { derivedStateOf { disciples.filter { it.isAlive } } }
    DisciplesTab(
        gameData = gameData,
        disciples = aliveDisciples.value,
        equipment = equipment,
        manuals = manuals,
        manualStacks = manualStacks,
        equipmentStacks = equipmentStacks,
        viewModel = viewModel
    )
}

@Composable
private fun FullScreenOverlayWarehouse(viewModel: GameViewModel, onDismiss: () -> Unit) {
    val gameData by viewModel.gameDataUi.collectAsState()
    val equipmentStacks by viewModel.equipmentStacks.collectAsState()
    val manualStacks by viewModel.manualStacks.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()

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
        onDismiss = onDismiss,
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
            onDismiss = onDismiss
        )
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
    BackHandler(enabled = true) { }
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
