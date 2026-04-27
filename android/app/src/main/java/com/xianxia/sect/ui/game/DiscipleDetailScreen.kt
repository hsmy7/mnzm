package com.xianxia.sect.ui.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.launch
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
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.engine.DiscipleStatCalculator
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.ui.game.components.ItemDetailDialog
import com.xianxia.sect.ui.game.components.LearnedManualDetailDialog
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.EmptyListMessage
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.TalentDetailDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.getRarityColor
import com.xianxia.sect.ui.components.getTalentRarityColor
import com.xianxia.sect.ui.theme.GameColors
import java.util.Locale

private fun calculatePreachingBonusesForDisplay(
    disciple: DiscipleAggregate,
    elderSlots: ElderSlots?,
    allDisciples: List<DiscipleAggregate>,
    sectPolicies: SectPolicies? = null
): Triple<Double, Double, Double> {
    if (elderSlots == null) return Triple(0.0, 0.0, 0.0)
    val allDisciplesById = allDisciples.associateBy { it.id }
    val dtype = disciple.discipleType
    val dRealm = disciple.realm
    var elderBonus = 0.0
    var mastersBonus = 0.0

    if (dtype == "outer") {
        val elderId = elderSlots.preachingElder
        if (elderId.isNotEmpty()) {
            val elder = allDisciplesById[elderId]
            if (elder != null && elder.isAlive) {
                val t = elder.getBaseStats().teaching
                if (dRealm >= elder.realm && t >= 80) {
                    elderBonus += (t - 80) * 0.01
                }
            }
        }
        for (slot in elderSlots.preachingMasters) {
            val mid = slot.discipleId
            if (mid.isNotEmpty()) {
                val m = allDisciplesById[mid]
                if (m != null && m.isAlive) {
                    val t = m.getBaseStats().teaching
                    if (dRealm >= m.realm && t >= 80) {
                        mastersBonus += (t - 80) * 0.005
                    }
                }
            }
        }
    }

    if (dtype == "inner") {
        val elderId = elderSlots.qingyunPreachingElder
        if (elderId.isNotEmpty()) {
            val elder = allDisciplesById[elderId]
            if (elder != null && elder.isAlive) {
                val t = elder.getBaseStats().teaching
                if (dRealm >= elder.realm && t >= 80) {
                    elderBonus += (t - 80) * 0.01
                }
            }
        }
        for (slot in elderSlots.qingyunPreachingMasters) {
            val mid = slot.discipleId
            if (mid.isNotEmpty()) {
                val m = allDisciplesById[mid]
                if (m != null && m.isAlive) {
                    val t = m.getBaseStats().teaching
                    if (dRealm >= m.realm && t >= 80) {
                        mastersBonus += (t - 80) * 0.005
                    }
                }
            }
        }
    }

    var cultivationSubsidyBonus = 0.0
    if (sectPolicies != null && sectPolicies.cultivationSubsidy && dRealm > 5) {
        cultivationSubsidyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_BASE_EFFECT
    }

    return Triple(elderBonus, mastersBonus, cultivationSubsidyBonus)
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
        com.xianxia.sect.core.engine.DiscipleStatCalculator.getMaxManualSlots(disciple)
    }

    var showRelationsDialog by remember { mutableStateOf(false) }
    var showStorageBagDialog by remember { mutableStateOf(false) }
    var showExpelConfirmDialog by remember { mutableStateOf(false) }

    val gameData by viewModel?.gameData?.collectAsState() ?: remember { mutableStateOf(null) }

    val currentIndex = allDisciples.indexOfFirst { it.id == disciple.id }
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex >= 0 && currentIndex < allDisciples.size - 1

    Dialog(onDismissRequest = onDismiss) {
        key(disciple.id) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = GameColors.PageBackground,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
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
                                    .background(if (disciple.isFollowed) Color(0xFFFFD700) else Color(0xFF999999))
                                    .clickable { viewModel?.toggleFollowDisciple(disciple.id) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (disciple.isFollowed) "已关注" else "关注",
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
                    Spacer(modifier = Modifier.height(16.dp))
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
                            isWorkStatusPosition = viewModel?.isPositionWorkStatus(disciple.id) ?: false,
                            elderSlots = gameData?.elderSlots,
                            allDisciples = allDisciples,
                            sectPolicies = gameData?.sectPolicies
                        )
                        HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
                        TalentsSection(talents, disciple.statusData)
                        HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
                        AttributesSection(disciple)
                        HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
                        CombatStatsSection(
                            disciple = disciple,
                            weapon = weapon,
                            armor = armor,
                            boots = boots,
                            accessory = accessory,
                            learnedManuals = learnedManuals,
                            manualProficiencies = manualProficiencies
                        )
                        HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
                        EquipmentSection(
                            weapon = weapon,
                            armor = armor,
                            boots = boots,
                            accessory = accessory,
                            onSlotClick = { slotType -> showEquipmentSelection = slotType },
                            onEquipmentClick = { equipment -> showEquipmentDetailDialog = equipment }
                        )
                        HorizontalDivider(color = GameColors.Border, thickness = 1.dp)
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
                }
            }

            if (hasPrev && onNavigateToDisciple != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000))
                        .clickable { onNavigateToDisciple(allDisciples[currentIndex - 1]) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "‹",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            if (hasNext && onNavigateToDisciple != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000))
                        .clickable { onNavigateToDisciple(allDisciples[currentIndex + 1]) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "›",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
        }
    }

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
            gameData = gameData,
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
                GameButton(
                    text = "确认",
                    onClick = {
                        viewModel?.expelDisciple(disciple.id)
                        showExpelConfirmDialog = false
                        onDismiss()
                    }
                )
            },
            dismissButton = {
                GameButton(
                    text = "取消",
                    onClick = { showExpelConfirmDialog = false }
                )
            }
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

            AlertDialog(
                onDismissRequest = { showManualReplaceSelection = false },
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
                                .clickable { showManualReplaceSelection = false }
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
                    if (availableManualStacks.isEmpty()) {
                        Text(
                            text = "暂无可更换的功法",
                            fontSize = 12.sp,
                            color = Color(0xFF999999),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(56.dp),
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
                                        isLocked = stack.isLocked
                                    ),
                                    isSelected = selectedReplaceManualId == stack.id,
                                    showViewButton = true,
                                    onClick = {
                                        selectedReplaceManualId = if (selectedReplaceManualId == stack.id) null else stack.id
                                    },
                                    onViewDetail = { showReplaceDetailStack = stack }
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
                            onClick = { showManualReplaceSelection = false }
                        )
                        GameButton(
                            text = "确认更换",
                            onClick = {
                                selectedReplaceManualId?.let { newId ->
                                    viewModel?.replaceManual(disciple.id, manual.id, newId)
                                }
                                showManualReplaceSelection = false
                                showManualDetailDialog = null
                            },
                            enabled = selectedReplaceManualId != null
                        )
                    }
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
        ItemDetailDialog(
            item = equipment,
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

@Composable
private fun RelationsDialog(
    disciple: DiscipleAggregate,
    allDisciples: List<DiscipleAggregate>,
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
private fun EquipmentSelectionDialog(
    slotType: String,
    allEquipment: List<EquipmentInstance>,
    equipmentStacks: List<EquipmentStack>,
    currentEquipmentId: String?,
    currentDiscipleId: String,
    discipleRealm: Int,
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

    val availableItems = remember(allEquipment, equipmentStacks, slotType, currentEquipmentId, currentDiscipleId, discipleRealm) {
        val slotEnum = try {
            EquipmentSlot.valueOf(slotType.uppercase(Locale.getDefault()))
        } catch (_: Exception) {
            EquipmentSlot.WEAPON
        }

        val stacks = equipmentStacks.filter { stack ->
            stack.slot == slotEnum &&
            GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm)
        }.map { stack -> EquipmentSelectionItem(stack.id, stack.name, stack.rarity, stack.quantity, stack.isLocked, true) }

        val instances = allEquipment.filter {
            it.slot == slotEnum &&
            it.id != currentEquipmentId &&
            (it.ownerId == null || it.ownerId == currentDiscipleId) &&
            GameConfig.Realm.meetsRealmRequirement(discipleRealm, it.minRealm)
        }.map { inst -> EquipmentSelectionItem(inst.id, inst.name, inst.rarity, 1, false, false) }

        (stacks + instances).sortedByDescending { it.rarity }
    }

    var showDetailItem by remember { mutableStateOf<Any?>(null) }

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
            if (availableItems.isEmpty()) {
                Text(
                    text = "暂无可用的$slotTypeText",
                    fontSize = 12.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(56.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(availableItems, key = { it.id }) { item ->
                        UnifiedItemCard(
                            data = ItemCardData(
                                id = item.id,
                                name = item.name,
                                rarity = item.rarity,
                                quantity = item.quantity,
                                isLocked = item.isLocked
                            ),
                            isSelected = selectedEquipmentId == item.id,
                            showViewButton = true,
                            onClick = {
                                onSelect(item.id)
                            },
                            onViewDetail = {
                                if (item.isStack) {
                                    equipmentStacks.find { it.id == item.id }?.let { showDetailItem = it }
                                } else {
                                    allEquipment.find { it.id == item.id }?.let { showDetailItem = it }
                                }
                            }
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
                    onClick = onDismiss
                )
                GameButton(
                    text = "确认装备",
                    onClick = onConfirm,
                    enabled = selectedEquipmentId != null
                )
            }
        }
    )

    showDetailItem?.let { item ->
        ItemDetailDialog(
            item = item,
            onDismiss = { showDetailItem = null }
        )
    }
}

private data class EquipmentSelectionItem(
    val id: String,
    val name: String,
    val rarity: Int,
    val quantity: Int,
    val isLocked: Boolean,
    val isStack: Boolean
)

@Composable
private fun ManualSelectionDialog(
    manualStacks: List<ManualStack>,
    allManuals: List<ManualInstance>,
    currentManualIds: List<String>,
    discipleRealm: Int,
    maxManualSlots: Int,
    selectedManualId: String?,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val availableManualStacks = remember(manualStacks, allManuals, currentManualIds, discipleRealm, maxManualSlots) {
        if (currentManualIds.size >= maxManualSlots) {
            emptyList()
        } else {
            val manualMap = allManuals.associateBy { it.id }
            val hasMindManual = currentManualIds.any { mid -> manualMap[mid]?.type == ManualType.MIND }
            val learnedNames = currentManualIds.mapNotNull { mid -> manualMap[mid]?.name }.toSet()
            manualStacks.filter { stack ->
                !(hasMindManual && stack.type == ManualType.MIND) &&
                stack.name !in learnedNames &&
                GameConfig.Realm.meetsRealmRequirement(discipleRealm, stack.minRealm)
            }.sortedByDescending { it.rarity }
        }
    }

    var showDetailStack by remember { mutableStateOf<ManualStack?>(null) }

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
            if (availableManualStacks.isEmpty()) {
                Text(
                    text = "暂无可学习的功法",
                    fontSize = 12.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(56.dp),
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
                                isLocked = stack.isLocked
                            ),
                            isSelected = selectedManualId == stack.id,
                            showViewButton = true,
                            onClick = {
                                onSelect(stack.id)
                            },
                            onViewDetail = { showDetailStack = stack }
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
                    onClick = onDismiss
                )
                GameButton(
                    text = "确认学习",
                    onClick = onConfirm,
                    enabled = selectedManualId != null
                )
            }
        }
    )

    showDetailStack?.let { stack ->
        ItemDetailDialog(
            item = stack,
            onDismiss = { showDetailStack = null }
        )
    }
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
private fun RelationItem(relation: String, disciple: DiscipleAggregate) {
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
    disciple: DiscipleAggregate,
    allManuals: List<ManualInstance> = emptyList(),
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    position: String? = null,
    isWorkStatusPosition: Boolean = false,
    elderSlots: ElderSlots? = null,
    allDisciples: List<DiscipleAggregate> = emptyList(),
    sectPolicies: SectPolicies? = null
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
            val isMajorBreakthrough = disciple.realmLayer >= GameConfig.Realm.get(disciple.realm).maxLayers
            val targetRealm = disciple.realm - 1
            val soulRequired = if (isMajorBreakthrough && targetRealm >= 0) {
                GameConfig.Realm.getSoulPowerRequirement(targetRealm)
            } else 0
            if (soulRequired > 0 && disciple.soulPower < soulRequired) {
                InfoItem("神魂 ${disciple.soulPower}/$soulRequired", Modifier.weight(1f), color = Color(0xFFFF6B6B))
            } else {
                InfoItem("突破率 ${GameUtils.formatPercent(breakthroughChance)}", Modifier.weight(1f))
            }
        }
        
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (disciple.realm != 0) {
                val manualsMap = remember(allManuals) {
                    allManuals.associateBy { it.id }
                }
                val proficiencyMap = remember(manualProficiencies, disciple.id) {
                    manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
                }
                val cultivationSpeed = remember(disciple, manualsMap, proficiencyMap, allDisciples, elderSlots, sectPolicies) {
                    val (preachingElderBonus, preachingMastersBonus, cultivationSubsidyBonus) = calculatePreachingBonusesForDisplay(
                        disciple, elderSlots, allDisciples,
                        sectPolicies = sectPolicies
                    )
                    disciple.calculateCultivationSpeed(
                        manualsMap, proficiencyMap,
                        preachingElderBonus = preachingElderBonus,
                        preachingMastersBonus = preachingMastersBonus,
                        cultivationSubsidyBonus = cultivationSubsidyBonus
                    )
                }
                
                // 境界、修炼进度条、每秒修炼值在同一行显示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.realmName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    val cultivationTarget = disciple.cultivationProgress.toFloat().coerceIn(0f, 1f)
                    val prevCultivationTarget = remember { mutableStateOf(cultivationTarget) }
                    val cultivationShouldSnap = cultivationTarget < prevCultivationTarget.value - 0.5f
                    val animatedCultivationProgress by animateFloatAsState(
                        targetValue = cultivationTarget,
                        animationSpec = if (cultivationShouldSnap) snap() else tween(durationMillis = 300),
                        label = "cultivationProgress"
                    )
                    SideEffect { prevCultivationTarget.value = cultivationTarget }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFE8E8E8)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = animatedCultivationProgress)
                                .fillMaxHeight()
                                .background(Color(0xFF4CAF50))
                        )
                        Text(
                            text = "${disciple.cultivation.toInt()}/${disciple.maxCultivation.toInt()}",
                            fontSize = 7.sp,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    Text(
                        text = "${String.format(Locale.getDefault(), "%.1f", cultivationSpeed)}/秒",
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50)
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

        Spacer(modifier = Modifier.height(8.dp))
        HpMpBars(disciple)
    }
}

@Composable
private fun HpMpBars(disciple: DiscipleAggregate) {
    val maxHp = disciple.maxHpFinal
    val maxMp = disciple.maxMpFinal
    val rawCurrentHp = disciple.currentHp
    val rawCurrentMp = disciple.currentMp
    val currentHpDisplay = if (rawCurrentHp < 0) maxHp else rawCurrentHp
    val currentMpDisplay = if (rawCurrentMp < 0) maxMp else rawCurrentMp
    val hpFraction = if (maxHp > 0) (currentHpDisplay.toFloat() / maxHp).coerceIn(0f, 1f) else 1f
    val mpFraction = if (maxMp > 0) (currentMpDisplay.toFloat() / maxMp).coerceIn(0f, 1f) else 1f

    val prevHpTarget = remember { mutableStateOf(hpFraction) }
    val prevMpTarget = remember { mutableStateOf(mpFraction) }
    val hpShouldSnap = hpFraction < prevHpTarget.value - 0.5f
    val mpShouldSnap = mpFraction < prevMpTarget.value - 0.5f

    val animatedHpFraction by animateFloatAsState(
        targetValue = hpFraction,
        animationSpec = if (hpShouldSnap) snap() else tween(durationMillis = 300),
        label = "hpProgress"
    )
    val animatedMpFraction by animateFloatAsState(
        targetValue = mpFraction,
        animationSpec = if (mpShouldSnap) snap() else tween(durationMillis = 300),
        label = "mpProgress"
    )
    SideEffect {
        prevHpTarget.value = hpFraction
        prevMpTarget.value = mpFraction
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "气血",
                fontSize = 9.sp,
                color = Color(0xFF666666),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(1.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE8E8E8)),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = animatedHpFraction)
                        .fillMaxHeight()
                        .background(Color(0xFFE74C3C))
                )
                Text(
                    text = "$currentHpDisplay/$maxHp",
                    fontSize = 7.sp,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "灵力",
                fontSize = 9.sp,
                color = Color(0xFF666666),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(1.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE8E8E8)),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = animatedMpFraction)
                        .fillMaxHeight()
                        .background(Color(0xFF3498DB))
                )
                Text(
                    text = "$currentMpDisplay/$maxMp",
                    fontSize = 7.sp,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun InfoItem(value: String, modifier: Modifier = Modifier, color: Color = Color.Black) {
    Text(
        text = value,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
    )
}

@Composable
private fun TalentsSection(talents: List<Talent>, statusData: Map<String, String> = emptyMap()) {
    var selectedTalent by remember { mutableStateOf<Talent?>(null) }
    
    // 计算战斗成长值
    val winGrowth = remember(statusData) {
        val growthAttrs = listOf("maxHp" to "生命", "maxMp" to "灵力", "physicalAttack" to "物攻",
            "magicAttack" to "法攻", "physicalDefense" to "物防", "magicDefense" to "法防", "speed" to "速度")
        growthAttrs.mapNotNull { (key, label) ->
            val value = statusData["winGrowth.$key"]?.toIntOrNull() ?: 0
            if (value > 0) "$label+$value" else null
        }
    }
    
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
                        val rarityColor = getTalentRarityColor(talent.rarity)
                        
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
        
        // 战斗成长显示（"百战通神"天赋效果）
        if (winGrowth.isNotEmpty()) {
            Text(
                text = "战斗成长: ${winGrowth.joinToString(" ")}",
                fontSize = 10.sp,
                color = Color(0xFF4CAF50)
            )
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
private fun AttributesSection(disciple: DiscipleAggregate) {
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
            DiscipleAttrText("悟性", disciple.comprehension, Modifier.weight(1f))
            DiscipleAttrText("智力", disciple.intelligence, Modifier.weight(1f))
            DiscipleAttrText("魅力", disciple.charm, Modifier.weight(1f))
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("忠诚", disciple.loyalty, Modifier.weight(1f))
            DiscipleAttrText("炼器", disciple.artifactRefining, Modifier.weight(1f))
            DiscipleAttrText("炼丹", disciple.pillRefining, Modifier.weight(1f))
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscipleAttrText("灵植", disciple.spiritPlanting, Modifier.weight(1f))
            DiscipleAttrText("传道", disciple.teaching, Modifier.weight(1f))
            DiscipleAttrText("道德", disciple.morality, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CombatStatsSection(
    disciple: DiscipleAggregate,
    weapon: EquipmentInstance?,
    armor: EquipmentInstance?,
    boots: EquipmentInstance?,
    accessory: EquipmentInstance?,
    learnedManuals: List<ManualInstance>,
    manualProficiencies: Map<String, List<ManualProficiencyData>>
) {
    val equipmentMap = remember(weapon, armor, boots, accessory) {
        mutableMapOf<String, EquipmentInstance>().apply {
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
    weapon: EquipmentInstance?,
    armor: EquipmentInstance?,
    boots: EquipmentInstance?,
    accessory: EquipmentInstance?,
    onSlotClick: (String) -> Unit,
    onEquipmentClick: (EquipmentInstance) -> Unit
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
    equipment: EquipmentInstance?, 
    modifier: Modifier = Modifier,
    onSlotClick: (String) -> Unit,
    onEquipmentClick: (EquipmentInstance) -> Unit,
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
    manuals: List<ManualInstance>,
    maxSlots: Int,
    manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap(),
    discipleId: String = "",
    onSlotClick: () -> Unit,
    onManualClick: (ManualInstance) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "功法",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        
        val manualSlots = mutableListOf<ManualInstance?>()
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
    manual: ManualInstance?,
    modifier: Modifier = Modifier,
    proficiencyData: ManualProficiencyData? = null,
    onSlotClick: () -> Unit,
    onManualClick: (ManualInstance) -> Unit
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
private fun StatItemWithBonus(name: String, baseValue: Int, finalValue: Int, modifier: Modifier = Modifier, currentDisplay: String? = null) {
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
            text = currentDisplay ?: finalValue.toString(),
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

@Composable
private fun StorageBagDialog(
    items: List<StorageBagItem>,
    spiritStones: Long,
    disciple: DiscipleAggregate,
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
                        columns = GridCells.Adaptive(56.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(items) { index, item ->
                            UnifiedItemCard(
                                data = ItemCardData(
                                    id = item.itemId,
                                    name = item.name,
                                    rarity = item.rarity,
                                    quantity = item.quantity,
                                    grade = item.grade
                                ),
                                isSelected = selectedItem?.itemId == item.itemId,
                                showViewButton = true,
                                onClick = {
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
            ItemDetailDialog(
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
    disciple: DiscipleAggregate,
    gameData: GameData,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf(RewardFilter.ALL) }
    var selectedItem by remember { mutableStateOf<RewardSelectedItem?>(null) }
    var rewardQuantity by remember { mutableIntStateOf(1) }
    var showDetailDialog by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<Any?>(null) }
    var isRewarding by remember { mutableStateOf(false) }
    val rewardScope = rememberCoroutineScope()

    val equipmentStacks by viewModel.equipmentStacks.collectAsState()
    val manualStacks by viewModel.manualStacks.collectAsState()
    val pills by viewModel.pills.collectAsState()
    val materials by viewModel.materials.collectAsState()
    val herbs by viewModel.herbs.collectAsState()
    val seeds by viewModel.seeds.collectAsState()

    val availableManuals = manualStacks
    val availableEquipment = equipmentStacks

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
                            equipment = availableEquipment,
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
                            items = availableEquipment,
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
                            rewardScope.launch {
                                try {
                                    viewModel.rewardItemsToDisciple(disciple.id, listOf(item.copy(quantity = rewardQuantity)))
                                } finally {
                                    selectedItem = null
                                    rewardQuantity = 1
                                    isRewarding = false
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    if (showDetailDialog) {
        detailItem?.let { item ->
            ItemDetailDialog(
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

        GameButton(
            text = "关闭",
            onClick = onDismiss
        )
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
            columns = GridCells.Adaptive(56.dp),
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
                        is EquipmentStack -> "equipment_${item.id}"
                        is ManualStack -> "manual_${item.id}"
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
                        is EquipmentStack -> RewardSelectedItem(item.id, "equipment", item.name, item.rarity, 1)
                        is ManualStack -> RewardSelectedItem(item.id, "manual", item.name, item.rarity, 1)
                        is Pill -> RewardSelectedItem(item.id, "pill", item.name, item.rarity, item.quantity, item.grade.displayName)
                        is Material -> RewardSelectedItem(item.id, "material", item.name, item.rarity, item.quantity)
                        is Herb -> RewardSelectedItem(item.id, "herb", item.name, item.rarity, item.quantity)
                        is Seed -> RewardSelectedItem(item.id, "seed", item.name, item.rarity, item.quantity)
                        else -> null
                    }
                }

                if (currentSelectedItem != null) {
                    val isSelected = selectedItem?.id == currentSelectedItem.id
                    UnifiedItemCard(
                        data = ItemCardData(
                            name = currentSelectedItem.name,
                            rarity = currentSelectedItem.rarity,
                            quantity = currentSelectedItem.quantity,
                            grade = currentSelectedItem.grade
                        ),
                        isSelected = isSelected,
                        showViewButton = true,
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
    equipment: List<EquipmentStack>,
    manuals: List<ManualStack>,
    pills: List<Pill>,
    materials: List<Material>,
    herbs: List<Herb>,
    seeds: List<Seed>,
    selectedItem: RewardSelectedItem?,
    onItemSelect: (RewardSelectedItem) -> Unit,
    onViewDetail: (Any) -> Unit = {}
) {
    val allItems: List<GameItem> = equipment + manuals + pills + materials + herbs + seeds

    if (allItems.isEmpty()) {
        EmptyListMessage("暂无道具")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(56.dp),
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
                        is EquipmentStack -> "equipment_${item.id}"
                        is ManualStack -> "manual_${item.id}"
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
                        is EquipmentStack -> RewardSelectedItem(item.id, "equipment", item.name, item.rarity, 1)
                        is ManualStack -> RewardSelectedItem(item.id, "manual", item.name, item.rarity, 1)
                        is Pill -> RewardSelectedItem(item.id, "pill", item.name, item.rarity, item.quantity, item.grade.displayName)
                        is Material -> RewardSelectedItem(item.id, "material", item.name, item.rarity, item.quantity)
                        is Herb -> RewardSelectedItem(item.id, "herb", item.name, item.rarity, item.quantity)
                        is Seed -> RewardSelectedItem(item.id, "seed", item.name, item.rarity, item.quantity)
                        else -> null
                    }
                }

                if (currentSelectedItem != null) {
                    val isSelected = selectedItem?.id == currentSelectedItem.id
                    UnifiedItemCard(
                        data = ItemCardData(
                            name = currentSelectedItem.name,
                            rarity = currentSelectedItem.rarity,
                            quantity = currentSelectedItem.quantity,
                            grade = currentSelectedItem.grade
                        ),
                        isSelected = isSelected,
                        showViewButton = true,
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
