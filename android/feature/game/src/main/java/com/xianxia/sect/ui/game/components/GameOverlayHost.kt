package com.xianxia.sect.ui.game.components


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.util.sortedByFollowAttributeAndRealm
import com.xianxia.sect.core.model.WarningStage
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.ui.game.AlchemyViewModel
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
import com.xianxia.sect.ui.theme.XianxiaColorScheme
import com.xianxia.sect.ui.components.RewardDisplayDialog
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.canRenderDialogs
import com.xianxia.sect.core.domain.dialog.DialogType

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
    val pendingMarriageProposals by viewModel.pendingMarriageProposals.collectAsStateWithLifecycle()
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()

    // 引擎事件弹窗生命周期门控（Bugly #3098）：Activity 销毁窗口期（token 失效但
    // 组合仍挂载、doFrame 已排队）禁止新 Dialog 进入组合，否则 Dialog.show 抛
    // BadTokenException。只门控渲染不早退——收集器保持运行，单值状态互相覆盖，
    // 返回前台仅显示最新一条（早退会让 Channel(UNLIMITED) 积压事件爆发回放）。
    // 用户主动打开的对话框（currentDialogType 路径）不门控，避免行为回归。
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val dialogRenderable = lifecycleState.canRenderDialogs()

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

    // 统一"仓库容量不足"提示框：手动获得路径（领取按钮等）与自动入库路径（溢出转邮件）共用
    var capacityWarningMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.capacityWarningEvents.collect { message ->
            capacityWarningMessage = message
        }
    }
    LaunchedEffect(Unit) {
        viewModel.warehouseFullEvent.collect { message ->
            capacityWarningMessage = message
        }
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
    // 单例遮罩层：无论开几个界面，永远只画一层遮罩
    val marriageProposalVisible = pendingMarriageProposals.firstOrNull() != null
    val attackWarnings by viewModel.attackWarnings.collectAsStateWithLifecycle()
    val shownWarningStageIds by viewModel.shownWarningStageIds.collectAsStateWithLifecycle()
    val attackWarningVisible = attackWarnings.any { warning ->
        val shownIds = shownWarningStageIds
        (warning.stage == WarningStage.DENUNCIATION && "${warning.warningId}:DENUNCIATION" !in shownIds) ||
            (warning.stage == WarningStage.WAR_DECLARATION && "${warning.warningId}:WAR_DECLARATION" !in shownIds)
    }
    val anyDialogVisible = currentDialogType != DialogType.None ||
        tipDialogMessage != null ||
        capacityWarningMessage != null ||
        pendingNotification != null ||
        currentAttack != null ||
        marriageProposalVisible ||
        attackWarningVisible ||
        viewModel.overlayOrder.isNotEmpty()
    if (anyDialogVisible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
        )
    }

    if (dialogRenderable && currentAttack != null && beastStillAlive) {
        BeastAttackWarningDialog(
            attack = currentAttack,
            currentSpiritStones = gdSnapshot.spiritStones,
            scrimEnabled = false,
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

    // ── 婚姻提议弹窗 ──────────────────────────────────────────

    val currentProposal = pendingMarriageProposals.firstOrNull()

    if (dialogRenderable && currentProposal != null) {
        val maleDisciple = disciples.find { it.id == currentProposal.maleId }
        val femaleDisciple = disciples.find { it.id == currentProposal.femaleId }
        if (maleDisciple != null && femaleDisciple != null) {
            MarriageApprovalDialog(
                maleDisciple = maleDisciple,
                femaleDisciple = femaleDisciple,
                onApprove = { viewModel.approveMarriage(currentProposal.maleId, currentProposal.femaleId) },
                onReject = { viewModel.rejectMarriage(currentProposal.maleId, currentProposal.femaleId) },
                scrimEnabled = false
            )
        }
    }

    // AI宗门进攻预警弹窗
    val gdForWarning by viewModel.gameDataUi.collectAsStateWithLifecycle()

    if (dialogRenderable) {
        AttackWarningDialogs(
        warnings = attackWarnings,
        shownStageIds = shownWarningStageIds,
        currentSpiritStones = gdForWarning.spiritStones,
        scrimEnabled = false,
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
    }

    val onDismiss: () -> Unit = { viewModel.dismissDialog() }

    if (currentDialogType != DialogType.None) {
        // 仅在 Dialog 可见时订阅 gameData，避免无 Dialog 时的不必要 StateFlow 订阅
        val gameData by viewModel.gameDataUi.collectAsStateWithLifecycle()

        key(currentDialogType) {
            OverlayDialogRoute(
                type = currentDialogType,
                vms = vms,
                callbacks = callbacks,
                gameData = gameData,
                onDismiss = onDismiss
            )
        }
    }

    if (dialogRenderable && showBattleRewardDialog && pendingBattleRewardCards.isNotEmpty()) {
        RewardDisplayDialog(
            title = "战斗奖励",
            cards = pendingBattleRewardCards,
            onConfirm = {
                viewModel.enqueueBattleRewardCards()
                showBattleRewardDialog = false
            }
        )
    }

    if (dialogRenderable) tipDialogMessage?.let { message ->
        StandardPromptDialog(
            onDismissRequest = { tipDialogMessage = null },
            title = if (tipDialogIsError) "错误" else "提示",
            text = message,
            confirmLabel = "确定",
            scrimEnabled = false
        )
    }

    if (dialogRenderable) capacityWarningMessage?.let { message ->
        StandardPromptDialog(
            onDismissRequest = { capacityWarningMessage = null },
            title = "仓库容量不足",
            text = message,
            confirmLabel = "知道了",
            // 支持点击屏幕外关闭（dismissOnClickOutside 默认 true）
            scrimEnabled = false
        )
    }

    // A6（2026-08-05）：云读档覆盖确认——目标槽位已有本地存档时不静默覆盖
    val cloudOverwrite by saveLoadViewModel.cloudOverwriteRequest.collectAsStateWithLifecycle()
    if (dialogRenderable && cloudOverwrite != null) {
        StandardPromptDialog(
            onDismissRequest = { saveLoadViewModel.cancelCloudOverwrite() },
            title = "覆盖本地存档？",
            text = "云端存档（第${cloudOverwrite!!.cloudYear}年${cloudOverwrite!!.cloudMonth}月 " +
                "${cloudOverwrite!!.cloudSectName}）将写入槽位 ${cloudOverwrite!!.slot}，" +
                "该槽位的本地存档将被覆盖。\n\n确定要覆盖吗？",
            confirmLabel = "覆盖并继续",
            onConfirm = { saveLoadViewModel.confirmCloudOverwrite() },
            dismissLabel = "取消",
            onDismiss = { saveLoadViewModel.cancelCloudOverwrite() },
            dismissOnClickOutside = false,
            scrimEnabled = false
        )
    }

    if (dialogRenderable && pendingNotification != null) {
        pendingNotification?.let { notification ->
            when (notification) {
                is GameNotification.RecruitFailed -> {
                    StandardPromptDialog(
                        onDismissRequest = { viewModel.clearNotification() },
                        title = "招募失败",
                        text = notification.reason,
                        confirmLabel = "知道了",
                        scrimEnabled = false
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
                if (dialogRenderable && result != null && showBattleResult) {
                    val log = battleLogs.find { it.id == result.battleLogId }
                    BattleResultDialog(
                        resultData = result,
                        battleLog = log,
                        viewModel = viewModel,
                        scrimEnabled = false,
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
                        onDismiss = { detailBattleLog = null },
                        scrimEnabled = false
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
                        scrimEnabled = false,
                        onNavigateToDisciple = req.onNavigateToDisciple
                            ?: { d -> viewModel.navigateDiscipleDetail(d) }
                    )
                }
            }
        }
    }

}

