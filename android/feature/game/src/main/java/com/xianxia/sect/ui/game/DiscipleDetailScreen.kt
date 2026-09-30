@file:Suppress("TooManyFunctions") // 私有辅助函数集中在本文件
package com.xianxia.sect.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.engine.BreakthroughBonusResult
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ResignGateResult
import com.xianxia.sect.core.model.evaluateResignGate
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.storageBagSpiritStones
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.components.JadePurchaseFlow
import com.xianxia.sect.ui.game.components.JadePurchaseOutcome
import com.xianxia.sect.ui.game.components.LearnedManualDetailDialog
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.game.components.detail.DiscipleTypeEditInteraction
import com.xianxia.sect.ui.game.components.detail.AttributesSection
import com.xianxia.sect.ui.game.components.detail.BasicInfoSection
import com.xianxia.sect.ui.game.components.detail.CombatStatsSection
import com.xianxia.sect.ui.game.components.detail.DetailActionCallbacks
import com.xianxia.sect.ui.game.components.detail.DetailRightPanel
import com.xianxia.sect.ui.game.components.detail.EquipmentSection
import com.xianxia.sect.ui.game.components.detail.EquipmentSelectionDialog
import com.xianxia.sect.ui.game.components.detail.EquipmentSelectionParams
import com.xianxia.sect.ui.game.components.detail.LifeLogDialog
import com.xianxia.sect.ui.game.components.detail.ManualSelectionDialog
import com.xianxia.sect.ui.game.components.detail.ManualSelectionParams
import com.xianxia.sect.ui.game.components.detail.ManualsSection
import com.xianxia.sect.ui.game.components.detail.ReplaceSelectionActions
import com.xianxia.sect.ui.game.components.detail.ReplaceSelectionConfig
import com.xianxia.sect.ui.game.components.detail.ReplaceSelectionScreen
import com.xianxia.sect.ui.game.components.detail.StorageBagDialog
import com.xianxia.sect.ui.game.components.detail.buildManualReplaceItems
import com.xianxia.sect.ui.game.dialogs.DiscipleChatDialog
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.delegate.equipItem
import com.xianxia.sect.ui.game.delegate.forgetManual
import com.xianxia.sect.ui.game.delegate.learnManual
import com.xianxia.sect.ui.game.delegate.purchaseBreakthroughBonus
import com.xianxia.sect.ui.game.delegate.releaseDiscipleForReassignment
import com.xianxia.sect.ui.game.delegate.replaceManual
import com.xianxia.sect.ui.game.delegate.unequipItem
import com.xianxia.sect.core.engine.domain.disciple.getMaxManualSlots

val LocalDismissDropdown = compositionLocalOf { {} }

/** 弟子详情对话框 UI 状态：跨区共享的弹窗开关/选中项 */
private class DiscipleDetailDialogState {
    var showEquipmentSelection by mutableStateOf<String?>(null)
    var showManualSelection by mutableStateOf(false)
    var showManualDetailDialog by mutableStateOf<ManualInstance?>(null)
    var showEquipmentDetailDialog by mutableStateOf<EquipmentInstance?>(null)
    var showStorageBagDialog by mutableStateOf(false)
    var showLifeLogDialog by mutableStateOf(false)
    var showChatDialog by mutableStateOf(false)
    var showResignConfirmDialog by mutableStateOf(false)
    var resignConfirmMessage by mutableStateOf("")
    var showResignBlockedDialog by mutableStateOf(false)
    var resignBlockedMessage by mutableStateOf("")
    var showBreakthroughJadeDialog by mutableStateOf(false)
    var showDiscipleTypeDropdown by mutableStateOf(false)

    /** 右侧面板动作回调集合：与 [DetailActionCallbacks] 一一对应 */
    fun actionCallbacks(
        disciple: DiscipleAggregate,
        viewModel: GameViewModel?,
        onNavigateToDisciple: ((DiscipleAggregate) -> Unit)?
    ): DetailActionCallbacks = DetailActionCallbacks(
        onShowStorageBag = { showStorageBagDialog = true },
        onShowLifeLog = { showLifeLogDialog = true },
        onShowChat = { showChatDialog = true },
        onShowResignConfirm = {
            when (val result = evaluateResignGate(disciple.status, disciple.isAlive)) {
                is ResignGateResult.CanResign -> viewModel?.disciple?.releaseDiscipleForReassignment(disciple.id)
                is ResignGateResult.ConfirmRequired -> {
                    resignConfirmMessage = result.message
                    showResignConfirmDialog = true
                }
                is ResignGateResult.Blocked -> {
                    resignBlockedMessage = result.message
                    showResignBlockedDialog = true
                }
                is ResignGateResult.Disabled -> Unit
            }
        },
        onNavigateToDisciple = onNavigateToDisciple
    )
}

@Composable
fun DiscipleDetailDialog(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate> = emptyList(),
    allEquipment: List<EquipmentInstance> = emptyList(),
    allManuals: List<ManualInstance> = emptyList(),
    manualStacks: List<ManualStack> = emptyList(),
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    viewModel: GameViewModel? = null,
    onDismiss: () -> Unit,
    onNavigateToDisciple: ((DiscipleAggregate) -> Unit)? = null,
    scrimEnabled: Boolean = true
) {
    val state = remember { DiscipleDetailDialogState() }

    // 功法/装备详情界面激活对应 FocusDomain，使熟练度/孕养进入实时轨
    LaunchedEffect(state.showManualDetailDialog) {
        if (state.showManualDetailDialog != null) viewModel?.activateSubDialogDomain("ManualDetail")
        else viewModel?.deactivateSubDialogDomain("ManualDetail")
    }
    LaunchedEffect(state.showEquipmentDetailDialog) {
        if (state.showEquipmentDetailDialog != null) viewModel?.activateSubDialogDomain("EquipmentDetail")
        else viewModel?.deactivateSubDialogDomain("EquipmentDetail")
    }
    // 弟子详情覆盖自身进入组合时激活 DiscipleDetail 域
    LaunchedEffect(disciple.id) { viewModel?.activateSubDialogDomain("DiscipleDetail") }
    // 离开组合时清理所有子界面域
    DisposableEffect(Unit) {
        onDispose {
            viewModel?.deactivateSubDialogDomain("ManualDetail")
            viewModel?.deactivateSubDialogDomain("EquipmentDetail")
            viewModel?.deactivateSubDialogDomain("DiscipleDetail")
        }
    }

    DiscipleDetailBody(
        disciple = disciple, allDisciples = allDisciples, viewModel = viewModel,
        allEquipment = allEquipment, allManuals = allManuals,
        manualProficiencies = manualProficiencies, scrimEnabled = scrimEnabled,
        state = state, onDismiss = onDismiss, onNavigateToDisciple = onNavigateToDisciple
    )
    DiscipleDetailSecondaryDialogs(
        disciple = disciple, viewModel = viewModel,
        allEquipment = allEquipment, allManuals = allManuals,
        manualStacks = manualStacks,
        manualProficiencies = manualProficiencies, state = state
    )
}

/** 弟子详情对话框主体：全屏对话框 + Tab 布局 + 内联覆盖层 */
@Suppress("LongParameterList")
@Composable
private fun DiscipleDetailBody(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    viewModel: GameViewModel?,
    allEquipment: List<EquipmentInstance>,
    allManuals: List<ManualInstance>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>,
    scrimEnabled: Boolean,
    state: DiscipleDetailDialogState,
    onDismiss: () -> Unit,
    onNavigateToDisciple: ((DiscipleAggregate) -> Unit)?
) {
    val elderSlots by viewModel?.elderSlots?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val sectPolicies by viewModel?.sectPolicies?.collectAsStateWithLifecycle() ?:
        remember { mutableStateOf(SectPolicies()) }
    val vmResidenceSlots by viewModel?.residenceSlots?.collectAsStateWithLifecycle() ?:
        remember { mutableStateOf(emptyList<ResidenceSlot>()) }
    val vmPlacedBuildings by viewModel?.placedBuildings?.collectAsStateWithLifecycle() ?:
        remember { mutableStateOf(emptyList<GridBuildingData>()) }
    val gameData by viewModel?.gameData?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    var localDiscipleType by remember(disciple.id) { mutableStateOf(disciple.discipleType) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "",
        mode = DialogMode.Full,
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        scrimEnabled = scrimEnabled,
        showHeader = false,
        showCloseButton = false
    ) {
        key(disciple.id) {
            BackHandler(onBack = onDismiss)
            // 首次查看时初始化日志（仅当尚无日志时生成合成事件）
            LaunchedEffect(disciple.id) { viewModel?.lifeEvents?.initializeLifeEvents(disciple.id) }

            CompositionLocalProvider(LocalDismissDropdown provides { state.showDiscipleTypeDropdown = false }) {
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
                        DiscipleDetailTabLayout(
                            disciple = disciple, allDisciples = allDisciples, viewModel = viewModel,
                            allEquipment = allEquipment, allManuals = allManuals,
                            manualProficiencies = manualProficiencies, elderSlots = elderSlots,
                            sectPolicies = sectPolicies, vmResidenceSlots = vmResidenceSlots,
                            vmPlacedBuildings = vmPlacedBuildings,
                            localDiscipleType = localDiscipleType,
                            onLocalDiscipleTypeChange = { localDiscipleType = it },
                            onNavigateToDisciple = onNavigateToDisciple, state = state
                        )
                        // Close button at top-right
                        CloseButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                        // 突破率玉符弹窗（内联覆盖层）必须渲染在根 Box 内、CloseButton 之后——渲染在
                        // UnifiedGameDialog 内容 lambda 之外会被平台 Dialog 窗口遮挡而不可见（4.00.92 事故同源）
                        DiscipleDetailInlineOverlays(disciple = disciple, viewModel = viewModel,
                            gameData = gameData, state = state)
                    }
                }
            } // CompositionLocalProvider
        }
    }
}

/** Tab 主行：左侧 Tab 按钮 + 内容区 + 分隔线 + 右侧面板 */
@Suppress("LongParameterList")
@Composable
private fun DiscipleDetailTabLayout(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    viewModel: GameViewModel?,
    allEquipment: List<EquipmentInstance>,
    allManuals: List<ManualInstance>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>,
    elderSlots: ElderSlots?,
    sectPolicies: SectPolicies?,
    vmResidenceSlots: List<ResidenceSlot>,
    vmPlacedBuildings: List<GridBuildingData>,
    localDiscipleType: String,
    onLocalDiscipleTypeChange: (String) -> Unit,
    onNavigateToDisciple: ((DiscipleAggregate) -> Unit)?,
    state: DiscipleDetailDialogState
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("信息", "属性", "装备", "功法")
    Row(modifier = Modifier.fillMaxSize()) {
        // Left: Content + tab buttons
        Row(modifier = Modifier.fillMaxHeight().weight(1f)) {
            // Tab buttons on left edge
            Column(
                modifier = Modifier.fillMaxHeight().width(44.dp),
                verticalArrangement = Arrangement.Center
            ) {
                tabs.forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .clickable { state.showDiscipleTypeDropdown = false; selectedTab = index },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, fontSize = 11.sp, color = Color.Black,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    if (index < tabs.lastIndex) HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(), thickness = 1.dp, color = Color(0xFF757575)
                    )
                }
            }
            // Content area
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)
            ) {
                DiscipleDetailTabContent(
                    selectedTab = selectedTab, disciple = disciple, allDisciples = allDisciples,
                    viewModel = viewModel, allEquipment = allEquipment, allManuals = allManuals,
                    manualProficiencies = manualProficiencies, elderSlots = elderSlots,
                    sectPolicies = sectPolicies, vmResidenceSlots = vmResidenceSlots,
                    vmPlacedBuildings = vmPlacedBuildings,
                    state = state
                )
            }
        }
        // Vertical divider
        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(GameColors.ButtonDisabled))
        // Right 40%: Portrait + basic info + action buttons
        DetailRightPanel(
            disciple = disciple, allDisciples = allDisciples,
            typeEdit = DiscipleTypeEditInteraction(
                localDiscipleType = localDiscipleType,
                showDropdown = state.showDiscipleTypeDropdown,
                onDropdownChange = { state.showDiscipleTypeDropdown = it },
                onTypeChange = onLocalDiscipleTypeChange
            ),
            actions = state.actionCallbacks(disciple = disciple,
                viewModel = viewModel, onNavigateToDisciple = onNavigateToDisciple),
            viewModel = viewModel
        )
    }
}

/** Tab 内容区：信息/属性/装备/功法 四分支（信息分支见 DiscipleDetailInfoTab） */
@Suppress("LongParameterList", "UnusedParameter")
@Composable
private fun DiscipleDetailTabContent(
    selectedTab: Int,
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    viewModel: GameViewModel?,
    allEquipment: List<EquipmentInstance>,
    allManuals: List<ManualInstance>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>,
    elderSlots: ElderSlots?,
    sectPolicies: SectPolicies?,
    vmResidenceSlots: List<ResidenceSlot>,
    vmPlacedBuildings: List<GridBuildingData>,
    state: DiscipleDetailDialogState
) {
    // 六部位已穿装备（B3 实例轨：显示序单一真源 = EquipmentSlot.displayOrder）
    val equippedByPart: Map<EquipmentSlot, EquipmentInstance?> = remember(
        disciple.headId, disciple.bodyId, disciple.handsId,
        disciple.feetId, disciple.weaponId, disciple.legsId, allEquipment
    ) {
        EquipmentSlot.displayOrder.associateWith { part ->
            val id = disciple.equipment?.slotId(part).orEmpty()
            allEquipment.find { it.id == id }
        }
    }
    val equipped = remember(equippedByPart) { equippedByPart.values.filterNotNull() }
    val learnedManuals = remember(disciple.manualIds, allManuals) { allManuals.filter { it.id in disciple.manualIds } }
    val maxManualSlots = remember(disciple.id) { DiscipleStatCalculator.getMaxManualSlots(disciple) }

    when (selectedTab) {
        0 -> DiscipleDetailInfoTab(
            disciple = disciple, allDisciples = allDisciples, allEquipment = allEquipment,
            allManuals = allManuals, manualProficiencies = manualProficiencies,
            elderSlots = elderSlots, sectPolicies = sectPolicies,
            residenceSlots = vmResidenceSlots, placedBuildings = vmPlacedBuildings,
            state = state
        )
        1 -> {
            AttributesSection(disciple)
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            CombatStatsSection(
                disciple = disciple, equipped = equipped,
                learnedManuals = learnedManuals,
                manualProficiencies = manualProficiencies
            )
        }
        2 -> EquipmentSection(
            equippedByPart = equippedByPart,
            onSlotClick = { slotType -> state.showEquipmentSelection = slotType },
            onEquipmentClick = { equipment -> state.showEquipmentDetailDialog = equipment }
        )
        3 -> ManualsSection(
            manuals = learnedManuals, maxSlots = maxManualSlots,
            manualProficiencies = manualProficiencies, discipleId = disciple.id,
            onSlotClick = { state.showManualSelection = true },
            onManualClick = { manual -> state.showManualDetailDialog = manual }
        )
    }
}

/** 信息 Tab：基本信息分区 */
@Suppress("LongParameterList")
@Composable
private fun DiscipleDetailInfoTab(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    allEquipment: List<EquipmentInstance>,
    allManuals: List<ManualInstance>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>,
    elderSlots: ElderSlots?,
    sectPolicies: SectPolicies?,
    residenceSlots: List<ResidenceSlot>,
    placedBuildings: List<GridBuildingData>,
    state: DiscipleDetailDialogState
) {
    BasicInfoSection(
        disciple = disciple,
        allEquipment = allEquipment,
        allManuals = allManuals,
        manualProficiencies = manualProficiencies,
        elderSlots = elderSlots,
        allDisciples = allDisciples,
        sectPolicies = sectPolicies,
        residenceSlots = residenceSlots,
        placedBuildings = placedBuildings,
        onBreakthroughJadeClick = { state.showBreakthroughJadeDialog = true }
    )
}

/** 内联覆盖层：突破率玉符弹窗（渲染在根 Box 最末，z 序最高） */
@Composable
private fun DiscipleDetailInlineOverlays(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    gameData: GameData?,
    state: DiscipleDetailDialogState
) {
    // 突破率玉符加成弹窗：渲染在根 Box 最末，z 序最高，
    // 在滚动内容流内直接渲染会被后续内容覆盖/随滚动错位
    if (state.showBreakthroughJadeDialog) {
        JadePurchaseFlow(
            title = "提高突破率",
            description = "消耗1玉符提高弟子突破率15%，最多提高两次",
            jadeSymbols = gameData?.jadeSymbols ?: 0,
            insufficientText = "玉符不足，无法提高突破率",
            purchase = {
                when (val result = viewModel?.disciple?.purchaseBreakthroughBonus(disciple.id)) {
                    is BreakthroughBonusResult.Success -> JadePurchaseOutcome.Success
                    is BreakthroughBonusResult.InsufficientJadeSymbols -> JadePurchaseOutcome.Insufficient
                    is BreakthroughBonusResult.LimitReached -> JadePurchaseOutcome.Success
                    is BreakthroughBonusResult.Error -> JadePurchaseOutcome.Failed(result.message)
                    null -> JadePurchaseOutcome.Success
                }
            },
            onDismiss = { state.showBreakthroughJadeDialog = false }
        )
    }
}

/** 次级对话框集合：日志/储物袋/聊天 + 确认/选择/详情类对话框 */
@Suppress("LongParameterList")
@Composable
private fun DiscipleDetailSecondaryDialogs(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    allEquipment: List<EquipmentInstance>,
    allManuals: List<ManualInstance>,
    manualStacks: List<ManualStack>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>,
    state: DiscipleDetailDialogState
) {
    val gameData by viewModel?.gameData?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val gameYear = gameData?.gameYear ?: 1

    if (state.showLifeLogDialog) {
        LifeLogDialog(discipleName = disciple.name,
            events = viewModel?.lifeEvents?.getLifeEvents(disciple.id) ?: emptyList(),
            onDismiss = { state.showLifeLogDialog = false })
    }
    if (state.showStorageBagDialog) {
        StorageBagDialog(items = disciple.storageBagItems, spiritStones = disciple.storageBagSpiritStones,
            disciple = disciple, viewModel = viewModel, onDismiss = { state.showStorageBagDialog = false })
    }
    if (state.showChatDialog) {
        val lastChatYear = viewModel?.disciple?.getLastChatYear(disciple.id)
        val hasCooldown = lastChatYear != null && lastChatYear == gameYear
        DiscipleChatDialog(disciple = disciple, gameYear = gameYear, hasCooldown = hasCooldown,
            viewModel = viewModel, onDismiss = { state.showChatDialog = false })
    }

    DiscipleDetailStandardDialogs(
        disciple = disciple, viewModel = viewModel, state = state
    )
    DiscipleDetailSelectionDialogs(
        disciple = disciple, viewModel = viewModel, allEquipment = allEquipment,
        manualStacks = manualStacks,
        allManuals = allManuals, state = state
    )
    DiscipleDetailTailDialogs(
        disciple = disciple, viewModel = viewModel, allEquipment = allEquipment, state = state
    )
    DiscipleDetailManualSection(
        disciple = disciple, viewModel = viewModel, manualStacks = manualStacks,
        allManuals = allManuals, manualProficiencies = manualProficiencies, state = state
    )
}

/** 确认类对话框：卸任确认/阻塞提示 */
@Composable
private fun DiscipleDetailStandardDialogs(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    state: DiscipleDetailDialogState
) {
    if (state.showResignConfirmDialog) {
        StandardPromptDialog(onDismissRequest = { state.showResignConfirmDialog = false },
            title = "卸任确认",
            text = state.resignConfirmMessage,
            confirmLabel = "确认卸任",
            onConfirm = {
                viewModel?.disciple?.releaseDiscipleForReassignment(disciple.id)
                state.showResignConfirmDialog = false
            },
            dismissLabel = "取消", onDismiss = { state.showResignConfirmDialog = false })
    }

    if (state.showResignBlockedDialog) {
        StandardPromptDialog(onDismissRequest = { state.showResignBlockedDialog = false },
            title = "无法卸任",
            text = state.resignBlockedMessage,
            confirmLabel = "确定",
            onConfirm = { state.showResignBlockedDialog = false },
            onDismiss = { state.showResignBlockedDialog = false })
    }
}

/** 装备/功法选择对话框 */
@Composable
private fun DiscipleDetailSelectionDialogs(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    allEquipment: List<EquipmentInstance>,
    manualStacks: List<ManualStack>,
    allManuals: List<ManualInstance>,
    state: DiscipleDetailDialogState
) {
    var selectedEquipmentId by remember { mutableStateOf<String?>(null) }
    var selectedManualId by remember { mutableStateOf<String?>(null) }
    val maxManualSlots = remember(disciple.id) { DiscipleStatCalculator.getMaxManualSlots(disciple) }

    state.showEquipmentSelection?.let { slotType ->
        EquipmentSelectionDialog(
            params = EquipmentSelectionParams(
                slotType = slotType,
                allEquipment = allEquipment,
                currentEquipmentId = EquipmentSlot.entries
                    .find { it.name.equals(slotType, ignoreCase = true) }
                    ?.let { part -> disciple.equipment?.slotId(part) }
                    ?.takeIf { it.isNotEmpty() },
                currentDiscipleId = disciple.id,
                discipleRealm = disciple.realm,
                selectedEquipmentId = selectedEquipmentId,
                viewModel = viewModel
            ),
            onSelect = { id -> selectedEquipmentId = id },
            onConfirm = { id ->
                viewModel?.disciple?.equipItem(disciple.id, id)
                state.showEquipmentSelection = null
                selectedEquipmentId = null
            },
            onDismiss = {
                state.showEquipmentSelection = null
                selectedEquipmentId = null
            }
        )
    }

    if (state.showManualSelection) {
        ManualSelectionDialog(
            params = ManualSelectionParams(
                manualStacks = manualStacks,
                allManuals = allManuals,
                currentManualIds = disciple.manualIds,
                discipleRealm = disciple.realm,
                maxManualSlots = maxManualSlots,
                selectedManualId = selectedManualId,
                viewModel = viewModel
            ),
            onSelect = { id -> selectedManualId = id },
            onConfirm = { id ->
                viewModel?.disciple?.learnManual(disciple.id, id)
                state.showManualSelection = false
                selectedManualId = null
            },
            onDismiss = {
                state.showManualSelection = false
                selectedManualId = null
            }
        )
    }
}

/** 装备详情弹窗（卸下/更换） */
@Composable
private fun DiscipleDetailTailDialogs(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    allEquipment: List<EquipmentInstance>,
    state: DiscipleDetailDialogState
) {
    state.showEquipmentDetailDialog?.let { equipment ->
        val liveEquipment = allEquipment.find { it.id == equipment.id } ?: equipment
        ItemDetailDialog(
            item = liveEquipment,
            onDismiss = { state.showEquipmentDetailDialog = null },
            viewModel = viewModel,
            extraActions = {
                GameButton(
                    text = "卸下",
                    onClick = {
                        viewModel?.disciple?.unequipItem(disciple.id, equipment.id)
                        state.showEquipmentDetailDialog = null
                    }
                )
                GameButton(
                    text = "更换",
                    onClick = {
                        state.showEquipmentDetailDialog = null
                        state.showEquipmentSelection = equipment.part.name
                    }
                )
            }
        )
    }
}

/** 功法详情与更换：已学功法详情 + 更换选择弹窗 */
@Composable
private fun DiscipleDetailManualSection(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    manualStacks: List<ManualStack>,
    allManuals: List<ManualInstance>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>,
    state: DiscipleDetailDialogState
) {
    state.showManualDetailDialog?.let { manual ->
        val proficiencyData = manualProficiencies[disciple.id]?.find { it.manualId == manual.id }
        var showManualReplaceSelection by remember { mutableStateOf(false) }

        LearnedManualDetailDialog(
            manual = manual,
            proficiencyData = proficiencyData,
            onForget = { viewModel?.disciple?.forgetManual(disciple.id, manual.id); state.showManualDetailDialog = null
                },
            onDismiss = { state.showManualDetailDialog = null },
            extraActions = { GameButton(text = "更换", onClick = { showManualReplaceSelection = true }) }
        )

        if (showManualReplaceSelection) {
            val watchedKeys = viewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value
                ?: emptySet()
            // 可选功法 + 心法状态（替换流程）：排除其他已学名称与境界不足的堆叠；
            // 心法置底禁用条件 = 弟子已有心法且原功法非心法（原功法为心法/弟子无心法时正常显示）
            val (eligibleStacks, discipleHasMind) = remember(
                manualStacks, allManuals, disciple.manualIds, manual, disciple.realm
            ) {
                val manualMap = allManuals.associateBy { it.id }
                val otherManualIds = disciple.manualIds.filter { it != manual.id }
                val learnedNames = otherManualIds.mapNotNull { mid -> manualMap[mid]?.name }.toSet()
                val eligible = manualStacks.filter { stack ->
                    stack.name !in learnedNames &&
                    GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)
                }
                val hasMind = disciple.manualIds.any { mid -> manualMap[mid]?.type == ManualType.MIND }
                eligible to hasMind
            }
            var selectedReplaceManualId by remember { mutableStateOf<String?>(null) }

            ManualReplaceDialog(
                availableManualStacks = eligibleStacks,
                mindItemsDisabled = discipleHasMind && manual.type != ManualType.MIND,
                discipleRealm = disciple.realm,
                watchedKeys = watchedKeys,
                selectedReplaceManualId = selectedReplaceManualId,
                onSelectReplaceManual = { id -> selectedReplaceManualId = id },
                onConfirmReplace = { newId ->
                    viewModel?.disciple?.replaceManual(disciple.id, manual.id, newId)
                    showManualReplaceSelection = false
                    state.showManualDetailDialog = null
                },
                onDismissReplace = { showManualReplaceSelection = false }
            )
        }
    }
}

/**
 * 功法更换选择对话框：全屏 7:3 双栏 + 心法规则。
 *
 * 进入默认选中列表第一个功法（[onConfirmReplace] 接收最终选中功法 id）。
 * 心法规则（mindItemsDisabled 由调用方计算）：弟子已有心法且更换的原功法非心法时，
 * 仓库心法置底置灰不可点击；更换的原功法为弟子心法、或弟子无心法时，仓库心法正常显示可点击。
 */
@Composable
private fun ManualReplaceDialog(
    availableManualStacks: List<ManualStack>,
    mindItemsDisabled: Boolean,
    discipleRealm: Int,
    watchedKeys: Set<String> = emptySet(),
    selectedReplaceManualId: String?,
    onSelectReplaceManual: (String) -> Unit,
    onConfirmReplace: (String) -> Unit,
    onDismissReplace: () -> Unit
) {
    val items = remember(
        availableManualStacks, discipleRealm, mindItemsDisabled, watchedKeys
    ) {
        buildManualReplaceItems(
            stacks = availableManualStacks,
            learnedNames = emptySet(),
            discipleRealm = discipleRealm,
            mindItemsDisabled = mindItemsDisabled,
            watchedKeys = watchedKeys
        )
    }

    ReplaceSelectionScreen(
        config = ReplaceSelectionConfig(
            title = "选择新功法",
            emptyText = "暂无可更换的功法",
            items = items,
            selectedId = selectedReplaceManualId,
            confirmLabel = "更换",
            actions = ReplaceSelectionActions(
                onSelect = onSelectReplaceManual,
                onConfirm = onConfirmReplace,
                onDismiss = onDismissReplace
            )
        )
    )
}

/**
 * DiscipleDetailDialog 便捷重载：自动从 GameViewModel 收集 StateFlow，
 * 顶层渲染由 MainGameScreen 负责，此处仅负责数据注入。
 */
@Composable
fun DiscipleDetailDialog(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    onNavigateToDisciple: ((DiscipleAggregate) -> Unit)? = null,
    scrimEnabled: Boolean = true
) {
    val equipment by viewModel.equipmentInstances.collectAsStateWithLifecycle()
    val manuals by viewModel.manualInstances.collectAsStateWithLifecycle()
    val manualStacks by viewModel.manualStacks.collectAsStateWithLifecycle()

    DiscipleDetailDialog(
        disciple = disciple,
        allDisciples = allDisciples,
        allEquipment = equipment,
        allManuals = manuals,
        manualStacks = manualStacks,
        manualProficiencies = manualProficiencies,
        viewModel = viewModel,
        onDismiss = onDismiss,
        onNavigateToDisciple = onNavigateToDisciple,
        scrimEnabled = scrimEnabled
    )
}
