package com.xianxia.sect.ui.game.tabs

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.R
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAttributeAndRealm
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.DiscipleCardStyles
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.HalfScreenDialog
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.components.discipleCardBorder
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.DropdownFilterButton
import com.xianxia.sect.ui.game.components.FilterChip
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getAttributeValue
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.theme.GameColors

// 其他Tab的占位实现
internal val REALM_FILTER_OPTIONS = listOf(
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
@Composable
internal fun DisciplesTab(
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    equipment: List<EquipmentInstance>,
    manuals: List<ManualInstance>,
    manualStacks: List<ManualStack>,
    equipmentStacks: List<EquipmentStack>,
    viewModel: GameViewModel
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }
    var selectedDisciple by remember { mutableStateOf<DiscipleAggregate?>(null) }

    val realmCounts = remember(disciples) {
        disciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(disciples) {
        disciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(disciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        disciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
        SpiritRootAttributeFilterBar(
            selectedSpiritRootFilter = selectedSpiritRootFilter,
            selectedAttributeSort = selectedAttributeSort,
            selectedRealmFilter = selectedRealmFilter,
            realmFilterOptions = REALM_FILTER_OPTIONS,
            realmCounts = realmCounts,
            spiritRootExpanded = spiritRootExpanded,
            attributeExpanded = attributeExpanded,
            realmExpanded = realmExpanded,
            spiritRootCounts = spiritRootCounts,
            onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
            onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
            onAttributeSortSelected = { selectedAttributeSort = it },
            onRealmFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
            onRealmFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it },
            onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
            onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
            onRealmExpandToggle = { realmExpanded = !realmExpanded }
        )

        if (filteredDisciples.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无弟子",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = filteredDisciples,
                    key = { it.id }
                ) { disciple ->
                    DiscipleCard(
                        disciple = disciple,
                        onClick = { selectedDisciple = disciple }
                    )
                }
            }
        }

    selectedDisciple?.let { selected ->
        val updatedDisciple = disciples.find { it.id == selected.id } ?: selected
        DiscipleDetailDialog(
            disciple = updatedDisciple,
            allDisciples = disciples,
            allEquipment = equipment,
            allManuals = manuals,
            manualStacks = manualStacks,
            equipmentStacks = equipmentStacks,
            manualProficiencies = gameData?.manualProficiencies ?: emptyMap(),
            viewModel = viewModel,
            onDismiss = { selectedDisciple = null },
            onNavigateToDisciple = { disciple -> selectedDisciple = disciple }
        )
    }
    }
}

@Composable
internal fun DiscipleCard(
    disciple: DiscipleAggregate,
    onClick: () -> Unit
) {
    PortraitDiscipleCard(disciple = disciple, onClick = onClick)
}

@Composable
internal fun ElderSlotWithDisciples(
    slotName: String,
    slotType: String,
    elder: DiscipleAggregate?,
    directDisciples: List<DirectDiscipleSlot>,
    onElderClick: () -> Unit,
    onElderRemove: () -> Unit,
    onDirectDiscipleClick: (Int) -> Unit,
    onDirectDiscipleRemove: (Int) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = slotName,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(4.dp))
        
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                .clickable(onClick = onElderClick),
            contentAlignment = Alignment.Center
        ) {
            if (elder != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = elder.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = elder.realmName,
                        fontSize = 8.sp,
                        color = Color.Black,
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    color = Color.Black
                )
            }
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                .clickable(onClick = onElderRemove)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = "卸任",
                fontSize = 9.sp,
                color = Color.Black
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(8.dp)
                .background(Color(0xFFCCCCCC))
        )
        
        Text(
            text = "亲传弟子",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            (0..1).forEach { index ->
                val disciple = directDisciples.find { it.index == index }
                DirectDiscipleSlotItem(
                    disciple = disciple,
                    onClick = { onDirectDiscipleClick(index) },
                    onRemove = { onDirectDiscipleRemove(index) }
                )
            }
        }
    }
}

@Composable
internal fun DirectDiscipleSlotItem(
    disciple: DirectDiscipleSlot?,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (disciple != null && disciple.isActive) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = disciple.discipleName,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = disciple.discipleRealm,
                        fontSize = 7.sp,
                        color = Color.Black,
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 16.sp,
                    color = Color.Black
                )
            }
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(GameColors.PageBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(3.dp))
                .clickable(onClick = onRemove)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "卸任",
                fontSize = 8.sp,
                color = Color.Black
            )
        }
    }
}

@Composable
internal fun DirectDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    requiredAttribute: Pair<String, String>?,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }

    val filteredDisciplesBase = remember(disciples, elderSlots) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            !elderSlots.isDiscipleInAnyPosition(it.id)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(filteredDisciplesBase, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort, requiredAttribute) {
        filteredDisciplesBase.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort, requiredAttribute?.first)
    }

    HalfScreenDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择亲传弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                CloseButton(onClick = onDismiss)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                SpiritRootAttributeFilterBar(
                    selectedSpiritRootFilter = selectedSpiritRootFilter,
                    selectedAttributeSort = selectedAttributeSort,
                    selectedRealmFilter = selectedRealmFilter,
                    realmFilterOptions = REALM_FILTER_OPTIONS,
                    realmCounts = realmCounts,
                    spiritRootExpanded = spiritRootExpanded,
                    attributeExpanded = attributeExpanded,
                    realmExpanded = realmExpanded,
                    spiritRootCounts = spiritRootCounts,
                    onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                    onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                    onAttributeSortSelected = { selectedAttributeSort = it },
                    onRealmFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
                    onRealmFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it },
                    onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                    onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                    onRealmExpandToggle = { realmExpanded = !realmExpanded },
                    isCompact = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无符合条件的弟子",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            items = filteredDisciples,
                            key = { it.id }
                        ) { disciple ->
                            val extraAttrs = requiredAttribute?.let { (attrKey, attrName) ->
                                val attrValue = when (attrKey) {
                                    "spiritPlanting" -> disciple.spiritPlanting
                                    "pillRefining" -> disciple.pillRefining
                                    "artifactRefining" -> disciple.artifactRefining
                                    "mining" -> disciple.mining
                                    "teaching" -> disciple.teaching
                                    "morality" -> disciple.morality
                                    "charm" -> disciple.charm
                                    else -> 0
                                }
                                listOf(attrName to attrValue)
                            } ?: emptyList()
                            PortraitDiscipleCard(
                                disciple = disciple,
                                extraAttributes = extraAttrs,
                                onClick = { onSelect(disciple.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
internal fun ElderDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    currentElderId: String?,
    requiredAttribute: Pair<String, String>?,
    elderSlots: ElderSlots,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }
    var realmExpanded by remember { mutableStateOf(false) }

    val filteredDisciplesBase = remember(disciples, elderSlots) {
        disciples.filter {
            it.realmLayer > 0 &&
            it.age >= 5 &&
            it.status == DiscipleStatus.IDLE &&
            it.discipleType == "inner" &&
            !elderSlots.isDiscipleInAnyPosition(it.id)
        }
    }

    val realmCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(filteredDisciplesBase) {
        filteredDisciplesBase.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(filteredDisciplesBase, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort, requiredAttribute) {
        filteredDisciplesBase.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort, requiredAttribute?.first)
    }

    HalfScreenDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                CloseButton(onClick = onDismiss)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                SpiritRootAttributeFilterBar(
                    selectedSpiritRootFilter = selectedSpiritRootFilter,
                    selectedAttributeSort = selectedAttributeSort,
                    selectedRealmFilter = selectedRealmFilter,
                    realmFilterOptions = REALM_FILTER_OPTIONS,
                    realmCounts = realmCounts,
                    spiritRootExpanded = spiritRootExpanded,
                    attributeExpanded = attributeExpanded,
                    realmExpanded = realmExpanded,
                    spiritRootCounts = spiritRootCounts,
                    onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                    onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                    onAttributeSortSelected = { selectedAttributeSort = it },
                    onRealmFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
                    onRealmFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it },
                    onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                    onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                    onRealmExpandToggle = { realmExpanded = !realmExpanded },
                    isCompact = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (filteredDisciples.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无符合条件的弟子",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredDisciples) { disciple ->
                            val extraAttrs = requiredAttribute?.let { (attrKey, attrName) ->
                                val attrValue = when (attrKey) {
                                    "spiritPlanting" -> disciple.spiritPlanting
                                    "pillRefining" -> disciple.pillRefining
                                    "artifactRefining" -> disciple.artifactRefining
                                    "mining" -> disciple.mining
                                    "teaching" -> disciple.teaching
                                    "morality" -> disciple.morality
                                    "charm" -> disciple.charm
                                    else -> 0
                                }
                                listOf(attrName to attrValue)
                            } ?: emptyList()
                            PortraitDiscipleCard(
                                disciple = disciple,
                                extraAttributes = extraAttrs,
                                onClick = { onSelect(disciple.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
