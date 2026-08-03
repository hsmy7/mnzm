package com.xianxia.sect.ui.game.components

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.model.guide.GuideTaskRegistry
import com.xianxia.sect.ui.game.ActivityViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.*
import com.xianxia.sect.ui.game.dialogs.GuideDialog
import com.xianxia.sect.ui.game.tabs.BuildingsTab
import com.xianxia.sect.ui.game.tabs.DisciplesTab
import com.xianxia.sect.ui.game.tabs.SettingsTab
import com.xianxia.sect.ui.game.tabs.WarehouseTab
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.theme.XianxiaColorScheme

private val CachedColorScheme = XianxiaColorScheme()

/**
 * DialogType 34 分支路由（从 GameOverlayHost 拆分）。
 *
 * 分支体缩进参差为历史遗留，搬移时原样保留以保证行为逐字节一致。
 * 仅在 Dialog 可见时由 GameOverlayHost 调用（key(currentDialogType) 外包裹）。
 */
@Composable
internal fun OverlayDialogRoute(
    type: DialogType,
    vms: OverlayViewModels,
    callbacks: OverlayCallbacks,
    gameData: GameData,
    onDismiss: () -> Unit
) {
    // 解构聚合参数（与 GameOverlayHost 保持一致）
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
    val onLogout = callbacks.onLogout
    val onRestartGame = callbacks.onRestartGame
    val limitAdTracking = callbacks.limitAdTracking
    val onLimitAdTrackingChanged = callbacks.onLimitAdTrackingChanged

    @Suppress("UNUSED_EXPRESSION")
    when (val currentDialogType = type) {
                is DialogType.None -> Unit
                is DialogType.Disciples -> {
            // C-3：脚手架统一（DialogTabScaffold 封装 setActiveTab/复位）
            DialogTabScaffold(tab = "DISCIPLES", viewModel = viewModel) {
                FullScreenOverlay(title = "弟子", onDismiss = onDismiss, scrimEnabled = false) {
                    DisciplesTabContent(viewModel = viewModel)
                }
            }
        }
        is DialogType.Warehouse -> {
            DialogTabScaffold(tab = "WAREHOUSE", viewModel = viewModel) {
                FullScreenOverlayWarehouse(viewModel = viewModel, onDismiss = onDismiss)
            }
        }
        is DialogType.Settings -> {
            DialogTabScaffold(tab = "SETTINGS", viewModel = viewModel) {
                FullScreenOverlay(title = "设置", onDismiss = onDismiss, scrimEnabled = false, deferContent = false) {
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
        }
        is DialogType.Buildings -> {
            DialogTabScaffold(tab = "BUILDINGS", viewModel = viewModel) {
                FullScreenOverlay(title = "建造", onDismiss = onDismiss, scrimEnabled = false, deferContent = false) {
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
        is DialogType.Lizhan -> {
            LizhanDialog(
                viewModel = viewModel,
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
                scrimEnabled = false,
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
private fun FullScreenOverlayWarehouse(
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    warehouseBaseCapacity: Int = GameConfig.Warehouse.BASE_CAPACITY,
    warehouseCapacityPerBuilding: Int = GameConfig.Warehouse.CAPACITY_PER_BUILDING
) {
    val gameData by viewModel.gameDataUi.collectAsStateWithLifecycle()
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()
    val pills by viewModel.pills.collectAsStateWithLifecycle()
    val materials by viewModel.materials.collectAsStateWithLifecycle()
    val herbs by viewModel.herbs.collectAsStateWithLifecycle()
    val seeds by viewModel.seeds.collectAsStateWithLifecycle()

    var showBulkSell by remember { mutableStateOf(false) }
    val warehouseCount = gameData.placedBuildings.count { it.displayName == "仓库" }
    val maxCap = warehouseBaseCapacity + warehouseCount * warehouseCapacityPerBuilding
    val totalItems = equipmentStacks.size + manualStacks.size + pills.size + materials.size + herbs.size + seeds.size
    val isFull = totalItems >= maxCap
    val titleText = buildString {
        append("仓库 ($totalItems/$maxCap)")
        if (isFull) append(" 仓库已满")
    }
    FullScreenOverlay(
        title = titleText,
        onDismiss = onDismiss,
        scrimEnabled = false,
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

/**
 * C-3：全屏 Tab 对话框脚手架（进入时设置 activeTab，退出时复位 OVERVIEW）。
 * 原 4 处逐字相同的 DisposableEffect 块统一封装。
 */
@Composable
private fun DialogTabScaffold(
    tab: String,
    viewModel: GameViewModel,
    content: @Composable () -> Unit
) {
    DisposableEffect(Unit) {
        viewModel.setActiveTab(tab)
        onDispose { viewModel.setActiveTab("OVERVIEW") }
    }
    content()
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
    scrimEnabled: Boolean = true,
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
        scrollableContent = false,
        scrimEnabled = scrimEnabled
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

