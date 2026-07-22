package com.xianxia.sect.ui.game.tabs

import androidx.compose.animation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.data.ChangelogData
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.RedeemResult
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.DiscipleAttrText
import com.xianxia.sect.ui.components.CircularCheckbox
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.DialogSoftInputGuard
import com.xianxia.sect.ui.components.DialogSystemBarGuard
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

                    val stopAlpha = if (!isAutoSaveActive) 1f else 0.5f
                    val stopBtnSize = ButtonSizes.StandardHeight + 6.dp
                    Box(
                        modifier = Modifier
                            .size(stopBtnSize)
                            .alpha(stopAlpha)
                            .clip(CircleShape)
                            .clickable { saveLoadViewModel.setAutoSaveIntervalMonths(0) },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ui_pause_button),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    }

                    var showEditIntervalDialog by remember { mutableStateOf(false) }
                    var editIntervalValue by remember { mutableStateOf("") }

                    val intervalAlpha = if (isAutoSaveActive) 1f else 0.5f
                    Box(
                        modifier = Modifier
                            .width(ButtonSizes.StandardWidth)
                            .height(ButtonSizes.StandardHeight)
                            .alpha(intervalAlpha)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                if (!isAutoSaveActive) {
                                    saveLoadViewModel.setAutoSaveIntervalMonths(3)
                                } else {
                                    editIntervalValue = gameData.autoSaveIntervalMonths.toString()
                                    showEditIntervalDialog = true
                                }
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
                            text = if (isAutoSaveActive) "${gameData.autoSaveIntervalMonths}月" else "3月",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                    
                    if (showEditIntervalDialog) {
                        DialogSoftInputGuard()
                        Dialog(
                            onDismissRequest = { showEditIntervalDialog = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
                        ) {
                        // 隐藏 Dialog Window 的系统状态栏/导航栏
                        DialogSystemBarGuard()

                        // 清除焦点 + 隐藏输入法，防止 FloatingActionMode BadTokenException
                        val editDialogView = LocalView.current
                        DisposableEffect(Unit) {
                            onDispose {
                                editDialogView.clearFocus()
                                val imm = editDialogView.context.getSystemService(
                                    android.content.Context.INPUT_METHOD_SERVICE
                                ) as? android.view.inputmethod.InputMethodManager
                                imm?.hideSoftInputFromWindow(editDialogView.windowToken, 0)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .imePadding()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { showEditIntervalDialog = false }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.83f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {}
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = com.xianxia.sect.feature.game.R.drawable.bg_horizontal),
                                    contentDescription = null,
                                    modifier = Modifier.matchParentSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Column(
                                    modifier = Modifier.padding(20.dp)
                                ) {
                                        Text(
                                            text = "设置自动存档间隔",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
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
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            GameButton(
                                                text = "取消",
                                                onClick = { showEditIntervalDialog = false },
                                                modifier = Modifier.width(ButtonSizes.StandardWidth)
                                            )
                                            GameButton(
                                                text = "确认",
                                                onClick = {
                                                    val months = editIntervalValue.toIntOrNull()
                                                    if (months != null && months in 1..12) {
                                                        saveLoadViewModel.setAutoSaveIntervalMonths(months)
                                                    }
                                                    showEditIntervalDialog = false
                                                },
                                                modifier = Modifier.width(ButtonSizes.StandardWidth)
                                            )
                                        }
                                }
                            }
                        }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                BoxWithConstraints {
                    val spacing = 16.dp
                    val columns = when {
                        maxWidth >= 720.dp -> 3
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
                                    .clickable { showSalaryConfigDialog = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ui_button),
                                    contentDescription = null,
                                    modifier = Modifier.matchParentSize(),
                                    contentScale = ContentScale.FillBounds
                                )
                                Text(
                                    text = "配置年俸",
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
                                    .clickable { showSaveSlotDialog = true },
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

                    @Composable
                    fun Item3() {
                        Column(modifier = itemModifier) {
                            Text(
                                text = "巡视楼设置",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Text(
                                    text = "巡视楼弹出战斗结算界面",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                CircularCheckbox(
                                    checked = gameData.patrolBattleResultPopup,
                                    onToggle = {
                                        viewModel.setPatrolBattleResultPopup(
                                            !gameData.patrolBattleResultPopup
                                        )
                                    }
                                )
                            }
                        }
                    }

                    @Composable
                    fun Item4() {
                        Column(modifier = itemModifier) {
                            Text(
                                text = "灵石补差价设置",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Text(
                                    text = "自动售卖中品灵石补差价",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                CircularCheckbox(
                                    checked = gameData.autoSellMidGradeForPurchase,
                                    onToggle = {
                                        viewModel.setAutoSellMidGradeForPurchase(
                                            !gameData.autoSellMidGradeForPurchase
                                        )
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Text(
                                    text = "自动售卖上品灵石补差价",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                CircularCheckbox(
                                    checked = gameData.autoSellHighGradeForPurchase,
                                    onToggle = {
                                        viewModel.setAutoSellHighGradeForPurchase(
                                            !gameData.autoSellHighGradeForPurchase
                                        )
                                    }
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
                            Item3()
                            Item4()

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
                            Item3()
                            Item4()

                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(ButtonSizes.StandardWidth)
                        .height(ButtonSizes.StandardHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showOtherSettingsDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ui_button),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.FillBounds
                    )
                    Text(
                        text = "其他设置",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(ButtonSizes.StandardWidth)
                            .height(ButtonSizes.StandardHeight)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { showResetDisciplesConfirmDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ui_button),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        Text(
                            text = "重置状态",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(ButtonSizes.StandardWidth)
                            .height(ButtonSizes.StandardHeight)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { showRestartConfirmDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ui_button),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        Text(
                            text = "重新开始",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(ButtonSizes.StandardWidth)
                            .height(ButtonSizes.StandardHeight)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { showExitConfirmDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ui_button),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        Text(
                            text = "退出游戏",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                    }
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
        Dialog(
            onDismissRequest = { showOtherSettingsDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            // 隐藏 Dialog Window 的系统状态栏/导航栏
            DialogSystemBarGuard()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showOtherSettingsDialog = false }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.83f)
                        .fillMaxHeight(0.78f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = com.xianxia.sect.feature.game.R.drawable.bg_horizontal),
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "其他设置",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            CloseButton(onClick = { showOtherSettingsDialog = false })
                        }
                        Spacer(modifier = Modifier.height(16.dp))

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
        }
    }

    if (showChangelogDialog) {
        ChangelogDialog(onDismiss = { showChangelogDialog = false })
    }

    if (showSalaryConfigDialog) {
        Dialog(
            onDismissRequest = { showSalaryConfigDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            // 隐藏 Dialog Window 的系统状态栏/导航栏
            DialogSystemBarGuard()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { showSalaryConfigDialog = false }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.83f)
                    .fillMaxHeight(0.78f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Image(
                    painter = painterResource(id = com.xianxia.sect.feature.game.R.drawable.bg_horizontal),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "年俸设置",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        CloseButton(onClick = { showSalaryConfigDialog = false })
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
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
    var saveCompleted by remember { mutableStateOf(false) }
    var loadCompleted by remember { mutableStateOf(false) }

    // 打开对话框时，检测 isSaving/isLoading 是否卡住超过阈值并自动恢复
    LaunchedEffect(Unit) {
        delay(1000) // 给正常操作 1 秒宽限期
        // 重新读取最新状态（避免竞态条件）
        val currentState = saveLoadViewModel.saveLoadState.value
        if (currentState.isSaving || currentState.isLoading) {
            saveLoadViewModel.cancelSaveLoad()
        }
    }

    val selectedSlotInfo = remember(saveSlots, selectedSlot) {
        saveSlots.find { it.slot == selectedSlot }
    }
    val isAutoSaveSlot = selectedSlotInfo?.isAutoSave == true

    LaunchedEffect(isBusy) {
        if (!isBusy && saveCompleted) {
            saveCompleted = false
            onDismiss()
        }
        if (!isBusy && loadCompleted) {
            loadCompleted = false
            onDismiss()
        }
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // 隐藏 Dialog Window 的系统状态栏/导航栏
        DialogSystemBarGuard()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (isBusy) saveLoadViewModel.cancelSaveLoad()
                    onDismiss()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = com.xianxia.sect.feature.game.R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
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
                        CloseButton(onClick = {
                            if (isBusy) {
                                saveLoadViewModel.cancelSaveLoad()
                            }
                            onDismiss()
                        })
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
                    items(saveSlots, key = { it.slot }, contentType = { "save_slot" }) { slot ->
                        SaveSlotCard(
                            slot = slot,
                            isSelected = selectedSlot == slot.slot,
                            onClick = { selectedSlot = slot.slot }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val saveEnabled = selectedSlot != null && !isBusy && !isAutoSaveSlot
                    Box(
                        modifier = Modifier
                            .width(ButtonSizes.StandardWidth)
                            .height(ButtonSizes.StandardHeight)
                            .alpha(if (saveEnabled) 1f else 0.45f)
                            .clip(RoundedCornerShape(4.dp))
                            .then(
                                if (saveEnabled) {
                                    Modifier.clickable {
                                        saveCompleted = true
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
                        if (isSaving && pendingSlot == selectedSlot) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black
                            )
                        } else {
                            Text(
                                text = if (isAutoSaveSlot) "自动存档不可保存" else "保存",
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                        }
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
                                        saveSlots.find { it.slot == selectedSlot }?.let { slot ->
                                            loadCompleted = true
                                            saveLoadViewModel.loadGame(slot)
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
                        if (isLoading && pendingSlot == selectedSlot) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black
                            )
                        } else {
                            Text(
                                text = "读取",
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                        }
                    }
                }
            }
        }
    }
    }
}  // SaveSlotDialog

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
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            // 隐藏 Dialog Window 的系统状态栏/导航栏
            DialogSystemBarGuard()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.83f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = com.xianxia.sect.feature.game.R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "更新日志",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    CloseButton(onClick = onDismiss)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 450.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
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
    }
}
}