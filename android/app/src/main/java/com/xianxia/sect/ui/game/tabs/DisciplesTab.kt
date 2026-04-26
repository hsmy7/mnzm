package com.xianxia.sect.ui.game.tabs

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAttributeAndRealm
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.DiscipleCardStyles
import com.xianxia.sect.ui.components.FollowedTag
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
            .background(GameColors.PageBackground)
    ) {
        SpiritRootAttributeFilterBar(
            selectedSpiritRootFilter = selectedSpiritRootFilter,
            selectedAttributeSort = selectedAttributeSort,
            spiritRootExpanded = spiritRootExpanded,
            attributeExpanded = attributeExpanded,
            spiritRootCounts = spiritRootCounts,
            onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
            onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
            onAttributeSortSelected = { selectedAttributeSort = it },
            onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
            onAttributeExpandToggle = { attributeExpanded = !attributeExpanded }
        )

        RealmFilterBar(
            filters = REALM_FILTER_OPTIONS,
            realmCounts = realmCounts,
            selectedFilter = selectedRealmFilter,
            onFilterSelected = { selectedRealmFilter = selectedRealmFilter + it },
            onFilterRemoved = { selectedRealmFilter = selectedRealmFilter - it }
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
                    color = Color(0xFF999999)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
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

@Composable
internal fun RealmFilterBar(
    filters: List<Pair<Int, String>>,
    realmCounts: Map<Int, Int>,
    selectedFilter: Set<Int>,
    onFilterSelected: (Int) -> Unit,
    onFilterRemoved: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GameColors.PageBackground)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filters.take(5).forEach { (realm, name) ->
                val isSelected = realm in selectedFilter
                val count = realmCounts[realm] ?: 0
                FilterChip(
                    text = "$name $count",
                    isSelected = isSelected,
                    onClick = {
                        if (isSelected) onFilterRemoved(realm)
                        else onFilterSelected(realm)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filters.drop(5).forEach { (realm, name) ->
                val isSelected = realm in selectedFilter
                val count = realmCounts[realm] ?: 0
                FilterChip(
                    text = "$name $count",
                    isSelected = isSelected,
                    onClick = {
                        if (isSelected) onFilterRemoved(realm)
                        else onFilterSelected(realm)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun FilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
            .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = if (isCompact) 4.dp else 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = if (isCompact) 9.sp else 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) GameColors.GoldDark else Color.Black
        )
    }
}

@Composable
internal fun DiscipleCard(
    disciple: DiscipleAggregate,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .discipleCardBorder()
            .clickable { onClick() }
            .padding(DiscipleCardStyles.cardPadding)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    if (disciple.isFollowed) {
                        FollowedTag()
                    }
                    Text(
                        text = disciple.status.displayName,
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val spiritRootColor = try {
                    Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                } catch (e: Exception) {
                    Color(0xFF666666)
                }
                Text(
                    text = disciple.spiritRootName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = spiritRootColor,
                    maxLines = 1
                )
                Text(
                    text = disciple.realmName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DiscipleAttrText("悟性", disciple.comprehension)
                DiscipleAttrText("忠诚", disciple.loyalty)
                DiscipleAttrText("道德", disciple.morality)
            }
        }
    }
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
                        color = Color(0xFF666666),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    color = Color(0xFF999999)
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
            color = Color(0xFF666666)
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
                        color = Color(0xFF666666),
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 16.sp,
                    color = Color(0xFF999999)
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
                    text = "选择亲传弟子",
                    fontSize = 12.sp,
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
                    .heightIn(max = 500.dp)
            ) {
                SpiritRootAttributeFilterBar(
                    selectedSpiritRootFilter = selectedSpiritRootFilter,
                    selectedAttributeSort = selectedAttributeSort,
                    spiritRootExpanded = spiritRootExpanded,
                    attributeExpanded = attributeExpanded,
                    spiritRootCounts = spiritRootCounts,
                    onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                    onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                    onAttributeSortSelected = { selectedAttributeSort = it },
                    onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                    onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                    isCompact = true
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        REALM_FILTER_OPTIONS.take(5).forEach { (realm, name) ->
                            val isSelected = realm in selectedRealmFilter
                            val count = realmCounts[realm] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                    .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realm else selectedRealmFilter + realm }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) GameColors.GoldDark else Color.Black
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        REALM_FILTER_OPTIONS.drop(5).forEach { (realm, name) ->
                            val isSelected = realm in selectedRealmFilter
                            val count = realmCounts[realm] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                    .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realm else selectedRealmFilter + realm }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) GameColors.GoldDark else Color.Black
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredDisciples,
                        key = { it.id }
                    ) { disciple ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(GameColors.PageBackground)
                                .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                                .clickable { onSelect(disciple.id) }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = disciple.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                    if (disciple.isFollowed) {
                                        FollowedTag()
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val spiritRootColor = try {
                                        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                                    } catch (e: Exception) {
                                        Color(0xFF666666)
                                    }
                                    Text(
                                        text = disciple.spiritRootName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = spiritRootColor
                                    )
                                    requiredAttribute?.let { (attrKey, attrName) ->
                                        val attrValue = when (attrKey) {
                                            "spiritPlanting" -> disciple.spiritPlanting
                                            "pillRefining" -> disciple.pillRefining
                                            "artifactRefining" -> disciple.artifactRefining
                                            "teaching" -> disciple.teaching
                                            "morality" -> disciple.morality
                                            "charm" -> disciple.charm
                                            else -> 0
                                        }
                                        Text(
                                            text = "$attrName:$attrValue",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2196F3)
                                        )
                                    }
                                    Text(
                                        text = disciple.realmName,
                                        fontSize = 12.sp,
                                        color = Color(0xFF666666)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
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
                    fontSize = 12.sp,
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
                    .heightIn(max = 500.dp)
            ) {
                SpiritRootAttributeFilterBar(
                    selectedSpiritRootFilter = selectedSpiritRootFilter,
                    selectedAttributeSort = selectedAttributeSort,
                    spiritRootExpanded = spiritRootExpanded,
                    attributeExpanded = attributeExpanded,
                    spiritRootCounts = spiritRootCounts,
                    onSpiritRootFilterSelected = { selectedSpiritRootFilter = selectedSpiritRootFilter + it },
                    onSpiritRootFilterRemoved = { selectedSpiritRootFilter = selectedSpiritRootFilter - it },
                    onAttributeSortSelected = { selectedAttributeSort = it },
                    onSpiritRootExpandToggle = { spiritRootExpanded = !spiritRootExpanded },
                    onAttributeExpandToggle = { attributeExpanded = !attributeExpanded },
                    isCompact = true
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        REALM_FILTER_OPTIONS.take(5).forEach { (realm, name) ->
                            val isSelected = realm in selectedRealmFilter
                            val count = realmCounts[realm] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                    .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realm else selectedRealmFilter + realm }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) GameColors.GoldDark else Color.Black
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        REALM_FILTER_OPTIONS.drop(5).forEach { (realm, name) ->
                            val isSelected = realm in selectedRealmFilter
                            val count = realmCounts[realm] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                    .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realm else selectedRealmFilter + realm }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) GameColors.GoldDark else Color.Black
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredDisciples) { disciple ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(GameColors.PageBackground)
                                .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                                .clickable { onSelect(disciple.id) }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = disciple.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                    if (disciple.isFollowed) {
                                        FollowedTag()
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val spiritRootColor = try {
                                        Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                                    } catch (e: Exception) {
                                        Color(0xFF666666)
                                    }
                                    Text(
                                        text = disciple.spiritRootName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = spiritRootColor
                                    )
                                    requiredAttribute?.let { (attrKey, attrName) ->
                                        val attrValue = when (attrKey) {
                                            "spiritPlanting" -> disciple.spiritPlanting
                                            "pillRefining" -> disciple.pillRefining
                                            "artifactRefining" -> disciple.artifactRefining
                                            "teaching" -> disciple.teaching
                                            "morality" -> disciple.morality
                                            "charm" -> disciple.charm
                                            else -> 0
                                        }
                                        Text(
                                            text = "$attrName:$attrValue",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2196F3)
                                        )
                                    }
                                    Text(
                                        text = disciple.realmName,
                                        fontSize = 12.sp,
                                        color = Color(0xFF666666)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
