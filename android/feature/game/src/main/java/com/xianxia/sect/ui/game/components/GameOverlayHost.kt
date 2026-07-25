package com.xianxia.sect.ui.game.components

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.util.sortedByFollowAttributeAndRealm
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.ui.game.AlchemyViewModel
import com.xianxia.sect.ui.game.ActivityViewModel
import com.xianxia.sect.ui.game.BattleViewModel
import com.xianxia.sect.ui.game.BloodRefiningViewModel
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import com.xianxia.sect.ui.game.ForgeViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HerbGardenViewModel
import com.xianxia.sect.ui.game.PatrolTowerViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.SaveLoadViewModel
import com.xianxia.sect.ui.game.SpiritMineViewModel
import com.xianxia.sect.ui.game.TopOverlay
import com.xianxia.sect.ui.game.WorldMapInteractionViewModel
import com.xianxia.sect.ui.game.WorldMapGarrisonViewModel
import com.xianxia.sect.ui.game.dialogs.*
import com.xianxia.sect.ui.game.tabs.BuildingsTab
import com.xianxia.sect.ui.game.tabs.DisciplesTab
import com.xianxia.sect.ui.game.tabs.SettingsTab
import com.xianxia.sect.ui.game.tabs.WarehouseTab
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.theme.XianxiaColorScheme
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.RewardDisplayDialog
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.model.guide.GuideTaskRegistry
import com.xianxia.sect.ui.game.dialogs.GuideDialog

private val CachedColorScheme = XianxiaColorScheme()

/** GameOverlayHost 所需的所有 ViewModel（聚合减少参数数量） */
data class OverlayViewModels(
    val game: GameViewModel,
    val saveLoad: SaveLoadViewModel,
    val production: ProductionViewModel,
    val alchemy: AlchemyViewModel,
    val forge: ForgeViewModel,
    val herbGarden: HerbGardenViewModel,
    val spiritMine: SpiritMineViewModel,
    val patrolTower: PatrolTowerViewModel,
    val bloodRefining: BloodRefiningViewModel,
    val worldMapInteraction: WorldMapInteractionViewModel,
    val worldMapGarrison: WorldMapGarrisonViewModel,
    val battle: BattleViewModel
)

/** GameOverlayHost 所需的回调参数 */
data class OverlayCallbacks(
    val onLogout: () -> Unit,
    val onRestartGame: () -> Unit,
    val limitAdTracking: Boolean,
    val onLimitAdTrackingChanged: (Boolean) -> Unit
)

@Composable
fun GameOverlayHost(
    vms: OverlayViewModels,
    callbacks: OverlayCallbacks
) {
    // 解构聚合参数为局部变量（保持 1000+ 行现有代码不变）
    val viewModel = vms.game
    val saveLoadViewModel = vms.saveLoad
    val productionViewModel = vms.production
    val alchemyViewModel = vms.alchemy
    val forgeViewModel = vms.forge
    val herbGardenViewModel = vms.herbGarden
    val spiritMineViewModel = vms.spiritMine
    val patrolTowerViewModel = vms.patrolTower
    val bloodRefiningViewModel = vms.bloodRefining
    val worldMapInteractionViewModel = vms.worldMapInteraction
    val worldMapGarrisonViewModel = vms.worldMapGarrison
    val battleViewModel = vms.battle
    val onLogout = callbacks.onLogout
    val onRestartGame = callbacks.onRestartGame
    val limitAdTracking = callbacks.limitAdTracking
    val onLimitAdTrackingChanged = callbacks.onLimitAdTrackingChanged

    var tipDialogMessage by remember { mutableStateOf<String?>(null) }
    var tipDialogIsError by remember { mutableStateOf(false) }

    var detailBattleLog by remember { mutableStateOf<BattleLog?>(null) }

    val currentDialogType by viewModel.currentDialogType.collectAsStateWithLifecycle()

    val pendingNotification by viewModel.pendingNotification.collectAsStateWithLifecycle()
    val pendingBattleResult by viewModel.pendingBattleResult.collectAsStateWithLifecycle()
    val pendingBattleRewardCards by viewModel.pendingBattleRewardCards.collectAsStateWithLifecycle()

    var showBattleResult by remember { mutableStateOf(false) }
    var showBattleRewardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pendingBattleResult) {
        if (pendingBattleResult != null) {
            showBattleResult = true
        }
    }

    LaunchedEffect(showBattleResult) {
        if (showBattleResult) viewModel.pushOverlay(TopOverlay.BATTLE_RESULT)
        else {
            viewModel.popOverlay(TopOverlay.BATTLE_RESULT)
            if (pendingBattleRewardCards.isNotEmpty()) {
                showBattleRewardDialog = true
            }
        }
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

    // 妖兽进攻预警
    val pendingBeastAttacks by viewModel.pendingBeastAttacks
        .collectAsStateWithLifecycle()
    val currentAttack = pendingBeastAttacks.firstOrNull()
    val coroutineScope = rememberCoroutineScope()
    val gdSnapshot by viewModel.gameDataUi.collectAsStateWithLifecycle()

    // 跳过已击败妖兽的预警弹窗（可能被 AI 宗门等异步处理击败）
    val beastStillAlive = currentAttack?.let { attack ->
        gdSnapshot.worldLevels.find { it.id == attack.beastLevel.id }?.defeated != true
    } ?: false

    if (currentAttack != null && !beastStillAlive) {
        LaunchedEffect(currentAttack) {
            viewModel.clearPendingBeastAttacks()
        }
    }

    if (currentAttack != null && beastStillAlive) {
        BeastAttackWarningDialog(
            attack = currentAttack,
            currentSpiritStones = gdSnapshot.spiritStones,
            onPayTribute = {
                coroutineScope.launch {
                    viewModel.resolveBeastAttackPayTribute(
                        currentAttack.beastLevel.id
                    )
                    viewModel.removePendingBeastAttack(
                        currentAttack.beastLevel.id
                    )
                }
            },
            onFight = {
                coroutineScope.launch {
                    viewModel.resolveBeastAttackFight(
                        currentAttack.beastLevel.id
                    )
                    viewModel.removePendingBeastAttack(
                        currentAttack.beastLevel.id
                    )
                }
            }
        )
    }

    // AI宗门进攻预警弹窗
    val attackWarnings by viewModel.attackWarnings.collectAsStateWithLifecycle()
    val shownWarningStageIds by viewModel.shownWarningStageIds.collectAsStateWithLifecycle()
    val gdForWarning by viewModel.gameDataUi.collectAsStateWithLifecycle()

    AttackWarningDialogs(
        warnings = attackWarnings,
        shownStageIds = shownWarningStageIds,
        currentSpiritStones = gdForWarning.spiritStones,
        onAppease = { warning ->
            viewModel.resolveAttackWarningAppease(warning.attackerSectId)
        },
        onBecomeVassal = { warning ->
            viewModel.resolveAttackWarningVassal(warning.attackerSectId)
        },
        onDismissWarning = { warning ->
            viewModel.markWarningStageShown(
                "${warning.warningId}:${warning.stage.name}"
            )
        }
    )

    val onDismiss: () -> Unit = { viewModel.dismissDialog() }

    if (currentDialogType != DialogType.None) {
        // 仅在 Dialog 可见时订阅 gameData，避免无 Dialog 时的不必要 StateFlow 订阅
        val gameData by viewModel.gameDataUi.collectAsStateWithLifecycle()

        key(currentDialogType) {
            @Suppress("UNUSED_EXPRESSION")
            when (val type = currentDialogType) {
                is DialogType.None -> Unit
                is DialogType.Disciples -> {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("DISCIPLES")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlay(title = "弟子", onDismiss = onDismiss) {
                DisciplesTabContent(viewModel = viewModel)
            }
        }
        is DialogType.Warehouse -> {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("WAREHOUSE")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlayWarehouse(viewModel = viewModel, onDismiss = onDismiss)
        }
        is DialogType.Settings -> {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("SETTINGS")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlay(title = "设置", onDismiss = onDismiss, deferContent = false) {
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
        is DialogType.Buildings -> {
            DisposableEffect(Unit) {
                viewModel.setActiveTab("BUILDINGS")
                onDispose { viewModel.setActiveTab("OVERVIEW") }
            }
            FullScreenOverlay(title = "建造", onDismiss = onDismiss, deferContent = false) {
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
        is DialogType.Recruit -> {
            val recruitList by viewModel.recruitListAggregates.collectAsStateWithLifecycle()
            RecruitDialog(
                recruitList = recruitList,
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = onDismiss
            )
        }
        is DialogType.Guide -> {
            val guideClaimedRewardIds by viewModel.guideClaimedRewardIds.collectAsStateWithLifecycle()
            GuideDialog(
                gameData = gameData,
                claimedRewardIds = guideClaimedRewardIds,
                allTasks = GuideTaskRegistry.ALL_TASKS,
                onClaimReward = { taskId -> viewModel.claimGuideReward(taskId) },
                onDismiss = onDismiss
            )
        }
        is DialogType.Diplomacy -> {
            DiplomacyDialog(
                gameData = gameData,
                viewModel = viewModel,
                interactionViewModel = worldMapInteractionViewModel,
                onDismiss = onDismiss
            )
        }
        is DialogType.Planting -> {
            val seeds by viewModel.seeds.collectAsStateWithLifecycle()
            PlantingDialog(
                seeds = seeds,
                gameData = gameData,
                viewModel = viewModel,
                activeSectId = gameData.activeSectId,
                onDismiss = onDismiss
            )
        }
        is DialogType.Merchant -> {
            MerchantDialog(
                gameData = gameData,
                viewModel = viewModel,
                onDismiss = onDismiss
            )
        }
        is DialogType.SalaryConfig -> { }
        is DialogType.WorldMap -> {
            val mapRenderData by viewModel.worldMapRenderData.collectAsStateWithLifecycle()
            val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent
            ) {
                WorldMapDialog(
                    worldSects = mapRenderData.worldMapSects,
                    mapRenderData = mapRenderData,
                    gameData = gameData,
                    disciples = disciples,
                    viewModel = viewModel,
                    interactionViewModel = worldMapInteractionViewModel,
                    garrisonViewModel = worldMapGarrisonViewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.BattleLog -> {
            val battleLogs by viewModel.battleLogs.collectAsStateWithLifecycle()
            val yearlyReports by viewModel.yearlyReports.collectAsStateWithLifecycle()
            BattleLogListDialog(
                battleLogs = battleLogs,
                yearlyReports = yearlyReports,
                onDismiss = onDismiss
            )
        }
        is DialogType.Mail -> {
            MailDialog(
                viewModel = viewModel,
                onDismiss = onDismiss
            )
        }
        is DialogType.Activity -> {
            val activityViewModel = androidx.hilt.navigation.compose.hiltViewModel<ActivityViewModel>()
            ActivityDialog(
                viewModel = activityViewModel,
                gameViewModel = viewModel,
                onDismiss = onDismiss
            )
        }
        is DialogType.SpiritMine -> {
            SpiritMineDialog(
                buildingInstanceId = type.buildingInstanceId,
                viewModel = viewModel,
                productionViewModel = productionViewModel,
                spiritMineViewModel = spiritMineViewModel,
                onDismiss = onDismiss
            )
        }
        is DialogType.HerbGarden -> {
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            DeferredContent {
                HerbGardenDialog(
                    gameData = gameData,
                    disciples = aliveDisciples,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.Alchemy -> {
            val alchemySlots by viewModel.alchemySlots.collectAsStateWithLifecycle()
            val materials by viewModel.materials.collectAsStateWithLifecycle()
            val herbs by viewModel.herbs.collectAsStateWithLifecycle()
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            DeferredContent {
                AlchemyDialog(
                    buildingInstanceId = type.buildingInstanceId,
                    alchemySlots = alchemySlots,
                    materials = materials,
                    herbs = herbs,
                    gameData = gameData,
                    disciples = aliveDisciples,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    alchemyViewModel = alchemyViewModel,
                    colors = CachedColorScheme,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.Forge -> {
            val forgeSlots by viewModel.forgeSlots.collectAsStateWithLifecycle()
            val materials by viewModel.materials.collectAsStateWithLifecycle()
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            DeferredContent {
                ForgeDialog(
                    buildingInstanceId = type.buildingInstanceId,
                    forgeSlots = forgeSlots,
                    materials = materials,
                    gameData = gameData,
                    disciples = aliveDisciples,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    forgeViewModel = forgeViewModel,
                    colors = CachedColorScheme,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.Library -> {
            val manuals by viewModel.manualInstances.collectAsStateWithLifecycle()
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            DeferredContent {
                LibraryDialog(
                    manuals = manuals,
                    disciples = aliveDisciples,
                    gameData = gameData,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.WenDaoPeak -> {
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            DeferredContent {
                WenDaoPeakDialog(
                    disciples = aliveDisciples,
                    gameData = gameData,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.QingyunPeak -> {
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            DeferredContent {
                QingyunPeakDialog(
                    disciples = aliveDisciples,
                    gameData = gameData,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.TianshuHall -> {
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            DeferredContent {
                TianshuHallDialog(
                    gameData = gameData,
                    disciples = aliveDisciples,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.LawEnforcementHall -> {
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            DeferredContent {
                LawEnforcementHallDialog(
                    disciples = aliveDisciples,
                    gameData = gameData,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.MissionHall -> {
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            DeferredContent {
                MissionHallDialog(
                    gameData = gameData,
                    disciples = aliveDisciples,
                    viewModel = viewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.ReflectionCliff -> {
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            DeferredContent {
                ReflectionCliffDialog(
                    disciples = aliveDisciples,
                    gameData = gameData,
                    onDismiss = onDismiss,
                    onExpelDisciple = { discipleId -> viewModel.expelDisciple(discipleId) },
                    onReleaseDisciple = { discipleId -> viewModel.releaseReflectionDisciple(discipleId) }
                )
            }
        }
        is DialogType.PatrolTower -> {
            val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
            PatrolTowerDialog(
                buildingInstanceId = type.buildingInstanceId,
                viewModel = viewModel,
                patrolTowerViewModel = patrolTowerViewModel,
                gameData = gameData,
                disciples = disciples,
                onDismiss = onDismiss
            )
        }
        is DialogType.BloodRefiningPool -> {
            val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
            val materials by viewModel.materials.collectAsStateWithLifecycle()
            DeferredContent {
                BloodRefiningPoolDialog(
                    buildingInstanceId = type.buildingInstanceId,
                    viewModel = viewModel,
                    bloodRefiningViewModel = bloodRefiningViewModel,
                    gameData = gameData,
                    disciples = disciples,
                    materials = materials,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.Residence -> {
            if (type.buildingInstanceId.isNotEmpty()) {
                val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
                ResidenceDialog(
                    buildingInstanceId = type.buildingInstanceId,
                    viewModel = viewModel,
                    disciples = disciples,
                    gameData = gameData,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.WarehouseBuilding -> {
            if (type.buildingInstanceId.isNotEmpty()) {
                val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
                WarehouseDialog(
                    buildingInstanceId = type.buildingInstanceId,
                    gameData = gameData,
                    disciples = disciples,
                    viewModel = viewModel,
                    productionViewModel = productionViewModel,
                    onDismiss = onDismiss
                )
            }
        }
        is DialogType.SectLevelDetail -> {
            val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
            SectLevelDetailDialog(
                gameData = gameData,
                aliveDisciples = aliveDisciples,
                viewModel = viewModel,
                onDismiss = onDismiss
            )
        }
        is DialogType.RenameSect -> {
            val onConfirm = remember(viewModel) {
                { newName: String -> viewModel.renameSect(newName) }
            }
            RenameSectDialog(
                currentName = gameData.sectName,
                onConfirm = onConfirm,
                onDismiss = onDismiss
            )
        }
        is DialogType.GameOver -> {
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
        is DialogType.BuildingSectLevelRequirement -> {
            val requiredLevel = BuildingFeatureRegistry.findByDisplayName(type.buildingName)?.requiredSectLevel ?: 0
            val levelName = SectLevel.levelName(requiredLevel)
            StandardPromptDialog(
                onDismissRequest = onDismiss,
                title = "建造限制",
                text = "需升级至${levelName}方可建造",
                confirmLabel = "知道了",
                scrimEnabled = true,
                dismissOnClickOutside = true
            )
        }
        is DialogType.CloudSave -> {
            CloudSaveDialog(
                saveLoadViewModel = saveLoadViewModel,
                onDismiss = onDismiss
            )
        }
        }
    }
    }

    if (showBattleRewardDialog && pendingBattleRewardCards.isNotEmpty()) {
        RewardDisplayDialog(
            title = "战斗奖励",
            cards = pendingBattleRewardCards,
            onConfirm = {
                viewModel.enqueueBattleRewardCards()
                showBattleRewardDialog = false
            }
        )
    }

    // 子对话框的遮罩控制：有主对话框显示时，子对话框不再叠加自己的遮罩
    val hasMainDialog = currentDialogType != DialogType.None
    val subDialogScrim = !hasMainDialog

    tipDialogMessage?.let { message ->
        StandardPromptDialog(
            onDismissRequest = { tipDialogMessage = null },
            title = if (tipDialogIsError) "错误" else "提示",
            text = message,
            confirmLabel = "确定",
            scrimEnabled = subDialogScrim
        )
    }

    if (showWarehouseFullDialog) {
        StandardPromptDialog(
            onDismissRequest = { showWarehouseFullDialog = false },
            title = "仓库已满",
            text = "仓库已满物品无法进入仓库直接遗失",
            confirmLabel = "知道了",
            scrimEnabled = subDialogScrim
        )
    }

    if (pendingNotification != null) {
        pendingNotification?.let { notification ->
            when (notification) {
                is GameNotification.RecruitFailed -> {
                    StandardPromptDialog(
                        onDismissRequest = { viewModel.clearNotification() },
                        title = "招募失败",
                        text = notification.reason,
                        confirmLabel = "知道了",
                        scrimEnabled = subDialogScrim
                    )
                }
            }
        }
    }

    viewModel.overlayOrder.forEach { overlay ->
        when (overlay) {
            TopOverlay.BATTLE_RESULT -> {
                val battleLogs by viewModel.battleLogs.collectAsStateWithLifecycle()
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
                val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
                val manualProficiencies by viewModel.manualProficiencies.collectAsStateWithLifecycle()
                val request by viewModel.detailDisciple.collectAsStateWithLifecycle()
                request?.let { req ->
                    val sortedDisciples = remember(aliveDisciples) {
                        aliveDisciples.sortedByFollowAttributeAndRealm()
                    }
                    val updatedDisciple = sortedDisciples
                        .find { it.id == req.disciple.id } ?: req.disciple
                    DiscipleDetailDialog(
                        disciple = updatedDisciple,
                        allDisciples = sortedDisciples,
                        manualProficiencies = manualProficiencies,
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
    val gameData by viewModel.gameDataUi.collectAsStateWithLifecycle()
    val aliveDisciples by viewModel.aliveDisciples.collectAsStateWithLifecycle()
    val equipment by viewModel.equipmentInstances.collectAsStateWithLifecycle()
    val manuals by viewModel.manualInstances.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    DisciplesTab(
        gameData = gameData,
        disciples = aliveDisciples,
        equipment = equipment,
        manuals = manuals,
        manualStacks = manualStacks,
        equipmentStacks = equipmentStacks,
        viewModel = viewModel
    )
}

@Composable
private fun FullScreenOverlayWarehouse(viewModel: GameViewModel, onDismiss: () -> Unit) {
    val gameData by viewModel.gameDataUi.collectAsStateWithLifecycle()
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()

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
private fun DeferredContent(content: @Composable () -> Unit) {
    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showContent = true
    }
    if (showContent) {
        content()
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f - it * 0.15f)
                        .height(16.dp)
                        .background(Color(0x1A000000), RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

@Composable
private fun FullScreenOverlay(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable (() -> Unit)? = null,
    deferContent: Boolean = true,
    content: @Composable () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = title,
        mode = DialogMode.Full,
        showCloseButton = true,
        dismissOnBackPress = true,
        dismissOnClickOutside = false,
        headerActions = actions,
        scrollableContent = false
    ) {
        if (deferContent) {
            DeferredContent { content() }
        } else {
            content()
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
