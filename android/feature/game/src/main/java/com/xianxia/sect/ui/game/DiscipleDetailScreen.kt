@file:Suppress("TooManyFunctions") // 拆分聚合:提取的私有辅助函数集中在原文件,文件级复杂度为拆分代价
package com.xianxia.sect.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.engine.BreakthroughBonusResult
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ResignGateResult
import com.xianxia.sect.core.model.evaluateResignGate
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.accessoryId
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.bootsId
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.storageBagItems
import com.xianxia.sect.core.model.storageBagSpiritStones
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.core.model.Physique
import com.xianxia.sect.core.model.Affix
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.util.sortedByWatchedThenRarity
import com.xianxia.sect.core.util.watchKey
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.components.JadePurchaseFlow
import com.xianxia.sect.ui.game.components.JadePurchaseOutcome
import com.xianxia.sect.ui.game.components.LearnedManualDetailDialog
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.TalentDetailDialog
import com.xianxia.sect.ui.components.PhysiqueDetailDialog
import com.xianxia.sect.ui.components.AffixDetailDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.game.components.detail.AffixesSection
import com.xianxia.sect.ui.game.components.detail.AttributesSection
import com.xianxia.sect.ui.game.components.detail.BasicInfoSection
import com.xianxia.sect.ui.game.components.detail.CombatStatsSection
import com.xianxia.sect.ui.game.components.detail.DetailActionCallbacks
import com.xianxia.sect.ui.game.components.detail.DetailRightPanel
import com.xianxia.sect.ui.game.components.detail.EquipmentSection
import com.xianxia.sect.ui.game.components.detail.EquipmentSelectionDialog
import com.xianxia.sect.ui.game.components.detail.LifeLogDialog
import com.xianxia.sect.ui.game.components.detail.ManualSelectionDialog
import com.xianxia.sect.ui.game.components.detail.ManualsSection
import com.xianxia.sect.ui.game.components.detail.MasterApprenticeSelectDialog
import com.xianxia.sect.ui.game.components.detail.PhysiquesSection
import com.xianxia.sect.ui.game.components.detail.RelationsDialog
import com.xianxia.sect.ui.game.components.detail.StorageBagDialog
import com.xianxia.sect.ui.game.components.detail.TalentsSection
import com.xianxia.sect.ui.game.dialogs.DiscipleChatDialog
import com.xianxia.sect.ui.game.dialogs.SpiritRootWashDialog
import com.xianxia.sect.ui.game.dialogs.TraitAddDialog
import com.xianxia.sect.ui.game.dialogs.TraitWashDialog
import com.xianxia.sect.ui.game.dialogs.WashSessionControl
import com.xianxia.sect.ui.game.dialogs.shared.RenameDiscipleDialog
import com.xianxia.sect.ui.theme.GameColors



val LocalDismissDropdown = compositionLocalOf { {} }

/** 弟子详情对话框 UI 状态（DiscipleDetailDialog 拆分）：跨区共享的弹窗开关/选中项 + 洗炼互斥入口 */
private class DiscipleDetailDialogState {
    var showEquipmentSelection by mutableStateOf<String?>(null)
    var showManualSelection by mutableStateOf(false)
    var showManualDetailDialog by mutableStateOf<ManualInstance?>(null)
    var showEquipmentDetailDialog by mutableStateOf<EquipmentInstance?>(null)
    var showRelationsDialog by mutableStateOf(false)
    var showStorageBagDialog by mutableStateOf(false)
    var showExpelConfirmDialog by mutableStateOf(false)
    var showApprenticeSelectDialog by mutableStateOf(false)
    var showLifeLogDialog by mutableStateOf(false)
    var showChatDialog by mutableStateOf(false)
    var showWashDialog by mutableStateOf(false)
    var showTalentWashDialog by mutableStateOf(false)
    var showPhysiqueWashDialog by mutableStateOf(false)
    var showAffixWashDialog by mutableStateOf(false)
    var showResignConfirmDialog by mutableStateOf(false)
    var resignConfirmMessage by mutableStateOf("")
    var showResignBlockedDialog by mutableStateOf(false)
    var resignBlockedMessage by mutableStateOf("")
    var selectedTalent by mutableStateOf<Talent?>(null)
    var selectedPhysique by mutableStateOf<Physique?>(null)
    var selectedAffix by mutableStateOf<Affix?>(null)
    var showRenameDialog by mutableStateOf(false)
    var showBreakthroughJadeDialog by mutableStateOf(false)
    var showTraitAddType by mutableStateOf<TraitWashType?>(null)
    var showDiscipleTypeDropdown by mutableStateOf(false)

    // 洗炼弹窗互斥入口（对抗性审查 2026-08-09 状态破坏者：四个洗炼入口若只置位自己的
    // bool，快速连点可在同帧叠加两个内联覆盖层，下层弹窗的洗炼按钮仍可被点到造成双扣玉符）
    fun openSpiritRootWash() {
        showWashDialog = true; showTalentWashDialog = false
        showPhysiqueWashDialog = false; showAffixWashDialog = false
    }
    fun openTalentWash() {
        showTalentWashDialog = true; showWashDialog = false
        showPhysiqueWashDialog = false; showAffixWashDialog = false
    }
    fun openPhysiqueWash() {
        showPhysiqueWashDialog = true; showWashDialog = false
        showTalentWashDialog = false; showAffixWashDialog = false
    }
    fun openAffixWash() {
        showAffixWashDialog = true; showWashDialog = false
        showTalentWashDialog = false; showPhysiqueWashDialog = false
    }

    /** 右侧面板动作回调集合（DiscipleDetailDialog 拆分）：与 [DetailActionCallbacks] 一一对应 */
    fun actionCallbacks(
        disciple: DiscipleAggregate,
        viewModel: GameViewModel?,
        onNavigateToDisciple: ((DiscipleAggregate) -> Unit)?
    ): DetailActionCallbacks = DetailActionCallbacks(
        onShowRelations = { showRelationsDialog = true },
        onShowStorageBag = { showStorageBagDialog = true },
        onShowExpelConfirm = { showExpelConfirmDialog = true },
        onShowLifeLog = { showLifeLogDialog = true },
        onShowApprentice = { showApprenticeSelectDialog = true },
        onRenameDisciple = { showRenameDialog = true },
        onShowChat = { showChatDialog = true },
        onShowResignConfirm = {
            when (val result = evaluateResignGate(disciple.status, disciple.isAlive)) {
                is ResignGateResult.CanResign -> viewModel?.releaseDiscipleForReassignment(disciple.id)
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
    equipmentStacks: List<EquipmentStack> = emptyList(),
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
        disciple = disciple, allDisciples = allDisciples, viewModel = viewModel,
        allEquipment = allEquipment, allManuals = allManuals,
        manualStacks = manualStacks, equipmentStacks = equipmentStacks,
        manualProficiencies = manualProficiencies, state = state, onDismiss = onDismiss
    )
}

/** 弟子详情对话框主体（DiscipleDetailDialog 拆分）：全屏对话框 + Tab 布局 + 内联覆盖层 */
// 拆分聚合:平铺参数搬移自原公共函数
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
    val sectPolicies by viewModel?.sectPolicies?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(SectPolicies()) }
    val vmResidenceSlots by viewModel?.residenceSlots?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList<ResidenceSlot>()) }
    val vmPlacedBuildings by viewModel?.placedBuildings?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList<GridBuildingData>()) }
    val gameData by viewModel?.gameData?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val gameYear = gameData?.gameYear ?: 1
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
            LaunchedEffect(disciple.id) { viewModel?.initializeLifeEvents(disciple.id) }

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
                            vmPlacedBuildings = vmPlacedBuildings, gameYear = gameYear,
                            gameData = gameData, localDiscipleType = localDiscipleType,
                            onLocalDiscipleTypeChange = { localDiscipleType = it },
                            onNavigateToDisciple = onNavigateToDisciple, state = state
                        )
                        // Close button at top-right
                        CloseButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                        // 改名/洗炼弹窗（内联覆盖层）必须渲染在根 Box 内、CloseButton 之后——渲染在
                        // UnifiedGameDialog 内容 lambda 之外会被平台 Dialog 窗口遮挡而不可见（4.00.92 事故同源）
                        DiscipleDetailInlineOverlays(disciple = disciple, viewModel = viewModel,
                            gameData = gameData, state = state)
                    }
                }
            } // CompositionLocalProvider
        }
    }
}

/** Tab 主行（DiscipleDetailDialog 拆分）：左侧 Tab 按钮 + 内容区 + 分隔线 + 右侧面板 */
// 拆分聚合:平铺参数搬移自原公共函数
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
    gameYear: Int,
    gameData: GameData?,
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
                    vmPlacedBuildings = vmPlacedBuildings, gameYear = gameYear,
                    gameData = gameData, state = state
                )
            }
        }
        // Vertical divider
        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(GameColors.ButtonDisabled))
        // Right 40%: Portrait + basic info + action buttons
        DetailRightPanel(
            disciple = disciple, allDisciples = allDisciples,
            localDiscipleType = localDiscipleType,
            showDiscipleTypeDropdown = state.showDiscipleTypeDropdown,
            onDiscipleTypeDropdownChange = { state.showDiscipleTypeDropdown = it },
            onLocalDiscipleTypeChange = onLocalDiscipleTypeChange,
            actions = state.actionCallbacks(disciple = disciple,
                viewModel = viewModel, onNavigateToDisciple = onNavigateToDisciple),
            viewModel = viewModel
        )
    }
}

/** Tab 内容区（DiscipleDetailDialog 拆分）：信息/属性/装备/功法 四分支（信息分支见 DiscipleDetailInfoTab） */
// 拆分聚合:平铺参数搬移自原公共函数
// 拆分搬移:参数保留原签名语义
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
    gameYear: Int,
    gameData: GameData?,
    state: DiscipleDetailDialogState
) {
    val weapon = remember(disciple.weaponId, allEquipment) {
        disciple.weaponId?.let { id -> allEquipment.find { it.id == id } }
    }
    val armor = remember(disciple.armorId, allEquipment) {
        disciple.armorId?.let { id -> allEquipment.find { it.id == id } }
    }
    val boots = remember(disciple.bootsId, allEquipment) {
        disciple.bootsId?.let { id -> allEquipment.find { it.id == id } }
    }
    val accessory = remember(disciple.accessoryId, allEquipment) {
        disciple.accessoryId?.let { id -> allEquipment.find { it.id == id } }
    }
    val learnedManuals = remember(disciple.manualIds, allManuals) { allManuals.filter { it.id in disciple.manualIds } }
    val maxManualSlots = remember(disciple.talentIds) { DiscipleStatCalculator.getMaxManualSlots(disciple) }

    when (selectedTab) {
        0 -> DiscipleDetailInfoTab(
            disciple = disciple, allDisciples = allDisciples, allEquipment = allEquipment,
            allManuals = allManuals, manualProficiencies = manualProficiencies,
            elderSlots = elderSlots, sectPolicies = sectPolicies,
            residenceSlots = vmResidenceSlots, placedBuildings = vmPlacedBuildings,
            gameYear = gameYear, gameData = gameData, state = state
        )
        1 -> {
            AttributesSection(disciple)
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            CombatStatsSection(
                disciple = disciple, weapon = weapon, armor = armor, boots = boots,
                accessory = accessory, learnedManuals = learnedManuals,
                manualProficiencies = manualProficiencies,
                bloodRefinementPct = gameData?.bloodRefinementPctTotals?.get(disciple.id)
            )
        }
        2 -> EquipmentSection(
            weapon = weapon, armor = armor, boots = boots, accessory = accessory,
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

/** 信息 Tab（DiscipleDetailDialog 拆分）：基本信息 + 天赋/体质/词条分区 */
// 拆分聚合:平铺参数搬移自原公共函数
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
    gameYear: Int,
    gameData: GameData?,
    state: DiscipleDetailDialogState
) {
    val talents = remember(disciple.talentIds) { TalentDatabase.getTalentsByIds(disciple.talentIds) }
    val physiques = remember(disciple.physiqueIds) { PhysiqueDatabase.getPhysiquesByIds(disciple.physiqueIds) }
    val affixes = remember(disciple.affixIds) { AffixDatabase.getAffixesByIds(disciple.affixIds) }
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
        gameYear = gameYear,
        gameSpeed = 1,
        bloodRefinementPct = gameData?.bloodRefinementPctTotals?.get(disciple.id),
        onWashSpiritRootClick = state::openSpiritRootWash,
        onBreakthroughJadeClick = { state.showBreakthroughJadeDialog = true }
    )
    Spacer(modifier = Modifier.height(12.dp))
    TalentsSection(
        talents,
        disciple.statusData,
        onTalentClick = { state.selectedTalent = it },
        onAddClick = { state.showTraitAddType = TraitWashType.TALENT }
    )
    Spacer(modifier = Modifier.height(12.dp))
    PhysiquesSection(
        physiques,
        onPhysiqueClick = { state.selectedPhysique = it },
        onAddClick = { state.showTraitAddType = TraitWashType.PHYSIQUE }
    )
    Spacer(modifier = Modifier.height(12.dp))
    AffixesSection(
        affixes,
        onAffixClick = { state.selectedAffix = it },
        onAddClick = { state.showTraitAddType = TraitWashType.AFFIX }
    )
}

/** 内联覆盖层（DiscipleDetailDialog 拆分）：改名/洗炼/突破率玉符/新增特质（渲染在根 Box 最末，z 序最高） */
@Composable
private fun DiscipleDetailInlineOverlays(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    gameData: GameData?,
    state: DiscipleDetailDialogState
) {
    // 洗炼保底计数（连续未出单灵根次数）：详情层常驻——弹窗关闭再打开不重置，
    // 保证"连续 3 次保底"语义跨洗炼会话成立（弹窗会话持有的计数在关闭时丢失）
    var washPityCount by remember { mutableIntStateOf(0) }
    if (state.showRenameDialog) {
        RenameDiscipleDialog(
            currentName = disciple.name,
            onConfirm = { newName ->
                viewModel?.renameDisciple(disciple.id, newName)
                state.showRenameDialog = false
            },
            onDismiss = { state.showRenameDialog = false }
        )
    }
    if (state.showWashDialog) {
        SpiritRootWashDialog(
            disciple = disciple,
            jadeSymbols = gameData?.jadeSymbols ?: 0,
            viewModel = viewModel,
            initialPityCount = washPityCount,
            onPityCountChanged = { washPityCount = it },
            onDismiss = { state.showWashDialog = false }
        )
    }
    // 突破率玉符加成弹窗（同洗炼弹窗：渲染在根 Box 最末，z 序最高，
    // 在滚动内容流内直接渲染会被后续内容覆盖/随滚动错位）
    if (state.showBreakthroughJadeDialog) {
        JadePurchaseFlow(
            title = "提高突破率",
            description = "消耗1玉符提高弟子突破率15%，最多提高两次",
            jadeSymbols = gameData?.jadeSymbols ?: 0,
            insufficientText = "玉符不足，无法提高突破率",
            purchase = {
                when (val result = viewModel?.purchaseBreakthroughBonus(disciple.id)) {
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
    // 新增天赋/体质/词条弹窗（同洗炼弹窗：内联覆盖层渲染在根 Box 最末，z 序最高；
    // 刷新结果由引擎持久化到 GameData.pendingTraitAdds——关闭再打开仍显示，可直接确认新增）
    state.showTraitAddType?.let { type ->
        TraitAddDialog(
            disciple = disciple,
            type = type,
            jadeSymbols = gameData?.jadeSymbols ?: 0,
            pendingTraitId = gameData?.pendingTraitAdds
                ?.firstOrNull { it.discipleId == disciple.id && it.type == type.name }
                ?.traitId,
            viewModel = viewModel,
            onDismiss = { state.showTraitAddType = null }
        )
    }
}

/** 次级对话框集合（DiscipleDetailDialog 拆分）：关系/日志/储物袋/聊天 + 确认/选择/洗炼/详情类对话框 */
// 拆分聚合:平铺参数搬移自原公共函数
@Suppress("LongParameterList")
@Composable
private fun DiscipleDetailSecondaryDialogs(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    viewModel: GameViewModel?,
    allEquipment: List<EquipmentInstance>,
    allManuals: List<ManualInstance>,
    manualStacks: List<ManualStack>,
    equipmentStacks: List<EquipmentStack>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>,
    state: DiscipleDetailDialogState,
    onDismiss: () -> Unit
) {
    val gameData by viewModel?.gameData?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val gameYear = gameData?.gameYear ?: 1

    if (state.showRelationsDialog) {
        RelationsDialog(disciple = disciple, allDisciples = allDisciples,
            onDismiss = { state.showRelationsDialog = false })
    }
    if (state.showLifeLogDialog) {
        LifeLogDialog(discipleName = disciple.name,
            events = viewModel?.getLifeEvents(disciple.id) ?: emptyList(),
            onDismiss = { state.showLifeLogDialog = false })
    }
    if (state.showStorageBagDialog) {
        StorageBagDialog(items = disciple.storageBagItems, spiritStones = disciple.storageBagSpiritStones,
            disciple = disciple, viewModel = viewModel, onDismiss = { state.showStorageBagDialog = false })
    }
    if (state.showChatDialog) {
        val lastChatYear = viewModel?.getLastChatYear(disciple.id)
        val hasCooldown = lastChatYear != null && lastChatYear == gameYear
        DiscipleChatDialog(disciple = disciple, gameYear = gameYear, hasCooldown = hasCooldown,
            viewModel = viewModel, onDismiss = { state.showChatDialog = false })
    }

    DiscipleDetailStandardDialogs(
        disciple = disciple, allDisciples = allDisciples, viewModel = viewModel,
        state = state, onDismiss = onDismiss
    )
    DiscipleDetailSelectionDialogs(
        disciple = disciple, viewModel = viewModel, allEquipment = allEquipment,
        equipmentStacks = equipmentStacks, manualStacks = manualStacks,
        allManuals = allManuals, state = state
    )
    DiscipleDetailTraitWashDialogs(disciple = disciple, viewModel = viewModel, state = state)
    DiscipleDetailTailDialogs(
        disciple = disciple, viewModel = viewModel, allEquipment = allEquipment, state = state
    )
    DiscipleDetailManualSection(
        disciple = disciple, viewModel = viewModel, manualStacks = manualStacks,
        allManuals = allManuals, manualProficiencies = manualProficiencies, state = state
    )
}

/** 确认/拜师类对话框（DiscipleDetailDialog 拆分）：驱逐确认 + 卸任确认/阻塞提示 + 拜师选择/确认 */
@Composable
private fun DiscipleDetailStandardDialogs(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
    viewModel: GameViewModel?,
    state: DiscipleDetailDialogState,
    onDismiss: () -> Unit
) {
    if (state.showExpelConfirmDialog) {
        StandardPromptDialog(onDismissRequest = { state.showExpelConfirmDialog = false },
            title = "确认驱逐",
            text = "确定要驱逐弟子 ${disciple.name} 吗？此操作不可撤销。",
            confirmLabel = "确认",
            onConfirm = { viewModel?.expelDisciple(disciple.id); state.showExpelConfirmDialog = false; onDismiss() },
            dismissLabel = "取消", onDismiss = { state.showExpelConfirmDialog = false })
    }

    if (state.showResignConfirmDialog) {
        StandardPromptDialog(onDismissRequest = { state.showResignConfirmDialog = false },
            title = "卸任确认",
            text = state.resignConfirmMessage,
            confirmLabel = "确认卸任",
            onConfirm = {
                viewModel?.releaseDiscipleForReassignment(disciple.id)
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

    var selectedMaster by remember { mutableStateOf<DiscipleAggregate?>(null) }
    var showApprenticeConfirmDialog by remember { mutableStateOf(false) }
    if (state.showApprenticeSelectDialog) {
        MasterApprenticeSelectDialog(
            currentDisciple = disciple,
            allDisciples = allDisciples,
            onDismiss = { state.showApprenticeSelectDialog = false },
            onMasterSelected = { master -> selectedMaster = master; showApprenticeConfirmDialog = true }
        )
    }

    selectedMaster?.let { master ->
        if (showApprenticeConfirmDialog) {
            StandardPromptDialog(onDismissRequest = { showApprenticeConfirmDialog = false },
                title = "拜师确认",
                text = "确认让 ${disciple.name}（${disciple.realmName}）拜 ${master.name}（${master.realmName}）为师？",
                confirmLabel = "确认",
                onConfirm = {
                    viewModel?.apprenticeToMaster(disciple.id, master.id)
                    showApprenticeConfirmDialog = false
                    selectedMaster = null
                },
                dismissLabel = "取消", onDismiss = { showApprenticeConfirmDialog = false })
        }
    }
}

/** 装备/功法选择对话框（DiscipleDetailDialog 拆分） */
@Composable
private fun DiscipleDetailSelectionDialogs(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    allEquipment: List<EquipmentInstance>,
    equipmentStacks: List<EquipmentStack>,
    manualStacks: List<ManualStack>,
    allManuals: List<ManualInstance>,
    state: DiscipleDetailDialogState
) {
    var selectedEquipmentId by remember { mutableStateOf<String?>(null) }
    var selectedManualId by remember { mutableStateOf<String?>(null) }
    val maxManualSlots = remember(disciple.talentIds) { DiscipleStatCalculator.getMaxManualSlots(disciple) }

    state.showEquipmentSelection?.let { slotType ->
        EquipmentSelectionDialog(
            slotType = slotType,
            allEquipment = allEquipment,
            equipmentStacks = equipmentStacks,
            currentEquipmentId = when (slotType) {
                "weapon" -> disciple.weaponId
                "armor" -> disciple.armorId
                "boots" -> disciple.bootsId
                "accessory" -> disciple.accessoryId
                else -> null
            },
            currentDiscipleId = disciple.id,
            discipleRealm = disciple.realm,
            selectedEquipmentId = selectedEquipmentId,
            onSelect = { id -> selectedEquipmentId = if (selectedEquipmentId == id) null else id },
            onConfirm = {
                selectedEquipmentId?.let { id -> viewModel?.equipItem(disciple.id, id) }
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
            manualStacks = manualStacks,
            allManuals = allManuals,
            currentManualIds = disciple.manualIds,
            discipleRealm = disciple.realm,
            maxManualSlots = maxManualSlots,
            selectedManualId = selectedManualId,
            onSelect = { id -> selectedManualId = if (selectedManualId == id) null else id },
            onConfirm = {
                selectedManualId?.let { id -> viewModel?.learnManual(disciple.id, id) }
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

/** 天赋/体质洗炼详情（DiscipleDetailDialog 拆分）：详情弹窗 + 洗炼覆盖层（保底计数常驻） */
@Composable
private fun DiscipleDetailTraitWashDialogs(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    state: DiscipleDetailDialogState
) {
    val gameData by viewModel?.gameData?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    // 洗炼天赋/体质保底计数（同洗炼灵根：详情层常驻，弹窗关闭再打开不重置）
    var talentWashPityCount by remember { mutableIntStateOf(0) }
    var physiqueWashPityCount by remember { mutableIntStateOf(0) }

    state.selectedTalent?.let { talent ->
        TalentDetailDialog(
            talent = talent,
            onDismiss = {
                state.selectedTalent = null
                state.showTalentWashDialog = false
            },
            onWashClick = state::openTalentWash,
            washOverlay = {
                if (state.showTalentWashDialog) {
                    TraitWashOverlay(
                        type = TraitWashType.TALENT,
                        targetId = talent.id,
                        disciple = disciple,
                        jadeSymbols = gameData?.jadeSymbols ?: 0,
                        viewModel = viewModel,
                        session = WashSessionControl(initialPityCount = talentWashPityCount,
                            onPityCountChanged = { talentWashPityCount = it }, washing = false, onWashingChange = {}),
                        onDismiss = { state.showTalentWashDialog = false }
                    )
                }
            }
        )
    }

    state.selectedPhysique?.let { physique ->
        PhysiqueDetailDialog(
            physique = physique,
            onDismiss = {
                state.selectedPhysique = null
                state.showPhysiqueWashDialog = false
            },
            onWashClick = state::openPhysiqueWash,
            washOverlay = {
                if (state.showPhysiqueWashDialog) {
                    TraitWashOverlay(
                        type = TraitWashType.PHYSIQUE,
                        targetId = physique.id,
                        disciple = disciple,
                        jadeSymbols = gameData?.jadeSymbols ?: 0,
                        viewModel = viewModel,
                        session = WashSessionControl(initialPityCount = physiqueWashPityCount,
                            onPityCountChanged = { physiqueWashPityCount = it }, washing = false, onWashingChange = {}),
                        onDismiss = { state.showPhysiqueWashDialog = false }
                    )
                }
            }
        )
    }
}

/** 词条洗炼/装备详情（DiscipleDetailDialog 拆分）：Affix 详情弹窗 + 装备详情弹窗 */
@Composable
private fun DiscipleDetailTailDialogs(
    disciple: DiscipleAggregate,
    viewModel: GameViewModel?,
    allEquipment: List<EquipmentInstance>,
    state: DiscipleDetailDialogState
) {
    val gameData by viewModel?.gameData?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    // 洗炼词条保底计数（详情层常驻，弹窗关闭再打开不重置）
    var affixWashPityCount by remember { mutableIntStateOf(0) }

    state.selectedAffix?.let { affix ->
        AffixDetailDialog(
            affix = affix,
            onDismiss = {
                state.selectedAffix = null
                state.showAffixWashDialog = false
            },
            onWashClick = state::openAffixWash,
            washOverlay = {
                if (state.showAffixWashDialog) {
                    TraitWashOverlay(
                        type = TraitWashType.AFFIX,
                        targetId = affix.id,
                        disciple = disciple,
                        jadeSymbols = gameData?.jadeSymbols ?: 0,
                        viewModel = viewModel,
                        session = WashSessionControl(initialPityCount = affixWashPityCount,
                            onPityCountChanged = { affixWashPityCount = it }, washing = false, onWashingChange = {}),
                        onDismiss = { state.showAffixWashDialog = false }
                    )
                }
            }
        )
    }

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
                        viewModel?.unequipItem(disciple.id, equipment.id)
                        state.showEquipmentDetailDialog = null
                    }
                )
                GameButton(
                    text = "更换",
                    onClick = {
                        state.showEquipmentDetailDialog = null
                        state.showEquipmentSelection = equipment.slot.name.lowercase(java.util.Locale.getDefault())
                    }
                )
            }
        )
    }
}

/** 功法详情与更换（DiscipleDetailDialog 拆分）：已学功法详情 + 更换选择弹窗 */
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
            onForget = { viewModel?.forgetManual(disciple.id, manual.id); state.showManualDetailDialog = null },
            onDismiss = { state.showManualDetailDialog = null },
            extraActions = { GameButton(text = "更换", onClick = { showManualReplaceSelection = true }) }
        )

        if (showManualReplaceSelection) {
            val watchedKeys = viewModel?.watchedItemIds?.collectAsStateWithLifecycle()?.value
                ?: emptySet()
            val availableManualStacks = remember(
                manualStacks, allManuals, disciple.manualIds, manual, disciple.realm, watchedKeys
            ) {
                val manualMap = allManuals.associateBy { it.id }
                val otherManualIds = disciple.manualIds.filter { it != manual.id }
                val hasMindManual = otherManualIds.any { mid -> manualMap[mid]?.type == ManualType.MIND }
                val learnedNames = otherManualIds.mapNotNull { mid -> manualMap[mid]?.name }.toSet()
                manualStacks.filter { stack ->
                    !(hasMindManual && stack.type == ManualType.MIND) &&
                    stack.name !in learnedNames &&
                    GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)
                }.sortedByWatchedThenRarity(watchedKeys)
            }
            var selectedReplaceManualId by remember { mutableStateOf<String?>(null) }
            var showReplaceDetailStack by remember { mutableStateOf<ManualStack?>(null) }

            ManualReplaceDialog(
                availableManualStacks = availableManualStacks,
                selectedReplaceManualId = selectedReplaceManualId,
                watchedKeys = watchedKeys,
                onSelectReplaceManual = { id ->
                    selectedReplaceManualId = if (selectedReplaceManualId == id) null else id
                },
                onViewReplaceDetail = { stack -> showReplaceDetailStack = stack },
                onConfirmReplace = {
                    selectedReplaceManualId?.let { newId -> viewModel?.replaceManual(disciple.id, manual.id, newId) }
                    showManualReplaceSelection = false
                    state.showManualDetailDialog = null
                },
                onDismissReplace = { showManualReplaceSelection = false }
            )

            showReplaceDetailStack?.let { stack ->
                ItemDetailDialog(item = stack, onDismiss = { showReplaceDetailStack = null }, viewModel = viewModel)
            }
        }
    }
}

/**
 * 功法更换选择对话框
 */
@Composable
private fun ManualReplaceDialog(
    availableManualStacks: List<ManualStack>,
    selectedReplaceManualId: String?,
    watchedKeys: Set<String> = emptySet(),
    onSelectReplaceManual: (String) -> Unit,
    onViewReplaceDetail: (ManualStack) -> Unit,
    onConfirmReplace: () -> Unit,
    onDismissReplace: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismissReplace,
        title = "选择新功法",
        mode = DialogMode.Auto,
        dismissOnClickOutside = false
    ) {
        if (availableManualStacks.isEmpty()) {
            Text(
                text = "暂无可更换的功法",
                fontSize = 12.sp,
                color = Color.Black,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(60.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(availableManualStacks, key = { it.id }, contentType = { "manual_stack" }) { stack ->
                    UnifiedItemCard(
                        data = ItemCardData(
                            id = stack.id,
                            name = stack.name,
                            rarity = stack.rarity,
                            quantity = stack.quantity,
                            isLocked = stack.isLocked,
                            isManual = true
                        ),
                        isSelected = selectedReplaceManualId == stack.id,
                        isFollowed = stack.watchKey() in watchedKeys,
                        onClick = { onSelectReplaceManual(stack.id) },
                        onLongPress = { onViewReplaceDetail(stack) }
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GameButton(
                text = "取消",
                onClick = onDismissReplace
            )
            GameButton(
                text = "确认更换",
                onClick = onConfirmReplace,
                enabled = selectedReplaceManualId != null
            )
        }
    }
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
    val equipmentStacks by viewModel.equipmentStacks.collectAsStateWithLifecycle()

    DiscipleDetailDialog(
        disciple = disciple,
        allDisciples = allDisciples,
        allEquipment = equipment,
        allManuals = manuals,
        manualStacks = manualStacks,
        equipmentStacks = equipmentStacks,
        manualProficiencies = manualProficiencies,
        viewModel = viewModel,
        onDismiss = onDismiss,
        onNavigateToDisciple = onNavigateToDisciple,
        scrimEnabled = scrimEnabled
    )
}

/**
 * 特质洗炼覆盖层（天赋/体质/词条详情 Dialog 的 overlay 槽位内容）。
 *
 * 洗炼弹窗 [TraitWashDialog] 是内联 Box 覆盖层（非平台 Dialog 窗口），必须与详情
 * 同窗口渲染（经 [SmallScreenDialog] overlay 槽位）——渲染在下层弟子详情窗口内
 * 会被上层详情窗口整体遮挡而不可见不可点（4.00.92 兑换码事故同源教训）。
 * 洗炼状态（弹窗开关/保底计数/互斥闭包）由 DiscipleDetailDialog 持有，此处仅透传。
 * 单槽语义：targetId 为详情界面点入的目标特质——从哪个详情进入，就洗炼哪一个。
 */
@Composable
private fun TraitWashOverlay(
    type: TraitWashType,
    targetId: String,
    disciple: DiscipleAggregate,
    jadeSymbols: Int,
    viewModel: GameViewModel?,
    session: WashSessionControl,
    onDismiss: () -> Unit
) {
    TraitWashDialog(
        disciple = disciple,
        type = type,
        targetId = targetId,
        jadeSymbols = jadeSymbols,
        viewModel = viewModel,
        washSession = session,
        onDismiss = onDismiss
    )
}
