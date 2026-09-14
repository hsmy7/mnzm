package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.intelligence
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.ProductionViewModel
import com.xianxia.sect.ui.game.ProductionTheme
import com.xianxia.sect.ui.game.ProductionElderSelectionDialog
import com.xianxia.sect.ui.game.ProductionDirectDiscipleSelectionDialog
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.components.ElderBonusInfoButton
import com.xianxia.sect.ui.components.ElderBonusInfoProvider
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.DiscipleSlot
import com.xianxia.sect.ui.game.assignDirectDisciple
import com.xianxia.sect.ui.game.assignElder
import com.xianxia.sect.ui.game.getLawEnforcementDisciples
import com.xianxia.sect.ui.game.getLawEnforcementElder
import com.xianxia.sect.ui.game.removeDirectDisciple
import com.xianxia.sect.ui.game.removeElder

@Composable
fun LawEnforcementHallDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    viewModel: GameViewModel,
    productionViewModel: ProductionViewModel,
    onDismiss: () -> Unit
) {
    var showElderSelection by remember { mutableStateOf(false) }
    var showDiscipleSelection by remember { mutableStateOf<Int?>(null) }

    val lawElder = productionViewModel.getLawEnforcementElder()
    val lawDisciples = productionViewModel.getLawEnforcementDisciples()
    val discipleMap = disciples.associateBy { it.id }

    val battleAndExplorationIds = remember(gameData) { buildBattleAndExplorationIds(gameData) }

    LawHallDialogFrame(
        elder = lawElder,
        lawDisciples = lawDisciples,
        disciples = disciples,
        callbacks = LawHallCallbacks(
            onElderClick = { lawElder?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it, disciples))
                } },
            onElderRemove = { productionViewModel.removeElder(ElderSlotType.LAW_ENFORCEMENT) },
            onElderSwap = { showElderSelection = true },
            onDiscipleClick = { index ->
                val slot = lawDisciples.find { it.index == index }
                val d = if (slot != null && slot.isActive) discipleMap[slot.discipleId] else null
                d?.let { viewModel.overlays.showDiscipleDetail(DiscipleDetailRequest(it, disciples)) }
            },
            onDiscipleRemove = { index -> productionViewModel.removeDirectDisciple("lawEnforcement", index) },
            onDiscipleSwap = { index -> showDiscipleSelection = index }
        ),
        onDismiss = onDismiss
    )

    val lawTheme = remember { buildLawTheme() }
    val selectionData = LawSelectionDialogData(theme = lawTheme, disciples = disciples,
        gameData = gameData, battleAndExplorationIds = battleAndExplorationIds)

    if (showElderSelection) {
        LawElderSelectionDialog(
            data = selectionData,
            elder = lawElder,
            onSelect = { discipleId ->
                productionViewModel.assignElder(ElderSlotType.LAW_ENFORCEMENT, discipleId)
                showElderSelection = false
            },
            onDismiss = { showElderSelection = false }
        )
    }

    showDiscipleSelection?.let { slotIndex ->
        LawDiscipleSelectionDialog(
            data = selectionData,
            slotIndex = slotIndex,
            viewModel = viewModel,
            onSelect = { discipleId ->
                productionViewModel.assignDirectDisciple("lawEnforcement", slotIndex, discipleId)
                showDiscipleSelection = null
            },
            onDismiss = { showDiscipleSelection = null }
        )
    }

}

/** 执法堂内容回调 */
private data class LawHallCallbacks(
    val onElderClick: () -> Unit,
    val onElderRemove: () -> Unit,
    val onElderSwap: () -> Unit,
    val onDiscipleClick: (Int) -> Unit,
    val onDiscipleRemove: (Int) -> Unit,
    val onDiscipleSwap: (Int) -> Unit
)

/** 参战/探索中弟子 ID 集合 */
private fun buildBattleAndExplorationIds(gameData: GameData?): Set<String> {
    if (gameData != null) {
        val battleIds = gameData.battleTeams.flatMap { it.slots.map { it.discipleId } }.filter { it.isNotEmpty() }
            .toSet()
        val explorationIds = gameData.caveExplorationTeams.flatMap { it.memberIds }.filter { it.isNotEmpty() }.toSet()
        return battleIds + explorationIds
    }
    return emptySet()
}

/** 执法堂主对话框：UnifiedGameDialog + 长老/弟子区 */
@Composable
private fun LawHallDialogFrame(
    elder: DiscipleAggregate?,
    lawDisciples: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    callbacks: LawHallCallbacks,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "执法堂",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LawEnforcementContent(
                elder = elder,
                lawDisciples = lawDisciples,
                disciples = disciples,
                callbacks = callbacks
            )
        }
    }
}

/** 执法堂内容区：门规标语 + 长老 + 弟子区 */
@Composable
private fun ColumnScope.LawEnforcementContent(
    elder: DiscipleAggregate?,
    lawDisciples: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    callbacks: LawHallCallbacks
) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "维护宗门纪律，执行门规",
            fontSize = 10.sp,
            color = Color(0xFFE74C3C),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            textAlign = TextAlign.Center
        )

        LawElderSection(
            elder = elder,
            onElderClick = callbacks.onElderClick,
            onElderRemove = callbacks.onElderRemove,
            onElderSwap = callbacks.onElderSwap
        )

        LawDisciplesSection(
            lawDisciples = lawDisciples,
            disciples = disciples,
            onDiscipleClick = callbacks.onDiscipleClick,
            onDiscipleRemove = callbacks.onDiscipleRemove,
            onDiscipleSwap = callbacks.onDiscipleSwap
        )
    }
}

/** 执法堂 ProductionTheme 构建 */
private fun buildLawTheme(): ProductionTheme = ProductionTheme(
    buildingId = "lawEnforcement",
    displayName = "执法堂",
    elderTitle = "执法长老",
    elderBonusInfo = ElderBonusInfoProvider.lawEnforcementElderInfo,
    coreAttributeName = "智力",
    coreAttributeColor = Color(0xFFE74C3C),
    defaultBorderColor = Color(0xFFE74C3C),
    workingStatusColor = GameColors.Info,
    selectedHighlightColor = GameColors.Gold,
    slotLabelPrefix = "执法",
    selectionDialogTitle = "",
    startProductionText = "",
    elderSelectionTitle = "选择执法长老",
    recommendAttributeText = "智力",
    getCoreAttributeValue = { it.intelligence },
    getElderId = { it.lawEnforcementElder },
    getDirectDisciples = { it.lawEnforcementDisciples },
    elderSortComparator = compareByDescending<DiscipleAggregate> { it.intelligence }
        .thenBy { it.realm }
        .thenByDescending { it.realmLayer },
    directDiscipleSortComparator = compareBy<DiscipleAggregate> { it.realm }
        .thenByDescending { it.realmLayer }
        .thenByDescending { it.intelligence }
)

/** 选择弹窗数据 */
private data class LawSelectionDialogData(
    val theme: ProductionTheme,
    val disciples: List<DiscipleAggregate>,
    val gameData: GameData?,
    val battleAndExplorationIds: Set<String>
)

/** 执法长老选择弹窗 */
@Composable
private fun LawElderSelectionDialog(
    data: LawSelectionDialogData,
    elder: DiscipleAggregate?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ProductionElderSelectionDialog(
        theme = data.theme,
        disciples = data.disciples.filter { it.isAlive },
        currentElderId = elder?.id,
        elderSlots = data.gameData?.elderSlots ?: ElderSlots(),
        onDismiss = onDismiss,
        onSelect = onSelect,
        battleAndExplorationIds = data.battleAndExplorationIds,
    )
}

/** 执法弟子选择弹窗 */
@Suppress("UnusedParameter")
@Composable
private fun LawDiscipleSelectionDialog(
    data: LawSelectionDialogData,
    slotIndex: Int,
    viewModel: GameViewModel,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ProductionDirectDiscipleSelectionDialog(
        theme = data.theme,
        disciples = data.disciples.filter { it.isAlive },
        elderSlots = data.gameData?.elderSlots ?: ElderSlots(),
        onDismiss = onDismiss,
        onSelect = onSelect,
        viewModel = viewModel,
        battleAndExplorationIds = data.battleAndExplorationIds,
    )
}

@Composable
private fun LawElderSection(
    elder: DiscipleAggregate?,
    onElderClick: () -> Unit,
    onElderRemove: () -> Unit,
    onElderSwap: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GameColors.CardBackground)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "执法长老",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(8.dp))

        ElderSlotItem(
            title = "执法长老",
            elder = elder,
            bonusInfo = ElderBonusInfoProvider.lawEnforcementElderInfo,
            onClick = onElderClick,
            onRemove = onElderRemove,
            onSwap = onElderSwap
        )
    }
}

@Composable
private fun LawDisciplesSection(
    lawDisciples: List<DirectDiscipleSlot>,
    disciples: List<DiscipleAggregate>,
    onDiscipleClick: (Int) -> Unit,
    onDiscipleRemove: (Int) -> Unit,
    onDiscipleSwap: (Int) -> Unit = {}
) {
    val discipleMap = disciples.associateBy { it.id }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GameColors.CardBackground)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "执法弟子",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ElderBonusInfoButton(bonusInfo = ElderBonusInfoProvider.lawEnforcementDiscipleInfo)
        }
        Spacer(modifier = Modifier.height(8.dp))

        LawDiscipleSlotRow(
            lawDisciples = lawDisciples,
            discipleMap = discipleMap,
            range = 0..3,
            onDiscipleClick = onDiscipleClick,
            onDiscipleRemove = onDiscipleRemove,
            onDiscipleSwap = onDiscipleSwap
        )

        Spacer(modifier = Modifier.height(6.dp))

        LawDiscipleSlotRow(
            lawDisciples = lawDisciples,
            discipleMap = discipleMap,
            range = 4..7,
            onDiscipleClick = onDiscipleClick,
            onDiscipleRemove = onDiscipleRemove,
            onDiscipleSwap = onDiscipleSwap
        )
    }
}

/** 执法弟子槽位行：给定索引区间渲染 4 个槽位 */
@Composable
private fun LawDiscipleSlotRow(
    lawDisciples: List<DirectDiscipleSlot>,
    discipleMap: Map<String, DiscipleAggregate>,
    range: IntRange,
    onDiscipleClick: (Int) -> Unit,
    onDiscipleRemove: (Int) -> Unit,
    onDiscipleSwap: (Int) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        range.forEach { index ->
            val slot = lawDisciples.find { it.index == index }
            val disciple = if (slot != null && slot.isActive) discipleMap[slot.discipleId] else null
            val spiritRootColor = slot?.discipleSpiritRootColor ?: ""
            LawDiscipleSlotItem(
                disciple = disciple,
                isActive = slot?.isActive == true,
                spiritRootColor = spiritRootColor,
                onClick = { onDiscipleClick(index) },
                onRemove = { onDiscipleRemove(index) },
                onSwap = { onDiscipleSwap(index) }
            )
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
@Composable
private fun ElderSlotItem(
    title: String,
    elder: DiscipleAggregate?,
    bonusInfo: com.xianxia.sect.ui.components.ElderBonusInfo,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onSwap: () -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            ElderBonusInfoButton(bonusInfo = bonusInfo)
        }
        Spacer(modifier = Modifier.height(4.dp))

        val borderColor = if (elder != null) {
            try {
                Color(android.graphics.Color.parseColor(elder.spiritRoot.countColor))
            } catch (ignored: Exception) {
                GameColors.SurfaceLightGray
            }
        } else {
            GameColors.Border
        }

        DiscipleSlot(
            disciple = elder,
            borderColor = borderColor,
            showActions = true,
            onSlotClick = { onClick() },
            onEmptySlotClick = { onSwap() },
            onDismiss = { onRemove() },
            onSwap = { onSwap() }
        )
    }
}

@Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
@Composable
private fun LawDiscipleSlotItem(
    disciple: DiscipleAggregate?,
    isActive: Boolean,
    spiritRootColor: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onSwap: () -> Unit = {}
) {
    val borderColor = if (isActive) {
        try {
            Color(android.graphics.Color.parseColor(spiritRootColor))
        } catch (ignored: Exception) {
            Color(0xFFE74C3C)
        }
    } else {
        GameColors.Border
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "执法弟子",
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(2.dp))

        DiscipleSlot(
            disciple = if (isActive) disciple else null,
            borderColor = borderColor,
            showActions = true,
            onSlotClick = { onClick() },
            onEmptySlotClick = { onSwap() },
            onDismiss = { onRemove() },
            onSwap = { onSwap() }
        )
    }
}
