@file:Suppress("TooManyFunctions") // 拆分聚合:提取的私有辅助函数集中在原文件,文件级复杂度为拆分代价
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.data.ChangelogData
import com.xianxia.sect.data.ChangelogEntry
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.engine.PerformanceMode
import com.xianxia.sect.core.render.ClarityMode
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.ui.components.CircularCheckbox
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.RewardItem
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
    var rewardItems by remember { mutableStateOf<List<RewardItem>>(emptyList()) }

    LaunchedEffect(redeemResult) {
        redeemResult?.let { result ->
            if (result.success && result.rewards.isNotEmpty()) {
                rewardItems = result.rewards.map { redeemRewardToItem(it) }
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

    RedeemCodeInput(
        codeInput = codeInput,
        onCodeChange = { codeInput = it.uppercase(Locale.getDefault()) },
        onConfirm = {
            if (codeInput.isNotBlank()) {
                viewModel.redeemCode(codeInput.trim())
            }
        },
        onDismiss = onDismiss
    )

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

/** 兑换码输入区（RedeemCodeDialog 拆分）：内联输入框 + 兑换/取消动作 */
@Composable
private fun RedeemCodeInput(
    codeInput: String,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "兑换码",
        confirmLabel = "兑换",
        onConfirm = onConfirm,
        dismissLabel = "取消",
        onDismiss = onDismiss,
        // 含输入框：挂载期间冻结宿主窗口系统栏操作（荣耀X70键盘频闪根治）
        freezeSystemBars = true
    ) {
        OutlinedTextField(
            value = codeInput,
            onValueChange = onCodeChange,
            label = { Text("请输入兑换码", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp)
        )
    }
}

/** 兑换奖励项映射（RedeemCodeDialog 拆分）：奖励明细 → 弹窗展示项（纯函数） */
private fun redeemRewardToItem(reward: RewardSelectedItem): RewardItem {
    val rarityColor = try {
        Color(android.graphics.Color.parseColor(GameConfig.Rarity.getColor(reward.rarity)))
    } catch (e: Exception) { Color.Black }
    return RewardItem(
        name = when (reward.type) {
            "spiritStones" -> "${reward.quantity}灵石"
            "disciple" -> "弟子 ${reward.name}"
            else -> "${reward.name} ×${reward.quantity}"
        },
        rarityColor = rarityColor
    )
}
@Composable
internal fun SettingsTab(
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val timeSpeed by saveLoadViewModel.timeSpeed.collectAsStateWithLifecycle()
    val gameData by viewModel.gameData.collectAsStateWithLifecycle()

    var showSaveSlotDialog by remember { mutableStateOf(false) }
    var showRestartConfirmDialog by remember { mutableStateOf(false) }
    var showResetDisciplesConfirmDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showOtherSettingsDialog by remember { mutableStateOf(false) }
    var showSalaryConfigDialog by remember { mutableStateOf(false) }

    SettingsTabContent(
        timeSpeed = timeSpeed, gameData = gameData,
        viewModel = viewModel, saveLoadViewModel = saveLoadViewModel,
        actions = SettingsTabActions(
            onSalaryClick = { showSalaryConfigDialog = true }, onSaveSlotClick = { showSaveSlotDialog = true },
            onOtherSettingsClick = { showOtherSettingsDialog = true },
            onResetDisciplesClick = { showResetDisciplesConfirmDialog = true },
            onRestartClick = { showRestartConfirmDialog = true }, onExitClick = { showExitConfirmDialog = true })
    )

    if (showSaveSlotDialog) {
        SaveSlotDialog(viewModel = viewModel, saveLoadViewModel = saveLoadViewModel,
            onDismiss = { showSaveSlotDialog = false })
    }

    RestartConfirmDialog(visible = showRestartConfirmDialog, onDismiss = { showRestartConfirmDialog = false },
        onConfirm = {
            showRestartConfirmDialog = false
            onDismiss()
            saveLoadViewModel.restartGame()
        })
    ResetDisciplesConfirmDialog(visible = showResetDisciplesConfirmDialog,
        onDismiss = { showResetDisciplesConfirmDialog = false }, onConfirm = {
            showResetDisciplesConfirmDialog = false
            saveLoadViewModel.resetAllDisciplesStatus()
        })
    ExitConfirmDialog(visible = showExitConfirmDialog, onDismiss = { showExitConfirmDialog = false },
        onConfirm = {
            showExitConfirmDialog = false
            onLogout()
        })

    if (showOtherSettingsDialog) {
        OtherSettingsDialog(viewModel = viewModel, onDismiss = { showOtherSettingsDialog = false },
            onRedeemCodeClick = { showOtherSettingsDialog = false; viewModel.openRedeemCodeDialog() },
            onChangelogClick = { showOtherSettingsDialog = false; showChangelogDialog = true })
    }

    if (showChangelogDialog) ChangelogDialog(onDismiss = { showChangelogDialog = false })

    if (showSalaryConfigDialog) {
        SalaryConfigDialog(gameData = gameData, viewModel = viewModel,
            onDismiss = { showSalaryConfigDialog = false })
    }
}

/** 设置页触发动作集合（SettingsTab 拆分）：六个入口按钮 → 各自对话框打开回调 */
private data class SettingsTabActions(
    val onSalaryClick: () -> Unit = {},
    val onSaveSlotClick: () -> Unit = {},
    val onOtherSettingsClick: () -> Unit = {},
    val onResetDisciplesClick: () -> Unit = {},
    val onRestartClick: () -> Unit = {},
    val onExitClick: () -> Unit = {}
)

/** 设置页主体列表（SettingsTab 拆分）：时间流速/性能模式/音频/触发按钮/操作行 */
@Composable
private fun SettingsTabContent(
    timeSpeed: Int,
    gameData: GameData,
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    actions: SettingsTabActions
) {
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
                item { TimeSpeedControlItem(saveLoadViewModel = saveLoadViewModel, timeSpeed = timeSpeed) }

                item {
                    val performanceMode by viewModel.performanceMode.collectAsStateWithLifecycle()
                    PerformanceModeItem(performanceMode = performanceMode,
                        onModeSelected = viewModel::setPerformanceMode)
                }

                item {
                    val clarityMode by viewModel.clarityMode.collectAsStateWithLifecycle()
                    ClarityModeItem(clarityMode = clarityMode,
                        onModeSelected = viewModel::setClarityMode)
                }

                item {
                    AudioToggleItem(musicEnabled = gameData.musicEnabled, soundEnabled = gameData.soundEnabled,
                        onMusicToggle = { viewModel.setMusicEnabled(!gameData.musicEnabled) },
                        onSoundToggle = { viewModel.setSoundEnabled(!gameData.soundEnabled) })
                }

                item {
                    SettingsDialogButtonsItem(onSalaryClick = actions.onSalaryClick,
                        onSaveSlotClick = actions.onSaveSlotClick)
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingsActionButton(label = "其他设置", withSound = true) { actions.onOtherSettingsClick() }
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsActionButton(label = "重置状态", withSound = true) { actions.onResetDisciplesClick() }
                        SettingsActionButton(label = "重新开始", withSound = false) { actions.onRestartClick() }
                        SettingsActionButton(label = "退出游戏", withSound = false) { actions.onExitClick() }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "版本 ${com.xianxia.sect.core.GameConfig.Game.VERSION}",
                        fontSize = 10.sp, color = Color.Black,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** 重新开始确认框（SettingsTab 拆分） */
@Composable
private fun RestartConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (visible) {
        StandardPromptDialog(
            onDismissRequest = onDismiss,
            title = "确认重新开始",
            text = "确定要重新开始游戏吗？当前游戏进度将会丢失！",
            confirmLabel = "确认",
            onConfirm = onConfirm,
            dismissLabel = "取消",
            onDismiss = onDismiss
        )
    }
}

/** 重置弟子状态确认框（SettingsTab 拆分） */
@Composable
private fun ResetDisciplesConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (visible) {
        StandardPromptDialog(
            onDismissRequest = onDismiss,
            title = "确认重置弟子状态",
            text = "确定要重置所有弟子状态吗？\n探索/战斗队伍将解散，工作/职务槽位将清空，监牢弟子不受影响。",
            confirmLabel = "确认",
            onConfirm = onConfirm,
            dismissLabel = "取消",
            onDismiss = onDismiss
        )
    }
}

/** 退出游戏确认框（SettingsTab 拆分） */
@Composable
private fun ExitConfirmDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (visible) {
        StandardPromptDialog(
            onDismissRequest = onDismiss,
            title = "确认退出",
            text = "确定要退出游戏吗？未保存的进度将会丢失。",
            confirmLabel = "确认退出",
            onConfirm = onConfirm,
            dismissLabel = "取消",
            onDismiss = onDismiss
        )
    }
}

/** 其他设置弹窗（SettingsTab 拆分）：兑换码/更新日志入口 + 个性化广告开关 */
@Composable
private fun OtherSettingsDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit,
    onRedeemCodeClick: () -> Unit,
    onChangelogClick: () -> Unit
) {
    val personalizedAdsEnabled by viewModel.personalizedAdsEnabled.collectAsStateWithLifecycle()
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "其他设置",
        mode = DialogMode.Half,
        scrollableContent = true,
        backgroundRes = com.xianxia.sect.feature.game.R.drawable.bg_horizontal,
        dismissOnClickOutside = false
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            Spacer(modifier = Modifier.height(12.dp))

            OtherSettingsActionRow(
                onRedeemCodeClick = onRedeemCodeClick,
                onChangelogClick = onChangelogClick
            )

            // 个性化广告开关（TapADN 合规要求：App 内必须提供退出个性化广告的能力）
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "个性化广告",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(8.dp))
            PersonalizedAdsToggle(
                personalizedAdsEnabled = personalizedAdsEnabled,
                onToggle = { viewModel.setPersonalizedAdsEnabled(!personalizedAdsEnabled) }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 其他设置功能入口行（OtherSettingsDialog 拆分）：兑换码 + 更新日志两个按钮 */
@Composable
private fun OtherSettingsActionRow(
    onRedeemCodeClick: () -> Unit,
    onChangelogClick: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(ButtonSizes.StandardWidth)
                .height(ButtonSizes.StandardHeight)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onRedeemCodeClick),
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
                .clickable(onClick = onChangelogClick),
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
}

/** 个性化广告开关行（OtherSettingsDialog 拆分）：复选框 + 说明文案 */
@Composable
private fun PersonalizedAdsToggle(
    personalizedAdsEnabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularCheckbox(
            checked = personalizedAdsEnabled,
            onToggle = onToggle
        )
        Text(
            text = "关闭后广告将不再基于您的兴趣推送",
            fontSize = 10.sp,
            color = Color.Black
        )
    }
}

/** 年俸设置弹窗（SettingsTab 拆分）：境界 → 年薪配置列表 */
@Composable
private fun SalaryConfigDialog(
    gameData: GameData,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
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

/** 自选清晰度五档（极低/低/中/高/极高），样式对齐 PerformanceModeItem；5 档用 weight 等分防窄屏溢出 */
@Composable
private fun ClarityModeItem(
    clarityMode: ClarityMode,
    onModeSelected: (ClarityMode) -> Unit
) {
    Text(
        text = "自选清晰度",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ClarityMode.entries.forEach { mode ->
            val modeAlpha = if (clarityMode == mode) 1f else 0.5f
            Box(
                modifier = Modifier
                    .weight(1f)
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
                    fontSize = 11.sp,
                    color = Color.Black
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = clarityMode.description,
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
        PauseToggleButton(
            isPaused = isPaused,
            pauseAlpha = pauseAlpha,
            btnSize = btnSize,
            onClick = { saveLoadViewModel.togglePause() }
        )

        listOf(1, 2).forEach { speed ->
            val speedAlpha = if (timeSpeed == speed && !isPaused) 1f else 0.5f
            SpeedToggleButton(
                speed = speed,
                speedAlpha = speedAlpha,
                onClick = { saveLoadViewModel.setTimeSpeed(speed) }
            )
        }
    }
}

/** 暂停/继续圆形按钮（TimeSpeedControlItem 拆分） */
@Composable
private fun PauseToggleButton(
    isPaused: Boolean,
    pauseAlpha: Float,
    btnSize: Dp,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(btnSize)
            .alpha(pauseAlpha)
            .clip(CircleShape)
            .clickable { onClick() },
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
}

/** 倍速切换按钮（TimeSpeedControlItem 拆分） */
@Composable
private fun SpeedToggleButton(
    speed: Int,
    speedAlpha: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(ButtonSizes.StandardWidth)
            .height(ButtonSizes.StandardHeight)
            .alpha(speedAlpha)
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() },
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

@Composable
internal fun SaveSlotDialog(
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    onDismiss: () -> Unit
) {
    val saveSlots by saveLoadViewModel.saveSlots.collectAsStateWithLifecycle()
    val saveLoadState by saveLoadViewModel.saveLoadState.collectAsStateWithLifecycle()
    val isBusy = saveLoadState.isBusy
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    // ── 转圈动画状态（最少显示 1 秒） ──
    var showAnimation by remember { mutableStateOf(false) }
    var animationStartTime by remember { mutableLongStateOf(0L) }
    var operationLabel by remember { mutableStateOf("") }

    // 打开对话框即刷新云存档摘要，slot 0（云存档槽位）才能显示真实数据
    //（而非 StorageEngine 的硬编码全 0 占位）
    LaunchedEffect(Unit) {
        saveLoadViewModel.checkCloudSave()
    }

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
    SaveLoadWatchdogEffect(saveLoadViewModel = saveLoadViewModel)

    UnifiedGameDialog(
        onDismissRequest = {
            if (isBusy) saveLoadViewModel.cancelSaveLoad()
            onDismiss()
        },
        title = "存档信息",
        mode = DialogMode.Large,
        dismissOnClickOutside = false,
        headerActions = {
            SaveSlotCancelAction(isBusy = isBusy, onCancel = {
                saveLoadViewModel.cancelSaveLoad()
                onDismiss()
            })
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showAnimation) {
                SaveSlotBusyIndicator(operationLabel = operationLabel)
            }

            if (!showAnimation) {
                SaveSlotDialogContent(
                    saveSlots = saveSlots,
                    selectedSlot = selectedSlot,
                    isBusy = isBusy,
                    onSlotClick = { selectedSlot = it },
                    onSave = { saveLoadViewModel.saveGame(it.toString()) },
                    onLoad = { saveLoadViewModel.loadGameFromSlot(it) }
                )
            }
        }
    }
}

/** 打开对话框时，检测 isSaving/isLoading 是否卡住超过阈值并自动恢复（SaveSlotDialog 拆分） */
@Composable
private fun SaveLoadWatchdogEffect(saveLoadViewModel: SaveLoadViewModel) {
    LaunchedEffect(Unit) {
        delay(30000) // 给云存档网络操作 30 秒宽限期
        val currentState = saveLoadViewModel.saveLoadState.value
        if (currentState.isSaving || currentState.isLoading) {
            saveLoadViewModel.cancelSaveLoad()
        }
    }
}

/** 对话框内容区（SaveSlotDialog 拆分）：转圈动画 + 槽位列表 + 操作按钮 */
@Composable
private fun ColumnScope.SaveSlotDialogContent(
    saveSlots: List<SaveSlot>,
    selectedSlot: Int?,
    isBusy: Boolean,
    onSlotClick: (Int) -> Unit,
    onSave: (Int) -> Unit,
    onLoad: (Int) -> Unit
) {
    SaveSlotList(
        saveSlots = saveSlots,
        selectedSlot = selectedSlot,
        onSlotClick = onSlotClick
    )
    SaveSlotActionRow(
        selectedSlot = selectedSlot,
        saveSlots = saveSlots,
        isBusy = isBusy,
        onSave = onSave,
        onLoad = onLoad
    )
}

/** 保存/读取中转圈指示（SaveSlotDialog 拆分） */
@Composable
private fun ColumnScope.SaveSlotBusyIndicator(operationLabel: String) {
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

/** 存档槽位列表（SaveSlotDialog 拆分） */
@Composable
private fun ColumnScope.SaveSlotList(
    saveSlots: List<SaveSlot>,
    selectedSlot: Int?,
    onSlotClick: (Int) -> Unit
) {
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
                onClick = { onSlotClick(slot.slot) }
            )
        }
    }
}

/** 保存/读取操作按钮行（SaveSlotDialog 拆分） */
@Composable
private fun SaveSlotActionRow(
    selectedSlot: Int?,
    saveSlots: List<SaveSlot>,
    isBusy: Boolean,
    onSave: (Int) -> Unit,
    onLoad: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val saveEnabled = selectedSlot != null && !isBusy
        SaveSlotActionButton(
            label = "保存",
            enabled = saveEnabled,
            onClick = { selectedSlot?.let(onSave) }
        )
        val loadEnabled = selectedSlot != null && saveSlots.find { it.slot == selectedSlot }?.isEmpty == false && !isBusy
        SaveSlotActionButton(
            label = "读取",
            enabled = loadEnabled,
            onClick = { selectedSlot?.let(onLoad) }
        )
    }
}

/** 存档操作按钮（SaveSlotDialog 拆分）：标准尺寸 + 背景图 + 可用态置灰 */
@Composable
private fun SaveSlotActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(ButtonSizes.StandardWidth)
            .height(ButtonSizes.StandardHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
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
            text = label,
            fontSize = 12.sp,
            color = Color.Black
        )
    }
}

/** 标题栏取消动作（SaveSlotDialog 拆分）：忙碌中显示"取消"按钮 */
@Composable
private fun SaveSlotCancelAction(isBusy: Boolean, onCancel: () -> Unit) {
    if (isBusy) {
        GameButton(
            text = "取消",
            onClick = onCancel
        )
    }
}

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
            SaveSlotDetails(slot = slot)
        }
    }
}

/** 存档详情（SaveSlotCard 拆分）：宗门/时间 + 弟子数/灵石（非空槽位） */
@Composable
private fun ColumnScope.SaveSlotDetails(slot: SaveSlot) {
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
                ChangelogEntryCard(entry = entry)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 单条更新日志卡片（ChangelogDialog 拆分）：版本/日期 + 变更明细 */
@Composable
private fun ChangelogEntryCard(entry: ChangelogEntry) {
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
