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
import com.xianxia.sect.core.ChangelogData
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.RedeemResult
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SaveLoadViewModel
import com.xianxia.sect.ui.theme.GameColors
import java.util.Locale

@Composable
internal fun RedeemCodeDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var codeInput by remember { mutableStateOf("") }
    val redeemResult by viewModel.redeemResult.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GameColors.PageBackground,
        title = {
            Text(
                text = "兑换码",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.uppercase(java.util.Locale.getDefault()) },
                    label = { Text("请输入兑换码", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                )

                redeemResult?.let { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (result.success) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (result.success) "兑换成功！" else "兑换失败",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (result.success) Color(0xFF2E7D32) else Color(0xFFC62828)
                            )

                            if (result.success && result.rewards.isNotEmpty()) {
                                Text(
                                    text = "获得奖励：",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666)
                                )
                                result.rewards.forEach { reward ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val rarityColor = try {
                                            Color(android.graphics.Color.parseColor(GameConfig.Rarity.getColor(reward.rarity)))
                                        } catch (e: Exception) {
                                            Color(0xFF666666)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(rarityColor)
                                        )
                                        Text(
                                            text = when (reward.type) {
                                                "spiritStones" -> "${reward.quantity}灵石"
                                                "disciple" -> "弟子 ${reward.name}"
                                                else -> "${reward.name} ×${reward.quantity}"
                                            },
                                            fontSize = 11.sp,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }

                            result.disciple?.let { disciple ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "弟子信息：",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF666666)
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(GameColors.PageBackground)
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
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
                                        }
                                        Text(
                                            text = disciple.genderName,
                                            fontSize = 11.sp,
                                            color = Color(0xFF666666)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        DiscipleAttrText("悟性", disciple.comprehension, fontSize = 10.sp)
                                        DiscipleAttrText("智力", disciple.intelligence, fontSize = 10.sp)
                                        DiscipleAttrText("魅力", disciple.charm, fontSize = 10.sp)
                                    }
                                    if (disciple.talentIds.isNotEmpty()) {
                                        Text(
                                            text = "天赋：${disciple.talentIds.size}个",
                                            fontSize = 10.sp,
                                            color = Color(0xFF9B59B6)
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
                GameButton(
                    text = "取消",
                    onClick = onDismiss
                )
                GameButton(
                    text = "兑换",
                    onClick = {
                        if (codeInput.isNotBlank()) {
                            viewModel.redeemCode(codeInput.trim())
                        }
                    },
                    enabled = codeInput.isNotBlank()
                )
            }
        }
    )
}
@Composable
internal fun SettingsTab(
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    onLogout: () -> Unit,
    limitAdTracking: Boolean = true,
    onLimitAdTrackingChanged: (Boolean) -> Unit = {}
) {
    val timeSpeed by saveLoadViewModel.timeSpeed.collectAsState()
    val gameData by viewModel.gameData.collectAsState()
    
    var showSaveSlotDialog by remember { mutableStateOf(false) }
    var showRestartConfirmDialog by remember { mutableStateOf(false) }
    var showResetDisciplesConfirmDialog by remember { mutableStateOf(false) }
    var showRedeemCodeDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }

    val showRedeemCodeDialogState by viewModel.showRedeemCodeDialog.collectAsState()
    val redeemResult by viewModel.redeemResult.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameColors.PageBackground)
            .padding(16.dp)
    ) {
        Text(
            text = "设置",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "时间流速",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                val isPaused by saveLoadViewModel.isPaused.collectAsState()
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isPaused) Color.Black else GameColors.PageBackground)
                            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                            .clickable { saveLoadViewModel.togglePause() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂停",
                            fontSize = 12.sp,
                            color = if (isPaused) Color.White else Color.Black
                        )
                    }
                    
                    listOf(1, 2).forEach { speed ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (timeSpeed == speed && !isPaused) Color.Black else GameColors.PageBackground)
                                .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                                .clickable { saveLoadViewModel.setTimeSpeed(speed) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${speed}倍速",
                                fontSize = 12.sp,
                                color = if (timeSpeed == speed && !isPaused) Color.White else Color.Black
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "自动存档",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAutoSaveActive = gameData.autoSaveIntervalMonths > 0
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isAutoSaveActive) Color(0xFFE74C3C) else GameColors.PageBackground)
                            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                            .clickable { saveLoadViewModel.setAutoSaveIntervalMonths(0) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "停止",
                            fontSize = 12.sp,
                            color = if (!isAutoSaveActive) Color.White else Color.Black
                        )
                    }
                    
                    var showEditIntervalDialog by remember { mutableStateOf(false) }
                    var editIntervalValue by remember { mutableStateOf("") }
                    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isAutoSaveActive) Color.Black else GameColors.PageBackground)
                            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                            .clickable {
                                if (!isAutoSaveActive) {
                                    saveLoadViewModel.setAutoSaveIntervalMonths(3)
                                } else {
                                    editIntervalValue = gameData.autoSaveIntervalMonths.toString()
                                    showEditIntervalDialog = true
                                }
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isAutoSaveActive) "${gameData.autoSaveIntervalMonths}月" else "3月",
                            fontSize = 12.sp,
                            color = if (isAutoSaveActive) Color.White else Color.Black
                        )
                    }
                    
                    if (showEditIntervalDialog) {
                        AlertDialog(
                            onDismissRequest = { showEditIntervalDialog = false },
                            containerColor = GameColors.PageBackground,
                            title = {
                                Text(
                                    text = "设置自动存档间隔",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    OutlinedTextField(
                                        value = editIntervalValue,
                                        onValueChange = { input ->
                                            val filtered = input.filter { it.isDigit() }
                                            editIntervalValue = filtered
                                        },
                                        modifier = Modifier.width(80.dp),
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontSize = 14.sp,
                                            textAlign = TextAlign.Center
                                        ),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                        )
                                    )
                                    Text(
                                        text = "月",
                                        fontSize = 14.sp,
                                        color = Color.Black
                                    )
                                }
                            },
                            confirmButton = {
                                GameButton(
                                    text = "确认",
                                    onClick = {
                                        val months = editIntervalValue.toIntOrNull()
                                        if (months != null && months in 1..12) {
                                            saveLoadViewModel.setAutoSaveIntervalMonths(months)
                                        }
                                        showEditIntervalDialog = false
                                    }
                                )
                            },
                            dismissButton = {
                                GameButton(
                                    text = "取消",
                                    onClick = { showEditIntervalDialog = false }
                                )
                            }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "月俸设置",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(GameColors.PageBackground)
                        .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                        .clickable { viewModel.openSalaryConfigDialog() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "配置月俸",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "存档管理",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(GameColors.PageBackground)
                            .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                            .clickable { showSaveSlotDialog = true }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "查看存档",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "隐私设置",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(GameColors.PageBackground)
                        .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "限制广告追踪",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        Text(
                            text = "阻止TapTap SDK收集OAID广告标识符",
                            fontSize = 10.sp,
                            color = Color(0xFF999999)
                        )
                        Text(
                            text = "更改将在下次启动应用后生效",
                            fontSize = 9.sp,
                            color = Color(0xFFCC8800)
                        )
                    }
                    Switch(
                        checked = limitAdTracking,
                        onCheckedChange = onLimitAdTrackingChanged,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = GameColors.SpiritBlue,
                            checkedThumbColor = Color.White
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "其他",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(GameColors.ButtonBackground)
                        .clickable { viewModel.openRedeemCodeDialog() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "兑换码",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(GameColors.ButtonBackground)
                        .clickable { showChangelogDialog = true }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "更新日志",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFF39C12))
                        .clickable { showResetDisciplesConfirmDialog = true }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "重置弟子状态",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE74C3C))
                        .clickable { showRestartConfirmDialog = true }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "重新开始",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(GameColors.PageBackground)
                        .border(1.dp, GameColors.Border, RoundedCornerShape(6.dp))
                        .clickable { onLogout() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "退出游戏",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "版本 ${com.xianxia.sect.core.GameConfig.Game.VERSION} (build ${com.xianxia.sect.BuildConfig.VERSION_CODE})",
                    fontSize = 10.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showSaveSlotDialog) {
        SaveSlotDialog(
            viewModel = viewModel,
            saveLoadViewModel = saveLoadViewModel,
            onDismiss = { showSaveSlotDialog = false }
        )
    }

    if (showRestartConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestartConfirmDialog = false },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = "确认重新开始",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = "确定要重新开始游戏吗？当前游戏进度将会丢失！",
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            },
            confirmButton = {
                GameButton(
                    text = "确认",
                    onClick = {
                        showRestartConfirmDialog = false
                        saveLoadViewModel.restartGame()
                    },
                    modifier = Modifier.height(32.dp)
                )
            },
            dismissButton = {
                GameButton(
                    text = "取消",
                    onClick = { showRestartConfirmDialog = false }
                )
            }
        )
    }

    if (showResetDisciplesConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetDisciplesConfirmDialog = false },
            containerColor = GameColors.PageBackground,
            title = {
                Text(
                    text = "确认重置弟子状态",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = "确定要重置所有弟子状态吗？\n探索/战斗队伍将解散，工作/职务槽位将清空，思过崖弟子不受影响。",
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            },
            confirmButton = {
                GameButton(
                    text = "确认",
                    onClick = {
                        showResetDisciplesConfirmDialog = false
                        saveLoadViewModel.resetAllDisciplesStatus()
                    },
                    modifier = Modifier.height(32.dp)
                )
            },
            dismissButton = {
                GameButton(
                    text = "取消",
                    onClick = { showResetDisciplesConfirmDialog = false }
                )
            }
        )
    }

    if (showRedeemCodeDialogState) {
        RedeemCodeDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.closeRedeemCodeDialog() }
        )
    }

    if (showChangelogDialog) {
        ChangelogDialog(onDismiss = { showChangelogDialog = false })
    }
}

@Composable
internal fun SaveSlotDialog(
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    onDismiss: () -> Unit
) {
    val saveSlots by saveLoadViewModel.saveSlots.collectAsState()
    val saveLoadState by saveLoadViewModel.saveLoadState.collectAsState()
    val isBusy = saveLoadState.isBusy
    val isSaving = saveLoadState.isSaving
    val isLoading = saveLoadState.isLoading
    val pendingSlot = saveLoadState.pendingSlot
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var saveCompleted by remember { mutableStateOf(false) }

    val selectedSlotInfo = remember(saveSlots, selectedSlot) {
        saveSlots.find { it.slot == selectedSlot }
    }
    val isAutoSaveSlot = selectedSlotInfo?.isAutoSave == true

    LaunchedEffect(isBusy) {
        if (!isBusy && saveCompleted) {
            saveCompleted = false
            onDismiss()
        }
    }
    
    Dialog(onDismissRequest = { 
        if (isBusy) {
            saveLoadViewModel.cancelSaveLoad()
        }
        onDismiss()
    }) {
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
                        .background(GameColors.PageBackground)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "存档信息",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isBusy) {
                            GameButton(
                                text = "取消",
                                onClick = {
                                    saveLoadViewModel.cancelSaveLoad()
                                    onDismiss()
                                }
                            )
                        }
                        GameButton(
                            text = "关闭",
                            onClick = {
                                if (isBusy) {
                                    saveLoadViewModel.cancelSaveLoad()
                                }
                                onDismiss()
                            }
                        )
                    }
                }
                
                if (isBusy) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFF3E0))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFFF9800)
                            )
                            Text(
                                text = when {
                                    isSaving -> "正在保存到槽位 ${if (pendingSlot == 0) "自动存档" else pendingSlot}..."
                                    isLoading -> "正在读取槽位 ${if (pendingSlot == 0) "自动存档" else pendingSlot}..."
                                    else -> ""
                                },
                                fontSize = 12.sp,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(saveSlots, key = { it.slot }) { slot ->
                        SaveSlotCard(
                            slot = slot,
                            isSelected = selectedSlot == slot.slot,
                            onClick = { if (!isBusy) selectedSlot = slot.slot }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedSlot != null && !isBusy && !isAutoSaveSlot) Color.Black else Color(0xFFCCCCCC))
                            .then(
                                if (selectedSlot != null && !isBusy && !isAutoSaveSlot) {
                                    Modifier.clickable {
                                        saveCompleted = true
                                        saveLoadViewModel.saveGame(selectedSlot.toString())
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSaving && pendingSlot == selectedSlot) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text(
                                text = if (isAutoSaveSlot) "自动存档不可保存" else "保存",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (selectedSlot != null && saveSlots.find { it.slot == selectedSlot }?.isEmpty == false && !isBusy) {
                                    Color.Black
                                } else {
                                    Color(0xFFCCCCCC)
                                }
                            )
                            .then(
                                if (selectedSlot != null && saveSlots.find { it.slot == selectedSlot }?.isEmpty == false && !isBusy) {
                                    Modifier.clickable {
                                        saveSlots.find { it.slot == selectedSlot }?.let { slot ->
                                            saveLoadViewModel.loadGame(slot)
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading && pendingSlot == selectedSlot) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Text(
                                text = "读取",
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SaveSlotCard(
    slot: SaveSlot,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (slot.isAutoSave) Color(0xFF4CAF50) else if (isSelected) Color.Black else GameColors.Border
    val borderWidth = if (slot.isAutoSave) 2.dp else if (isSelected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFFF0F0F0) else GameColors.PageBackground)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (slot.isAutoSave) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF4CAF50))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "自动",
                                fontSize = 10.sp,
                                color = Color.White
                            )
                        }
                    }
                    Text(
                        text = slot.displayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
                Text(
                    text = if (slot.isEmpty) "空" else slot.saveTime,
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            }
            
            if (!slot.isEmpty) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = slot.sectName,
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Text(
                        text = slot.displayTime,
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "弟子: ${slot.discipleCount}",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "灵石: ${slot.spiritStones}",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangelogDialog(onDismiss: () -> Unit) {
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
                    text = "更新日志",
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
                    Text(text = "×", fontSize = 16.sp, color = Color(0xFF666666))
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
                ChangelogData.entries.forEach { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(GameColors.CardBackground)
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "v${entry.version}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = GameColors.GoldDark
                            )
                            Text(
                                text = entry.date,
                                fontSize = 10.sp,
                                color = Color(0xFF999999)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        entry.changes.forEach { change ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                            ) {
                                Text(
                                    text = "•",
                                    fontSize = 11.sp,
                                    color = Color(0xFF666666)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = change,
                                    fontSize = 11.sp,
                                    color = Color(0xFF333333),
                                    lineHeight = 18.sp
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
