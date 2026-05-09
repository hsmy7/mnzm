package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.R
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.HalfScreenDialog
import com.xianxia.sect.ui.components.HorizontalDiscipleCard
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getAttributeValue
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.game.map.MapItem
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun LevelDetailDialog(
    level: MapItem.Level,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onAttack: (List<String?>) -> Unit,
    onDismiss: () -> Unit
) {
    val slots = remember { mutableStateListOf(*arrayOfNulls<String?>(8)) }
    var targetSlotIndex by remember { mutableIntStateOf(-1) }
    var showDiscipleSelection by remember { mutableStateOf(false) }
    var selectedDiscipleDetail by remember { mutableStateOf<DiscipleAggregate?>(null) }

    // Collect state needed by DiscipleDetailDialog
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val manualStacks by viewModel.manualStacks.collectAsState()
    val equipmentStacks by viewModel.equipmentStacks.collectAsState()
    val gameData by viewModel.gameData.collectAsState()

    val imageRes = remember(level) {
        when (level.levelType) {
            LevelType.BEAST -> when (level.beastType ?: 0) {
                0 -> R.drawable.tiger_beast
                1 -> R.drawable.wolf_beast
                2 -> R.drawable.snake_beast
                3 -> R.drawable.bear_beast
                4 -> R.drawable.eagle_beast
                5 -> R.drawable.fox_beast
                6 -> R.drawable.dragon_beast
                else -> R.drawable.turtle_beast
            }
            LevelType.CAVE -> when (level.caveImageIndex) {
                0 -> R.drawable.cave_1
                1 -> R.drawable.cave_2
                else -> R.drawable.cave_3
            }
        }
    }

    val realmDisplayName = remember(level) {
        GameConfig.Realm.getName(level.realm)
    }

    val occupiedCount = remember(slots) { slots.count { it != null } }

    // One-click appoint: fill empty slots with highest-realm idle disciples
    val oneClickAppoint: () -> Unit = {
        val idleDisciples = disciples.filter {
            it.status == DiscipleStatus.IDLE && it.realmLayer > 0 && it.age >= 5
        }.sortedWith(
            compareByDescending<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer }
        )

        val assignedIds = slots.filterNotNull().toSet()
        val available = idleDisciples.filter { it.id !in assignedIds }

        var idx = 0
        for (i in 0 until 8) {
            if (slots[i] == null && idx < available.size) {
                slots[i] = available[idx].id
                idx++
            }
        }
    }

    HalfScreenDialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (level.levelType == LevelType.CAVE && level.caveName.isNotEmpty()) level.caveName else level.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                CloseButton(onClick = onDismiss)
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ========== Top section: image + info ==========
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = imageRes),
                        contentDescription = null,
                        modifier = Modifier.size(120.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        if (level.levelType == LevelType.CAVE && level.caveName.isNotEmpty()) {
                            Text(
                                text = level.caveName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "守护兽：${level.name}",
                                fontSize = 13.sp,
                                color = Color.Black
                            )
                        } else {
                            Text(
                                text = level.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = realmDisplayName,
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "数量：${level.count}",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ========== Middle section: 2x4 slots ==========
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (row in 0 until 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (col in 0 until 4) {
                                val slotIndex = row * 4 + col
                                val discipleId = slots[slotIndex]
                                val disciple =
                                    if (discipleId != null) disciples.find { it.id == discipleId }
                                    else null

                                LevelSlotBox(
                                    disciple = disciple,
                                    modifier = Modifier.weight(1f),
                                    onSlotClick = {
                                        if (disciple != null) {
                                            selectedDiscipleDetail = disciple
                                        } else {
                                            targetSlotIndex = slotIndex
                                            showDiscipleSelection = true
                                        }
                                    },
                                    onDismiss = {
                                        slots[slotIndex] = null
                                    },
                                    onSwap = {
                                        targetSlotIndex = slotIndex
                                        showDiscipleSelection = true
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // One-click appoint button
                GameButton(
                    text = "一键任命",
                    onClick = oneClickAppoint,
                    width = ButtonSizes.StandardWidth,
                    height = ButtonSizes.StandardHeight,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ========== Bottom section: action buttons ==========
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    GameButton(
                        text = "进攻",
                        onClick = { onAttack(slots.toList()) },
                        enabled = occupiedCount > 0,
                        width = ButtonSizes.StandardWidth,
                        height = ButtonSizes.StandardHeight,
                        fontSize = 11.sp
                    )
                    GameButton(
                        text = "关闭",
                        onClick = onDismiss,
                        width = ButtonSizes.StandardWidth,
                        height = ButtonSizes.StandardHeight,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    // ========== Disciple selection dialog ==========
    if (showDiscipleSelection && targetSlotIndex >= 0) {
        LevelSlotSelectionDialog(
            disciples = disciples,
            onSelect = { discipleId ->
                slots[targetSlotIndex] = discipleId
                showDiscipleSelection = false
                targetSlotIndex = -1
            },
            onDismiss = {
                showDiscipleSelection = false
                targetSlotIndex = -1
            }
        )
    }

    // ========== Disciple detail dialog ==========
    selectedDiscipleDetail?.let { disciple ->
        val updated = disciples.find { it.id == disciple.id } ?: disciple
        DiscipleDetailDialog(
            disciple = updated,
            allDisciples = disciples,
            allEquipment = equipment,
            allManuals = manuals,
            manualStacks = manualStacks,
            equipmentStacks = equipmentStacks,
            manualProficiencies = gameData?.manualProficiencies ?: emptyMap(),
            viewModel = viewModel,
            onDismiss = { selectedDiscipleDetail = null }
        )
    }
}

// ==================== Slot Box Component ====================

@Composable
private fun LevelSlotBox(
    disciple: DiscipleAggregate?,
    modifier: Modifier = Modifier,
    onSlotClick: () -> Unit,
    onDismiss: () -> Unit,
    onSwap: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Square slot box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (disciple != null) Color(0xFFFFF8E1)
                    else GameColors.CardBackground
                )
                .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                .clickable { onSlotClick() },
            contentAlignment = Alignment.Center
        ) {
            if (disciple != null) {
                Text(
                    text = disciple.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(4.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }

        // Action buttons below slot (only visible when occupied)
        if (disciple != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text(
                    text = "卸任",
                    fontSize = 9.sp,
                    color = Color.Black,
                    modifier = Modifier.clickable { onDismiss() }
                )
                Text(
                    text = "更换",
                    fontSize = 9.sp,
                    color = Color.Black,
                    modifier = Modifier.clickable { onSwap() }
                )
            }
        }
    }
}

// ==================== Disciple Selection Dialog ====================

@Composable
private fun LevelSlotSelectionDialog(
    disciples: List<DiscipleAggregate>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }

    val idleDisciples = remember(disciples) {
        disciples.filter {
            it.status == DiscipleStatus.IDLE && it.realmLayer > 0 && it.age >= 5
        }
    }

    val realmFilters = listOf(
        0 to "仙人",
        1 to "渡劫",
        2 to "大乘",
        3 to "合体",
        4 to "炼虚",
        5 to "化神",
        6 to "元婴",
        7 to "金丹",
        8 to "筑基",
        9 to "炼气"
    )

    val realmCounts = remember(idleDisciples) {
        idleDisciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(idleDisciples) {
        idleDisciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(
        idleDisciples,
        selectedRealmFilter,
        selectedSpiritRootFilter,
        selectedAttributeSort
    ) {
        idleDisciples.applyFilters(
            selectedRealmFilter,
            selectedSpiritRootFilter,
            selectedAttributeSort
        )
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
                    text = "选择弟子",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                CloseButton(onClick = onDismiss)
            }
        },
        text = {
            if (idleDisciples.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无空闲弟子",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                ) {
                    // Filter bar
                    SpiritRootAttributeFilterBar(
                        selectedSpiritRootFilter = selectedSpiritRootFilter,
                        selectedAttributeSort = selectedAttributeSort,
                        spiritRootExpanded = spiritRootExpanded,
                        attributeExpanded = attributeExpanded,
                        spiritRootCounts = spiritRootCounts,
                        onSpiritRootFilterSelected = {
                            selectedSpiritRootFilter = selectedSpiritRootFilter + it
                        },
                        onSpiritRootFilterRemoved = {
                            selectedSpiritRootFilter = selectedSpiritRootFilter - it
                        },
                        onAttributeSortSelected = { selectedAttributeSort = it },
                        onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                        onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                        isCompact = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Realm filter chips
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        realmFilters.chunked(4).forEach { chunk ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                chunk.forEach { (realm, name) ->
                                    val isSelected = realm in selectedRealmFilter
                                    val count = realmCounts[realm] ?: 0
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (isSelected) GameColors.Gold.copy(alpha = 0.3f)
                                                else GameColors.PageBackground
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) GameColors.Gold else GameColors.Border,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable {
                                                selectedRealmFilter =
                                                    if (isSelected) selectedRealmFilter - realm
                                                    else selectedRealmFilter + realm
                                            }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$name $count",
                                            fontSize = 9.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold
                                            else FontWeight.Normal,
                                            color = if (isSelected) GameColors.GoldDark
                                            else Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Disciple list
                    if (filteredDisciples.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无符合条件的弟子",
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredDisciples, key = { it.id }) { disciple ->
                                HorizontalDiscipleCard(
                                    disciple = disciple,
                                    isSelected = false,
                                    onClick = { onSelect(disciple.id) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
