package com.xianxia.sect.ui.game.dialogs

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
import com.xianxia.sect.core.model.BattleSlotType
import com.xianxia.sect.core.model.BattleTeamSlot
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.theme.GameColors

@Composable
internal fun BattleTeamDialog(
    slots: List<BattleTeamSlot>,
    hasExistingTeam: Boolean,
    teamStatus: String = "idle",
    isAtSect: Boolean = true,
    isOccupying: Boolean = false,
    onSlotClick: (Int) -> Unit,
    onRemoveClick: (Int) -> Unit,
    onCreateTeam: () -> Unit,
    onMoveClick: () -> Unit = {},
    onDisbandClick: () -> Unit = {},
    onReturnClick: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val elderSlots = slots.filter { it.slotType == BattleSlotType.ELDER }
    val discipleSlots = slots.filter { it.slotType == BattleSlotType.DISCIPLE }
    val isIdle = teamStatus == "idle"
    val isStationed = teamStatus == "stationed"
    val isReturning = teamStatus == "returning"
    val canManageTeam = hasExistingTeam && isIdle && isAtSect
    val canMoveTeam = hasExistingTeam && (isIdle || isStationed)
    var showDisbandConfirm by remember { mutableStateOf(false) }

    if (showDisbandConfirm) {
        AlertDialog(
            onDismissRequest = { showDisbandConfirm = false },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = "确认解散",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = "解散后队伍配置将丢失，所有成员将恢复空闲状态。确定要解散战斗队伍吗？",
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            },
            confirmButton = {
                GameButton(
                    text = "确认解散",
                    onClick = {
                        showDisbandConfirm = false
                        onDisbandClick()
                    },
                    backgroundColor = Color(0xFFE53935)
                )
            },
            dismissButton = {
                GameButton(
                    text = "取消",
                    onClick = { showDisbandConfirm = false }
                )
            }
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
                    text = if (hasExistingTeam) "管理战斗队伍" else "组建战斗队伍",
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
                    .heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "战斗长老（化神及以上）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF666666)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    elderSlots.forEach { slot ->
                        BattleTeamSlotItem(
                            slot = slot,
                            onClick = { onSlotClick(slot.index) },
                            onRemoveClick = { onRemoveClick(slot.index) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "战斗弟子",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF666666)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    discipleSlots.take(4).forEach { slot ->
                        BattleTeamSlotItem(
                            slot = slot,
                            onClick = { onSlotClick(slot.index) },
                            onRemoveClick = { onRemoveClick(slot.index) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    discipleSlots.drop(4).forEach { slot ->
                        BattleTeamSlotItem(
                            slot = slot,
                            onClick = { onSlotClick(slot.index) },
                            onRemoveClick = { onRemoveClick(slot.index) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "已选择 ${slots.count { it.discipleId.isNotEmpty() }}/10 名弟子",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
            }
        },
        confirmButton = {
            if (hasExistingTeam) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (canManageTeam) {
                        GameButton(
                            text = "移动",
                            onClick = onMoveClick
                        )
                        GameButton(
                            text = "解散",
                            onClick = { showDisbandConfirm = true },
                            backgroundColor = Color(0xFFE53935)
                        )
                    } else if (isStationed) {
                        GameButton(
                            text = "移动",
                            onClick = onMoveClick
                        )
                        GameButton(
                            text = "返回",
                            onClick = onReturnClick,
                            backgroundColor = Color(0xFF4CAF50)
                        )
                        GameButton(
                            text = "解散",
                            onClick = { showDisbandConfirm = true },
                            backgroundColor = Color(0xFFE53935)
                        )
                    } else if (isReturning) {
                        Text(
                            text = "队伍正在返回中...",
                            fontSize = 12.sp,
                            color = Color(0xFF888888)
                        )
                    }
                    GameButton(
                        text = "关闭",
                        onClick = onDismiss
                    )
                }
            } else {
                GameButton(
                    text = "组建队伍",
                    onClick = onCreateTeam
                )
            }
        }
    )
}

@Composable
internal fun RowScope.BattleTeamSlotItem(
    slot: BattleTeamSlot,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(if (slot.discipleId.isNotEmpty()) Color(0xFFFFF8E1) else GameColors.CardBackground)
                .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                .clickable { onClick() }
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (slot.discipleId.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = slot.discipleName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = slot.discipleRealm,
                        fontSize = 10.sp,
                        color = Color(0xFF666666)
                    )
                }
            } else {
                Text(
                    text = "+",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF999999)
                )
            }
        }
        
        if (slot.discipleId.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "卸任",
                fontSize = 10.sp,
                color = Color(0xFFE53935),
                modifier = Modifier.clickable { onRemoveClick() }
            )
        }
    }
}

@Composable
internal fun BattleTeamDiscipleSelectionDialog(
    disciples: List<DiscipleAggregate>,
    isElderSlot: Boolean = false,
    onSelect: (DiscipleAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }

    val realmFilters = if (isElderSlot) {
        listOf(
            0 to "仙人",
            1 to "渡劫",
            2 to "大乘",
            3 to "合体",
            4 to "炼虚",
            5 to "化神",
            6 to "元婴"
        )
    } else {
        listOf(
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
    }

    val realmCounts = remember(disciples) {
        disciples.groupingBy { it.realm }.eachCount()
    }

    val spiritRootCounts = remember(disciples) {
        disciples.groupingBy { it.getSpiritRootCount() }.eachCount()
    }

    val filteredDisciples = remember(disciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        disciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
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
                    text = if (isElderSlot) "选择战斗长老（化神及以上）" else "选择战斗弟子",
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
            if (disciples.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isElderSlot) "暂无符合条件的战斗长老（需化神及以上）" else "暂无可用弟子",
                        fontSize = 12.sp,
                        color = Color(0xFF999999)
                    )
                }
            } else {
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
                        if (isElderSlot) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                realmFilters.forEach { (realm, name) ->
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
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                realmFilters.take(5).forEach { (realm, name) ->
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
                                realmFilters.drop(5).forEach { (realm, name) ->
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
                                    .clickable { onSelect(disciple) }
                                    .padding(12.dp)
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
                                            Text(
                                                text = disciple.realmName,
                                                fontSize = 11.sp,
                                                color = Color(0xFF666666)
                                            )
                                        }
                                    }
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