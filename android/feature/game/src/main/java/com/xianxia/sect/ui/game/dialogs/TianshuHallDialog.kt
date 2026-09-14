@file:Suppress("TooManyFunctions") // 私有辅助函数集中在本文件
package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.charm
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.ui.components.ElderBonusInfo
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.ProductionElderSection
import com.xianxia.sect.ui.game.ALCHEMY_THEME
import com.xianxia.sect.ui.game.FORGE_THEME
import com.xianxia.sect.ui.game.HERB_GARDEN_THEME
import com.xianxia.sect.ui.game.ProductionTheme
import com.xianxia.sect.ui.game.ProductionElderSelectionDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.assignElder
import com.xianxia.sect.ui.game.getViceSectMasterIntelligenceBonus
import com.xianxia.sect.ui.game.removeElder
import com.xianxia.sect.ui.game.removeViceSectMaster
import com.xianxia.sect.ui.game.setViceSectMaster
import com.xianxia.sect.ui.game.toggleAlchemyIncentive
import com.xianxia.sect.ui.game.toggleAsceticTraining
import com.xianxia.sect.ui.game.toggleBenevolentGovernance
import com.xianxia.sect.ui.game.toggleCultivationSubsidy
import com.xianxia.sect.ui.game.toggleCurfew
import com.xianxia.sect.ui.game.toggleEnhancedSecurity
import com.xianxia.sect.ui.game.toggleForgeIncentive
import com.xianxia.sect.ui.game.toggleFrugality
import com.xianxia.sect.ui.game.toggleHerbCultivation
import com.xianxia.sect.ui.game.toggleManualResearch
import com.xianxia.sect.ui.game.toggleMoralEducation
import com.xianxia.sect.ui.game.toggleOpenRecruitment
import com.xianxia.sect.ui.game.toggleRelaxedMgmt
import com.xianxia.sect.ui.game.toggleRewardPunish
import com.xianxia.sect.ui.game.toggleSpiritMineBoost
import com.xianxia.sect.ui.game.toggleSpiritSpring
import com.xianxia.sect.ui.game.toggleStrictTraining
import com.xianxia.sect.core.usecase.toggleAlchemyIncentive
import com.xianxia.sect.core.usecase.toggleCurfew
import com.xianxia.sect.core.usecase.toggleEnhancedSecurity
import com.xianxia.sect.core.usecase.toggleForgeIncentive
import com.xianxia.sect.core.usecase.toggleFrugality
import com.xianxia.sect.core.usecase.toggleHerbCultivation
import com.xianxia.sect.core.usecase.toggleManualResearch
import com.xianxia.sect.core.usecase.toggleRelaxedMgmt
import com.xianxia.sect.core.usecase.toggleRewardPunish
import com.xianxia.sect.core.usecase.toggleSpiritSpring
import com.xianxia.sect.core.usecase.toggleStrictTraining

@Composable
fun TianshuHallDialog(
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    val flags = remember { TianshuHallFlags() }

    val state = rememberTianshuHallState(gameData = gameData, disciples = disciples)

    UnifiedGameDialog(
        onDismissRequest = onDismiss, title = "天枢殿",
        mode = DialogMode.Half, scrollableContent = false
    ) {
        TianshuHallElderSection(
            state = state,
            flags = flags,
            disciples = disciples,
            viewModel = viewModel,
            productionViewModel = productionViewModel
        )
    }

    TianshuElderSelectionDialogs(
        flags = flags,
        state = state,
        viewModel = viewModel,
        productionViewModel = productionViewModel
    )

    TianshuSectDialogs(
        flags = flags,
        state = state,
        viewModel = viewModel,
        productionViewModel = productionViewModel
    )
}

/** 天枢殿子弹窗开关：7 项 mutableStateOf 开关状态 */
private class TianshuHallFlags {
    var showViceSectMasterSelectDialog by mutableStateOf(false)
    var showAlchemyElderSelectDialog by mutableStateOf(false)
    var showForgeElderSelectDialog by mutableStateOf(false)
    var showHerbGardenElderSelectDialog by mutableStateOf(false)
    var showRecruitingElderSelectDialog by mutableStateOf(false)
    var showSectAffairsDialog by mutableStateOf(false)
    var showSectPoliciesDialog by mutableStateOf(false)
}

/** 天枢殿派生状态 */
private data class TianshuHallState(
    val gameData: GameData?,
    val disciples: List<DiscipleAggregate>,
    val elderSlots: ElderSlots?,
    val viceSectMaster: DiscipleAggregate?,
    val recruitingElder: DiscipleAggregate?,
    val alchemyElder: DiscipleAggregate?,
    val forgeElder: DiscipleAggregate?,
    val herbGardenElder: DiscipleAggregate?,
    val battleAndExplorationIds: Set<String>
)

/** 天枢殿派生状态计算 */
@Composable
private fun rememberTianshuHallState(
    gameData: GameData?,
    disciples: List<DiscipleAggregate>
): TianshuHallState {
    val elderSlots = gameData?.elderSlots
    val discipleMap = disciples.associateBy { it.id }
    val battleAndExplorationIds = remember(gameData) {
        if (gameData != null) {
            val battleIds = gameData.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }
                .toSet()
            val explorationIds = gameData.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }
                .toSet()
            battleIds + explorationIds
        } else emptySet()
    }
    return TianshuHallState(
        gameData = gameData,
        disciples = disciples,
        elderSlots = elderSlots,
        viceSectMaster = discipleMap[elderSlots?.viceSectMaster],
        recruitingElder = discipleMap[elderSlots?.recruitingElder],
        alchemyElder = discipleMap[elderSlots?.alchemyElder],
        forgeElder = discipleMap[elderSlots?.forgeElder],
        herbGardenElder = discipleMap[elderSlots?.herbGardenElder],
        battleAndExplorationIds = battleAndExplorationIds
    )
}

/** 天枢殿长老区：副宗主/纳徒长老/生产长老/操作按钮 */
@Composable
private fun TianshuHallElderSection(
    state: TianshuHallState,
    flags: TianshuHallFlags,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TianshuViceSectMasterSlot(
            viceSectMaster = state.viceSectMaster,
            disciples = disciples,
            viewModel = viewModel,
            onSelect = { flags.showViceSectMasterSelectDialog = true },
            onRemove = { productionViewModel.removeViceSectMaster() }
        )

        Spacer(modifier = Modifier.height(4.dp))

        TianshuRecruitingElderSlot(
            recruitingElder = state.recruitingElder,
            disciples = disciples,
            viewModel = viewModel,
            onSelect = { flags.showRecruitingElderSelectDialog = true },
            onRemove = { productionViewModel.removeElder(ElderSlotType.RECRUITING) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        TianshuProductionEldersRow(
            state = state,
            disciples = disciples,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onAlchemySwap = { flags.showAlchemyElderSelectDialog = true },
            onForgeSwap = { flags.showForgeElderSelectDialog = true },
            onHerbGardenSwap = { flags.showHerbGardenElderSelectDialog = true }
        )

        HorizontalDivider(color = GameColors.Border, thickness = 1.dp)

        TianshuSectActionRow(
            onOpenSectAffairs = { flags.showSectAffairsDialog = true },
            onOpenSectPolicies = { flags.showSectPoliciesDialog = true }
        )
    }
}

/** 副宗主槽位 */
@Composable
private fun TianshuViceSectMasterSlot(
    viceSectMaster: DiscipleAggregate?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "副宗主",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(4.dp))

        DiscipleSlot(
            disciple = viceSectMaster,
            showActions = true,
            onSlotClick = {
                viceSectMaster?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
            },
            onEmptySlotClick = onSelect,
            onDismiss = onRemove,
            onSwap = onSelect
        )
    }
}

/** 纳徒长老槽位 */
@Composable
private fun TianshuRecruitingElderSlot(
    recruitingElder: DiscipleAggregate?,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    // 纳徒长老
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "纳徒长老",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ElderBonusInfoButton(
                bonusInfo = ElderBonusInfoProvider.recruitingElderInfo
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        DiscipleSlot(
            disciple = recruitingElder,
            showActions = true,
            onSlotClick = {
                recruitingElder?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
            },
            onEmptySlotClick = onSelect,
            onDismiss = onRemove,
            onSwap = onSelect
        )
    }
}

/** 生产长老行：炼丹/锻造/灵田三槽位 */
@Composable
private fun TianshuProductionEldersRow(
    state: TianshuHallState,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onAlchemySwap: () -> Unit,
    onForgeSwap: () -> Unit,
    onHerbGardenSwap: () -> Unit
) {
    val alchemyElder = state.alchemyElder
    val forgeElder = state.forgeElder
    val herbGardenElder = state.herbGardenElder
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
    ) {
        ProductionElderSection(
            theme = ALCHEMY_THEME,
            elder = alchemyElder,
            onSlotClick = {
                alchemyElder?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
            },
            onElderRemove = { productionViewModel.removeElder(ElderSlotType.ALCHEMY) },
            onSwap = onAlchemySwap
        )

        ProductionElderSection(
            theme = FORGE_THEME,
            elder = forgeElder,
            onSlotClick = {
                forgeElder?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
            },
            onElderRemove = { productionViewModel.removeElder(ElderSlotType.FORGE) },
            onSwap = onForgeSwap
        )

        ProductionElderSection(
            theme = HERB_GARDEN_THEME,
            elder = herbGardenElder,
            onSlotClick = {
                herbGardenElder?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
            },
            onElderRemove = { productionViewModel.removeElder(ElderSlotType.HERB_GARDEN) },
            onSwap = onHerbGardenSwap
        )
    }
}

/** 宗门操作按钮行：宗门管理/宗门政策 */
@Composable
private fun TianshuSectActionRow(
    onOpenSectAffairs: () -> Unit,
    onOpenSectPolicies: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        GameButton(
            text = "宗门管理",
            onClick = onOpenSectAffairs,
            modifier = Modifier.width(ButtonSizes.StandardWidth)
        )

        GameButton(
            text = "宗门政策",
            onClick = onOpenSectPolicies,
            modifier = Modifier.width(ButtonSizes.StandardWidth)
        )
    }
}

/** 天枢殿长老选择子弹窗组 */
@Composable
private fun TianshuElderSelectionDialogs(
    flags: TianshuHallFlags,
    state: TianshuHallState,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel
) {
    if (flags.showViceSectMasterSelectDialog) {
        TianshuViceSectMasterSelectionDialog(
            state = state, viewModel = viewModel,
            onDismiss = { flags.showViceSectMasterSelectDialog = false },
            onSelected = { id ->
                productionViewModel.setViceSectMaster(id)
                flags.showViceSectMasterSelectDialog = false
            }
        )
    }

    if (flags.showAlchemyElderSelectDialog) {
        TianshuProductionElderSelectionDialog(
            state = state, viewModel = viewModel, theme = ALCHEMY_THEME,
            elderId = state.elderSlots?.alchemyElder,
            onDismiss = { flags.showAlchemyElderSelectDialog = false },
            onSelected = { id ->
                productionViewModel.assignElder(ElderSlotType.ALCHEMY, id)
                flags.showAlchemyElderSelectDialog = false
            }
        )
    }

    if (flags.showForgeElderSelectDialog) {
        TianshuProductionElderSelectionDialog(
            state = state, viewModel = viewModel, theme = FORGE_THEME, elderId = state.elderSlots?.forgeElder,
            onDismiss = { flags.showForgeElderSelectDialog = false },
            onSelected = { id ->
                productionViewModel.assignElder(ElderSlotType.FORGE, id)
                flags.showForgeElderSelectDialog = false
            }
        )
    }

    if (flags.showHerbGardenElderSelectDialog) {
        TianshuProductionElderSelectionDialog(
            state = state, viewModel = viewModel, theme = HERB_GARDEN_THEME,
            elderId = state.elderSlots?.herbGardenElder,
            onDismiss = { flags.showHerbGardenElderSelectDialog = false },
            onSelected = { id ->
                productionViewModel.assignElder(ElderSlotType.HERB_GARDEN, id)
                flags.showHerbGardenElderSelectDialog = false
            }
        )
    }

    if (flags.showRecruitingElderSelectDialog) {
        TianshuRecruitingElderSelectionDialog(
            state = state, viewModel = viewModel,
            onDismiss = { flags.showRecruitingElderSelectDialog = false },
            onSelected = { id ->
                productionViewModel.assignElder(ElderSlotType.RECRUITING, id)
                flags.showRecruitingElderSelectDialog = false
            }
        )
    }
}

/** 副宗主选择弹窗 */
@Composable
private fun TianshuViceSectMasterSelectionDialog(
    state: TianshuHallState,
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    val tianshuTheme = remember {
        ProductionTheme(
            buildingId = "tianshu",
            displayName = "天枢殿",
            elderTitle = "副宗主",
            elderBonusInfo = ElderBonusInfo(
                title = "副宗主",
                requiredAttribute = "智力",
                effectDescription = "副宗主掌管宗门事务",
                bonusFormula = "智力越高，效率越高"
            ),
            coreAttributeName = "智力",
            coreAttributeColor = Color(0xFF4A90E2),
            defaultBorderColor = Color(0xFF4A90E2),
            workingStatusColor = GameColors.Info,
            selectedHighlightColor = GameColors.Gold,
            slotLabelPrefix = "",
            selectionDialogTitle = "",
            startProductionText = "",
            elderSelectionTitle = "选择副宗主",
            recommendAttributeText = "智力",
            getCoreAttributeValue = { it.intelligence },
            getElderId = { it.viceSectMaster },
            getDirectDisciples = { emptyList() },
            elderSortComparator = compareByDescending<DiscipleAggregate> { it.intelligence }
                .thenBy { it.realm }
                .thenByDescending { it.realmLayer },
            directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }

    ProductionElderSelectionDialog(
        theme = tianshuTheme,
        disciples = state.disciples.filter { it.isAlive },
        currentElderId = state.elderSlots?.viceSectMaster,
        elderSlots = state.elderSlots ?: ElderSlots(),
        onDismiss = onDismiss,
        onSelect = onSelected,
        battleAndExplorationIds = state.battleAndExplorationIds,
        viewModel = viewModel,
    )
}

/** 生产长老选择弹窗：炼丹/锻造/灵田共用 */
@Composable
private fun TianshuProductionElderSelectionDialog(
    state: TianshuHallState,
    viewModel: GameViewModel,
    theme: ProductionTheme,
    elderId: String?,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    ProductionElderSelectionDialog(
        theme = theme,
        disciples = state.disciples.filter { it.isAlive },
        currentElderId = elderId,
        elderSlots = state.elderSlots ?: ElderSlots(),
        onDismiss = onDismiss,
        onSelect = onSelected,
        battleAndExplorationIds = state.battleAndExplorationIds,
        viewModel = viewModel,
    )
}

/** 纳徒长老选择弹窗 */
@Composable
private fun TianshuRecruitingElderSelectionDialog(
    state: TianshuHallState,
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    val recruitingTheme = remember {
        ProductionTheme(
            buildingId = "recruiting",
            displayName = "天枢殿",
            elderTitle = "纳徒长老",
            elderBonusInfo = ElderBonusInfoProvider.recruitingElderInfo,
            coreAttributeName = "魅力",
            coreAttributeColor = Color(0xFFFF69B4),
            defaultBorderColor = Color(0xFFFF69B4),
            workingStatusColor = Color(0xFFFF1493),
            selectedHighlightColor = GameColors.Gold,
            slotLabelPrefix = "",
            selectionDialogTitle = "",
            startProductionText = "",
            elderSelectionTitle = "选择纳徒长老",
            recommendAttributeText = "魅力",
            getCoreAttributeValue = { it.charm },
            getElderId = { it.recruitingElder },
            getDirectDisciples = { emptyList() },
            elderSortComparator = compareByDescending<DiscipleAggregate> { it.charm }
                .thenBy { it.realm }
                .thenByDescending { it.realmLayer },
            directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }

    ProductionElderSelectionDialog(
        theme = recruitingTheme,
        disciples = state.disciples,
        currentElderId = state.elderSlots?.recruitingElder,
        elderSlots = state.elderSlots ?: ElderSlots(),
        onDismiss = onDismiss,
        onSelect = onSelected,
        battleAndExplorationIds = state.battleAndExplorationIds,
        viewModel = viewModel,
    )
}

/** 宗门管理/政策子弹窗组 */
@Composable
private fun TianshuSectDialogs(
    flags: TianshuHallFlags,
    state: TianshuHallState,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel
) {
    if (flags.showSectAffairsDialog) {
        SectManagementDialog(
            gameData = state.gameData,
            viewModel = viewModel,
            onDismiss = { flags.showSectAffairsDialog = false }
        )
    }

    if (flags.showSectPoliciesDialog) {
        SectPoliciesDialog(
            gameData = state.gameData,
            viewModel = viewModel,
            productionViewModel = productionViewModel,
            onDismiss = { flags.showSectPoliciesDialog = false }
        )
    }
}

@Composable
@Suppress("UnusedParameter") // viewModel: 弹窗/组件统一签名约定：保持调用点参数面一致并预留子组件扩展消费
private fun SectPoliciesDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "宗门政策",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SectPoliciesContent(
                gameData = gameData,
                productionViewModel = productionViewModel
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 宗门政策内容区：四类政策分组列表 */
@Composable
private fun ColumnScope.SectPoliciesContent(
    gameData: GameData?,
    productionViewModel: ProductionViewModel
) {
    val sectPolicies = gameData?.sectPolicies
    val viceBonus = productionViewModel.getViceSectMasterIntelligenceBonus()
    val viceBonusText = if (viceBonus > 0) " (副宗主加成+${(viceBonus * 100).toInt()}%)" else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectPoliciesProductionList(
            sectPolicies = sectPolicies,
            viceBonusText = viceBonusText,
            productionViewModel = productionViewModel
        )
        SectPoliciesCultivationList(
            sectPolicies = sectPolicies,
            viceBonusText = viceBonusText,
            productionViewModel = productionViewModel
        )
        SectPoliciesSecurityList(
            sectPolicies = sectPolicies,
            viceBonusText = viceBonusText,
            productionViewModel = productionViewModel
        )
        SectPoliciesManagementList(
            sectPolicies = sectPolicies,
            viceBonusText = viceBonusText,
            productionViewModel = productionViewModel
        )
    }
}

/** 生产类政策列表 */
@Composable
private fun SectPoliciesProductionList(
    sectPolicies: SectPolicies?,
    viceBonusText: String,
    productionViewModel: ProductionViewModel
) {
    // ═══ 生产类 ═══
    PolicyItem(
        title = "灵矿增产",
        effect = "灵石产出+20%$viceBonusText",
        cost = "采矿弟子忠诚-1/月",
        checked = sectPolicies?.spiritMineBoost ?: false,
        onCheckedChange = { productionViewModel.toggleSpiritMineBoost() }
    )

    PolicyItem(
        title = "丹道激励",
        effect = "炼丹成功率+10%$viceBonusText",
        cost = "炼丹时间+10%，月耗3000灵石",
        checked = sectPolicies?.alchemyIncentive ?: false,
        onCheckedChange = { productionViewModel.toggleAlchemyIncentive() }
    )

    PolicyItem(
        title = "锻造激励",
        effect = "锻造成功率+10%$viceBonusText",
        cost = "锻造时间+10%，月耗3000灵石",
        checked = sectPolicies?.forgeIncentive ?: false,
        onCheckedChange = { productionViewModel.toggleForgeIncentive() }
    )

    PolicyItem(
        title = "灵药培育",
        effect = "灵药生长速度+20%$viceBonusText",
        cost = "月耗3000灵石",
        checked = sectPolicies?.herbCultivation ?: false,
        onCheckedChange = { productionViewModel.toggleHerbCultivation() }
    )

    PolicyItem(
        title = "灵泉灌溉",
        effect = "灵田生长速度+15%",
        cost = "月耗2000灵石",
        checked = sectPolicies?.spiritSpring ?: false,
        onCheckedChange = { productionViewModel.toggleSpiritSpring() }
    )

    PolicyItem(
        title = "开源节流",
        effect = "所有弟子年俸-30%",
        cost = "年俸发放不加忠诚",
        checked = sectPolicies?.frugality ?: false,
        onCheckedChange = { productionViewModel.toggleFrugality() }
    )
}

/** 修行类政策列表 */
@Composable
private fun SectPoliciesCultivationList(
    sectPolicies: SectPolicies?,
    viceBonusText: String,
    productionViewModel: ProductionViewModel
) {
    // ═══ 修行类 ═══
    PolicyItem(
        title = "修行津贴",
        effect = "化神境以下弟子修炼速度+15%$viceBonusText",
        cost = "300灵石/化神下弟子/月",
        checked = sectPolicies?.cultivationSubsidy ?: false,
        onCheckedChange = { productionViewModel.toggleCultivationSubsidy() }
    )

    PolicyItem(
        title = "功法研习",
        effect = "功法修炼速度+20%$viceBonusText",
        cost = "月耗4000灵石",
        checked = sectPolicies?.manualResearch ?: false,
        onCheckedChange = { productionViewModel.toggleManualResearch() }
    )

    PolicyItem(
        title = "苦修令",
        effect = "修炼速度+25%$viceBonusText",
        cost = "800灵石/弟子/月",
        checked = sectPolicies?.asceticTraining ?: false,
        onCheckedChange = { productionViewModel.toggleAsceticTraining() }
    )
}

/** 治安类政策列表 */
@Composable
private fun SectPoliciesSecurityList(
    sectPolicies: SectPolicies?,
    viceBonusText: String,
    productionViewModel: ProductionViewModel
) {
    // ═══ 治安类 ═══
    PolicyItem(
        title = "增强治安",
        effect = "执法堂抓捕率+20%$viceBonusText",
        cost = "弟子忠诚-1/月，月耗3000灵石",
        checked = sectPolicies?.enhancedSecurity ?: false,
        onCheckedChange = { productionViewModel.toggleEnhancedSecurity() }
    )

    PolicyItem(
        title = "宵禁",
        effect = "治安事件-30%，叛逃-20%",
        cost = "弟子忠诚-1/月，月耗1000灵石",
        checked = sectPolicies?.curfew ?: false,
        onCheckedChange = { productionViewModel.toggleCurfew() }
    )

    PolicyItem(
        title = "赏善罚恶",
        effect = "执法效率+30%",
        cost = "月耗3000灵石",
        checked = sectPolicies?.rewardPunish ?: false,
        onCheckedChange = { productionViewModel.toggleRewardPunish() }
    )
}

/** 管理类政策列表 */
@Suppress("UnusedParameter")
@Composable
private fun SectPoliciesManagementList(
    sectPolicies: SectPolicies?,
    viceBonusText: String,
    productionViewModel: ProductionViewModel
) {
    // ═══ 管理类 ═══
    PolicyItem(
        title = "广纳门徒",
        effect = "招募弟子数上限+50%",
        cost = "5万灵石/3年",
        checked = sectPolicies?.openRecruitment ?: false,
        onCheckedChange = { productionViewModel.toggleOpenRecruitment() }
    )

    PolicyItem(
        title = "严苛训练",
        effect = "战斗伤害+5%",
        cost = "弟子忠诚-1/月，月耗2万灵石",
        checked = sectPolicies?.strictTraining ?: false,
        onCheckedChange = { productionViewModel.toggleStrictTraining() }
    )

    PolicyItem(
        title = "松弛管理",
        effect = "弟子忠诚+2/月",
        cost = "弟子修炼速度-10%，月耗3000灵石",
        checked = sectPolicies?.relaxedMgmt ?: false,
        onCheckedChange = { productionViewModel.toggleRelaxedMgmt() }
    )

    PolicyItem(
        title = "教化之道",
        effect = "每月所有弟子道德+1（上限70）",
        cost = "100灵石/弟子/月",
        checked = sectPolicies?.moralEducation ?: false,
        onCheckedChange = { productionViewModel.toggleMoralEducation() }
    )

    PolicyItem(
        title = "仁政爱徒",
        effect = "每月所有弟子忠诚+1（上限100）",
        cost = "100灵石/弟子/月",
        checked = sectPolicies?.benevolentGovernance ?: false,
        onCheckedChange = { productionViewModel.toggleBenevolentGovernance() }
    )
}

@Composable
private fun PolicyItem(
    title: String,
    effect: String,
    cost: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = effect,
                fontSize = 10.sp,
                color = Color.Black
            )
            Text(
                text = cost,
                fontSize = 10.sp,
                color = Color.Black
            )
        }

        Checkbox(
            checked = checked,
            onCheckedChange = { newChecked ->
                if (!onCheckedChange(newChecked)) {
                    return@Checkbox
                }
            }
        )
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = GameColors.Border)
}
