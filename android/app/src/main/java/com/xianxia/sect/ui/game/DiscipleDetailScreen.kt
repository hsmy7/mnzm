package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.core.data.TalentDatabase
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.game.components.getBuffTypeName
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.components.EmptyListMessage
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.getRarityColor
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun DiscipleDetailDialog(
    disciple: Disciple,
    allDisciples: List<Disciple> = emptyList(),
    allEquipment: List<Equipment> = emptyList(),
    allManuals: List<Manual> = emptyList(),
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    viewModel: GameViewModel? = null,
    onDismiss: () -> Unit
) {
    val talents = remember(disciple.talentIds) {
        TalentDatabase.getTalentsByIds(disciple.talentIds)
    }
    
    var showEquipmentSelection by remember { mutableStateOf<String?>(null) }
    var selectedEquipmentId by remember { mutableStateOf<String?>(null) }
    var showManualSelection by remember { mutableStateOf(false) }
    var selectedManualId by remember { mutableStateOf<String?>(null) }
    var showManualDetailDialog by remember { mutableStateOf<Manual?>(null) }
    var showEquipmentDetailDialog by remember { mutableStateOf<Equipment?>(null) }

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
        val talentEffects = TalentDatabase.calculateTalentEffects(disciple.talentIds)
        val manualSlotBonus = talentEffects["manualSlot"]?.toInt() ?: 0
        5 + manualSlotBonus
    }

    var showRelationsDialog by remember { mutableStateOf(false) }
    var showStorageBagDialog by remember { mutableStateOf(false) }
    var showExpelConfirmDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF4CAF50))
                            .clickable { showRelationsDialog = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "关系",
                            fontSize = 10.sp,
                            color = Color.White
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF2196F3))
                            .clickable { showStorageBagDialog = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "储物袋",
                            fontSize = 10.sp,
                            color = Color.White
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFE74C3C))
                            .clickable { showExpelConfirmDialog = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "驱逐",
                            fontSize = 10.sp,
                            color = Color.White
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BasicInfoSection(
                    disciple = disciple,
                    allManuals = allManuals,
                    manualProficiencies = manualProficiencies,
                    position = viewModel?.getDisciplePosition(disciple.id),
                    isWorkStatusPosition = viewModel?.isPositionWorkStatus(disciple.id) ?: false
                )
                Divider(color = GameColors.Border, thickness = 1.dp)
                TalentsSection(talents)
                Divider(color = GameColors.Border, thickness = 1.dp)
                AttributesSection(disciple)
                Divider(color = GameColors.Border, thickness = 1.dp)
                CombatStatsSection(
                    disciple = disciple,
                    weapon = weapon,
                    armor = armor,
                    boots = boots,
                    accessory = accessory,
                    learnedManuals = learnedManuals,
                    manualProficiencies = manualProficiencies
                )
                Divider(color = GameColors.Border, thickness = 1.dp)
                EquipmentSection(
                    weapon = weapon,
                    armor = armor,
                    boots = boots,
                    accessory = accessory,
                    onSlotClick = { slotType -> showEquipmentSelection = slotType },
                    onEquipmentClick = { equipment -> showEquipmentDetailDialog = equipment }
                )
                Divider(color = GameColors.Border, thickness = 1.dp)
                ManualsSection(
                    manuals = learnedManuals,
                    maxSlots = maxManualSlots,
                    manualProficiencies = manualProficiencies,
                    discipleId = disciple.id,
                    onSlotClick = { showManualSelection = true },
                    onManualClick = { manual ->
                        showManualDetailDialog = manual
                    }
                )
            }
        },
        confirmButton = {}
    )

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
            gameData = viewModel?.gameData?.value,
            onDismiss = { showStorageBagDialog = false }
        )
    }

    if (showExpelConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExpelConfirmDialog = false },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = "确认驱逐",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = "确定要驱逐弟子 ${disciple.name} 吗？此操作不可撤销。",
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel?.expelDisciple(disciple.id)
                        showExpelConfirmDialog = false
                        onDismiss()
                    }
                ) {
                    Text("确认", color = Color(0xFFE74C3C))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExpelConfirmDialog = false }) {
                    Text("取消", color = Color(0xFF666666))
                }
            }
        )
    }
    
    showEquipmentSelection?.let { slotType ->
        EquipmentSelectionDialog(
            slotType = slotType,
            allEquipment = allEquipment,
            currentEquipmentId = when (slotType) {
                "weapon" -> disciple.weaponId
                "armor" -> disciple.armorId
                "boots" -> disciple.bootsId
                "accessory" -> disciple.accessoryId
                else -> null
            },
            currentDiscipleId = disciple.id,
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
            allManuals = allManuals,
            currentManualIds = disciple.manualIds,
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

    showManualDetailDialog?.let { manual ->
        val proficiencyData = manualProficiencies[disciple.id]?.find { it.manualId == manual.id }
        ManualDetailDialog(
            manual = manual,
            proficiencyData = proficiencyData,
            allManuals = allManuals,
            currentManualIds = disciple.manualIds,
            onReplace = { newManualId ->
                viewModel?.replaceManual(disciple.id, manual.id, newManualId)
                showManualDetailDialog = null
            },
            onForget = {
                viewModel?.forgetManual(disciple.id, manual.id)
                showManualDetailDialog = null
            },
            onDismiss = {
                showManualDetailDialog = null
            }
        )
    }

    showEquipmentDetailDialog?.let { equipment ->
        EquipmentDetailDialog(
            equipment = equipment,
            allEquipment = allEquipment,
            disciple = disciple,
            onUnequip = {
                viewModel?.unequipItem(disciple.id, equipment.id)
                showEquipmentDetailDialog = null
            },
            onReplace = { newEquipmentId ->
                viewModel?.equipItem(disciple.id, newEquipmentId)
                showEquipmentDetailDialog = null
            },
            onDismiss = {
                showEquipmentDetailDialog = null
            }
        )
    }
}

@Composable
private fun RelationsDialog(
    disciple: Disciple,
    allDisciples: List<Disciple>,
    onDismiss: () -> Unit
) {
    val partner = remember(disciple.partnerId, allDisciples) {
        disciple.partnerId?.let { id -> allDisciples.find { it.id == id } }
    }

    val parent1 = remember(disciple.parentId1, allDisciples) {
        disciple.parentId1?.let { id -> allDisciples.find { it.id == id } }
    }

    val parent2 = remember(disciple.parentId2, allDisciples) {
        disciple.parentId2?.let { id -> allDisciples.find { it.id == id } }
    }

    val children = remember(disciple.id, allDisciples) {
        allDisciples.filter { it.parentId1 == disciple.id || it.parentId2 == disciple.id }
    }

    val siblings = remember(disciple.parentId1, disciple.parentId2, allDisciples) {
        if (disciple.parentId1 == null && disciple.parentId2 == null) {
            emptyList()
        } else {
            allDisciples.filter { 
                it.id != disciple.id && 
                (it.parentId1 == disciple.parentId1 || it.parentId2 == disciple.parentId2 ||
                 it.parentId1 == disciple.parentId2 || it.parentId2 == disciple.parentId1)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "关系",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (parent1 != null || parent2 != null) {
                    RelationCategory("父母") {
                        parent1?.let { RelationItem("父亲", it) }
                        parent2?.let { RelationItem("母亲", it) }
                    }
                }

                if (partner != null) {
                    RelationCategory("道侣") {
                        RelationItem("道侣", partner)
                    }
                }

                if (children.isNotEmpty()) {
                    RelationCategory("子嗣") {
                        children.forEach { child ->
                            val relation = if (child.gender == "male") "子" else "女"
                            RelationItem(relation, child)
                        }
                    }
                }

                if (siblings.isNotEmpty()) {
                    RelationCategory("兄弟姐妹") {
                        siblings.forEach { sibling ->
                            val relation = if (sibling.gender == "male") "兄弟" else "姐妹"
                            RelationItem(relation, sibling)
                        }
                    }
                }

                if (parent1 == null && parent2 == null && partner == null && children.isEmpty() && siblings.isEmpty()) {
                    Text(
                        text = "无关系",
                        fontSize = 12.sp,
                        color = Color(0xFF999999)
                    )
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ManualDetailDialog(
    manual: Manual,
    proficiencyData: ManualProficiencyData?,
    allManuals: List<Manual>,
    currentManualIds: List<String>,
    onReplace: (String) -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit
) {
    val rarityColor = when (manual.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }

    val availableManuals = remember(allManuals, currentManualIds) {
        allManuals.filter { it.id !in currentManualIds && !it.isLearned }.sortedByDescending { it.rarity }
    }

    var showReplaceSelection by remember { mutableStateOf(false) }
    var showForgetConfirmDialog by remember { mutableStateOf(false) }
    var selectedManualId by remember { mutableStateOf<String?>(null) }

    val proficiency = proficiencyData?.proficiency ?: 0.0
    val masteryLevel = proficiencyData?.masteryLevel ?: 0
    val mastery = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel)
    val thresholds = ManualProficiencySystem.getProficiencyThresholds(manual.rarity)
    val maxProficiency = ManualProficiencySystem.getMaxProficiency(manual.rarity)

    val currentThreshold = when (mastery) {
        ManualProficiencySystem.MasteryLevel.NOVICE -> 0.0
        ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.NOVICE] ?: 0.0
        ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS] ?: 100.0
        ManualProficiencySystem.MasteryLevel.PERFECTION -> thresholds[ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS] ?: 200.0
    }
    val nextThreshold = when (mastery) {
        ManualProficiencySystem.MasteryLevel.NOVICE -> thresholds[ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS] ?: 100.0
        ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS] ?: 200.0
        ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS -> thresholds[ManualProficiencySystem.MasteryLevel.PERFECTION] ?: 300.0
        ManualProficiencySystem.MasteryLevel.PERFECTION -> maxProficiency
    }

    val progressInCurrentLevel = if (mastery == ManualProficiencySystem.MasteryLevel.PERFECTION) {
        1.0
    } else {
        val denominator = nextThreshold - currentThreshold
        if (denominator > 0) {
            ((proficiency - currentThreshold) / denominator).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    }

    if (showReplaceSelection) {
        AlertDialog(
            onDismissRequest = { showReplaceSelection = false },
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
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { showReplaceSelection = false }
                            .background(GameColors.CardBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (availableManuals.isEmpty()) {
                        Text(
                            text = "暂无可更换的功法",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    } else {
                        availableManuals.forEach { newManual ->
                            val newManualRarityColor = when (newManual.rarity) {
                                1 -> Color(0xFF95A5A6)
                                2 -> Color(0xFF27AE60)
                                3 -> Color(0xFF3498DB)
                                4 -> Color(0xFF9B59B6)
                                5 -> Color(0xFFF39C12)
                                6 -> Color(0xFFE74C3C)
                                else -> Color(0xFF95A5A6)
                            }

                            val isSelected = selectedManualId == newManual.id

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) Color(0xFFE3F2FD) else GameColors.PageBackground)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color(0xFF2196F3) else GameColors.Border,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable { selectedManualId = newManual.id }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = newManual.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = newManualRarityColor
                                        )
                                        Text(
                                            text = getRarityText(newManual.rarity),
                                            fontSize = 10.sp,
                                            color = newManualRarityColor
                                        )
                                    }
                                    Text(
                                        text = "修炼速度+${(newManual.cultivationSpeedPercent).toInt()}%",
                                        fontSize = 10.sp,
                                        color = Color(0xFF666666)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { showReplaceSelection = false }) {
                        Text("取消", color = Color(0xFF666666))
                    }
                    GameButton(
                        text = "确认更换",
                        onClick = {
                            selectedManualId?.let { onReplace(it) }
                        },
                        enabled = selectedManualId != null
                    )
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = GameColors.PageBackground,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = manual.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = rarityColor
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(GameColors.CardBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 450.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${getRarityText(manual.rarity)} · ${manual.type.displayName}",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )

                    Divider(color = GameColors.Border, thickness = 1.dp)

                    Text(
                        text = manual.description,
                        fontSize = 12.sp,
                        color = Color(0xFF333333)
                    )

                    Divider(color = GameColors.Border, thickness = 1.dp)

                    Text(
                        text = "熟练度",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${mastery.displayName}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = rarityColor
                        )
                        Text(
                            text = "${proficiency.toInt()}/${maxProficiency.toInt()}",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFE8E8E8))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = progressInCurrentLevel.toFloat().coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(rarityColor)
                        )
                    }

                    if (mastery != ManualProficiencySystem.MasteryLevel.PERFECTION) {
                        val nextLevelName = when (mastery) {
                            ManualProficiencySystem.MasteryLevel.NOVICE -> "小成"
                            ManualProficiencySystem.MasteryLevel.SMALL_SUCCESS -> "大成"
                            ManualProficiencySystem.MasteryLevel.GREAT_SUCCESS -> "圆满"
                            ManualProficiencySystem.MasteryLevel.PERFECTION -> "已圆满"
                        }
                        Text(
                            text = "距离${nextLevelName}还需 ${(nextThreshold - proficiency).toInt()} 熟练度",
                            fontSize = 10.sp,
                            color = Color(0xFF999999)
                        )
                    } else {
                        Text(
                            text = "已达圆满境界",
                            fontSize = 10.sp,
                            color = Color(0xFFFFD700)
                        )
                    }

                    Divider(color = GameColors.Border, thickness = 1.dp)

                    Text(
                        text = "加成效果",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    val stats = manual.stats
                    if (stats.isNotEmpty()) {
                        stats.forEach { (key, value) ->
                            val statName = when (key) {
                                "cultivationSpeedPercent" -> "修炼速度"
                                "physicalAttack" -> "物理攻击"
                                "magicAttack" -> "法术攻击"
                                "physicalDefense" -> "物理防御"
                                "magicDefense" -> "法术防御"
                                "hp" -> "生命"
                                "mp" -> "灵力"
                                "speed" -> "速度"
                                "critRate" -> "暴击率"
                                else -> key
                            }
                            val bonusMultiplier = mastery.bonus
                            val finalValue = if (key.contains("Percent")) {
                                (value * bonusMultiplier).toInt()
                            } else {
                                (value * bonusMultiplier).toInt()
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = statName,
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666)
                                )
                                Text(
                                    text = if (key.contains("Percent")) "+$finalValue%" else "+$finalValue",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }

                    manual.skill?.let { skill ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "附带技能",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Text(
                            text = skill.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = rarityColor
                        )
                        if (skill.description.isNotEmpty()) {
                            Text(
                                text = skill.description,
                                fontSize = 10.sp,
                                color = Color(0xFF333333)
                            )
                        }
                        if (skill.skillType == com.xianxia.sect.core.engine.SkillType.SUPPORT) {
                            Text(
                                text = "类型：辅助",
                                fontSize = 10.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (skill.healPercent > 0) {
                            val healTypeName = when (skill.healType) {
                                com.xianxia.sect.core.engine.HealType.HP -> "生命"
                                com.xianxia.sect.core.engine.HealType.MP -> "灵力"
                            }
                            Text(
                                text = "治疗：${(skill.healPercent * 100).toInt()}% $healTypeName",
                                fontSize = 10.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (skill.damageMultiplier > 0 && skill.skillType == com.xianxia.sect.core.engine.SkillType.ATTACK) {
                            Text(
                                text = "伤害类型：${if (skill.damageType == com.xianxia.sect.core.engine.DamageType.PHYSICAL) "物理" else "法术"}",
                                fontSize = 10.sp,
                                color = Color(0xFF666666)
                            )
                            Text(
                                text = "伤害倍率：${(skill.damageMultiplier * 100).toInt()}%",
                                fontSize = 10.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        Text(
                            text = "连击次数：${skill.hits}",
                            fontSize = 10.sp,
                            color = Color(0xFF666666)
                        )
                        if (skill.cooldown > 0) {
                            Text(
                                text = "冷却回合：${skill.cooldown}",
                                fontSize = 10.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (skill.mpCost > 0) {
                            Text(
                                text = "灵力消耗：${skill.mpCost}",
                                fontSize = 10.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        skill.buffs.forEach { (buffType, value, duration) ->
                            val buffName = getBuffTypeName(buffType)
                            Text(
                                text = "$buffName：+${(value * 100).toInt()}% (${duration}回合)",
                                fontSize = 10.sp,
                                color = Color(0xFF666666)
                            )
                        }
                        if (skill.buffs.isEmpty() && skill.buffType != null && skill.buffValue > 0) {
                            val buffName = getBuffTypeName(skill.buffType)
                            val durationText = if (skill.buffDuration > 0) " (${skill.buffDuration}回合)" else ""
                            Text(
                                text = "$buffName：+${(skill.buffValue * 100).toInt()}%$durationText",
                                fontSize = 10.sp,
                                color = Color(0xFF666666)
                            )
                        }
                    }

                    if (manual.minRealm < 9) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "需求境界：${com.xianxia.sect.core.GameConfig.Realm.getName(manual.minRealm)}",
                            fontSize = 10.sp,
                            color = Color(0xFF999999)
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { showForgetConfirmDialog = true }) {
                        Text("遗忘", color = Color(0xFFE74C3C))
                    }
                    GameButton(
                        text = "更换",
                        onClick = { showReplaceSelection = true }
                    )
                }
            }
        )
    }

    if (showForgetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showForgetConfirmDialog = false },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = "确认遗忘",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Column {
                    Text(
                        text = "确定要遗忘功法「${manual.name}」吗？",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "当前熟练度：${mastery.displayName} (${proficiency.toInt()}/${maxProficiency.toInt()})",
                        fontSize = 11.sp,
                        color = Color(0xFF999999)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "遗忘后熟练度将无法恢复！",
                        fontSize = 11.sp,
                        color = Color(0xFFE74C3C)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForgetConfirmDialog = false
                        onForget()
                    }
                ) {
                    Text("确认遗忘", color = Color(0xFFE74C3C))
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirmDialog = false }) {
                    Text("取消", color = Color(0xFF666666))
                }
            }
        )
    }
}

@Composable
private fun EquipmentDetailDialog(
    equipment: Equipment,
    allEquipment: List<Equipment>,
    disciple: Disciple,
    onUnequip: () -> Unit,
    onReplace: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val rarityColor = when (equipment.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }

    val availableEquipment = remember(allEquipment, equipment, disciple.id) {
        val slotType = equipment.slot.name.lowercase()
        allEquipment.filter {
            it.slot.name.lowercase() == slotType &&
            it.id != equipment.id &&
            (!it.isEquipped || it.ownerId == disciple.id)
        }.sortedByDescending { it.rarity }
    }

    var showReplaceSelection by remember { mutableStateOf(false) }
    var selectedEquipmentId by remember { mutableStateOf<String?>(null) }

    val maxNurtureLevel = EquipmentNurtureSystem.getMaxNurtureLevel(equipment.rarity)
    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(equipment.nurtureLevel, equipment.rarity)
    val progressPercent = EquipmentNurtureSystem.getNurtureProgressPercent(equipment)

    if (showReplaceSelection) {
        AlertDialog(
            onDismissRequest = { showReplaceSelection = false },
            containerColor = GameColors.PageBackground,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "选择新装备",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { showReplaceSelection = false }
                            .background(GameColors.CardBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (availableEquipment.isEmpty()) {
                        Text(
                            text = "暂无可更换的装备",
                            fontSize = 12.sp,
                            color = Color(0xFF999999)
                        )
                    } else {
                        availableEquipment.forEach { newEquip ->
                            val newEquipRarityColor = when (newEquip.rarity) {
                                1 -> Color(0xFF95A5A6)
                                2 -> Color(0xFF27AE60)
                                3 -> Color(0xFF3498DB)
                                4 -> Color(0xFF9B59B6)
                                5 -> Color(0xFFF39C12)
                                6 -> Color(0xFFE74C3C)
                                else -> Color(0xFF95A5A6)
                            }

                            val isSelected = selectedEquipmentId == newEquip.id

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) Color(0xFFE3F2FD) else GameColors.PageBackground)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color(0xFF2196F3) else GameColors.Border,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable { selectedEquipmentId = newEquip.id }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = newEquip.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = newEquipRarityColor
                                        )
                                        Text(
                                            text = getRarityText(newEquip.rarity),
                                            fontSize = 10.sp,
                                            color = newEquipRarityColor
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        val finalStats = newEquip.getFinalStats()
                                        if (finalStats.physicalAttack > 0) {
                                            Text(
                                                text = "物攻+${finalStats.physicalAttack}",
                                                fontSize = 10.sp,
                                                color = Color(0xFF666666)
                                            )
                                        }
                                        if (finalStats.magicAttack > 0) {
                                            Text(
                                                text = "法攻+${finalStats.magicAttack}",
                                                fontSize = 10.sp,
                                                color = Color(0xFF666666)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { showReplaceSelection = false }) {
                        Text("取消", color = Color(0xFF666666))
                    }
                    GameButton(
                        text = "确认更换",
                        onClick = {
                            selectedEquipmentId?.let { onReplace(it) }
                        },
                        enabled = selectedEquipmentId != null
                    )
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = GameColors.PageBackground,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = equipment.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = rarityColor
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(GameColors.CardBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 450.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${getRarityText(equipment.rarity)} · ${equipment.slot.displayName}",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                    
                    if (equipment.minRealm < 9) {
                        Text(
                            text = "需求境界：${com.xianxia.sect.core.GameConfig.Realm.getName(equipment.minRealm)}",
                            fontSize = 11.sp,
                            color = Color(0xFFE74C3C)
                        )
                    }

                    Divider(color = GameColors.Border, thickness = 1.dp)

                    Text(
                        text = equipment.description,
                        fontSize = 12.sp,
                        color = Color(0xFF333333)
                    )

                    Divider(color = GameColors.Border, thickness = 1.dp)

                    Text(
                        text = "孕养等级",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "孕养等级",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Text(
                            text = "Lv.${equipment.nurtureLevel}/${maxNurtureLevel}",
                            fontSize = 11.sp,
                            color = Color(0xFF666666)
                        )
                    }

                    if (equipment.nurtureLevel < maxNurtureLevel) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFE8E8E8))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction = progressPercent / 100f)
                                    .fillMaxHeight()
                                    .background(rarityColor)
                            )
                        }

                        Text(
                            text = "孕养进度：${equipment.nurtureProgress.toInt()}/${expRequired.toInt()}",
                            fontSize = 10.sp,
                            color = Color(0xFF999999)
                        )
                    } else {
                        Text(
                            text = "已达最高孕养等级",
                            fontSize = 10.sp,
                            color = Color(0xFFFFD700)
                        )
                    }

                    Divider(color = GameColors.Border, thickness = 1.dp)

                    Text(
                        text = "加成效果",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    val finalStats = equipment.getFinalStats()
                    val baseStats = equipment.stats

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (finalStats.physicalAttack > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "物理攻击", fontSize = 11.sp, color = Color(0xFF666666))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (baseStats.physicalAttack != finalStats.physicalAttack) {
                                        Text(
                                            text = "(${baseStats.physicalAttack})",
                                            fontSize = 10.sp,
                                            color = Color(0xFF999999)
                                        )
                                    }
                                    Text(
                                        text = "+${finalStats.physicalAttack}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                        if (finalStats.magicAttack > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "法术攻击", fontSize = 11.sp, color = Color(0xFF666666))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (baseStats.magicAttack != finalStats.magicAttack) {
                                        Text(
                                            text = "(${baseStats.magicAttack})",
                                            fontSize = 10.sp,
                                            color = Color(0xFF999999)
                                        )
                                    }
                                    Text(
                                        text = "+${finalStats.magicAttack}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                        if (finalStats.physicalDefense > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "物理防御", fontSize = 11.sp, color = Color(0xFF666666))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (baseStats.physicalDefense != finalStats.physicalDefense) {
                                        Text(
                                            text = "(${baseStats.physicalDefense})",
                                            fontSize = 10.sp,
                                            color = Color(0xFF999999)
                                        )
                                    }
                                    Text(
                                        text = "+${finalStats.physicalDefense}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                        if (finalStats.magicDefense > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "法术防御", fontSize = 11.sp, color = Color(0xFF666666))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (baseStats.magicDefense != finalStats.magicDefense) {
                                        Text(
                                            text = "(${baseStats.magicDefense})",
                                            fontSize = 10.sp,
                                            color = Color(0xFF999999)
                                        )
                                    }
                                    Text(
                                        text = "+${finalStats.magicDefense}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                        if (finalStats.speed > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "速度", fontSize = 11.sp, color = Color(0xFF666666))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (baseStats.speed != finalStats.speed) {
                                        Text(
                                            text = "(${baseStats.speed})",
                                            fontSize = 10.sp,
                                            color = Color(0xFF999999)
                                        )
                                    }
                                    Text(
                                        text = "+${finalStats.speed}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                        if (finalStats.hp > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "生命", fontSize = 11.sp, color = Color(0xFF666666))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (baseStats.hp != finalStats.hp) {
                                        Text(
                                            text = "(${baseStats.hp})",
                                            fontSize = 10.sp,
                                            color = Color(0xFF999999)
                                        )
                                    }
                                    Text(
                                        text = "+${finalStats.hp}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                        if (finalStats.mp > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "灵力", fontSize = 11.sp, color = Color(0xFF666666))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (baseStats.mp != finalStats.mp) {
                                        Text(
                                            text = "(${baseStats.mp})",
                                            fontSize = 10.sp,
                                            color = Color(0xFF999999)
                                        )
                                    }
                                    Text(
                                        text = "+${finalStats.mp}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                        }
                        if (equipment.critChance > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "暴击率", fontSize = 11.sp, color = Color(0xFF666666))
                                Text(
                                    text = "+${GameUtils.formatPercent(equipment.critChance)}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    }

                    val totalBonus = (equipment.totalMultiplier - 1.0) * 100
                    if (totalBonus > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "总加成：+${totalBonus.toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFD700)
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onUnequip) {
                        Text("卸下", color = Color(0xFFE74C3C))
                    }
                    GameButton(
                        text = "更换",
                        onClick = { showReplaceSelection = true }
                    )
                }
            }
        )
    }
}

@Composable
private fun EquipmentSelectionDialog(
    slotType: String,
    allEquipment: List<Equipment>,
    currentEquipmentId: String?,
    currentDiscipleId: String,
    selectedEquipmentId: String?,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val slotTypeText = when (slotType) {
        "weapon" -> "武器"
        "armor" -> "护甲"
        "boots" -> "靴子"
        "accessory" -> "饰品"
        else -> "装备"
    }
    
    val availableEquipment = remember(allEquipment, slotType, currentEquipmentId, currentDiscipleId) {
        val filtered = allEquipment.filter { 
            it.slot.name.lowercase() == slotType.lowercase() && 
            it.id != currentEquipmentId &&
            (!it.isEquipped || it.ownerId == currentDiscipleId)
        }
        filtered.sortedByDescending { it.rarity }
    }

    var showDetailEquipment by remember { mutableStateOf<Equipment?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择$slotTypeText",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            if (availableEquipment.isEmpty()) {
                Text(
                    text = "暂无可用的$slotTypeText",
                    fontSize = 12.sp,
                    color = Color(0xFF999999)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(availableEquipment) { equipment ->
                        EquipmentSelectionCard(
                            equipment = equipment,
                            isSelected = selectedEquipmentId == equipment.id,
                            onSelect = { 
                                onSelect(if (selectedEquipmentId == equipment.id) "" else equipment.id)
                            },
                            onViewDetail = { showDetailEquipment = equipment }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = Color(0xFF666666))
                }
                GameButton(
                    text = "确认装备",
                    onClick = onConfirm,
                    enabled = selectedEquipmentId != null
                )
            }
        }
    )

    showDetailEquipment?.let { equipment ->
        val rarityColor = when (equipment.rarity) {
            1 -> Color(0xFF95A5A6)
            2 -> Color(0xFF27AE60)
            3 -> Color(0xFF3498DB)
            4 -> Color(0xFF9B59B6)
            5 -> Color(0xFFF39C12)
            6 -> Color(0xFFE74C3C)
            else -> Color(0xFF95A5A6)
        }
        AlertDialog(
            onDismissRequest = { showDetailEquipment = null },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = equipment.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor
                )
            },
            text = {
                Column {
                    Text(
                        text = getRarityText(equipment.rarity),
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                    if (equipment.minRealm < 9) {
                        Text(
                            text = "需求境界：${com.xianxia.sect.core.GameConfig.Realm.getName(equipment.minRealm)}",
                            fontSize = 11.sp,
                            color = Color(0xFFE74C3C)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = GameColors.Border, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = equipment.description,
                        fontSize = 12.sp,
                        color = Color(0xFF333333)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text("部位：${equipment.slot.displayName}", fontSize = 11.sp, color = Color(0xFF666666))
                    if (equipment.nurtureLevel > 0) {
                        Text("孕养等级：Lv.${equipment.nurtureLevel}", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text("属性：", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3498DB))
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    val finalStats = equipment.getFinalStats()
                    if (finalStats.physicalAttack > 0) Text("  物理攻击 +${finalStats.physicalAttack}", fontSize = 11.sp, color = Color(0xFF666666))
                    if (finalStats.magicAttack > 0) Text("  法术攻击 +${finalStats.magicAttack}", fontSize = 11.sp, color = Color(0xFF666666))
                    if (finalStats.physicalDefense > 0) Text("  物理防御 +${finalStats.physicalDefense}", fontSize = 11.sp, color = Color(0xFF666666))
                    if (finalStats.magicDefense > 0) Text("  法术防御 +${finalStats.magicDefense}", fontSize = 11.sp, color = Color(0xFF666666))
                    if (finalStats.speed > 0) Text("  速度 +${finalStats.speed}", fontSize = 11.sp, color = Color(0xFF666666))
                    if (finalStats.hp > 0) Text("  生命 +${finalStats.hp}", fontSize = 11.sp, color = Color(0xFF666666))
                    if (finalStats.mp > 0) Text("  灵力 +${finalStats.mp}", fontSize = 11.sp, color = Color(0xFF666666))
                    if (equipment.critChance > 0) Text("  暴击率 +${GameUtils.formatPercent(equipment.critChance)}", fontSize = 11.sp, color = Color(0xFF666666))
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun EquipmentSelectionCard(
    equipment: Equipment,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onViewDetail: () -> Unit = {}
) {
    val rarityColor = when (equipment.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 2.dp,
                color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (isSelected) Color(0xFFFFF8E1) else GameColors.PageBackground)
            .clickable { onSelect() }
            .padding(8.dp)
    ) {
        Text(
            text = equipment.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 2.dp)
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun ManualSelectionDialog(
    allManuals: List<Manual>,
    currentManualIds: List<String>,
    selectedManualId: String?,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val availableManuals = remember(allManuals, currentManualIds) {
        allManuals.filter { it.id !in currentManualIds && !it.isLearned }.sortedByDescending { it.rarity }
    }

    var showDetailManual by remember { mutableStateOf<Manual?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择功法",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            if (availableManuals.isEmpty()) {
                Text(
                    text = "暂无可学习的功法",
                    fontSize = 12.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(availableManuals, key = { it.id }) { manual ->
                        ManualSelectionCard(
                            manual = manual,
                            isSelected = selectedManualId == manual.id,
                            onSelect = {
                                onSelect(if (selectedManualId == manual.id) "" else manual.id)
                            },
                            onViewDetail = { showDetailManual = manual }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = Color(0xFF666666))
                }
                GameButton(
                    text = "确认学习",
                    onClick = onConfirm,
                    enabled = selectedManualId != null
                )
            }
        }
    )

    showDetailManual?.let { manual ->
        val rarityColor = when (manual.rarity) {
            1 -> Color(0xFF95A5A6)
            2 -> Color(0xFF27AE60)
            3 -> Color(0xFF3498DB)
            4 -> Color(0xFF9B59B6)
            5 -> Color(0xFFF39C12)
            6 -> Color(0xFFE74C3C)
            else -> Color(0xFF95A5A6)
        }
        AlertDialog(
            onDismissRequest = { showDetailManual = null },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = manual.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor
                )
            },
            text = {
                Column {
                    Text(
                        text = getRarityText(manual.rarity),
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = GameColors.Border, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = manual.description,
                        fontSize = 12.sp,
                        color = Color(0xFF333333)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("类型：${manual.type.displayName}", fontSize = 11.sp, color = Color(0xFF666666))
                    if (manual.minRealm < 9) {
                        Text("需求境界：${com.xianxia.sect.core.GameConfig.Realm.getName(manual.minRealm)}", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    val stats = manual.stats
                    if (stats.isNotEmpty()) {
                        Text("属性加成：", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3498DB))
                        Spacer(modifier = Modifier.height(4.dp))
                        stats.forEach { (key, value) ->
                            val statName = when (key) {
                                "cultivationSpeedPercent" -> "修炼速度"
                                "physicalAttack" -> "物理攻击"
                                "magicAttack" -> "法术攻击"
                                "physicalDefense" -> "物理防御"
                                "magicDefense" -> "法术防御"
                                "hp" -> "生命"
                                "mp" -> "灵力"
                                "speed" -> "速度"
                                "critRate" -> "暴击率"
                                else -> key
                            }
                            if (key.contains("Percent")) {
                                Text("  $statName +$value%", fontSize = 11.sp, color = Color(0xFF666666))
                            } else {
                                Text("  $statName +$value", fontSize = 11.sp, color = Color(0xFF666666))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    manual.skill?.let { skill ->
                        Text("技能：${skill.name}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3498DB))
                        if (skill.description.isNotEmpty()) {
                            Text("  ${skill.description}", fontSize = 11.sp, color = Color(0xFF333333))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("  伤害类型：${if (skill.damageType == com.xianxia.sect.core.engine.DamageType.PHYSICAL) "物理" else "法术"}", fontSize = 11.sp, color = Color(0xFF666666))
                        Text("  伤害倍率：${GameUtils.formatPercent(skill.damageMultiplier)}", fontSize = 11.sp, color = Color(0xFF666666))
                        Text("  连击次数：${skill.hits}", fontSize = 11.sp, color = Color(0xFF666666))
                        Text("  冷却回合：${skill.cooldown}", fontSize = 11.sp, color = Color(0xFF666666))
                        Text("  灵力消耗：${skill.mpCost}", fontSize = 11.sp, color = Color(0xFF666666))
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun ManualSelectionCard(
    manual: Manual,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onViewDetail: () -> Unit
) {
    val rarityColor = when (manual.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 2.dp,
                color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (isSelected) Color(0xFFFFF8E1) else GameColors.PageBackground)
            .clickable { onSelect() }
            .padding(8.dp)
    ) {
        Text(
            text = manual.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 2.dp)
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun ManualReplaceDialog(
    currentManual: Manual,
    allManuals: List<Manual>,
    currentManualIds: List<String>,
    onSelect: (String) -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit
) {
    val availableManuals = remember(allManuals, currentManualIds) {
        allManuals.filter { it.id !in currentManualIds && !it.isLearned }.sortedByDescending { it.rarity }
    }
    
    var selectedManualId by remember { mutableStateOf<String?>(null) }
    
    val rarityColor = when (currentManual.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "功法操作",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onDismiss() }
                        .background(GameColors.CardBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "当前功法: ${currentManual.name}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor
                )
                
                Divider(color = GameColors.Border, thickness = 1.dp)
                
                Text(
                    text = "选择新功法进行更换:",
                    fontSize = 12.sp,
                    color = Color.Black
                )
                
                if (availableManuals.isEmpty()) {
                    Text(
                        text = "暂无可更换的功法",
                        fontSize = 12.sp,
                        color = Color(0xFF999999)
                    )
                } else {
                    availableManuals.forEach { manual ->
                        val manualRarityColor = when (manual.rarity) {
                            1 -> Color(0xFF95A5A6)
                            2 -> Color(0xFF27AE60)
                            3 -> Color(0xFF3498DB)
                            4 -> Color(0xFF9B59B6)
                            5 -> Color(0xFFF39C12)
                            6 -> Color(0xFFE74C3C)
                            else -> Color(0xFF95A5A6)
                        }
                        
                        val isSelected = selectedManualId == manual.id
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Color(0xFFE3F2FD) else GameColors.PageBackground)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF2196F3) else GameColors.Border,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { selectedManualId = manual.id }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = manual.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = manualRarityColor
                                    )
                                    Text(
                                        text = getRarityText(manual.rarity),
                                        fontSize = 10.sp,
                                        color = manualRarityColor
                                    )
                                }
                                Text(
                                    text = "修炼速度+${(manual.cultivationSpeedPercent)}%",
                                    fontSize = 10.sp,
                                    color = Color(0xFF666666)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = Color(0xFF666666))
                }
                TextButton(onClick = onForget) {
                    Text("遗忘", color = Color(0xFFE74C3C))
                }
                GameButton(
                    text = "更换",
                    onClick = { 
                        selectedManualId?.let { onSelect(it) }
                    },
                    enabled = selectedManualId != null
                )
            }
        }
    )
}

@Composable
private fun RelationCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        content()
    }
}

@Composable
private fun RelationItem(relation: String, disciple: Disciple) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = relation,
            fontSize = 12.sp,
            color = Color(0xFF666666)
        )
        Text(
            text = disciple.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

@Composable
private fun BasicInfoSection(
    disciple: Disciple,
    allManuals: List<Manual> = emptyList(),
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    position: String? = null,
    isWorkStatusPosition: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "基本信息",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = disciple.genderName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            if (position != null) {
                Text(
                    text = position,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isWorkStatusPosition) Color(0xFFFF9800) else Color(0xFF4CAF50)
                )
            }
            Text(
                text = disciple.status.displayName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            // 根据灵根数量显示不同颜色
            val spiritRootCountColor = try {
                Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
            } catch (e: Exception) {
                Color(0xFF666666)
            }
            Text(
                text = disciple.spiritRootName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = spiritRootCountColor,
                maxLines = 1
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoItem("寿命 ${disciple.age}/${disciple.lifespan}", Modifier.weight(1f))
            val breakthroughChance = disciple.getBreakthroughChance()
            InfoItem("突破率 ${GameUtils.formatPercent(breakthroughChance)}", Modifier.weight(1f))
        }
        
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (disciple.realm != 0) {
                val manualsMap = remember(allManuals) { allManuals.associateBy { it.id } }
                val proficiencyMap = remember(manualProficiencies, disciple.id) {
                    manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
                }
                val cultivationSpeed = remember(disciple, manualsMap, proficiencyMap) {
                    disciple.calculateCultivationSpeedPerSecond(manualsMap, proficiencyMap)
                }
                
                // 境界、每秒修炼值、当前修为/最大修为在同一行显示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.realmName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${String.format("%.1f", cultivationSpeed)}/秒",
                            fontSize = 10.sp,
                            color = Color(0xFF4CAF50)
                        )
                        Text(
                            text = "${disciple.cultivation.toInt()}/${disciple.maxCultivation.toInt()}",
                            fontSize = 10.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFFE8E8E8))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = disciple.cultivationProgress.toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color(0xFF4CAF50))
                    )
                }
            } else {
                // 渡劫期只显示境界名称
                Text(
                    text = disciple.realmName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun InfoItem(value: String, modifier: Modifier = Modifier) {
    Text(
        text = value,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black,
        modifier = modifier
    )
}

@Composable
private fun TalentsSection(talents: List<Talent>) {
    var selectedTalent by remember { mutableStateOf<Talent?>(null) }
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "天赋",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        
        if (talents.isEmpty()) {
            Text(
                text = "无天赋",
                fontSize = 12.sp,
                color = Color(0xFF999999)
            )
        } else {
            talents.chunked(5).forEach { rowTalents ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowTalents.forEach { talent ->
                        val rarityColor = when (talent.rarity) {
                            1 -> Color(0xFF95A5A6)
                            2 -> Color(0xFF27AE60)
                            3 -> Color(0xFF3498DB)
                            4 -> Color(0xFF9B59B6)
                            5 -> Color(0xFFF39C12)
                            6 -> Color(0xFFE74C3C)
                            else -> Color(0xFF95A5A6)
                        }
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, rarityColor, RoundedCornerShape(4.dp))
                                .clickable { selectedTalent = talent }
                                .padding(vertical = 3.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = talent.name,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = rarityColor,
                                maxLines = 1
                            )
                        }
                    }
                    repeat(5 - rowTalents.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
    
    selectedTalent?.let { talent ->
        TalentDetailDialog(
            talent = talent,
            onDismiss = { selectedTalent = null }
        )
    }
}

@Composable
private fun TalentDetailDialog(talent: Talent, onDismiss: () -> Unit) {
    val rarityColor = when (talent.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = talent.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = rarityColor
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "天赋效果",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                
                if (talent.effects.isEmpty()) {
                    Text(
                        text = talent.description,
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                } else {
                    talent.effects.forEach { (key, value) ->
                        val effectText = formatTalentEffectText(key, value)
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "•",
                                fontSize = 12.sp,
                                color = Color(0xFF999999)
                            )
                            Text(
                                text = effectText,
                                fontSize = 12.sp,
                                color = Color(0xFF666666)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Color(0xFF666666))
            }
        }
    )
}

private fun formatTalentEffectText(key: String, value: Any): String {
    val keyName = formatEffectKey(key)
    val doubleValue = value.toString().toDoubleOrNull() ?: 0.0

    if (key == "winBattleRandomAttrPlus") {
        val point = kotlin.math.abs(doubleValue).toInt().coerceAtLeast(1)
        return "$keyName +$point"
    }

    val flatKeys = setOf(
        "manualSlot",
        "comprehensionFlat",
        "intelligenceFlat",
        "teachingFlat",
        "artifactRefiningFlat",
        "pillRefiningFlat",
        "spiritPlantingFlat",
        "charmFlat",
        "loyaltyFlat",
        "moralityFlat"
    )

    val valueText = if (key in flatKeys) {
        kotlin.math.abs(doubleValue).toInt().toString()
    } else {
        val percentValue = kotlin.math.abs(doubleValue) * 100
        if (percentValue % 1 == 0.0) {
            String.format("%d%%", percentValue.toLong())
        } else {
            String.format("%.1f%%", percentValue)
        }
    }

    val sign = if (doubleValue >= 0) "+" else "-"
    return "$keyName $sign$valueText"
}

private fun formatEffectKey(key: String): String {
    return when (key) {
        "cultivationSpeed" -> "修炼速度"
        "breakthroughChance" -> "突破概率"
        "physicalAttack" -> "物攻"
        "magicAttack" -> "法攻"
        "physicalDefense" -> "物防"
        "magicDefense" -> "法防"
        "speed" -> "速度"
        "critRate" -> "暴击率"
        "maxHp" -> "生命上限"
        "maxMp" -> "法力上限"
        "alchemySuccess" -> "炼丹成功率"
        "forgeSuccess" -> "炼器成功率"
        "miningOutput" -> "挖矿产量"
        "herbYield" -> "草药产量"
        "rareDropRate" -> "稀有掉落率"
        "manualLearnSpeed" -> "功法学习速度"
        "lifespan" -> "寿命"
        "partnerChance" -> "结侣概率"
        "manualSlot" -> "功法槽位"
        "comprehensionFlat" -> "悟性"
        "intelligenceFlat" -> "智力"
        "teachingFlat" -> "教导"
        "artifactRefiningFlat" -> "炼器"
        "pillRefiningFlat" -> "炼丹"
        "spiritPlantingFlat" -> "种植"
        "charmFlat" -> "魅力"
        "loyaltyFlat" -> "忠诚"
        "moralityFlat" -> "道德"
        "winBattleRandomAttrPlus" -> "胜利后随机属性成长（无上限）"
        else -> key
    }
}
@Composable
private fun AttributesSection(disciple: Disciple) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "属性",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AttributeItem("悟性", disciple.comprehension, Modifier.weight(1f))
            AttributeItem("智力", disciple.intelligence, Modifier.weight(1f))
            AttributeItem("魅力", disciple.charm, Modifier.weight(1f))
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AttributeItem("忠诚", disciple.loyalty, Modifier.weight(1f))
            AttributeItem("炼器", disciple.artifactRefining, Modifier.weight(1f))
            AttributeItem("炼丹", disciple.pillRefining, Modifier.weight(1f))
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AttributeItem("灵植", disciple.spiritPlanting, Modifier.weight(1f))
            AttributeItem("传道", disciple.teaching, Modifier.weight(1f))
            AttributeItem("道德", disciple.morality, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AttributeItem(name: String, value: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            fontSize = 11.sp,
            color = Color(0xFF999999)
        )
        Text(
            text = value.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

@Composable
private fun CombatStatsSection(
    disciple: Disciple,
    weapon: Equipment?,
    armor: Equipment?,
    boots: Equipment?,
    accessory: Equipment?,
    learnedManuals: List<Manual>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>
) {
    val equipmentMap = remember(weapon, armor, boots, accessory) {
        mutableMapOf<String, Equipment>().apply {
            weapon?.let { put(it.id, it) }
            armor?.let { put(it.id, it) }
            boots?.let { put(it.id, it) }
            accessory?.let { put(it.id, it) }
        }
    }
    
    val manualMap = remember(learnedManuals) {
        learnedManuals.associateBy { it.id }
    }
    
    val discipleProficiencies = remember(disciple.id, manualProficiencies) {
        manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
    }
    
    val finalStats = remember(disciple, equipmentMap, manualMap, discipleProficiencies) {
        disciple.getFinalStats(equipmentMap, manualMap, discipleProficiencies)
    }
    
    val baseStats = remember(disciple) {
        disciple.getBaseStats()
    }
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "战斗属性",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItemWithBonus("气血", baseStats.maxHp, finalStats.maxHp, Modifier.weight(1f))
            StatItemWithBonus("灵力", baseStats.maxMp, finalStats.maxMp, Modifier.weight(1f))
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItemWithBonus("物攻", baseStats.physicalAttack, finalStats.physicalAttack, Modifier.weight(1f))
            StatItemWithBonus("法攻", baseStats.magicAttack, finalStats.magicAttack, Modifier.weight(1f))
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItemWithBonus("物防", baseStats.physicalDefense, finalStats.physicalDefense, Modifier.weight(1f))
            StatItemWithBonus("法防", baseStats.magicDefense, finalStats.magicDefense, Modifier.weight(1f))
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatItemWithBonus("速度", baseStats.speed, finalStats.speed, Modifier.weight(1f))
            StatItem("神魂", disciple.soulPower, Modifier.weight(1f))
        }
    }
}

@Composable
private fun EquipmentSection(
    weapon: Equipment?,
    armor: Equipment?,
    boots: Equipment?,
    accessory: Equipment?,
    onSlotClick: (String) -> Unit,
    onEquipmentClick: (Equipment) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "装备",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EquipmentSlot("武器", weapon, Modifier.weight(1f), onSlotClick, onEquipmentClick, "weapon")
            EquipmentSlot("护甲", armor, Modifier.weight(1f), onSlotClick, onEquipmentClick, "armor")
            EquipmentSlot("靴子", boots, Modifier.weight(1f), onSlotClick, onEquipmentClick, "boots")
            EquipmentSlot("饰品", accessory, Modifier.weight(1f), onSlotClick, onEquipmentClick, "accessory")
        }
    }
}

@Composable
private fun EquipmentSlot(
    slotName: String, 
    equipment: Equipment?, 
    modifier: Modifier = Modifier,
    onSlotClick: (String) -> Unit,
    onEquipmentClick: (Equipment) -> Unit,
    slotType: String
) {
    val rarityColor = remember(equipment?.rarity) {
        when (equipment?.rarity) {
            1 -> Color(0xFF95A5A6)
            2 -> Color(0xFF27AE60)
            3 -> Color(0xFF3498DB)
            4 -> Color(0xFF9B59B6)
            5 -> Color(0xFFF39C12)
            6 -> Color(0xFFE74C3C)
            else -> GameColors.Border
        }
    }
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = slotName,
            fontSize = 10.sp,
            color = Color(0xFF666666)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, rarityColor, RoundedCornerShape(8.dp))
                .clickable { 
                    if (equipment != null) {
                        onEquipmentClick(equipment)
                    } else {
                        onSlotClick(slotType)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (equipment != null) {
                Text(
                    text = equipment.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor,
                    maxLines = 2
                )
            } else {
                Text(
                    text = "+",
                    fontSize = 24.sp,
                    color = Color(0xFF999999)
                )
            }
        }
    }
}

@Composable
private fun ManualsSection(
    manuals: List<Manual>,
    maxSlots: Int,
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    discipleId: String = "",
    onSlotClick: () -> Unit,
    onManualClick: (Manual) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "功法",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        
        val manualSlots = mutableListOf<Manual?>()
        manuals.take(maxSlots).forEach { manualSlots.add(it) }
        while (manualSlots.size < maxSlots) manualSlots.add(null)
        
        val proficiencyMap = remember(manualProficiencies, discipleId) {
            manualProficiencies[discipleId]?.associateBy { it.manualId } ?: emptyMap()
        }
        
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            manualSlots.chunked(4).forEachIndexed { rowIndex, rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowSlots.forEachIndexed { slotIndex, manual ->
                        val proficiencyData = manual?.id?.let { proficiencyMap[it] }
                        key(manual?.id ?: "empty_${rowIndex}_$slotIndex") {
                            ManualSlot(
                                manual = manual,
                                proficiencyData = proficiencyData,
                                modifier = Modifier.weight(1f),
                                onSlotClick = onSlotClick,
                                onManualClick = onManualClick
                            )
                        }
                    }
                    repeat(4 - rowSlots.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualSlot(
    manual: Manual?,
    proficiencyData: ManualProficiencyData? = null,
    modifier: Modifier = Modifier,
    onSlotClick: () -> Unit,
    onManualClick: (Manual) -> Unit
) {
    val rarityColor = remember(manual?.rarity) {
        when (manual?.rarity) {
            1 -> Color(0xFF95A5A6)
            2 -> Color(0xFF27AE60)
            3 -> Color(0xFF3498DB)
            4 -> Color(0xFF9B59B6)
            5 -> Color(0xFFF39C12)
            6 -> Color(0xFFE74C3C)
            else -> GameColors.Border
        }
    }
    
    val masteryLevel = proficiencyData?.masteryLevel ?: 0
    val mastery = ManualProficiencySystem.MasteryLevel.fromLevel(masteryLevel)
    val masteryText = mastery.displayName
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, rarityColor, RoundedCornerShape(8.dp))
                .clickable {
                    if (manual != null) {
                        onManualClick(manual)
                    } else {
                        onSlotClick()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (manual != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = manual.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = rarityColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (proficiencyData != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = masteryText,
                            fontSize = 8.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    color = Color(0xFF999999)
                )
            }
        }
    }
}

@Composable
private fun StatItem(name: String, value: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            fontSize = 11.sp,
            color = Color(0xFF666666)
        )
        Text(
            text = value.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

@Composable
private fun StatItemWithBonus(name: String, baseValue: Int, finalValue: Int, modifier: Modifier = Modifier) {
    val bonus = finalValue - baseValue
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            fontSize = 11.sp,
            color = Color(0xFF666666)
        )
        Text(
            text = finalValue.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        if (bonus > 0) {
            Text(
                text = "(+$bonus)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF27AE60)
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color(0xFF666666)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

private fun getRarityText(rarity: Int): String {
    return when (rarity) {
        1 -> "普通"
        2 -> "优秀"
        3 -> "稀有"
        4 -> "史诗"
        5 -> "传说"
        else -> "普通"
    }
}

@Composable
private fun StorageBagItemCard(
    item: StorageBagItem,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onViewDetail: () -> Unit = {}
) {
    val rarityColor = when (item.rarity) {
        1 -> Color(0xFF95A5A6)
        2 -> Color(0xFF27AE60)
        3 -> Color(0xFF3498DB)
        4 -> Color(0xFF9B59B6)
        5 -> Color(0xFFF39C12)
        6 -> Color(0xFFE74C3C)
        else -> Color(0xFF95A5A6)
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 2.dp,
                color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                shape = RoundedCornerShape(6.dp)
            )
            .background(if (isSelected) Color(0xFFFFF8E1) else GameColors.PageBackground)
            .clickable { onSelect() }
            .padding(4.dp)
    ) {
        Text(
            text = item.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = rarityColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 2.dp)
        )

        if (item.quantity > 1) {
            Text(
                text = "x${item.quantity}",
                fontSize = 8.sp,
                color = Color(0xFF666666),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun StorageBagDialog(
    items: List<StorageBagItem>,
    spiritStones: Long,
    disciple: Disciple,
    viewModel: GameViewModel?,
    gameData: GameData?,
    onDismiss: () -> Unit
) {
    var showRewardDialog by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<StorageBagItem?>(null) }
    var showDetailDialog by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "储物袋",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFF9800))
                            .clickable { showRewardDialog = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "赏赐",
                            fontSize = 10.sp,
                            color = Color.White
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "灵石:$spiritStones",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2196F3)
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(GameColors.CardBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = "储物袋为空",
                        fontSize = 12.sp,
                        color = Color(0xFF999999)
                    )
                } else {
                    Text(
                        text = "共 ${items.size} 种物品",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(items) { index, item ->
                            StorageBagItemCard(
                                item = item,
                                isSelected = selectedItem?.itemId == item.itemId,
                                onSelect = {
                                    selectedItem = if (selectedItem?.itemId == item.itemId) null else item
                                },
                                onViewDetail = {
                                    showDetailDialog = true
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )

    if (showDetailDialog) {
        selectedItem?.let { item ->
            StorageBagItemDetailDialog(
                item = item,
                onDismiss = { showDetailDialog = false }
            )
        }
    }

    if (showRewardDialog && viewModel != null && gameData != null) {
        RewardItemsDialog(
            disciple = disciple,
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { showRewardDialog = false }
        )
    }
}

@Composable
private fun StorageBagItemDetailDialog(
    item: StorageBagItem,
    onDismiss: () -> Unit
) {
    val rarityColor = getRarityColor(item.rarity)
    
    val effects = buildList {
        add("类型：${item.itemType}")
        add("数量：${item.quantity}")
        add("获得时间：第${item.obtainedYear}年${item.obtainedMonth}月")
        
        item.effect?.let { effect ->
            add("")
            add("效果:")
            if (effect.cultivationSpeed > 1.0) add("  修炼速度 x${effect.cultivationSpeed}")
            if (effect.cultivationPercent > 0) add("  修为 +${GameUtils.formatPercent(effect.cultivationPercent)}")
            if (effect.breakthroughChance > 0) add("  突破概率 +${GameUtils.formatPercent(effect.breakthroughChance)}")
            if (effect.heal > 0) add("  恢复生命 ${effect.heal}")
            if (effect.healPercent > 0) add("  恢复生命 ${GameUtils.formatPercent(effect.healPercent)}")
            if (effect.hpPercent > 0) add("  生命 +${GameUtils.formatPercent(effect.hpPercent)}")
            if (effect.mpPercent > 0) add("  灵力 +${GameUtils.formatPercent(effect.mpPercent)}")
            if (effect.mpRecoverPercent > 0) add("  恢复灵力 ${GameUtils.formatPercent(effect.mpRecoverPercent)}")
            if (effect.extendLife > 0) add("  延寿 ${effect.extendLife} 年")
            if (effect.battleCount > 0) add("  持续 ${effect.battleCount} 场战斗")
            if (effect.physicalAttackPercent > 0) add("  物理攻击 +${GameUtils.formatPercent(effect.physicalAttackPercent)}")
            if (effect.magicAttackPercent > 0) add("  法术攻击 +${GameUtils.formatPercent(effect.magicAttackPercent)}")
            if (effect.physicalDefensePercent > 0) add("  物理防御 +${GameUtils.formatPercent(effect.physicalDefensePercent)}")
            if (effect.magicDefensePercent > 0) add("  法术防御 +${GameUtils.formatPercent(effect.magicDefensePercent)}")
            if (effect.speedPercent > 0) add("  速度 +${GameUtils.formatPercent(effect.speedPercent)}")
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.rarityName,
                    fontSize = 10.sp,
                    color = rarityColor
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                effects.forEach { effect ->
                    Text(
                        text = effect,
                        fontSize = 11.sp,
                        color = Color.Black,
                        lineHeight = 16.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = GameColors.TextSecondary)
            }
        }
    )
}

private enum class RewardFilter(val displayName: String) {
    ALL("全部"),
    EQUIPMENT("装备"),
    PILL("丹药"),
    MANUAL("功法"),
    HERB("草药"),
    SEED("种子"),
    MATERIAL("材料")
}

@Composable
private fun RewardItemsDialog(
    disciple: Disciple,
    gameData: GameData,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf(RewardFilter.ALL) }
    var selectedItem by remember { mutableStateOf<RewardSelectedItem?>(null) }
    var rewardQuantity by remember { mutableStateOf(1) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<Any?>(null) }
    var isRewarding by remember { mutableStateOf(false) }

    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()

    // 过滤掉已学习的功法（已学习的功法不可再次赏赐）
    val availableManuals = manuals.filter { !it.isLearned }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                RewardHeader(
                    discipleName = disciple.name,
                    onDismiss = onDismiss
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GameColors.PageBackground)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RewardFilterButton(
                            text = RewardFilter.ALL.displayName,
                            selected = selectedFilter == RewardFilter.ALL,
                            onClick = { selectedFilter = RewardFilter.ALL },
                            modifier = Modifier.weight(1f)
                        )
                        RewardFilterButton(
                            text = RewardFilter.EQUIPMENT.displayName,
                            selected = selectedFilter == RewardFilter.EQUIPMENT,
                            onClick = { selectedFilter = RewardFilter.EQUIPMENT },
                            modifier = Modifier.weight(1f)
                        )
                        RewardFilterButton(
                            text = RewardFilter.PILL.displayName,
                            selected = selectedFilter == RewardFilter.PILL,
                            onClick = { selectedFilter = RewardFilter.PILL },
                            modifier = Modifier.weight(1f)
                        )
                        RewardFilterButton(
                            text = RewardFilter.MANUAL.displayName,
                            selected = selectedFilter == RewardFilter.MANUAL,
                            onClick = { selectedFilter = RewardFilter.MANUAL },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RewardFilterButton(
                            text = RewardFilter.HERB.displayName,
                            selected = selectedFilter == RewardFilter.HERB,
                            onClick = { selectedFilter = RewardFilter.HERB },
                            modifier = Modifier.weight(1f)
                        )
                        RewardFilterButton(
                            text = RewardFilter.SEED.displayName,
                            selected = selectedFilter == RewardFilter.SEED,
                            onClick = { selectedFilter = RewardFilter.SEED },
                            modifier = Modifier.weight(1f)
                        )
                        RewardFilterButton(
                            text = RewardFilter.MATERIAL.displayName,
                            selected = selectedFilter == RewardFilter.MATERIAL,
                            onClick = { selectedFilter = RewardFilter.MATERIAL },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(GameColors.CardBackground)
                ) {
                    when (selectedFilter) {
                        RewardFilter.ALL -> RewardAllItemsGrid(
                            equipment = equipment,
                            manuals = availableManuals,
                            pills = pills,
                            materials = materials,
                            herbs = herbs,
                            seeds = seeds,
                            selectedItem = selectedItem,
                            onItemSelect = { item ->
                                selectedItem = if (selectedItem?.id == item.id) null else item
                                rewardQuantity = 1
                            },
                            onViewDetail = { item ->
                                detailItem = item
                                showDetailDialog = true
                            }
                        )
                        RewardFilter.EQUIPMENT -> RewardItemGrid(
                            items = equipment,
                            selectedItem = selectedItem,
                            onItemSelect = { item ->
                                selectedItem = if (selectedItem?.id == item.id) null else item
                                rewardQuantity = 1
                            },
                            onViewDetail = { item ->
                                detailItem = item
                                showDetailDialog = true
                            }
                        )
                        RewardFilter.PILL -> RewardItemGrid(
                            items = pills,
                            selectedItem = selectedItem,
                            onItemSelect = { item ->
                                selectedItem = if (selectedItem?.id == item.id) null else item
                                rewardQuantity = 1
                            },
                            onViewDetail = { item ->
                                detailItem = item
                                showDetailDialog = true
                            }
                        )
                        RewardFilter.MANUAL -> RewardItemGrid(
                            items = availableManuals,
                            selectedItem = selectedItem,
                            onItemSelect = { item ->
                                selectedItem = if (selectedItem?.id == item.id) null else item
                                rewardQuantity = 1
                            },
                            onViewDetail = { item ->
                                detailItem = item
                                showDetailDialog = true
                            }
                        )
                        RewardFilter.HERB -> RewardItemGrid(
                            items = herbs,
                            selectedItem = selectedItem,
                            onItemSelect = { item ->
                                selectedItem = if (selectedItem?.id == item.id) null else item
                                rewardQuantity = 1
                            },
                            onViewDetail = { item ->
                                detailItem = item
                                showDetailDialog = true
                            }
                        )
                        RewardFilter.SEED -> RewardItemGrid(
                            items = seeds,
                            selectedItem = selectedItem,
                            onItemSelect = { item ->
                                selectedItem = if (selectedItem?.id == item.id) null else item
                                rewardQuantity = 1
                            },
                            onViewDetail = { item ->
                                detailItem = item
                                showDetailDialog = true
                            }
                        )
                        RewardFilter.MATERIAL -> RewardItemGrid(
                            items = materials,
                            selectedItem = selectedItem,
                            onItemSelect = { item ->
                                selectedItem = if (selectedItem?.id == item.id) null else item
                                rewardQuantity = 1
                            },
                            onViewDetail = { item ->
                                detailItem = item
                                showDetailDialog = true
                            }
                        )
                    }
                }

                RewardBottomPanel(
                    selectedItem = selectedItem,
                    rewardQuantity = rewardQuantity,
                    maxQuantity = selectedItem?.quantity ?: 1,
                    isRewarding = isRewarding,
                    onQuantityChange = { rewardQuantity = it },
                    onRewardClick = {
                        val item = selectedItem
                        if (item != null && rewardQuantity > 0 && !isRewarding) {
                            isRewarding = true
                            viewModel.rewardItemsToDisciple(disciple.id, listOf(item.copy(quantity = rewardQuantity)))
                            selectedItem = null
                            rewardQuantity = 1
                            isRewarding = false
                        }
                    }
                )
            }
        }
    }

    if (showDetailDialog) {
        detailItem?.let { item ->
            RewardItemDetailDialog(
                item = item,
                onDismiss = { showDetailDialog = false }
            )
        }
    }
}

@Composable
private fun RewardHeader(
    discipleName: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "赏赐道具",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Text(
                text = "给予弟子: $discipleName",
                fontSize = 11.sp,
                color = GameColors.TextSecondary
            )
        }

        TextButton(onClick = onDismiss) {
            Text("关闭", color = GameColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun <T> RewardItemGrid(
    items: List<T>,
    selectedItem: RewardSelectedItem?,
    onItemSelect: (RewardSelectedItem) -> Unit,
    onViewDetail: (Any) -> Unit = {}
) {
    if (items.isEmpty()) {
        EmptyListMessage("暂无道具")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = items,
                key = { item ->
                    when (item) {
                        is Equipment -> "equipment_${item.id}"
                        is Manual -> "manual_${item.id}"
                        is Pill -> "pill_${item.id}_${item.quantity}"
                        is Material -> "material_${item.id}_${item.quantity}"
                        is Herb -> "herb_${item.id}_${item.quantity}"
                        is Seed -> "seed_${item.id}_${item.quantity}"
                        else -> item.hashCode().toString()
                    }
                }
            ) { item ->
                val currentSelectedItem = remember(item) {
                    when (item) {
                        is Equipment -> RewardSelectedItem(item.id, "equipment", item.name, item.rarity, 1)
                        is Manual -> RewardSelectedItem(item.id, "manual", item.name, item.rarity, 1)
                        is Pill -> RewardSelectedItem(item.id, "pill", item.name, item.rarity, item.quantity)
                        is Material -> RewardSelectedItem(item.id, "material", item.name, item.rarity, item.quantity)
                        is Herb -> RewardSelectedItem(item.id, "herb", item.name, item.rarity, item.quantity)
                        is Seed -> RewardSelectedItem(item.id, "seed", item.name, item.rarity, item.quantity)
                        else -> null
                    }
                }

                if (currentSelectedItem != null) {
                    val isSelected = selectedItem?.id == currentSelectedItem.id
                    RewardItemCard(
                        name = currentSelectedItem.name,
                        rarity = currentSelectedItem.rarity,
                        quantity = currentSelectedItem.quantity,
                        isSelected = isSelected,
                        onClick = { onItemSelect(currentSelectedItem) },
                        onViewDetail = { onViewDetail(item as Any) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RewardAllItemsGrid(
    equipment: List<Equipment>,
    manuals: List<Manual>,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    seeds: List<Seed>,
    selectedItem: RewardSelectedItem?,
    onItemSelect: (RewardSelectedItem) -> Unit,
    onViewDetail: (Any) -> Unit = {}
) {
    val allItems = equipment + manuals + pills + materials + herbs + seeds

    if (allItems.isEmpty()) {
        EmptyListMessage("暂无道具")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = allItems,
                key = { item ->
                    when (item) {
                        is Equipment -> "equipment_${item.id}"
                        is Manual -> "manual_${item.id}"
                        is Pill -> "pill_${item.id}_${item.quantity}"
                        is Material -> "material_${item.id}_${item.quantity}"
                        is Herb -> "herb_${item.id}_${item.quantity}"
                        is Seed -> "seed_${item.id}_${item.quantity}"
                        else -> item.hashCode().toString()
                    }
                }
            ) { item ->
                val currentSelectedItem = remember(item) {
                    when (item) {
                        is Equipment -> RewardSelectedItem(item.id, "equipment", item.name, item.rarity, 1)
                        is Manual -> RewardSelectedItem(item.id, "manual", item.name, item.rarity, 1)
                        is Pill -> RewardSelectedItem(item.id, "pill", item.name, item.rarity, item.quantity)
                        is Material -> RewardSelectedItem(item.id, "material", item.name, item.rarity, item.quantity)
                        is Herb -> RewardSelectedItem(item.id, "herb", item.name, item.rarity, item.quantity)
                        is Seed -> RewardSelectedItem(item.id, "seed", item.name, item.rarity, item.quantity)
                        else -> null
                    }
                }

                if (currentSelectedItem != null) {
                    val isSelected = selectedItem?.id == currentSelectedItem.id
                    RewardItemCard(
                        name = currentSelectedItem.name,
                        rarity = currentSelectedItem.rarity,
                        quantity = currentSelectedItem.quantity,
                        isSelected = isSelected,
                        onClick = { onItemSelect(currentSelectedItem) },
                        onViewDetail = { onViewDetail(item as Any) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RewardFilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color.Black else GameColors.ButtonBackground)
            .border(1.dp, if (selected) Color.Black else GameColors.ButtonBorder, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Color.Black
        )
    }
}

@Composable
private fun RewardItemCard(
    name: String,
    rarity: Int,
    quantity: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onViewDetail: () -> Unit = {}
) {
    val rarityColor = getRarityColor(rarity)
    
    Box(
        modifier = Modifier.size(68.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(
                    width = if (isSelected) 3.dp else 2.dp,
                    color = if (isSelected) Color(0xFFFFD700) else rarityColor,
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable(onClick = onClick)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = GameColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            
            if (quantity > 1) {
                Text(
                    text = "x$quantity",
                    fontSize = 9.sp,
                    color = GameColors.TextSecondary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFFD700))
                    .clickable { onViewDetail() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "查看",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun RewardBottomPanel(
    selectedItem: RewardSelectedItem?,
    rewardQuantity: Int,
    maxQuantity: Int,
    isRewarding: Boolean = false,
    onQuantityChange: (Int) -> Unit,
    onRewardClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedItem != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (rewardQuantity > 1 && !isRewarding) Color(0xFF4CAF50) else GameColors.Border)
                            .clickable(enabled = rewardQuantity > 1 && !isRewarding) { onQuantityChange(rewardQuantity - 1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "-",
                            fontSize = 16.sp,
                            color = if (rewardQuantity > 1 && !isRewarding) Color.White else Color(0xFF999999)
                        )
                    }
                    
                    Text(
                        text = "$rewardQuantity",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRewarding) GameColors.TextSecondary else Color.Black
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (rewardQuantity < maxQuantity && !isRewarding) Color(0xFF4CAF50) else GameColors.Border)
                            .clickable(enabled = rewardQuantity < maxQuantity && !isRewarding) { onQuantityChange(rewardQuantity + 1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+",
                            fontSize = 16.sp,
                            color = if (rewardQuantity < maxQuantity && !isRewarding) Color.White else Color(0xFF999999)
                        )
                    }
                    
                    Text(
                        text = "/ $maxQuantity",
                        fontSize = 12.sp,
                        color = GameColors.TextSecondary
                    )
                } else {
                    Text(
                        text = "请选择要赏赐的道具",
                        fontSize = 12.sp,
                        color = GameColors.TextSecondary
                    )
                }
            }
            
            GameButton(
                text = if (isRewarding) "赏赐中..." else "赏赐",
                onClick = onRewardClick,
                modifier = Modifier.height(36.dp),
                enabled = selectedItem != null && rewardQuantity > 0 && !isRewarding
            )
        }
    }
}

@Composable
private fun RewardItemDetailDialog(
    item: Any,
    onDismiss: () -> Unit
) {
    val name: String
    val rarity: Int
    val description: String
    val effects: List<String>
    
    when (item) {
        is Equipment -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("部位：${item.slot.displayName}")
                if (item.nurtureLevel > 0) {
                    add("孕养等级：Lv.${item.nurtureLevel}")
                }
                add("")
                add("属性:")
                val finalStats = item.getFinalStats()
                if (finalStats.physicalAttack > 0) add("  物理攻击 +${finalStats.physicalAttack}")
                if (finalStats.magicAttack > 0) add("  法术攻击 +${finalStats.magicAttack}")
                if (finalStats.physicalDefense > 0) add("  物理防御 +${finalStats.physicalDefense}")
                if (finalStats.magicDefense > 0) add("  法术防御 +${finalStats.magicDefense}")
                if (finalStats.speed > 0) add("  速度 +${finalStats.speed}")
                if (finalStats.hp > 0) add("  生命 +${finalStats.hp}")
                if (finalStats.mp > 0) add("  灵力 +${finalStats.mp}")
                if (item.critChance > 0) add("  暴击率 +${GameUtils.formatPercent(item.critChance)}")
            }
        }
        is Manual -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型：${item.type.displayName}")
                if (item.minRealm < 9) {
                    add("需求境界：${com.xianxia.sect.core.GameConfig.Realm.getName(item.minRealm)}")
                }
                add("")
                val stats = item.stats
                if (stats.isNotEmpty()) {
                    add("属性加成:")
                    stats.forEach { (key, value) ->
                        val statName = when (key) {
                            "cultivationSpeedPercent" -> "修炼速度"
                            "physicalAttack" -> "物理攻击"
                            "magicAttack" -> "法术攻击"
                            "physicalDefense" -> "物理防御"
                            "magicDefense" -> "法术防御"
                            "hp" -> "生命"
                            "mp" -> "灵力"
                            "speed" -> "速度"
                            "critRate" -> "暴击率"
                            else -> key
                        }
                        if (key.contains("Percent")) {
                            add("  $statName +$value%")
                        } else {
                            add("  $statName +$value")
                        }
                    }
                }
                item.skill?.let { skill ->
                    add("")
                    add("技能：${skill.name}")
                    if (skill.description.isNotEmpty()) {
                        add("  ${skill.description}")
                    }
                    add("  伤害类型：${if (skill.damageType == com.xianxia.sect.core.engine.DamageType.PHYSICAL) "物理" else "法术"}")
                    add("  伤害倍率：${GameUtils.formatPercent(skill.damageMultiplier)}")
                    add("  连击次数：${skill.hits}")
                    add("  冷却回合：${skill.cooldown}")
                    add("  灵力消耗：${skill.mpCost}")
                }
            }
        }
        is Pill -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型：${item.category.displayName}")
                add("数量：${item.quantity}")
                add("")
                add("效果:")
                when (item.category) {
                    com.xianxia.sect.core.model.PillCategory.BREAKTHROUGH -> {
                        if (item.breakthroughChance > 0) {
                            add("  突破概率 +${GameUtils.formatPercent(item.breakthroughChance)}")
                        }
                        if (item.targetRealm > 0) {
                            add("  目标境界：${com.xianxia.sect.core.GameConfig.Realm.getName(item.targetRealm)}")
                        }
                        if (item.isAscension) {
                            add("  可用于渡劫")
                        }
                    }
                    com.xianxia.sect.core.model.PillCategory.CULTIVATION -> {
                        if (item.cultivationPercent > 0) {
                            add("  修为 +${GameUtils.formatPercent(item.cultivationPercent)}")
                        }
                        if (item.cultivationSpeed > 1.0) {
                            add("  修炼速度 x${item.cultivationSpeed}")
                        }
                        if (item.skillExpPercent > 0) {
                            add("  功法熟练度 +${GameUtils.formatPercent(item.skillExpPercent)}")
                        }
                        if (item.extendLife > 0) {
                            add("  延寿 ${item.extendLife} 年")
                        }
                    }
                    com.xianxia.sect.core.model.PillCategory.BATTLE_PHYSICAL, com.xianxia.sect.core.model.PillCategory.BATTLE_MAGIC, com.xianxia.sect.core.model.PillCategory.BATTLE_STATUS -> {
                        if (item.physicalAttackPercent > 0) add("  物理攻击 +${GameUtils.formatPercent(item.physicalAttackPercent)}")
                        if (item.magicAttackPercent > 0) add("  法术攻击 +${GameUtils.formatPercent(item.magicAttackPercent)}")
                        if (item.physicalDefensePercent > 0) add("  物理防御 +${GameUtils.formatPercent(item.physicalDefensePercent)}")
                        if (item.magicDefensePercent > 0) add("  法术防御 +${GameUtils.formatPercent(item.magicDefensePercent)}")
                        if (item.hpPercent > 0) add("  生命 +${GameUtils.formatPercent(item.hpPercent)}")
                        if (item.mpPercent > 0) add("  灵力 +${GameUtils.formatPercent(item.mpPercent)}")
                        if (item.speedPercent > 0) add("  速度 +${GameUtils.formatPercent(item.speedPercent)}")
                        if (item.battleCount > 0) add("  持续 ${item.battleCount} 场战斗")
                    }
                    com.xianxia.sect.core.model.PillCategory.HEALING -> {
                        if (item.heal > 0) add("  恢复生命 ${item.heal}")
                        if (item.healPercent > 0) add("  恢复生命 ${GameUtils.formatPercent(item.healPercent)}")
                        if (item.healMaxHpPercent > 0) add("  恢复生命 ${GameUtils.formatPercent(item.healMaxHpPercent)} 最大生命")
                        if (item.mpRecoverMaxMpPercent > 0) add("  恢复灵力 ${GameUtils.formatPercent(item.mpRecoverMaxMpPercent)} 最大灵力")
                        if (item.revive) add("  可复活弟子")
                        if (item.clearAll) add("  清除所有负面状态")
                    }
                }
                if (item.duration > 0 && !item.category.isBattlePill) {
                    add("  持续 ${item.duration} 月")
                }
            }
        }
        is Material -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("类型：${item.category.displayName}")
                add("数量：${item.quantity}")
                
                val recipes = com.xianxia.sect.core.data.ForgeRecipeDatabase.getRecipesByMaterial(item.id)
                if (recipes.isNotEmpty()) {
                    add("")
                    add("可用于锻造：")
                    recipes.forEach { recipe ->
                        add("  • ${recipe.name}")
                    }
                }
            }
        }
        is Herb -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                if (item.category.isNotEmpty()) {
                    add("类型：${item.category}")
                }
                add("数量：${item.quantity}")
                
                val recipes = com.xianxia.sect.core.data.PillRecipeDatabase.getRecipesByHerb(item.id)
                if (recipes.isNotEmpty()) {
                    add("")
                    add("可用于炼制：")
                    recipes.forEach { recipe ->
                        add("  • ${recipe.name}")
                    }
                }
            }
        }
        is Seed -> {
            name = item.name
            rarity = item.rarity
            description = item.description
            effects = buildList {
                add("数量：${item.quantity}")
                add("成熟时间：${item.growTime}月")
                add("预计收获：${item.yield}个")
                
                val herb = com.xianxia.sect.core.data.HerbDatabase.getHerbFromSeed(item.id)
                if (herb != null) {
                    add("")
                    add("长成后：${herb.name}")
                    add("  类型：${herb.category}")
                }
            }
        }
        else -> {
            name = "未知物品"
            rarity = 1
            description = ""
            effects = emptyList()
        }
    }
    
    val rarityColor = getRarityColor(rarity)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = rarityColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (rarity) {
                        1 -> "凡品"
                        2 -> "灵品"
                        3 -> "宝品"
                        4 -> "仙品"
                        5 -> "神品"
                        6 -> "圣品"
                        else -> "凡品"
                    },
                    fontSize = 10.sp,
                    color = rarityColor
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (description.isNotEmpty()) {
                    Text(
                        text = description,
                        fontSize = 12.sp,
                        color = GameColors.TextSecondary
                    )
                    Divider(color = GameColors.Background, thickness = 1.dp)
                }
                
                effects.forEach { effect ->
                    Text(
                        text = effect,
                        fontSize = 11.sp,
                        color = Color.Black,
                        lineHeight = 16.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = GameColors.TextSecondary)
            }
        }
    )
}
