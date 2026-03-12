package com.xianxia.sect.ui.game

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ExplorationStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEvent
import com.xianxia.sect.core.model.MapMarker
import com.xianxia.sect.core.model.MapMarkerType
import com.xianxia.sect.core.model.MapPath
import com.xianxia.sect.core.model.WorldSect

/**
 * 游戏主界面 - 包含三个板块：
 * 1. 宗门信息板块：宗门名称/弟子数量/灵石数量/游戏内时间
 * 2. 快捷操作板块：宗门管理/世界地图/招募弟子/秘境探索
 * 3. 宗门消息板块：显示宗门发生的事件
 * 
 * 仅使用文字，禁止任何图标
 */
@Composable
fun SectMainScreen(
    viewModel: GameViewModel,
    onLogout: () -> Unit
) {
    val gameData by viewModel.gameData.collectAsState()
    val disciples by viewModel.disciples.collectAsState()
    val events by viewModel.events.collectAsState()
    
    val aliveDiscipleCount = remember(disciples) {
        disciples.count { it.isAlive }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            // 第一板块：宗门信息板块（固定高度）
            SectInfoPanel(
                gameData = gameData,
                discipleCount = aliveDiscipleCount,
                onLogout = onLogout
            )
            
            // 第二板块：快捷操作板块（固定高度）
            QuickActionPanel(
                viewModel = viewModel
            )
            
            // 第三板块：宗门消息板块（占据剩余空间）
            Box(modifier = Modifier.weight(1f)) {
                SectMessagePanel(
                    events = events,
                    currentYear = gameData.gameYear,
                    onViewAll = { viewModel.openEventLogDialog() }
                )
            }
        }
        
        // 对话框显示
        SectMainScreenDialogs(viewModel = viewModel)
    }
}

/**
 * 第一板块：宗门信息板块
 * 包含：宗门名称、弟子数量、灵石数量、游戏内时间
 */
@Composable
private fun SectInfoPanel(
    gameData: GameData?,
    discipleCount: Int,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 第一行：宗门名称和时间
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 宗门名称
            Text(
                text = gameData?.sectName ?: "青云宗",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                letterSpacing = 0.sp
            )
            
            // 游戏内时间
            Text(
                text = "${gameData?.gameYear ?: 1}年${gameData?.gameMonth ?: 1}月",
                fontSize = 12.sp,
                color = Color.Black,
                fontWeight = FontWeight.Normal
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 第二行：弟子数量和灵石数量
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 弟子数量
            InfoItem(
                label = "弟子",
                value = "$discipleCount"
            )
            
            // 灵石数量
            InfoItem(
                label = "灵石",
                value = "${gameData?.spiritStones ?: 0}"
            )
        }
    }
}

/**
 * 信息项组件 - 纯文字显示
 */
@Composable
private fun InfoItem(
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

/**
 * 第二板块：快捷操作板块
 * 包含：宗门管理、世界地图、招募弟子、秘境探索
 */
@Composable
private fun QuickActionPanel(
    viewModel: GameViewModel
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 板块标题
            Text(
                text = "快捷操作",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            // 操作按钮网格 - 2x2布局
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 第一行：宗门管理、世界地图
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        text = "宗门管理",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openSectManagementDialog() }
                    )
                    QuickActionButton(
                        text = "世界地图",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openWorldMapDialog() }
                    )
                }
                
                // 第二行：招募弟子、秘境探索
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        text = "招募弟子",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openRecruitDialog() }
                    )
                    QuickActionButton(
                        text = "秘境探索",
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.openSecretRealmDialog() }
                    )
                }
            }
        }
    }
}

/**
 * 快捷操作按钮 - 纯文字按钮
 */
@Composable
private fun QuickActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = Color.Black,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 第三板块：宗门消息板块
 * 显示宗门发生的事件（仅显示十年内）
 */
@Composable
private fun SectMessagePanel(
    events: List<GameEvent>,
    currentYear: Int,
    onViewAll: () -> Unit
) {
    val recentEvents = remember(events, currentYear) {
        events.filter { it.year >= currentYear - 10 }
            .sortedByDescending { it.year * 12 + it.month }
            .take(20)
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(12.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "宗门消息",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                
                if (recentEvents.isNotEmpty()) {
                    Text(
                        text = "查看全部",
                        fontSize = 12.sp,
                        color = Color.Black,
                        modifier = Modifier.clickable(onClick = onViewAll)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (recentEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无消息",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentEvents) { event ->
                        EventMessageItem(event = event)
                        if (event != recentEvents.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(top = 8.dp),
                                color = Color(0xFFEEEEEE),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 事件消息项 - 纯文字显示
 */
@Composable
private fun EventMessageItem(event: GameEvent) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 第一行：时间在最左侧
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatEventTime(event.timestamp),
                fontSize = 12.sp,
                color = Color(0xFF666666)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // 第二行：事件内容
        Text(
            text = event.message,
            fontSize = 12.sp,
            color = Color.Black,
            lineHeight = 16.sp
        )
    }
}

/**
 * 格式化事件时间
 */
private fun formatEventTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

/**
 * 主界面对话框管理
 */
@Composable
private fun SectMainScreenDialogs(viewModel: GameViewModel) {
    val showSectManagementDialog by viewModel.showSectManagementDialog.collectAsState()
    val showWorldMapDialog by viewModel.showWorldMapDialog.collectAsState()
    val showSecretRealmDialog by viewModel.showSecretRealmDialog.collectAsState()
    val showRecruitDialog by viewModel.showRecruitDialog.collectAsState()
    val showEventLogDialog by viewModel.showEventLogDialog.collectAsState()
    
    if (showSectManagementDialog) {
        SectManagementDialog(
            onDismiss = { viewModel.closeSectManagementDialog() }
        )
    }
    
    if (showWorldMapDialog) {
        val gameData by viewModel.gameData.collectAsState()
        WorldMapDialog(
            worldSects = gameData?.worldMapSects ?: emptyList(),
            onDismiss = { viewModel.closeWorldMapDialog() }
        )
    }
    
    if (showSecretRealmDialog) {
        val disciples by viewModel.disciples.collectAsState()
        SecretRealmDialog(
            disciples = disciples.filter { it.isAlive },
            viewModel = viewModel,
            onDismiss = { viewModel.closeSecretRealmDialog() }
        )
    }
    
    if (showRecruitDialog) {
        val gameData by viewModel.gameData.collectAsState()
        RecruitDialog(
            recruitList = gameData?.recruitList ?: emptyList(),
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { viewModel.closeRecruitDialog() }
        )
    }
    
    if (showEventLogDialog) {
        val events by viewModel.events.collectAsState()
        EventLogDialog(
            events = events,
            onDismiss = { viewModel.closeEventLogDialog() }
        )
    }
}

/**
 * 宗门管理对话框
 */
@Composable
private fun SectManagementDialog(
    onDismiss: () -> Unit
) {
    CommonDialog(
        title = "宗门管理",
        onDismiss = onDismiss
    ) {
        Text(
            text = "宗门管理功能开发中...",
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

/**
 * 世界地图对话框
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WorldMapDialog(
    worldSects: List<WorldSect>,
    onDismiss: () -> Unit
) {
    val markers = remember(worldSects) {
        worldSects.map { sect ->
            MapMarker(
                id = sect.id,
                name = sect.name,
                type = MapMarkerType.SECT,
                x = (sect.x / 4000f).coerceIn(0f, 1f),
                y = (sect.y / 3500f).coerceIn(0f, 1f),
                level = sect.level,
                ownerId = if (sect.isPlayerSect) "player" else sect.id,
                isCapital = sect.isPlayerSect,
                description = sect.levelName
            )
        }
    }
    
    val paths = remember(worldSects) {
        val sectIds = worldSects.map { it.id }.toSet()
        val pathSet = mutableSetOf<Pair<String, String>>()
        worldSects.forEach { sect ->
            (sect.connectedSectIds ?: emptyList()).forEach { connectedId ->
                if (connectedId in sectIds) {
                    val (id1, id2) = if (sect.id < connectedId) {
                        sect.id to connectedId
                    } else {
                        connectedId to sect.id
                    }
                    pathSet.add(id1 to id2)
                }
            }
        }
        pathSet.map { (from, to) -> MapPath(from, to) }
    }
    
    val window = androidx.compose.ui.platform.LocalContext.current.let {
        (it as? android.app.Activity)?.window
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        window?.let { w ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).let { controller ->
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
        }
    }

    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(0, 0),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            clippingEnabled = false
        )
    ) {
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .background(Color(0xFFA8B878))
        ) {
            WorldMapScreen(
                markers = markers,
                paths = paths,
                onBack = onDismiss,
                onMarkerClick = { marker ->
                },
                onFunctionClick = { function ->
                }
            )
        }
    }
}

/**
 * 秘境探索对话框
 */
@Composable
fun SecretRealmDialog(
    disciples: List<Disciple>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val secretRealms = remember { GameConfig.Dungeons.getAll() }
    val teams by viewModel.teams.collectAsState()
    var selectedRealm by remember { mutableStateOf<GameConfig.DungeonConfig?>(null) }
    var showDispatchDialog by remember { mutableStateOf(false) }
    var showTeamDialog by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = Color.White
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

                Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)

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

    if (showDispatchDialog && selectedRealm != null) {
        DispatchTeamDialog(
            realm = selectedRealm!!,
            disciples = disciples,
            viewModel = viewModel,
            onDismiss = {
                showDispatchDialog = false
                selectedRealm = null
            }
        )
    }

    if (showTeamDialog && selectedRealm != null) {
        ExplorationTeamDialog(
            realm = selectedRealm!!,
            viewModel = viewModel,
            onDismiss = {
                showTeamDialog = false
                selectedRealm = null
            }
        )
    }
}

@Composable
private fun SecretRealmCard(
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
            containerColor = if (hasActiveTeam) Color(0xFFE3F2FD) else Color.White
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
private fun DispatchTeamDialog(
    realm: GameConfig.DungeonConfig,
    disciples: List<Disciple>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val selectedDisciples = remember { mutableStateListOf<String>() }
    val maxTeamSize = 7
    var selectedRealmFilter by remember { mutableStateOf<Int?>(null) }

    // 只显示空闲且有境界的弟子
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

    val sortedDisciples = remember(idleDisciples) {
        idleDisciples.sortedWith(
            compareBy<Disciple> { it.realm }
                .thenByDescending { it.realmLayer }
        )
    }

    val filteredDisciples = remember(sortedDisciples, selectedRealmFilter) {
        if (selectedRealmFilter == null) {
            sortedDisciples
        } else {
            sortedDisciples.filter { it.realm == selectedRealmFilter }
        }
    }

    val selectTopDisciples = {
        val availableDisciples = idleDisciples
            .filter { !selectedDisciples.contains(it.id) }
            .sortedWith(
                compareBy<Disciple> { it.realm }
                    .thenByDescending { it.realmLayer }
            )
        val toSelect = availableDisciples.take(maxTeamSize - selectedDisciples.size)
        toSelect.forEach { selectedDisciples.add(it.id) }
    }

    val clearSelection = {
        selectedDisciples.clear()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
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
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .clickable { selectTopDisciples() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "一键选择",
                            fontSize = 11.sp,
                            color = Color.Black
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                            .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                            .clickable { clearSelection() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "一键取消",
                            fontSize = 11.sp,
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

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.take(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFFE0E0E0) else Color.White)
                                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        realmFilters.drop(5).forEach { (realmVal, name) ->
                            val isSelected = selectedRealmFilter == realmVal
                            val count = realmCounts[realmVal] ?: 0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) Color(0xFFE0E0E0) else Color.White)
                                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                                    .clickable { selectedRealmFilter = if (isSelected) null else realmVal }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$name $count",
                                    fontSize = 9.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.Black
                                )
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
                        items(filteredDisciples) { disciple ->
                            val isSelected = selectedDisciples.contains(disciple.id)
                            val canSelect = isSelected || selectedDisciples.size < maxTeamSize

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) Color(0xFFE3F2FD) else Color(0xFFF5F5F5))
                                    .clickable(enabled = canSelect) {
                                        if (isSelected) {
                                            selectedDisciples.remove(disciple.id)
                                        } else if (selectedDisciples.size < maxTeamSize) {
                                            selectedDisciples.add(disciple.id)
                                        }
                                    }
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = disciple.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val spiritRootColor = try {
                                            Color(android.graphics.Color.parseColor(disciple.spiritRoot.countColor))
                                        } catch (e: Exception) {
                                            Color(0xFF666666)
                                        }
                                        Text(
                                            text = disciple.spiritRootName,
                                            fontSize = 10.sp,
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
                                if (isSelected) {
                                    Text(
                                        text = "✓",
                                        fontSize = 14.sp,
                                        color = Color(0xFF2196F3)
                                    )
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
                TextButton(onClick = onDismiss) {
                    Text("取消", color = Color(0xFF666666))
                }
                Button(
                    onClick = {
                        if (selectedDisciples.isNotEmpty()) {
                            viewModel.dispatchTeamToDungeon(realm.id, selectedDisciples.toList())
                            onDismiss()
                        }
                    },
                    enabled = selectedDisciples.isNotEmpty()
                ) {
                    Text("派遣")
                }
            }
        }
    )
}

@Composable
private fun CommonDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
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
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        },
        confirmButton = {}
    )
}

/**
 * 探索队伍对话框 - 显示队伍信息和召回按钮
 * 使用4列布局显示弟子槽位，槽位大小与长老槽位一致(40.dp)
 */
@Composable
private fun ExplorationTeamDialog(
    realm: GameConfig.DungeonConfig,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val teams by viewModel.teams.collectAsState()
    val disciples by viewModel.disciples.collectAsState()
    val equipment by viewModel.equipment.collectAsState()
    val manuals by viewModel.manuals.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    val battleLogs by viewModel.battleLogs.collectAsState()
    var selectedDisciple by remember { mutableStateOf<Disciple?>(null) }
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
            color = Color.White
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

                Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)

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
                            Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)
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
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF5722)
                            )
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
            manualProficiencies = gameData?.manualProficiencies ?: emptyMap(),
            viewModel = viewModel,
            onDismiss = { selectedDisciple = null }
        )
    }
    
    selectedBattleLog?.let { log ->
        BattleLogDetailDialog(
            log = log,
            onDismiss = { selectedBattleLog = null }
        )
    }
}

/**
 * 队伍成员槽位 - 显示弟子名称、境界和血条
 * 槽位大小与长老槽位一致(40.dp)，4列布局
 */
@Composable
private fun TeamMemberSlot(
    disciple: Disciple,
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = disciple.name,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDead) Color(0xFF999999) else Color.Black,
                    maxLines = 1
                )
                Text(
                    text = disciple.realmName,
                    fontSize = 7.sp,
                    color = if (isDead) Color(0xFFAAAAAA) else Color(0xFF666666),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BattleLogItem(
    log: BattleLog,
    onClick: () -> Unit
) {
    val resultColor = when (log.result) {
        BattleResult.WIN -> Color(0xFF4CAF50)
        BattleResult.LOSE -> Color(0xFFF44336)
        BattleResult.DRAW -> Color(0xFFFF9800)
    }
    
    val resultText = when (log.result) {
        BattleResult.WIN -> "胜利"
        BattleResult.LOSE -> "失败"
        BattleResult.DRAW -> "平局"
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F8F8)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "第${log.year}年${log.month}月",
                    fontSize = 11.sp,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "回合: ${log.turns} | 敌人: ${log.enemies.size}",
                    fontSize = 10.sp,
                    color = Color(0xFF999999)
                )
            }
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(resultColor)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = resultText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun BattleLogDetailDialog(
    log: BattleLog,
    onDismiss: () -> Unit
) {
    val resultColor = when (log.result) {
        BattleResult.WIN -> Color(0xFF4CAF50)
        BattleResult.LOSE -> Color(0xFFF44336)
        BattleResult.DRAW -> Color(0xFFFF9800)
    }
    
    val resultText = when (log.result) {
        BattleResult.WIN -> "胜利"
        BattleResult.LOSE -> "失败"
        BattleResult.DRAW -> "平局"
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "战斗详情",
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
                
                Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "第${log.year}年${log.month}月",
                                fontSize = 12.sp,
                                color = Color(0xFF666666)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(resultColor)
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = resultText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "战斗回合: ${log.turns}",
                            fontSize = 11.sp,
                            color = Color(0xFF333333)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "我方弟子",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF333333)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // 我方弟子槽位布局（每行4个）
                    items(log.teamMembers.chunked(4)) { rowMembers ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            rowMembers.forEach { member ->
                                BattleParticipantSlot(
                                    name = member.name,
                                    realmName = member.realmName,
                                    hp = member.hp,
                                    maxHp = member.maxHp,
                                    isAlive = member.isAlive
                                )
                            }
                            repeat(4 - rowMembers.size) {
                                Spacer(modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "敌方妖兽",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF333333)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // 敌方妖兽槽位布局（每行4个）
                    items(log.enemies.chunked(4)) { rowEnemies ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            rowEnemies.forEach { enemy ->
                                BattleParticipantSlot(
                                    name = enemy.name,
                                    realmName = enemy.realmName,
                                    hp = enemy.hp,
                                    maxHp = enemy.maxHp,
                                    isAlive = enemy.isAlive
                                )
                            }
                            repeat(4 - rowEnemies.size) {
                                Spacer(modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                    
                    if (log.rounds.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Divider(color = Color(0xFFE0E0E0), thickness = 1.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "战斗过程",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF333333)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        items(log.rounds) { round ->
                            BattleRoundItem(round = round)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 战斗参与者槽位 - 显示名称、境界和血条
 * 使用长老槽位样式，槽位大小40.dp，血条在槽位外部上方
 * 血条为0时显示死亡状态
 */
@Composable
private fun BattleParticipantSlot(
    name: String,
    realmName: String,
    hp: Int,
    maxHp: Int,
    isAlive: Boolean
) {
    val hpPercent = maxHp.takeIf { it > 0 }?.let {
        (hp.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    } ?: 0f

    val hpColor = when {
        hpPercent > 0.6f -> Color(0xFF4CAF50)
        hpPercent > 0.3f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 血条（槽位外部上方）
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

        Spacer(modifier = Modifier.height(2.dp))

        // 槽位 - 40.dp大小，与长老槽位一致
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isAlive) Color.White else Color(0xFFEEEEEE))
                .border(1.dp, if (isAlive) Color(0xFFE0E0E0) else Color(0xFFCCCCCC), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isAlive) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = name,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1
                    )
                    Text(
                        text = realmName,
                        fontSize = 7.sp,
                        color = Color(0xFF666666),
                        maxLines = 1
                    )
                }
            } else {
                // 死亡状态显示"死亡"
                Text(
                    text = "死亡",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF44336),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BattleRoundItem(
    round: BattleLogRound
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "第${round.roundNumber}回合",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF333333)
        )
        
        round.actions.forEach { action ->
            BattleActionItem(action = action)
        }
    }
}

@Composable
private fun BattleActionItem(
    action: BattleLogAction
) {
    val actionColor = when {
        action.isKill -> Color(0xFFF44336)
        action.isCrit -> Color(0xFFFF9800)
        else -> Color(0xFF666666)
    }
    
    val critText = if (action.isCrit) " [暴击]" else ""
    val killText = if (action.isKill) " [击杀]" else ""
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 2.dp)
    ) {
        Text(
            text = "${action.attacker} → ${action.target}: ${action.damage}${critText}${killText}",
            fontSize = 10.sp,
            color = actionColor
        )
    }
}


