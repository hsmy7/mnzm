@file:Suppress("TooManyFunctions") // 拆分聚合:提取的私有辅助函数集中在原文件,文件级复杂度为拆分代价
package com.xianxia.sect.ui.game.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.state.PendingMarriageProposal
import com.xianxia.sect.core.util.sortedByFollowAttributeAndRealm
import com.xianxia.sect.ui.game.AlchemyViewModel
import com.xianxia.sect.ui.game.BattleViewModel
import com.xianxia.sect.ui.game.BloodRefiningViewModel
import com.xianxia.sect.ui.game.CloudOverwriteRequest
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
import com.xianxia.sect.ui.game.dialogs.AttackWarningDialogs
import com.xianxia.sect.ui.game.dialogs.BattleLogDetailDialog
import com.xianxia.sect.ui.game.dialogs.BattleResultDialog
import com.xianxia.sect.ui.game.dialogs.BeastAttackWarningDialog
import com.xianxia.sect.ui.game.dialogs.MarriageApprovalDialog
import com.xianxia.sect.ui.theme.XianxiaColorScheme
import com.xianxia.sect.ui.components.LocalDialogScrimHosted
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
    val onRestartGame: () -> Unit
)

@Composable
fun GameOverlayHost(
    vms: OverlayViewModels,
    callbacks: OverlayCallbacks
) {
    // 对话框窗口触摸 → 刷新引擎闲置计时（Dialog 独立 Window 不触发
    // Activity.onUserInteraction；CompositionLocal 经 Dialog 组合子树继承，
    // 一处提供覆盖全部对话框，防对话框内挂机误触发动态帧率降档）
    val dialogTouchReporter = vms.game::onUserInteraction
    androidx.compose.runtime.CompositionLocalProvider(
        com.xianxia.sect.ui.components.LocalOnUserInteraction provides dialogTouchReporter,
        // 单例遮罩守卫：本宿主已绘制 GameOverlayScrim，宿主下所有对话框
        // （含嵌套子对话框）强制不自画遮罩，防多层半透明黑 α 叠加使界面外一片黑
        LocalDialogScrimHosted provides true
    ) {
    val viewModel = vms.game
    val saveLoadViewModel = vms.saveLoad

    val state = rememberGameOverlayDialogState(viewModel = viewModel)
    val data = rememberGameOverlayDialogData(viewModel = viewModel, state = state)

    GameOverlayScrim(visible = data.anyDialogVisible)

    GameOverlayAttackSections(
        currentAttack = data.currentAttack,
        beastStillAlive = data.beastStillAlive,
        dialogRenderable = data.dialogRenderable,
        gdSnapshot = data.gdSnapshot,
        coroutineScope = data.coroutineScope,
        viewModel = viewModel,
        attackWarnings = data.attackWarnings,
        shownWarningStageIds = data.shownWarningStageIds
    )

    MarriageProposalSection(
        currentProposal = data.currentProposal,
        disciples = data.disciples,
        dialogRenderable = data.dialogRenderable,
        viewModel = viewModel
    )

    val onDismiss: () -> Unit = { viewModel.dismissDialog() }

    GameOverlayDialogs(
        vms = vms,
        callbacks = callbacks,
        state = state,
        data = data,
        viewModel = viewModel,
        saveLoadViewModel = saveLoadViewModel,
        onDismiss = onDismiss
    )

    } // CompositionLocalProvider(LocalOnUserInteraction)

}

/** GameOverlayHost 弹窗本地状态（GameOverlayHost 拆分）：弹窗开关 + 引擎事件流订阅 */
private class GameOverlayDialogState {
    var tipDialogMessage by mutableStateOf<String?>(null)
    var tipDialogIsError by mutableStateOf(false)
    var detailBattleLog by mutableStateOf<BattleLog?>(null)
    var capacityWarningMessage by mutableStateOf<String?>(null)
    var showBattleResult by mutableStateOf(false)
    var showBattleRewardDialog by mutableStateOf(false)
}

/** GameOverlayHost 派生弹窗数据（GameOverlayHost 拆分）：StateFlow 收集 + 可见性推导 */
private data class GameOverlayDialogData(
    val currentDialogType: DialogType,
    val pendingNotification: GameNotification?,
    val currentProposal: PendingMarriageProposal?,
    val disciples: List<DiscipleAggregate>,
    val dialogRenderable: Boolean,
    val currentAttack: PendingBeastAttack?,
    val coroutineScope: CoroutineScope,
    val gdSnapshot: GameData,
    val beastStillAlive: Boolean,
    val attackWarnings: List<AttackWarning>,
    val shownWarningStageIds: List<String>,
    val anyDialogVisible: Boolean
)

/** GameOverlayHost 事件流订阅（GameOverlayHost 拆分）：错误/成功/容量警告 + 战斗结算联动 */
@Composable
private fun rememberGameOverlayDialogState(viewModel: GameViewModel): GameOverlayDialogState {
    val state = remember { GameOverlayDialogState() }
    val pendingBattleResult by viewModel.pendingBattleResult.collectAsStateWithLifecycle()
    val pendingBattleRewardCards by viewModel.pendingBattleRewardCards.collectAsStateWithLifecycle()

    LaunchedEffect(pendingBattleResult) {
        if (pendingBattleResult != null) {
            state.showBattleResult = true
        }
    }

    LaunchedEffect(state.showBattleResult) {
        if (state.showBattleResult) viewModel.pushOverlay(TopOverlay.BATTLE_RESULT)
        else {
            viewModel.popOverlay(TopOverlay.BATTLE_RESULT)
            if (pendingBattleRewardCards.isNotEmpty()) {
                state.showBattleRewardDialog = true
            }
        }
    }

    LaunchedEffect(state.detailBattleLog) {
        if (state.detailBattleLog != null) viewModel.pushOverlay(TopOverlay.BATTLE_LOG_DETAIL)
        else viewModel.popOverlay(TopOverlay.BATTLE_LOG_DETAIL)
    }

    LaunchedEffect(Unit) {
        viewModel.errorEvents.collect { message ->
            state.tipDialogMessage = message
            state.tipDialogIsError = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.successEvents.collect { message ->
            state.tipDialogMessage = message
            state.tipDialogIsError = false
        }
    }

    // 统一"仓库容量不足"提示框：手动获得路径（领取按钮等）与自动入库路径（溢出转邮件）共用
    LaunchedEffect(Unit) {
        viewModel.capacityWarningEvents.collect { message ->
            state.capacityWarningMessage = message
        }
    }

    LaunchedEffect(Unit) {
        viewModel.warehouseFullEvent.collect { message ->
            state.capacityWarningMessage = message
        }
    }
    return state
}

/** GameOverlayHost 派生弹窗数据计算（GameOverlayHost 拆分）：StateFlow 收集 + 可见性推导 */
@Composable
private fun rememberGameOverlayDialogData(
    viewModel: GameViewModel,
    state: GameOverlayDialogState
): GameOverlayDialogData {
    val currentDialogType by viewModel.currentDialogType.collectAsStateWithLifecycle()
    val pendingNotification by viewModel.pendingNotification.collectAsStateWithLifecycle()
    val pendingMarriageProposals by viewModel.pendingMarriageProposals.collectAsStateWithLifecycle()
    val disciples by viewModel.discipleAggregates.collectAsStateWithLifecycle()
    // 引擎事件弹窗生命周期门控（Bugly #3098）：Activity 销毁窗口期禁止新 Dialog 进入组合，
    // 只门控渲染不早退（收集器保持运行，返回前台仅显示最新一条）；用户主动打开的对话框不门控
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val dialogRenderable = lifecycleState.canRenderDialogs()
    // 妖兽进攻预警
    val pendingBeastAttacks by viewModel.pendingBeastAttacks
        .collectAsStateWithLifecycle()
    val currentAttack = pendingBeastAttacks.firstOrNull()
    val coroutineScope = rememberCoroutineScope()
    val gdSnapshot by viewModel.gameDataUi.collectAsStateWithLifecycle()
    // 跳过已击败妖兽的预警弹窗（可能被 AI 宗门等异步处理击败）
    val beastStillAlive = isBeastStillAlive(currentAttack, gdSnapshot)
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
        "${warning.warningId}:${warning.stage.name}" !in shownWarningStageIds
    }
    val anyDialogVisible = anyGameOverlayVisible(
        currentDialogType = currentDialogType,
        tipDialogMessage = state.tipDialogMessage,
        capacityWarningMessage = state.capacityWarningMessage,
        pendingNotification = pendingNotification,
        currentAttack = currentAttack,
        marriageProposalVisible = marriageProposalVisible,
        attackWarningVisible = attackWarningVisible,
        overlayOrderNonEmpty = viewModel.overlayOrder.isNotEmpty()
    )
    return GameOverlayDialogData(
        currentDialogType = currentDialogType, pendingNotification = pendingNotification,
        currentProposal = pendingMarriageProposals.firstOrNull(), disciples = disciples,
        dialogRenderable = dialogRenderable, currentAttack = currentAttack,
        coroutineScope = coroutineScope, gdSnapshot = gdSnapshot,
        beastStillAlive = beastStillAlive, attackWarnings = attackWarnings,
        shownWarningStageIds = shownWarningStageIds, anyDialogVisible = anyDialogVisible
    )
}
/** 妖兽是否仍存活（GameOverlayHost 拆分）：可能被 AI 宗门等异步处理击败 */
private fun isBeastStillAlive(
    currentAttack: PendingBeastAttack?,
    gdSnapshot: GameData
): Boolean = currentAttack?.let { attack ->
    gdSnapshot.worldLevels.find { it.id == attack.beastLevel.id }?.defeated != true
} ?: false

/** 任意弹窗可见判断（GameOverlayHost 拆分）：单例遮罩层条件 */
// 拆分聚合:平铺参数搬移自原公共函数
@Suppress("LongParameterList")
private fun anyGameOverlayVisible(
    currentDialogType: DialogType,
    tipDialogMessage: String?,
    capacityWarningMessage: String?,
    pendingNotification: GameNotification?,
    currentAttack: PendingBeastAttack?,
    marriageProposalVisible: Boolean,
    attackWarningVisible: Boolean,
    overlayOrderNonEmpty: Boolean
): Boolean = currentDialogType != DialogType.None ||
    tipDialogMessage != null ||
    capacityWarningMessage != null ||
    pendingNotification != null ||
    currentAttack != null ||
    marriageProposalVisible ||
    attackWarningVisible ||
    overlayOrderNonEmpty

/** 全局对话框遮罩（GameOverlayHost 拆分）：任意弹窗可见时绘制单例遮罩 */
@Composable
private fun GameOverlayScrim(visible: Boolean) {
    if (visible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
        )
    }
}

/** 妖兽进攻预警 + AI 宗门进攻预警弹窗（GameOverlayHost 拆分） */
// 拆分聚合:平铺参数搬移自原公共函数
@Suppress("LongParameterList")
@Composable
private fun GameOverlayAttackSections(
    currentAttack: PendingBeastAttack?,
    beastStillAlive: Boolean,
    dialogRenderable: Boolean,
    gdSnapshot: GameData,
    coroutineScope: CoroutineScope,
    viewModel: GameViewModel,
    attackWarnings: List<AttackWarning>,
    shownWarningStageIds: List<String>
) {
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

    // AI宗门进攻预警弹窗
    if (dialogRenderable) {
        AttackWarningDialogs(
            warnings = attackWarnings,
            shownStageIds = shownWarningStageIds,
            scrimEnabled = false,
            onDismissWarning = { warning ->
                viewModel.markWarningStageShown(
                    "${warning.warningId}:${warning.stage.name}"
                )
            }
        )
    }
}

/** 婚姻提议弹窗（GameOverlayHost 拆分） */
@Composable
private fun MarriageProposalSection(
    currentProposal: PendingMarriageProposal?,
    disciples: List<DiscipleAggregate>,
    dialogRenderable: Boolean,
    viewModel: GameViewModel
) {
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
}

/** GameOverlayHost 弹窗区（GameOverlayHost 拆分）：对话框路由 + 提示弹窗 + 叠加层栈 */
@Composable
private fun GameOverlayDialogs(
    vms: OverlayViewModels,
    callbacks: OverlayCallbacks,
    state: GameOverlayDialogState,
    data: GameOverlayDialogData,
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    onDismiss: () -> Unit
) {
    val pendingBattleResult by viewModel.pendingBattleResult.collectAsStateWithLifecycle()
    val pendingBattleRewardCards by viewModel.pendingBattleRewardCards.collectAsStateWithLifecycle()
    GameDialogRouteSection(
        currentDialogType = data.currentDialogType,
        vms = vms, callbacks = callbacks, onDismiss = onDismiss
    )
    GameBattleRewardSection(
        dialogRenderable = data.dialogRenderable,
        showBattleRewardDialog = state.showBattleRewardDialog,
        pendingBattleRewardCards = pendingBattleRewardCards,
        viewModel = viewModel, onDismiss = { state.showBattleRewardDialog = false }
    )
    GameTipDialogSection(
        dialogRenderable = data.dialogRenderable,
        message = state.tipDialogMessage, isError = state.tipDialogIsError,
        onDismiss = { state.tipDialogMessage = null }
    )
    GameCapacityWarningSection(
        dialogRenderable = data.dialogRenderable,
        message = state.capacityWarningMessage,
        onDismiss = { state.capacityWarningMessage = null }
    )
    // A6（2026-08-05）：云读档覆盖确认——目标槽位已有本地存档时不静默覆盖
    val cloudOverwrite by saveLoadViewModel.cloudOverwriteRequest.collectAsStateWithLifecycle()
    GameCloudOverwriteSection(
        dialogRenderable = data.dialogRenderable,
        cloudOverwrite = cloudOverwrite, saveLoadViewModel = saveLoadViewModel
    )
    GameNotificationSection(
        dialogRenderable = data.dialogRenderable,
        pendingNotification = data.pendingNotification, viewModel = viewModel
    )
    GameOverlayStackSection(
        viewModel = viewModel, dialogRenderable = data.dialogRenderable,
        showBattleResult = state.showBattleResult,
        pendingBattleResult = pendingBattleResult, detailBattleLog = state.detailBattleLog,
        onCloseBattleResult = {
            viewModel.dismissBattleResult()
            state.showBattleResult = false
        },
        onViewBattleLogDetail = { selectedLog ->
            viewModel.dismissBattleResult()
            state.showBattleResult = false
            state.detailBattleLog = selectedLog
        },
        onCloseBattleLogDetail = { state.detailBattleLog = null }
    )
}

/** 当前对话框路由（GameOverlayHost 拆分）：仅在 Dialog 可见时订阅 gameData */
@Composable
private fun GameDialogRouteSection(
    currentDialogType: DialogType,
    vms: OverlayViewModels,
    callbacks: OverlayCallbacks,
    onDismiss: () -> Unit
) {
    if (currentDialogType != DialogType.None) {
        // 仅在 Dialog 可见时订阅 gameData，避免无 Dialog 时的不必要 StateFlow 订阅
        val gameData by vms.game.gameDataUi.collectAsStateWithLifecycle()

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
}

/** 战斗奖励卡片弹窗（GameOverlayHost 拆分） */
@Composable
private fun GameBattleRewardSection(
    dialogRenderable: Boolean,
    showBattleRewardDialog: Boolean,
    pendingBattleRewardCards: List<RewardCardItem>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    if (dialogRenderable && showBattleRewardDialog && pendingBattleRewardCards.isNotEmpty()) {
        RewardDisplayDialog(
            title = "战斗奖励",
            cards = pendingBattleRewardCards,
            onConfirm = {
                viewModel.enqueueBattleRewardCards()
                onDismiss()
            }
        )
    }
}

/** 错误/成功提示弹窗（GameOverlayHost 拆分） */
@Composable
private fun GameTipDialogSection(
    dialogRenderable: Boolean,
    message: String?,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    if (dialogRenderable) message?.let {
        StandardPromptDialog(
            onDismissRequest = onDismiss,
            title = if (isError) "错误" else "提示",
            text = it,
            confirmLabel = "确定",
            scrimEnabled = false
        )
    }
}

/** 仓库容量不足提示弹窗（GameOverlayHost 拆分） */
@Composable
private fun GameCapacityWarningSection(
    dialogRenderable: Boolean,
    message: String?,
    onDismiss: () -> Unit
) {
    if (dialogRenderable) message?.let {
        StandardPromptDialog(
            onDismissRequest = onDismiss,
            title = "仓库容量不足",
            text = it,
            confirmLabel = "知道了",
            // 支持点击屏幕外关闭（dismissOnClickOutside 默认 true）
            scrimEnabled = false
        )
    }
}

/** 云读档覆盖确认弹窗（GameOverlayHost 拆分） */
@Composable
private fun GameCloudOverwriteSection(
    dialogRenderable: Boolean,
    cloudOverwrite: CloudOverwriteRequest?,
    saveLoadViewModel: SaveLoadViewModel
) {
    if (dialogRenderable && cloudOverwrite != null) {
        // J 项：4 处 `!!` 清理——?.let 安全访问（cloudOverwrite 已判非空，语义等价）
        cloudOverwrite.let { request ->
            StandardPromptDialog(
                onDismissRequest = { saveLoadViewModel.cancelCloudOverwrite() },
                title = "覆盖本地存档？",
                text = "云端存档（第${request.cloudYear}年${request.cloudMonth}月 " +
                    "${request.cloudSectName}）将写入槽位 ${request.slot}，" +
                    "该槽位的本地存档将被覆盖。\n\n确定要覆盖吗？",
                confirmLabel = "覆盖并继续",
                onConfirm = { saveLoadViewModel.confirmCloudOverwrite() },
                dismissLabel = "取消",
                onDismiss = { saveLoadViewModel.cancelCloudOverwrite() },
                dismissOnClickOutside = false,
                scrimEnabled = false
            )
        }
    }
}

/** 引擎事件通知弹窗（GameOverlayHost 拆分） */
@Composable
private fun GameNotificationSection(
    dialogRenderable: Boolean,
    pendingNotification: GameNotification?,
    viewModel: GameViewModel
) {
    if (dialogRenderable && pendingNotification != null) {
        pendingNotification.let { notification ->
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
}

/** 叠加层栈（GameOverlayHost 拆分）：战斗结果/战斗日志详情/弟子详情 */
// 拆分聚合:平铺参数搬移自原公共函数
@Suppress("LongParameterList")
@Composable
private fun GameOverlayStackSection(
    viewModel: GameViewModel,
    dialogRenderable: Boolean,
    showBattleResult: Boolean,
    pendingBattleResult: BattleResultUIData?,
    detailBattleLog: BattleLog?,
    onCloseBattleResult: () -> Unit,
    onViewBattleLogDetail: (BattleLog) -> Unit,
    onCloseBattleLogDetail: () -> Unit
) {
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
                        onConfirm = { onCloseBattleResult() },
                        onViewDetail = { selectedLog -> onViewBattleLogDetail(selectedLog) },
                        onDismiss = { onCloseBattleResult() }
                    )
                }
            }

            TopOverlay.BATTLE_LOG_DETAIL -> {
                detailBattleLog?.let { log ->
                    BattleLogDetailDialog(
                        log = log,
                        onDismiss = { onCloseBattleLogDetail() },
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
