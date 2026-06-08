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
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.components.LearnedManualDetailDialog
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.TalentDetailDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.R
import com.xianxia.sect.ui.game.components.detail.*
import com.xianxia.sect.ui.theme.GameColors

val LocalDismissDropdown = compositionLocalOf { {} }

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
    onNavigateToDisciple: ((DiscipleAggregate) -> Unit)? = null
) {
    val talents = remember(disciple.talentIds) {
        TalentDatabase.getTalentsByIds(disciple.talentIds)
    }

    var showEquipmentSelection by remember { mutableStateOf<String?>(null) }
    var selectedEquipmentId by remember { mutableStateOf<String?>(null) }
    var showManualSelection by remember { mutableStateOf(false) }
    var selectedManualId by remember { mutableStateOf<String?>(null) }
    var showManualDetailDialog by remember { mutableStateOf<ManualInstance?>(null) }
    var showEquipmentDetailDialog by remember { mutableStateOf<EquipmentInstance?>(null) }

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

    val learnedManuals = remember(disciple.manualIds, allManuals) {
        allManuals.filter { it.id in disciple.manualIds }
    }

    val maxManualSlots = remember(disciple.talentIds) {
        DiscipleStatCalculator.getMaxManualSlots(disciple)
    }

    var showRelationsDialog by remember { mutableStateOf(false) }
    var showStorageBagDialog by remember { mutableStateOf(false) }
    var showExpelConfirmDialog by remember { mutableStateOf(false) }
    var showDiscipleTypeDropdown by remember { mutableStateOf(false) }
    var localDiscipleType by remember(disciple.id) { mutableStateOf(disciple.discipleType) }
    var selectedTalent by remember { mutableStateOf<Talent?>(null) }

    val elderSlots by viewModel?.elderSlots?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val sectPolicies by viewModel?.sectPolicies?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(SectPolicies()) }
    val vmResidenceSlots by viewModel?.residenceSlots?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList<ResidenceSlot>()) }
    val vmPlacedBuildings by viewModel?.placedBuildings?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList<GridBuildingData>()) }

    val currentIndex = allDisciples.indexOfFirst { it.id == disciple.id }
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex >= 0 && currentIndex < allDisciples.size - 1

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("信息", "属性", "装备", "功法")

    key(disciple.id) {
    BackHandler(onBack = onDismiss)
    CompositionLocalProvider(LocalDismissDropdown provides { showDiscipleTypeDropdown = false }) {
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
                                        .clickable { showDiscipleTypeDropdown = false; selectedTab = index },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label, fontSize = 11.sp, color = Color.Black,
                                        fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                                if (index < tabs.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.fillMaxWidth(),
                                        thickness = 1.dp,
                                        color = Color(0xFF757575)
                                    )
                                }
                            }
                        }
                        // Content area
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)
                        ) {
                            when (selectedTab) {
                                0 -> {
                                    BasicInfoSection(
                                        disciple = disciple,
                                        allEquipment = allEquipment,
                                        allManuals = allManuals,
                                        manualProficiencies = manualProficiencies,
                                        position = viewModel?.getDisciplePosition(disciple.id),
                                        isWorkStatusPosition = viewModel?.isPositionWorkStatus(disciple.id) ?: false,
                                        elderSlots = elderSlots,
                                        allDisciples = allDisciples,
                                        sectPolicies = sectPolicies,
                                        residenceSlots = vmResidenceSlots,
                                        placedBuildings = vmPlacedBuildings,
                                        viewModel = viewModel
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    TalentsSection(talents, disciple.statusData, onTalentClick = { selectedTalent = it })
                                }
                                1 -> {
                                    AttributesSection(disciple)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    CombatStatsSection(
                                        disciple = disciple,
                                        weapon = weapon,
                                        armor = armor,
                                        boots = boots,
                                        accessory = accessory,
                                        learnedManuals = learnedManuals,
                                        manualProficiencies = manualProficiencies
                                    )
                                }
                                2 -> EquipmentSection(
                                    weapon = weapon,
                                    armor = armor,
                                    boots = boots,
                                    accessory = accessory,
                                    onSlotClick = { slotType -> showEquipmentSelection = slotType },
                                    onEquipmentClick = { equipment -> showEquipmentDetailDialog = equipment }
                                )
                                3 -> ManualsSection(
                                    manuals = learnedManuals,
                                    maxSlots = maxManualSlots,
                                    manualProficiencies = manualProficiencies,
                                    discipleId = disciple.id,
                                    onSlotClick = { showManualSelection = true },
                                    onManualClick = { manual -> showManualDetailDialog = manual }
                                )
                            }
                        }
                    }
                    // Vertical divider
                    Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color(0xFFBDBDBD)))
                    // Right 40%: Portrait + basic info + action buttons
                    DetailRightPanel(
                        disciple = disciple,
                        allDisciples = allDisciples,
                        localDiscipleType = localDiscipleType,
                        showDiscipleTypeDropdown = showDiscipleTypeDropdown,
                        onDiscipleTypeDropdownChange = { showDiscipleTypeDropdown = it },
                        onLocalDiscipleTypeChange = { localDiscipleType = it },
                        onShowRelations = { showRelationsDialog = true },
                        onShowStorageBag = { showStorageBagDialog = true },
                        onShowExpelConfirm = { showExpelConfirmDialog = true },
                        onNavigateToDisciple = onNavigateToDisciple,
                        viewModel = viewModel
                    )
                }
                // Close button at top-right
                CloseButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                )
            }
        }
    } // CompositionLocalProvider

    if (showRelationsDialog) {
        RelationsDialog(
            disciple = disciple,
            allDisciples = allDisciples,
            onDismiss = { showRelationsDialog = false }
        )
    }

    if (showStorageBagDialog) {
        StorageBagDialog(
            items = disciple.storageBagItems,
            spiritStones = disciple.storageBagSpiritStones,
            disciple = disciple,
            viewModel = viewModel,
            onDismiss = { showStorageBagDialog = false }
        )
    }

    if (showExpelConfirmDialog) {
        StandardPromptDialog(
            onDismissRequest = { showExpelConfirmDialog = false },
            title = "确认驱逐",
            text = "确定要驱逐弟子 ${disciple.name} 吗？此操作不可撤销。",
            confirmLabel = "确认",
            onConfirm = {
                viewModel?.expelDisciple(disciple.id)
                showExpelConfirmDialog = false
                onDismiss()
            },
            dismissLabel = "取消",
            onDismiss = { showExpelConfirmDialog = false }
        )
    }

    showEquipmentSelection?.let { slotType ->
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
            onSelect = { id ->
                selectedEquipmentId = if (selectedEquipmentId == id) null else id
            },
            onConfirm = {
                selectedEquipmentId?.let { id ->
                    viewModel?.equipItem(disciple.id, id)
                }
                showEquipmentSelection = null
                selectedEquipmentId = null
            },
            onDismiss = {
                showEquipmentSelection = null
                selectedEquipmentId = null
            }
        )
    }

    if (showManualSelection) {
        ManualSelectionDialog(
            manualStacks = manualStacks,
            allManuals = allManuals,
            currentManualIds = disciple.manualIds,
            discipleRealm = disciple.realm,
            maxManualSlots = maxManualSlots,
            selectedManualId = selectedManualId,
            onSelect = { id ->
                selectedManualId = if (selectedManualId == id) null else id
            },
            onConfirm = {
                selectedManualId?.let { id ->
                    viewModel?.learnManual(disciple.id, id)
                }
                showManualSelection = false
                selectedManualId = null
            },
            onDismiss = {
                showManualSelection = false
                selectedManualId = null
            }
        )
    }

    selectedTalent?.let { talent ->
        TalentDetailDialog(
            talent = talent,
            onDismiss = { selectedTalent = null }
        )
    }

    showManualDetailDialog?.let { manual ->
        val proficiencyData = manualProficiencies[disciple.id]?.find { it.manualId == manual.id }
        var showManualReplaceSelection by remember { mutableStateOf(false) }

        LearnedManualDetailDialog(
            manual = manual,
            proficiencyData = proficiencyData,
            onForget = {
                viewModel?.forgetManual(disciple.id, manual.id)
                showManualDetailDialog = null
            },
            onDismiss = {
                showManualDetailDialog = null
            },
            extraActions = {
                GameButton(
                    text = "更换",
                    onClick = {
                        showManualReplaceSelection = true
                    }
                )
            }
        )

        if (showManualReplaceSelection) {
            val availableManualStacks = remember(manualStacks, allManuals, disciple.manualIds, manual, disciple.realm) {
                val manualMap = allManuals.associateBy { it.id }
                val otherManualIds = disciple.manualIds.filter { it != manual.id }
                val hasMindManual = otherManualIds.any { mid -> manualMap[mid]?.type == ManualType.MIND }
                val learnedNames = otherManualIds.mapNotNull { mid -> manualMap[mid]?.name }.toSet()
                manualStacks.filter { stack ->
                    !(hasMindManual && stack.type == ManualType.MIND) &&
                    stack.name !in learnedNames &&
                    GameConfig.Realm.meetsRealmRequirement(disciple.realm, stack.minRealm)
                }.sortedByDescending { it.rarity }
            }
            var selectedReplaceManualId by remember { mutableStateOf<String?>(null) }
            var showReplaceDetailStack by remember { mutableStateOf<ManualStack?>(null) }

            ManualReplaceDialog(
                availableManualStacks = availableManualStacks,
                selectedReplaceManualId = selectedReplaceManualId,
                onSelectReplaceManual = { id ->
                    selectedReplaceManualId = if (selectedReplaceManualId == id) null else id
                },
                onViewReplaceDetail = { stack -> showReplaceDetailStack = stack },
                onConfirmReplace = {
                    selectedReplaceManualId?.let { newId ->
                        viewModel?.replaceManual(disciple.id, manual.id, newId)
                    }
                    showManualReplaceSelection = false
                    showManualDetailDialog = null
                },
                onDismissReplace = {
                    showManualReplaceSelection = false
                }
            )

            showReplaceDetailStack?.let { stack ->
                ItemDetailDialog(
                    item = stack,
                    onDismiss = { showReplaceDetailStack = null }
                )
            }
        }
    }

    showEquipmentDetailDialog?.let { equipment ->
        val liveEquipment = allEquipment.find { it.id == equipment.id } ?: equipment
        ItemDetailDialog(
            item = liveEquipment,
            onDismiss = {
                showEquipmentDetailDialog = null
            },
            extraActions = {
                GameButton(
                    text = "卸下",
                    onClick = {
                        viewModel?.unequipItem(disciple.id, equipment.id)
                        showEquipmentDetailDialog = null
                    }
                )
                GameButton(
                    text = "更换",
                    onClick = {
                        showEquipmentDetailDialog = null
                        showEquipmentSelection = equipment.slot.name.lowercase(java.util.Locale.getDefault())
                    }
                )
            }
        )
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
    onSelectReplaceManual: (String) -> Unit,
    onViewReplaceDetail: (ManualStack) -> Unit,
    onConfirmReplace: () -> Unit,
    onDismissReplace: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissReplace,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择新功法",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                CloseButton(onClick = onDismissReplace)
            }
        },
        text = {
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
                    items(availableManualStacks, key = { it.id }) { stack ->
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
                            showViewButton = true,
                            onClick = { onSelectReplaceManual(stack.id) },
                            onViewDetail = { onViewReplaceDetail(stack) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
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
    onNavigateToDisciple: ((DiscipleAggregate) -> Unit)? = null
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
        onNavigateToDisciple = onNavigateToDisciple
    )
}
