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
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ExplorationStatus
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAndRealm
import com.xianxia.sect.ui.components.DiscipleCardStyles
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.ui.components.HorizontalDiscipleCard
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.discipleCardBorder
import com.xianxia.sect.ui.game.ATTRIBUTE_FILTER_OPTIONS
import com.xianxia.sect.ui.game.AttributeFilterOption
import com.xianxia.sect.ui.game.DiscipleDetailDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.applyFilters
import com.xianxia.sect.ui.game.components.DropdownFilterButton
import com.xianxia.sect.ui.game.components.SpiritRootAttributeFilterBar
import com.xianxia.sect.ui.game.dialogs.BattleLogDetailDialog
import com.xianxia.sect.ui.game.dialogs.BattleLogItem
import com.xianxia.sect.ui.game.getAttributeValue
import com.xianxia.sect.ui.game.getSpiritRootCount
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun SecretRealmDialog(
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val secretRealms = remember { GameConfig.Dungeons.getAll() }
    val teams by viewModel.teams.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    var selectedRealm by remember { mutableStateOf<GameConfig.DungeonConfig?>(null) }
    var showDispatchDialog by remember { mutableStateOf(false) }
    var showTeamDialog by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "秘境探索",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF999999), RoundedCornerShape(4.dp))
                                .background(if (gameData.smartBattleEnabled) Color(0xFFFF9800) else Color(0xFFBDBDBD))
                                .clickable { viewModel.toggleSmartBattle() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "智能战斗",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { onDismiss() }
                                .background(Color(0xFFF5F5F5)),
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

                HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "妖兽境界与材料品阶将根据探索队伍的平均境界动态调整",
                        fontSize = 11.sp,
                        color = Color(0xFF666666),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "队伍境界越高，遇到的妖兽越强，获得的材料品阶越高",
                        fontSize = 10.sp,
                        color = Color(0xFF999999),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(secretRealms.chunked(2)) { rowRealms ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowRealms.forEach { realm ->
                                val activeTeam = teams.find {
                                    it.dungeon == realm.id &&
                                    (it.status == ExplorationStatus.TRAVELING || it.status == ExplorationStatus.EXPLORING)
                                }
                                SecretRealmCard(
                                    realm = realm,
                                    hasActiveTeam = activeTeam != null,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedRealm = realm
                                        if (activeTeam != null) {
                                            showTeamDialog = true
                                        } else {
                                            showDispatchDialog = true
                                        }
                                    }
                                )
                            }
                            if (rowRealms.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDispatchDialog) {
        selectedRealm?.let { realm ->
            DispatchTeamDialog(
                realm = realm,
                disciples = disciples,
                viewModel = viewModel,
                onDismiss = {
                    showDispatchDialog = false
                    selectedRealm = null
                }
            )
        }
    }

    if (showTeamDialog) {
        selectedRealm?.let { realm ->
            ExplorationTeamDialog(
                realm = realm,
                viewModel = viewModel,
                onDismiss = {
                    showTeamDialog = false
                    selectedRealm = null
                }
            )
        }
    }
}

@Composable
internal fun SecretRealmCard(
    realm: GameConfig.DungeonConfig,
    hasActiveTeam: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasActiveTeam) Color(0xFFE3F2FD) else GameColors.PageBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = realm.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )
                if (hasActiveTeam) {
                    Text(
                        text = "探索中",
                        fontSize = 10.sp,
                        color = Color(0xFF2196F3),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = realm.description,
                fontSize = 11.sp,
                color = Color(0xFF666666),
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
internal fun DispatchTeamDialog(
    realm: GameConfig.DungeonConfig,
    disciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val selectedDisciples = remember { mutableStateListOf<String>() }
    val maxTeamSize = 8
    var selectedRealmFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedSpiritRootFilter by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedAttributeSort by remember { mutableStateOf<String?>(null) }
    var spiritRootExpanded by remember { mutableStateOf(false) }
    var attributeExpanded by remember { mutableStateOf(false) }

    val idleDisciples = remember(disciples) {
        disciples.filter { 
            it.status == DiscipleStatus.IDLE && 
            it.realmLayer > 0 && 
            it.age >= 5 
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

    val filteredDisciples = remember(idleDisciples, selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort) {
        idleDisciples.applyFilters(selectedRealmFilter, selectedSpiritRootFilter, selectedAttributeSort)
    }

    val selectTopDisciples = {
        val availableDisciples = idleDisciples
            .filter { !selectedDisciples.contains(it.id) }
            .sortedByFollowAndRealm()
        val toSelect = availableDisciples.take(maxTeamSize - selectedDisciples.size)
        toSelect.forEach { selectedDisciples.add(it.id) }
    }

    val clearSelection = {
        selectedDisciples.clear()
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
                    text = "派遣队伍 - ${realm.name}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GameButton(
                        text = "一键选择",
                        onClick = { selectTopDisciples() }
                    )
                    GameButton(
                        text = "一键取消",
                        onClick = { clearSelection() }
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(Color(0xFFF5F5F5)),
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
                    .heightIn(max = 500.dp)
            ) {
                Text(
                    text = "选择弟子 (${selectedDisciples.size}/$maxTeamSize)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))

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
                        realmFilters.chunked(4).forEach { chunk ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                chunk.forEach { (realmVal, name) ->
                                    val isSelected = realmVal in selectedRealmFilter
                                    val count = realmCounts[realmVal] ?: 0
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSelected) GameColors.Gold.copy(alpha = 0.3f) else GameColors.PageBackground)
                                            .border(1.dp, if (isSelected) GameColors.Gold else GameColors.Border, RoundedCornerShape(4.dp))
                                            .clickable { selectedRealmFilter = if (isSelected) selectedRealmFilter - realmVal else selectedRealmFilter + realmVal }
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
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (filteredDisciples.isEmpty()) {
                    Text(
                        text = "暂无可用弟子",
                        fontSize = 12.sp,
                        color = Color(0xFF999999)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = filteredDisciples,
                            key = { it.id }
                        ) { disciple ->
                            val isSelected = selectedDisciples.contains(disciple.id)
                            val canSelect = isSelected || selectedDisciples.size < maxTeamSize

                            HorizontalDiscipleCard(
                                disciple = disciple,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelected) {
                                        selectedDisciples.remove(disciple.id)
                                    } else if (selectedDisciples.size < maxTeamSize) {
                                        selectedDisciples.add(disciple.id)
                                    }
                                }
                            )
                        }
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
                Button(
                    onClick = {
                        if (selectedDisciples.isNotEmpty()) {
                            viewModel.dispatchTeamToDungeon(realm.id, selectedDisciples.toList())
                            onDismiss()
                        }
                    },
                    enabled = selectedDisciples.isNotEmpty(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GameColors.ButtonBackground,
                        contentColor = Color.Black,
                        disabledContainerColor = Color(0xFFE0E0E0),
                        disabledContentColor = Color(0xFF9E9E9E)
                    ),
                    border = BorderStroke(1.dp, if (selectedDisciples.isNotEmpty()) GameColors.ButtonBorder else Color(0xFFBDBDBD))
                ) {
                    Text("派遣")
                }
            }
        }
    )
}

@Composable
internal fun ExplorationTeamDialog(
    realm: GameConfig.DungeonConfig,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val teams by viewModel.teams.collectAsState()
    val disciples by viewModel.discipleAggregates.collectAsState()
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val manualStacks by viewModel.manualStacks.collectAsState()
    val equipmentStacks by viewModel.equipmentStacks.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    val battleLogs by viewModel.battleLogs.collectAsState()
    var selectedDisciple by remember { mutableStateOf<DiscipleAggregate?>(null) }
    var selectedBattleLog by remember { mutableStateOf<BattleLog?>(null) }

    val activeTeam = teams.find {
        it.dungeon == realm.id &&
        (it.status == ExplorationStatus.TRAVELING || it.status == ExplorationStatus.EXPLORING)
    }
    
    val teamBattleLogs = remember(battleLogs, activeTeam) {
        if (activeTeam == null) emptyList()
        else {
            battleLogs
                .filter { it.teamId == activeTeam.id }
                .sortedByDescending { it.timestamp }
                .take(20)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = GameColors.PageBackground
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${realm.name} - 探索队伍",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                            .background(Color(0xFFF5F5F5)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "×",
                            fontSize = 16.sp,
                            color = Color(0xFF666666)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)

                if (activeTeam != null) {
                    val teamMembers = activeTeam.memberIds.mapNotNull { memberId ->
                        disciples.find { it.id == memberId }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        item {
                            val remainingMonths = activeTeam.getRemainingMonths(gameData.gameYear, gameData.gameMonth)
                            val remainingYears = remainingMonths / 12
                            val remainingMonthsPart = remainingMonths % 12
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "队伍成员",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF333333)
                                )
                                Text(
                                    text = "剩余时间: ${remainingYears}年${remainingMonthsPart}个月",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666)
                                )
                            }
                        }
                        
                        items(teamMembers.chunked(4)) { rowMembers ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                            ) {
                                rowMembers.forEach { disciple ->
                                    TeamMemberSlot(
                                        disciple = disciple,
                                        onClick = { selectedDisciple = disciple }
                                    )
                                }
                                repeat(4 - rowMembers.size) {
                                    Spacer(modifier = Modifier.size(40.dp))
                                }
                            }
                        }
                        
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                text = "战斗日志",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF333333),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        
                        if (teamBattleLogs.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "暂无战斗记录",
                                        fontSize = 12.sp,
                                        color = Color(0xFF999999)
                                    )
                                }
                            }
                        } else {
                            items(teamBattleLogs) { log ->
                                BattleLogItem(
                                    log = log,
                                    onClick = { selectedBattleLog = log }
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.recallTeam(activeTeam.id)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GameColors.ButtonBackground,
                                contentColor = Color.Black
                            ),
                            border = BorderStroke(1.dp, GameColors.ButtonBorder)
                        ) {
                            Text(
                                text = "召回队伍",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "该秘境没有正在探索的队伍",
                            fontSize = 14.sp,
                            color = Color(0xFF999999)
                        )
                    }
                }
            }
        }
    }

    selectedDisciple?.let { disciple ->
        val updatedDisciple = disciples.find { it.id == disciple.id } ?: disciple
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
            onNavigateToDisciple = { d -> selectedDisciple = d }
        )
    }
    
    selectedBattleLog?.let { log ->
        BattleLogDetailDialog(
            log = log,
            onDismiss = { selectedBattleLog = null }
        )
    }
}

@Composable
internal fun TeamMemberSlot(
    disciple: DiscipleAggregate,
    onClick: () -> Unit
) {
    val isDead = !disciple.isAlive
    
    val currentHp = if (isDead) 0 else disciple.statusData["currentHp"]?.toIntOrNull() ?: disciple.maxHp
    val hpPercent = disciple.maxHp.takeIf { it > 0 }?.let {
        (currentHp.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    } ?: 1f

    val hpColor = when {
        hpPercent > 0.6f -> Color(0xFF4CAF50)
        hpPercent > 0.3f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isDead) {
            Text(
                text = "死亡",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF44336),
                maxLines = 1
            )
        } else {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFE0E0E0))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(hpPercent)
                        .background(hpColor)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isDead) Color(0xFFEEEEEE) else Color.White)
                .border(1.dp, if (isDead) Color(0xFFCCCCCC) else Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = disciple.name,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDead) Color(0xFF999999) else Color.Black,
                    maxLines = 1
                )
                if (disciple.isFollowed) { FollowedTag() }
            }
            Text(
                text = disciple.realmName,
                fontSize = 7.sp,
                color = if (isDead) Color(0xFFAAAAAA) else Color(0xFF666666),
                maxLines = 1
            )
        }
    }
}