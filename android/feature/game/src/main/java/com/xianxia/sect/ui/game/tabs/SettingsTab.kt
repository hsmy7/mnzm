package com.xianxia.sect.ui.game.tabs

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.data.ChangelogData
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.PerformanceMode
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.ui.components.CircularCheckbox
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.SalaryRealmCard
import com.xianxia.sect.ui.game.SaveLoadViewModel
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors
import java.util.Locale

@Composable
internal fun RedeemCodeDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var codeInput by remember { mutableStateOf("") }
    val redeemResult by viewModel.redeemResult.collectAsStateWithLifecycle()
    var showRewardDialog by remember { mutableStateOf(false) }
    var showTipDialog by remember { mutableStateOf(false) }
    var tipMessage by remember { mutableStateOf("") }
    var tipIsError by remember { mutableStateOf(false) }
    var rewardItems by remember { mutableStateOf<List<com.xianxia.sect.ui.game.dialogs.RewardItem>>(emptyList()) }

    LaunchedEffect(redeemResult) {
        redeemResult?.let { result ->
            if (result.success && result.rewards.isNotEmpty()) {
                rewardItems = result.rewards.map { reward ->
                    val rarityColor = try {
                        Color(android.graphics.Color.parseColor(GameConfig.Rarity.getColor(reward.rarity)))
                    } catch (e: Exception) { Color.Black }
                    com.xianxia.sect.ui.game.dialogs.RewardItem(
                        name = when (reward.type) {
                            "spiritStones" -> "${reward.quantity}灵石"
                            "disciple" -> "弟子 ${reward.name}"
                            else -> "${reward.name} ×${reward.quantity}"
                        },
                        rarityColor = rarityColor
                    )
                }
                showRewardDialog = true
            } else if (result.success) {
                tipMessage = "兑换成功！"
                tipIsError = false
                showTipDialog = true
            } else {
                tipMessage = result.message.ifBlank { "兑换失败" }
                tipIsError = true
                showTipDialog = true
            }
        }
    }

    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "兑换码",
        confirmLabel = "兑换",
        onConfirm = {
            if (codeInput.isNotBlank()) {
                viewModel.redeemCode(codeInput.trim())
            }
        },
        dismissLabel = "取消",
        onDismiss = onDismiss
    ) {
        OutlinedTextField(
            value = codeInput,
            onValueChange = { codeInput = it.uppercase(Locale.getDefault()) },
            label = { Text("请输入兑换码", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp)
        )
    }

    if (showRewardDialog) {
        com.xianxia.sect.ui.game.dialogs.RewardDialog(
            title = "兑换成功！",
            rewards = rewardItems,
            onDismiss = { showRewardDialog = false }
        )
    }

    if (showTipDialog) {
        StandardPromptDialog(
            onDismissRequest = { showTipDialog = false },
            title = if (tipIsError) "错误" else "提示",
            text = tipMessage,
            confirmLabel = "确定"
        )
    }
}
@Composable
internal fun SettingsTab(
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
    limitAdTracking: Boolean = true,
    onLimitAdTrackingChanged: (Boolean) -> Unit = {}
) {
    val timeSpeed by saveLoadViewModel.timeSpeed.collectAsStateWithLifecycle()
    val gameData by viewModel.gameData.collectAsStateWithLifecycle()
    
    var showSaveSlotDialog by remember { mutableStateOf(false) }
    var showRestartConfirmDialog by remember { mutableStateOf(false) }
    var showResetDisciplesConfirmDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showRedeemCodeDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showOtherSettingsDialog by remember { mutableStateOf(false) }
    var showSalaryConfigDialog by remember { mutableStateOf(false) }

    val showRedeemCodeDialogState by viewModel.showRedeemCodeDialog.collectAsStateWithLifecycle()
    val redeemResult by viewModel.redeemResult.collectAsStateWithLifecycle()
    
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TimeSpeedControlItem(saveLoadViewModel, timeSpeed)
            }

            item {
                val performanceMode by viewModel.performanceMode.collectAsStateWithLifecycle()
                PerformanceModeItem(
                    performanceMode = performanceMode,
                    onModeSelected = viewModel::setPerformanceMode
                )
            }

            item {
                AudioToggleItem(
                    musicEnabled = gameData.musicEnabled,
                    soundEnabled = gameData.soundEnabled,
                    onMusicToggle = { viewModel.setMusicEnabled(!gameData.musicEnabled) },
                    onSoundToggle = { viewModel.setSoundEnabled(!gameData.soundEnabled) }
                )
            }

            item {
                SettingsDialogButtonsItem(
                    onSalaryClick = { showSalaryConfigDialog = true },
                    onSaveSlotClick = { showSaveSlotDialog = true }
                )
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                SettingsActionButton("其他设置", withSound = true) { showOtherSettingsDialog = true }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsActionButton("重置状态", withSound = true) { showResetDisciplesConfirmDialog = true }
                    SettingsActionButton("重新开始", withSound = false) { showRestartConfirmDialog = true }
                    SettingsActionButton("退出游戏", withSound = false) { showExitConfirmDialog = true }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "版本 ${com.xianxia.sect.core.GameConfig.Game.VERSION}",
                    fontSize = 10.sp,
                    color = Color.Black,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
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
        StandardPromptDialog(
            onDismissRequest = { showRestartConfirmDialog = false },
            title = "确认重新开始",
            text = "确定要重新开始游戏吗？当前游戏进度将会丢失！",
            confirmLabel = "确认",
            onConfirm = {
                showRestartConfirmDialog = false
                onDismiss()
                saveLoadViewModel.restartGame()
            },
            dismissLabel = "取消",
            onDismiss = { showRestartConfirmDialog = false }
        )
    }

    if (showResetDisciplesConfirmDialog) {
        StandardPromptDialog(
            onDismissRequest = { showResetDisciplesConfirmDialog = false },
            title = "确认重置弟子状态",
            text = "确定要重置所有弟子状态吗？\n探索/战斗队伍将解散，工作/职务槽位将清空，监牢弟子不受影响。",
            confirmLabel = "确认",
            onConfirm = {
                showResetDisciplesConfirmDialog = false
                saveLoadViewModel.resetAllDisciplesStatus()
            },
            dismissLabel = "取消",
            onDismiss = { showResetDisciplesConfirmDialog = false }
        )
    }

    if (showExitConfirmDialog) {
        StandardPromptDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = "确认退出",
            text = "确定要退出游戏吗？游戏进度会自动保存。",
            confirmLabel = "确认退出",
            onConfirm = {
                showExitConfirmDialog = false
                onLogout()
            },
            dismissLabel = "取消",
            onDismiss = { showExitConfirmDialog = false }
        )
    }

    if (showRedeemCodeDialogState) {
        RedeemCodeDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.closeRedeemCodeDialog() }
        )
    }

    if (showOtherSettingsDialog) {
        UnifiedGameDialog(
            onDismissRequest = { showOtherSettingsDialog = false },
            title = "其他设置",
            mode = DialogMode.Half,
            scrollableContent = true,
            backgroundRes = com.xianxia.sect.feature.game.R.drawable.bg_horizontal,
            dismissOnClickOutside = false
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(GameColors.PageBackground)
                        .border(1.dp, GameColors.Border, RoundedCornerShape(4.dp))
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
                            color = Color.Black
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

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(ButtonSizes.StandardWidth)
                            .height(ButtonSizes.StandardHeight)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                showOtherSettingsDialog = false
                                viewModel.openRedeemCodeDialog()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ui_button),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        Text(
                            text = "兑换码",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(ButtonSizes.StandardWidth)
                            .height(ButtonSizes.StandardHeight)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                showOtherSettingsDialog = false
                                showChangelogDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ui_button),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        Text(
                            text = "更新日志",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showChangelogDialog) {
        ChangelogDialog(onDismiss = { showChangelogDialog = false })
    }

    if (showSalaryConfigDialog) {
        UnifiedGameDialog(
            onDismissRequest = { showSalaryConfigDialog = false },
            title = "年俸设置",
            mode = DialogMode.Half,
            dismissOnClickOutside = false
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val realms = listOf(
                    0 to "仙人", 1 to "渡劫", 2 to "大乘", 3 to "合体",
                    4 to "炼虚", 5 to "化神", 6 to "元婴", 7 to "金丹",
                    8 to "筑基", 9 to "练气"
                )
                items(realms, key = { it.first }, contentType = { "realm" }) { (realm, name) ->
                    val salary = gameData.yearlySalary[realm] ?: 0
                    val enabled = gameData.yearlySalaryEnabled[realm] ?: true
                    SalaryRealmCard(
                        realmName = name,
                        salary = salary,
                        enabled = enabled,
                        onEnabledChange = { viewModel.setYearlySalaryEnabled(realm, it) }
                    )
                }
            }
        }
    }
}

/** 年俸设置 + 存档管理对话框触发按钮（响应式 1/2 列），从 SettingsTab 主体抽出 */
@Composable
private fun SettingsDialogButtonsItem(
    onSalaryClick: () -> Unit,
    onSaveSlotClick: () -> Unit
) {
    Spacer(modifier = Modifier.height(4.dp))
    BoxWithConstraints {
        val spacing = 16.dp
        val columns = when {
            maxWidth >= 480.dp -> 2
            else -> 1
        }
        val itemModifier = if (columns <= 1) {
            Modifier.fillMaxWidth()
        } else {
            val w = (maxWidth - spacing * (columns - 1)) / columns
            Modifier.width(w)
        }

        @Composable
        fun Item1() {
            Column(modifier = itemModifier) {
                Text(
                    text = "年俸设置",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(ButtonSizes.StandardWidth)
                        .height(ButtonSizes.StandardHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .clickableWithSound(onClick = onSalaryClick),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ui_button),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.FillBounds
                    )
                    Text(
                        text = "年俸",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }

        @Composable
        fun Item2() {
            Column(modifier = itemModifier) {
                Text(
                    text = "存档管理",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(ButtonSizes.StandardWidth)
                        .height(ButtonSizes.StandardHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .clickableWithSound(onClick = onSaveSlotClick),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ui_button),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.FillBounds
                    )
                    Text(
                        text = "查看存档",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }

        if (columns <= 1) {
            Column(
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                Item1()
                Item2()
            }
        } else {
            val rowSpacing = Arrangement.spacedBy(spacing)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = rowSpacing,
                verticalArrangement = rowSpacing
            ) {
                Item1()
                Item2()
            }
        }
    }
}

/** 音乐/音效开关（双列复选框），从 SettingsTab 主体抽出 */
@Composable
private fun AudioToggleItem(
    musicEnabled: Boolean,
    soundEnabled: Boolean,
    onMusicToggle: () -> Unit,
    onSoundToggle: () -> Unit
) {
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "音乐",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(8.dp))
            CircularCheckbox(
                checked = musicEnabled,
                onToggle = onMusicToggle
            )
        }
        Column {
            Text(
                text = "音效",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(8.dp))
            CircularCheckbox(
                checked = soundEnabled,
                onToggle = onSoundToggle
            )
        }
    }
}

/** 设置操作按钮（标准尺寸 + 背景图 + 文本），消除 3 处重复模式 */
@Composable
private fun SettingsActionButton(
    label: String,
    withSound: Boolean,
    onClick: () -> Unit
) {
    val modifier = Modifier
        .width(ButtonSizes.StandardWidth)
        .height(ButtonSizes.StandardHeight)
        .clip(RoundedCornerShape(4.dp))
    Box(
        modifier = if (withSound) modifier.clickableWithSound(onClick = onClick) else modifier.clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ui_button),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

/** 性能模式三档（节能/均衡/性能），样式对齐 TimeSpeedControlItem */
@Composable
private fun PerformanceModeItem(
    performanceMode: PerformanceMode,
    onModeSelected: (PerformanceMode) -> Unit
) {
    Text(
        text = "性能模式",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PerformanceMode.entries.forEach { mode ->
            val modeAlpha = if (performanceMode == mode) 1f else 0.5f
            Box(
                modifier = Modifier
                    .width(ButtonSizes.StandardWidth)
                    .height(ButtonSizes.StandardHeight)
                    .alpha(modeAlpha)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onModeSelected(mode) },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ui_button),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds
                )
                Text(
                    text = mode.displayName,
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = performanceMode.description,
        fontSize = 10.sp,
        color = Color.Black
    )
}

/** 时间流速控制（暂停/继续 + 1/2 倍速切换），从 SettingsTab 主体抽出的独立 item */
@Composable
private fun TimeSpeedControlItem(
    saveLoadViewModel: SaveLoadViewModel,
    timeSpeed: Int
) {
    Text(
        text = "时间流速",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )
    Spacer(modifier = Modifier.height(8.dp))

    val isPaused by saveLoadViewModel.isPaused.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val pauseAlpha = if (isPaused) 1f else 0.5f
        val btnSize = ButtonSizes.StandardHeight + 6.dp
        Box(
            modifier = Modifier
                .size(btnSize)
                .alpha(pauseAlpha)
                .clip(CircleShape)
                .clickable { saveLoadViewModel.togglePause() },
            contentAlignment = Alignment.Center
        ) {
            if (isPaused) {
                Image(
                    painter = painterResource(id = R.drawable.ui_play_button),
                    contentDescription = "继续",
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ui_pause_button),
                    contentDescription = "暂停",
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds
                )
            }
        }

        listOf(1, 2).forEach { speed ->
            val speedAlpha = if (timeSpeed == speed && !isPaused) 1f else 0.5f
            Box(
                modifier = Modifier
                    .width(ButtonSizes.StandardWidth)
                    .height(ButtonSizes.StandardHeight)
                    .alpha(speedAlpha)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { saveLoadViewModel.setTimeSpeed(speed) },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ui_button),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds
                )
                Text(
                    text = "${speed}倍速",
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
internal fun SaveSlotDialog(
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    onDismiss: () -> Unit
) {
    val saveSlots by saveLoadViewModel.saveSlots.collectAsStateWithLifecycle()
    val saveLoadState by saveLoadViewModel.saveLoadState.collectAsStateWithLifecycle()
    val isBusy = saveLoadState.isBusy
    val isSaving = saveLoadState.isSaving
    val isLoading = saveLoadState.isLoading
    val pendingSlot = saveLoadState.pendingSlot
    var selectedSlot by remember { mutableStateOf<Int?>(null) }

    // ── 转圈动画状态（最少显示 1 秒） ──
    var showAnimation by remember { mutableStateOf(false) }
    var animationStartTime by remember { mutableLongStateOf(0L) }
    var operationLabel by remember { mutableStateOf("") }

    LaunchedEffect(isBusy) {
        if (isBusy) {
            animationStartTime = System.currentTimeMillis()
            showAnimation = true
            operationLabel = if (saveLoadState.isSaving) "保存中..." else "读取中..."
        } else if (showAnimation) {
            val elapsed = System.currentTimeMillis() - animationStartTime
            if (elapsed < 1000) {
                delay(1000 - elapsed)
            }
            showAnimation = false
        }
    }

    // 打开对话框时，检测 isSaving/isLoading 是否卡住超过阈值并自动恢复
    LaunchedEffect(Unit) {
        delay(30000) // 给云存档网络操作 30 秒宽限期
        val currentState = saveLoadViewModel.saveLoadState.value
        if (currentState.isSaving || currentState.isLoading) {
            saveLoadViewModel.cancelSaveLoad()
        }
    }

    val selectedSlotInfo = remember(saveSlots, selectedSlot) {
        saveSlots.find { it.slot == selectedSlot }
    }

    UnifiedGameDialog(
        onDismissRequest = {
            if (isBusy) saveLoadViewModel.cancelSaveLoad()
            onDismiss()
        },
        title = "存档信息",
        mode = DialogMode.Large,
        dismissOnClickOutside = false,
        headerActions = {
            if (isBusy) {
                GameButton(
                    text = "取消",
                    onClick = {
                        saveLoadViewModel.cancelSaveLoad()
                        onDismiss()
                    }
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
                // ── 转圈动画（保存/读取中） ──
                if (showAnimation) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                strokeWidth = 4.dp,
                                color = Color.Black
                            )
                            Text(
                                text = operationLabel,
                                fontSize = 16.sp,
                                color = Color.Black
                            )
                        }
                    }
                }

                if (!showAnimation) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(saveSlots, key = { it.slot }, contentType = { "save_slot" }) { slot ->
                            SaveSlotCard(
                                slot = slot,
                                isSelected = selectedSlot == slot.slot,
                                onClick = { selectedSlot = slot.slot }
                            )
                        }
                    }
                }

                if (!showAnimation) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val saveEnabled = selectedSlot != null && !isBusy
                    Box(
                        modifier = Modifier
                            .width(ButtonSizes.StandardWidth)
                            .height(ButtonSizes.StandardHeight)
                            .alpha(if (saveEnabled) 1f else 0.45f)
                            .clip(RoundedCornerShape(4.dp))
                            .then(
                                if (saveEnabled) {
                                    Modifier.clickable {
                                        saveLoadViewModel.saveGame(selectedSlot.toString())
                                    }
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ui_button),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        Text(
                            text = "保存",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                    val loadEnabled = selectedSlot != null && saveSlots.find { it.slot == selectedSlot }?.isEmpty == false && !isBusy
                    Box(
                        modifier = Modifier
                            .width(ButtonSizes.StandardWidth)
                            .height(ButtonSizes.StandardHeight)
                            .alpha(if (loadEnabled) 1f else 0.45f)
                            .clip(RoundedCornerShape(4.dp))
                            .then(
                                if (loadEnabled) {
                                    Modifier.clickable {
                                        selectedSlot?.let { slotId ->
                                            saveLoadViewModel.loadGameFromSlot(slotId)
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ui_button),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        Text(
                            text = "读取",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                }
            }  // if (!showAnimation) buttons
            }
        }
}  // SaveSlotDialog

@Composable
internal fun SaveSlotCard(
    slot: SaveSlot,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) Color.Black else GameColors.Border
    val borderWidth = if (isSelected) 2.dp else 1.dp

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
            .clickableWithSound { onClick() }
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    color = Color.Black
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
                        color = Color.Black
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "弟子: ${slot.discipleCount}",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Text(
                        text = "灵石: ${slot.spiritStones}",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangelogDialog(onDismiss: () -> Unit) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "更新日志",
        mode = DialogMode.Auto,
        backgroundRes = com.xianxia.sect.feature.game.R.drawable.bg_horizontal,
        dismissOnClickOutside = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 450.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
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
                                color = Color.Black
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
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = change,
                                    fontSize = 11.sp,
                                    color = Color.Black,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
}
