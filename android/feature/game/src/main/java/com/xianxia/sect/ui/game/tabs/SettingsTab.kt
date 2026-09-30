@file:Suppress("TooManyFunctions") // 私有辅助函数集中在本文件
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.data.ChangelogData
import com.xianxia.sect.data.ChangelogEntry
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.RewardSelectedItem
import com.xianxia.sect.core.engine.PerformanceMode
import com.xianxia.sect.core.render.ClarityMode
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.cloud.CloudSaveEntry
import com.xianxia.sect.ui.components.CircularCheckbox
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.TextInputDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.dialogs.RewardItem
import com.xianxia.sect.ui.game.dialogs.SalaryRealmCard
import com.xianxia.sect.ui.game.SaveLoadState
import com.xianxia.sect.ui.game.SaveLoadViewModel
import com.xianxia.sect.ui.game.cancelSaveLoad
import com.xianxia.sect.ui.game.checkCloudSave
import com.xianxia.sect.ui.game.deleteSlot
import com.xianxia.sect.ui.game.loadCloudSlot
import com.xianxia.sect.ui.game.queryCloudSlotEntries
import com.xianxia.sect.ui.game.saveGame
import com.xianxia.sect.ui.game.saveload.CloudSlotEntryCard
import com.xianxia.sect.ui.game.saveload.MigrationActions
import com.xianxia.sect.ui.game.saveload.MigrationUiState
import com.xianxia.sect.ui.game.saveload.SaveMigrationCard
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors
import java.text.SimpleDateFormat
import java.util.Locale
import com.xianxia.sect.ui.game.cancelSaveLoad
import com.xianxia.sect.ui.game.checkCloudSave
import com.xianxia.sect.ui.game.saveGame
import com.xianxia.sect.core.engine.BuildConfig
import com.xianxia.sect.core.memory.MemoryBudgetView

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
                viewModel.redeem.redeemCode(codeInput.trim())
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

/** 兑换码输入区：统一文本输入对话框（独立平台 Dialog 窗口） */
@Composable
private fun RedeemCodeInput(
    codeInput: String,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    TextInputDialog(
        onDismissRequest = onDismiss,
        title = "兑换码",
        value = codeInput,
        onValueChange = onCodeChange,
        label = "请输入兑换码",
        confirmLabel = "兑换",
        dismissLabel = "取消",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

/** 兑换奖励项映射：奖励明细 → 弹窗展示项（纯函数） */
@Suppress("TooGenericExceptionCaught") // 防御兜底: 探针/可选增强失败即降级默认值, 异常类型不可枚举
private fun redeemRewardToItem(reward: RewardSelectedItem): RewardItem {
    val rarityColor = try {
        Color(android.graphics.Color.parseColor(GameConfig.Rarity.getColor(reward.rarity)))
    } catch (ignored: Exception) { Color.Black }
    return RewardItem(
        name = when (reward.type) {
            "spiritStones" -> "${reward.quantity}灵石"
            "fragment" -> "${reward.name}碎片${reward.quantity}"
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
    val gameData by viewModel.gameData.collectAsStateWithLifecycle()

    var showSaveSlotDialog by remember { mutableStateOf(false) }
    var showRestartConfirmDialog by remember { mutableStateOf(false) }
    var showResetDisciplesConfirmDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showOtherSettingsDialog by remember { mutableStateOf(false) }
    var showSalaryConfigDialog by remember { mutableStateOf(false) }

    SettingsTabContent(
        gameData = gameData,
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
            onRedeemCodeClick = { showOtherSettingsDialog = false; viewModel.redeem.openRedeemCodeDialog() },
            onChangelogClick = { showOtherSettingsDialog = false; showChangelogDialog = true })
    }

    if (showChangelogDialog) ChangelogDialog(onDismiss = { showChangelogDialog = false })

    if (showSalaryConfigDialog) {
        SalaryConfigDialog(gameData = gameData, viewModel = viewModel,
            onDismiss = { showSalaryConfigDialog = false })
    }
}

/** 设置页触发动作集合：六个入口按钮 → 各自对话框打开回调 */
private data class SettingsTabActions(
    val onSalaryClick: () -> Unit = {},
    val onSaveSlotClick: () -> Unit = {},
    val onOtherSettingsClick: () -> Unit = {},
    val onResetDisciplesClick: () -> Unit = {},
    val onRestartClick: () -> Unit = {},
    val onExitClick: () -> Unit = {}
)

/** 设置页主体列表：暂停/性能模式/音频/触发按钮/操作行 */
@Composable
private fun SettingsTabContent(
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
                item { PauseControlItem(saveLoadViewModel = saveLoadViewModel) }

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
                        onSoundToggle = { viewModel.settings.setSoundEnabled(!gameData.soundEnabled) })
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

/** 重新开始确认框 */
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

/** 重置弟子状态确认框 */
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
            text = "确定要重置所有弟子状态吗？\n探索/战斗队伍将解散，工作/职务槽位将清空。",
            confirmLabel = "确认",
            onConfirm = onConfirm,
            dismissLabel = "取消",
            onDismiss = onDismiss
        )
    }
}

/** 退出游戏确认框 */
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

/** 其他设置弹窗：兑换码/更新日志入口 + 个性化广告开关 */
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
                onToggle = { viewModel.ads.setPersonalizedAdsEnabled(!personalizedAdsEnabled) }
            )

            // P4.4/D3：Debug 构建内存分类可观测（GPU/纹理只读快照，Release 不引用）
            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "内存 Debug",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                val memLines = remember { MemoryBudgetView.formatDebugLines() }
                memLines.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 10.sp,
                        color = Color.Black,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 其他设置功能入口行：兑换码 + 更新日志两个按钮 */
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

/** 个性化广告开关行：复选框 + 说明文案 */
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

/** 年俸设置弹窗：境界 → 年薪配置列表 */
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
                    onEnabledChange = { viewModel.settings.setYearlySalaryEnabled(realm, it) }
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

/** 暂停/继续控制（游戏以单一时速推进），从 SettingsTab 主体抽出的独立 item */
@Composable
private fun PauseControlItem(
    saveLoadViewModel: SaveLoadViewModel
) {
    Text(
        text = "暂停",
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
    }
}

/** 暂停/继续圆形按钮 */
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

@Composable
@Suppress("UnusedParameter") // viewModel: 弹窗/组件统一签名约定：保持调用点参数面一致并预留子组件扩展消费
internal fun SaveSlotDialog(
    viewModel: GameViewModel,
    saveLoadViewModel: SaveLoadViewModel,
    onDismiss: () -> Unit
) {
    val saveSlots by saveLoadViewModel.saveSlots.collectAsStateWithLifecycle()
    val saveLoadState by saveLoadViewModel.saveLoadState.collectAsStateWithLifecycle()
    // SR-6 存量迁移引导：迁移卡常驻本弹窗（可见性判据 MigrationUiState.visible）
    val migration by saveLoadViewModel.migrationCoordinator.state.collectAsStateWithLifecycle()
    val isBusy = saveLoadState.isBusy
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var deleteTarget by remember { mutableStateOf<Int?>(null) }
    var cloudEntries by remember { mutableStateOf<List<CloudSaveEntry>>(emptyList()) }
    // ── 转圈动画状态（最少显示 1 秒；持状态对象传入最短显示时长 Effect） ──
    val showAnimation = remember { mutableStateOf(false) }
    val animationStartTime = remember { mutableLongStateOf(0L) }
    val operationLabel = remember { mutableStateOf("") }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    SaveSlotOpenRefreshEffect(saveLoadViewModel, onCloudEntries = { cloudEntries = it })
    SaveSlotBusyMinDurationEffect(saveLoadState, showAnimation, animationStartTime, operationLabel)
    SaveLoadWatchdogEffect(saveLoadViewModel = saveLoadViewModel)

    val migrationActions = buildMigrationActions(saveLoadViewModel, rememberCoroutineScope())

    SaveSlotDialogContainer(saveLoadViewModel, isBusy, onDismiss) {
        if (showAnimation.value) {
            SaveSlotBusyIndicator(operationLabel = operationLabel.value)
        }
        if (!showAnimation.value) {
            SaveSlotEditableContent(
                state = SaveSlotListState(
                    saveSlots, migration, migrationActions, cloudEntries, dateFormat, selectedSlot
                ),
                isBusy = isBusy,
                onSlotClick = { selectedSlot = it },
                onDeleteClick = { deleteTarget = it },
                onCloudSlotLoad = { saveLoadViewModel.loadCloudSlot(it) },
                onSubmit = SaveSlotSubmitActions(
                    onSave = { saveLoadViewModel.saveGame(it.toString()) },
                    onLoad = { saveLoadViewModel.loadGameFromSlot(it) }
                )
            )
        }
    }

    DeleteSlotConfirmDialog(
        target = deleteTarget,
        onDismiss = { deleteTarget = null },
        onConfirm = {
            saveLoadViewModel.deleteSlot(it)
            deleteTarget = null
        }
    )
}

/** 弹窗容器：标题/取消动作 + 全屏内容列（忙碌中点外/取消 = 中止当前保存读取） */
@Composable
private fun SaveSlotDialogContainer(
    saveLoadViewModel: SaveLoadViewModel,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
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
            content()
        }
    }
}

/** 列表区 + 操作按钮行（转圈动画未显示时的可编辑内容） */
@Composable
private fun ColumnScope.SaveSlotEditableContent(
    state: SaveSlotListState,
    isBusy: Boolean,
    onSlotClick: (Int) -> Unit,
    onDeleteClick: (Int) -> Unit,
    onCloudSlotLoad: (Int) -> Unit,
    onSubmit: SaveSlotSubmitActions
) {
    SaveSlotContentList(
        state = state,
        onSlotClick = onSlotClick,
        onDeleteClick = onDeleteClick,
        onCloudSlotLoad = onCloudSlotLoad
    )
    SaveSlotActionRow(
        selectedSlot = state.selectedSlot,
        saveSlots = state.saveSlots,
        isBusy = isBusy,
        onSave = onSubmit.onSave,
        onLoad = onSubmit.onLoad
    )
}

/** 保存/读取提交动作（分组传参，控制 Composable 形参预算） */
private data class SaveSlotSubmitActions(
    val onSave: (Int) -> Unit,
    val onLoad: (Int) -> Unit
)

/** 打开弹窗时的刷新面：云摘要（slot 0 显示真实数据）+ 迁移扫描（阶段 A 纯本地零云请求）
 *  + 云槽位列表（LEGACY 短路零查询）。 */
@Composable
private fun SaveSlotOpenRefreshEffect(
    saveLoadViewModel: SaveLoadViewModel,
    onCloudEntries: (List<CloudSaveEntry>) -> Unit
) {
    LaunchedEffect(Unit) {
        saveLoadViewModel.checkCloudSave()
        saveLoadViewModel.migrationCoordinator.scan()
        onCloudEntries(saveLoadViewModel.queryCloudSlotEntries())
    }
}

/** 保存/读取转圈动画的最短显示时长（1 秒）——忙碌即显，收尾不足 1 秒补足 */
@Composable
private fun SaveSlotBusyMinDurationEffect(
    saveLoadState: SaveLoadState,
    showAnimation: MutableState<Boolean>,
    animationStartTime: MutableState<Long>,
    operationLabel: MutableState<String>
) {
    LaunchedEffect(saveLoadState.isBusy) {
        if (saveLoadState.isBusy) {
            animationStartTime.value = System.currentTimeMillis()
            showAnimation.value = true
            operationLabel.value = if (saveLoadState.isSaving) "保存中..." else "读取中..."
        } else if (showAnimation.value) {
            val elapsed = System.currentTimeMillis() - animationStartTime.value
            if (elapsed < 1000) {
                delay(1000 - elapsed)
            }
            showAnimation.value = false
        }
    }
}

/** 迁移卡动作位：经弹窗协程驱动协调器（未登录时 start 内部自报服务不可达，不在此重复拦截） */
private fun buildMigrationActions(
    saveLoadViewModel: SaveLoadViewModel,
    scope: CoroutineScope
): MigrationActions = MigrationActions(
    onStart = { scope.launch { saveLoadViewModel.migrationCoordinator.start() } },
    onDecision = { slot, keepLocal ->
        scope.launch { saveLoadViewModel.migrationCoordinator.resolveConflict(slot, keepLocal) }
    },
    onLegacyDownload = { targetSlot ->
        scope.launch { saveLoadViewModel.migrationCoordinator.migrateLegacyArchive(targetSlot) }
    },
    onEnableCloud = { saveLoadViewModel.migrationCoordinator.confirmEnableCloud() }
)

/** 删除存档确认（破坏性操作显式确认；文案与既有删除确认一致） */
@Composable
private fun DeleteSlotConfirmDialog(
    target: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    if (target == null) return
    StandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "确认删除",
        text = "确定要删除存档吗？此操作不可撤销。",
        dismissLabel = "取消",
        onDismiss = onDismiss,
        confirmLabel = "删除",
        onConfirm = { onConfirm(target) }
    )
}

/** 存档列表区渲染入参（分组传参，控制 Composable 形参预算） */
private data class SaveSlotListState(
    val saveSlots: List<SaveSlot>,
    val migration: MigrationUiState,
    val migrationActions: MigrationActions,
    val cloudEntries: List<CloudSaveEntry>,
    val dateFormat: SimpleDateFormat,
    val selectedSlot: Int?
)

/** 对话框内容区：迁移引导卡 + 槽位列表 + 云槽位区 + 操作按钮 */
@Composable
private fun ColumnScope.SaveSlotContentList(
    state: SaveSlotListState,
    onSlotClick: (Int) -> Unit,
    onDeleteClick: (Int) -> Unit,
    onCloudSlotLoad: (Int) -> Unit
) {
    val emptyLocalSlots = state.saveSlots
        .filter { it.isEmpty && it.slot != 0 }
        .map { it.slot }
    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // SR-6 迁移引导卡：有可迁内容即常驻列表首位（收口即消失，判据 MigrationUiState.visible）
        if (state.migration.visible) {
            item(key = "migration_card", contentType = { "migration_card" }) {
                SaveMigrationCard(
                    migration = state.migration,
                    emptyLocalSlots = emptyLocalSlots,
                    actions = state.migrationActions
                )
            }
        }
        items(state.saveSlots, key = { it.slot }, contentType = { "save_slot" }) { slot ->
            SaveSlotCard(
                slot = slot,
                isSelected = state.selectedSlot == slot.slot,
                onClick = { onSlotClick(slot.slot) },
                onDeleteClick = if (slot.slot != 0 && !slot.isEmpty) {
                    { onDeleteClick(slot.slot) }
                } else {
                    null
                }
            )
        }
        // SR-3 云端槽位存档区（已登录 + 非 LEGACY 时非空；点击下载到本机槽并加载）
        if (state.cloudEntries.isNotEmpty()) {
            item(key = "cloud_slot_header", contentType = { "cloud_slot_header" }) {
                Text(
                    text = "云端槽位存档",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
            items(state.cloudEntries, key = { "cloud_${it.slot}" }, contentType = { "cloud_slot" }) { entry ->
                CloudSlotEntryCard(
                    entry = entry,
                    dateFormat = state.dateFormat,
                    onClick = { onCloudSlotLoad(entry.slot) }
                )
            }
        }
    }
}

/** 打开对话框时，检测 isSaving/isLoading 是否卡住超过阈值并自动恢复 */
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

/** 保存/读取中转圈指示 */
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

/** 保存/读取操作按钮行 */
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
        val loadEnabled = selectedSlot != null && saveSlots.find { it
            .slot == selectedSlot }?.isEmpty == false && !isBusy
        SaveSlotActionButton(
            label = "读取",
            enabled = loadEnabled,
            onClick = { selectedSlot?.let(onLoad) }
        )
    }
}

/** 存档操作按钮：标准尺寸 + 背景图 + 可用态置灰 */
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

/** 标题栏取消动作：忙碌中显示"取消"按钮 */
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
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = slot.displayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 删除入口：本地非空存档显示（slot 0 云会话不在此删除）
                    if (onDeleteClick != null) {
                        Text(
                            text = "✕",
                            fontSize = 14.sp,
                            color = Color(0xFFE53935),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onDeleteClick() }
                                .padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                        )
                    }
                    Text(
                        text = if (slot.isEmpty) "空" else slot.saveTime,
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                }
            }
            SaveSlotDetails(slot = slot)
        }
    }
}

/** 存档详情：宗门/时间 + 弟子数/灵石（非空槽位） */
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

/** 单条更新日志卡片：版本/日期 + 变更明细 */
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
