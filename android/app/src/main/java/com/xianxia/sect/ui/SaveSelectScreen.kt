package com.xianxia.sect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.xianxia.sect.core.util.InputValidator
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.taptap.TapCloudSaveManager
import com.xianxia.sect.ui.components.GameBackground
import com.xianxia.sect.ui.components.InlineStandardPromptDialog
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.theme.GameColors
import java.text.SimpleDateFormat
import java.util.*
import com.xianxia.sect.ui.model.SaveSelectMode

private data class SlotStyle(
    val border: Color,
    val background: Color,
    val icon: Color
)

private val slotStyles = mapOf(
    "empty" to SlotStyle(Color(0xFFDDDDDD), GameColors.CardBackground, Color(0xFFCCCCCC)),
    "filled" to SlotStyle(Color(0xFF4A90E2), Color(0xFFF0F7FF), Color(0xFF4A90E2))
)

private fun SaveSlot.resolveStyle(): SlotStyle {
    val key = if (isEmpty) "empty" else "filled"
    return slotStyles.getValue(key)
}

@Composable
fun SaveSelectScreen(
    mode: SaveSelectMode,
    saveSlots: List<SaveSlot>,
    onNewGame: (Int, String) -> Unit,
    onLoadSlot: (Int) -> Unit,
    onDeleteSlot: (Int) -> Unit,
    onBack: () -> Unit,
    cloudSaveInfo: TapCloudSaveManager.CloudSaveInfo? = null,
    onCloudSaveLoad: () -> Unit = {}
) {
    var showOverwriteConfirm by remember { mutableStateOf<Int?>(null) }
    var showSectNameDialog by remember { mutableStateOf<Int?>(null) }
    var sectNameInput by remember { mutableStateOf("") }
    var sectNameError by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<Int?>(null) }
    var showCloudSaveInfo by remember { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale
    val dateFormat = remember(locale) { SimpleDateFormat("yyyy-MM-dd HH:mm", locale) }

    GameBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp)
        ) {
            // 返回 + 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "< 返回",
                    fontSize = 14.sp,
                    color = GameColors.SpiritBlue,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onBack() }
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (mode == SaveSelectMode.NEW_GAME) "选择新游戏存档" else "选择读取存档",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.weight(1f))
                // 占位，保持标题居中
                Spacer(modifier = Modifier.width(1.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (saveSlots.isEmpty()) {
                    Text(
                        text = "暂无存档数据",
                        fontSize = 16.sp,
                        color = Color.Black,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    saveSlots.forEach { slot ->
                        SaveSlotCard(
                            slot = slot,
                            mode = mode,
                            dateFormat = dateFormat,
                            cloudSaveInfo = cloudSaveInfo,
                            onClick = {
                                if (slot.slot == 0) {
                                    if (mode == SaveSelectMode.LOAD_SAVE && cloudSaveInfo?.hasSaveData == true) {
                                        onCloudSaveLoad()
                                    } else {
                                        showCloudSaveInfo = true
                                    }
                                } else when (mode) {
                                    SaveSelectMode.NEW_GAME -> {
                                        if (slot.isEmpty) {
                                            showSectNameDialog = slot.slot
                                            sectNameInput = ""
                                            sectNameError = null
                                        } else {
                                            showOverwriteConfirm = slot.slot
                                        }
                                    }
                                    SaveSelectMode.LOAD_SAVE -> {
                                        if (slot.isEmpty) {
                                            showSectNameDialog = slot.slot
                                            sectNameInput = ""
                                            sectNameError = null
                                        } else {
                                            onLoadSlot(slot.slot)
                                        }
                                    }
                                }
                            },
                            onDeleteClick = { showDeleteConfirm = slot.slot }
                        )
                    }
                }
            }
        }
    }

    // ── 提示框：确认覆盖旧存档 ──
    if (showOverwriteConfirm != null) {
        StandardPromptDialog(
            onDismissRequest = { showOverwriteConfirm = null },
            title = "确认覆盖",
            text = "是否覆盖旧存档创建新游戏？",
            dismissLabel = "取消",
            onDismiss = { showOverwriteConfirm = null },
            confirmLabel = "创建",
            onConfirm = {
                val slot = showOverwriteConfirm ?: return@StandardPromptDialog
                showOverwriteConfirm = null
                // 确认覆盖后弹出宗门名输入
                showSectNameDialog = slot
                sectNameInput = ""
                sectNameError = null
            }
        )
    }

    // ── 提示框：确认删除存档 ──
    if (showDeleteConfirm != null) {
        StandardPromptDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = "确认删除",
            text = "确定要删除存档吗？此操作不可撤销。",
            dismissLabel = "取消",
            onDismiss = { showDeleteConfirm = null },
            confirmLabel = "删除",
            onConfirm = {
                val slot = showDeleteConfirm ?: return@StandardPromptDialog
                showDeleteConfirm = null
                onDeleteSlot(slot)
            }
        )
    }

    // ── 云存档信息对话框 ──
    if (showCloudSaveInfo) {
        StandardPromptDialog(
            onDismissRequest = { showCloudSaveInfo = false },
            title = "☁ 云存档",
            text = "云存档可以将存档上传至云端，在其他设备继续游戏。\n\n请进入游戏后，在「设置」中管理云存档的上传和下载。",
            confirmLabel = "知道了",
            onConfirm = { showCloudSaveInfo = false },
            dismissOnClickOutside = true
        )
    }

    // ── 宗门名输入对话框 ──
    if (showSectNameDialog != null) {
        val focusRequester = remember { FocusRequester() }
        InlineStandardPromptDialog(
            onDismissRequest = { showSectNameDialog = null },
            title = "创建宗门",
            confirmLabel = "创建",
            dismissLabel = "取消",
            dismissOnClickOutside = false,
            onDismiss = { showSectNameDialog = null },
            onConfirm = {
                if (sectNameError == null) {
                    val name = sectNameInput.trim().ifEmpty { "青云宗" }
                    showSectNameDialog?.let { onNewGame(it, name) }
                    showSectNameDialog = null
                }
            },
            content = {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(100) // 等待 Dialog 入场动画完成，兼容 ColorOS/FuntouchOS
                    focusRequester.requestFocus()
                }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = sectNameInput,
                    onValueChange = { newValue ->
                        if (newValue.length <= 6) {
                            sectNameInput = newValue
                            sectNameError = newValue.takeIf { it.isNotBlank() }
                                ?.let { InputValidator.validateSectName(it) }
                        }
                    },
                    placeholder = { Text("青云宗", color = Color(0xFF999999)) },
                    singleLine = true,
                    isError = sectNameError != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                Text(
                    text = sectNameError ?: "${sectNameInput.length}/6",
                    fontSize = 11.sp,
                    color = if (sectNameError != null)
                        Color(0xFFEF5350) else Color.Black,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = TextAlign.End
                )
            }
        )
    }
}

@Composable
fun SaveSlotCard(
    slot: SaveSlot,
    mode: SaveSelectMode,
    dateFormat: SimpleDateFormat,
    cloudSaveInfo: TapCloudSaveManager.CloudSaveInfo? = null,
    onClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null
) {
    val style = slot.resolveStyle()
    val canClick = true

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (slot.slot == 0) Color(0xFFF0F7FF) else style.background
            )
            .border(
                2.dp,
                if (slot.slot == 0) Color(0xFF4A90E2) else style.border,
                RoundedCornerShape(8.dp)
            )
            .then(
                if (canClick) Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (slot.slot == 0) Color(0xFF4A90E2) else style.icon
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (slot.slot == 0) "云" else slot.slot.toString(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                if (slot.slot == 0) {
                    // 云存档入口：显示云端存档信息（三行格式与本地存档一致）
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (cloudSaveInfo?.hasSaveData == true) {
                                    cloudSaveInfo?.sectName?.ifEmpty { "云存档" } ?: "云存档"
                                } else "云存档",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (cloudSaveInfo?.hasSaveData == true) Color.Black else Color(0xFF4A90E2)
                            )
                        }
                        if (cloudSaveInfo?.hasSaveData == true) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "第${cloudSaveInfo.gameYear}年 ${cloudSaveInfo.gameMonth}月",
                                fontSize = 13.sp,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "弟子: ${cloudSaveInfo.discipleCount}  灵石: ${cloudSaveInfo.spiritStones}",
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                            if (cloudSaveInfo.lastModifiedTime > 0) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "上次同步: ${formatTime(cloudSaveInfo.lastModifiedTime)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF999999)
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "暂无云存档数据",
                                fontSize = 13.sp,
                                color = Color(0xFF999999)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (mode == SaveSelectMode.LOAD_SAVE) "点击从云端下载存档" else "进入游戏后上传",
                                fontSize = 11.sp,
                                color = Color(0xFFBBBBBB)
                            )
                        }
                    }
                } else if (!slot.isEmpty) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = slot.displayName.ifEmpty { slot.sectName.ifEmpty { "存档 ${slot.slot}" } },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "第${slot.gameYear}年 ${slot.gameMonth}月",
                            fontSize = 13.sp,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "弟子: ${slot.discipleCount}  灵石: ${slot.spiritStones}",
                            fontSize = 12.sp,
                            color = Color.Black
                        )
                        if (slot.timestamp > 0) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = dateFormat.format(Date(slot.timestamp)),
                                fontSize = 11.sp,
                                color = Color.Black
                            )
                        }
                    }
                } else {
                    Text(
                        text = "空槽位 - 点击创建新游戏",
                        fontSize = 16.sp,
                        color = Color.Black
                    )
                }
            }

            // 删除按钮：仅非空存档显示
            if (!slot.isEmpty && onDeleteClick != null) {
                Text(
                    text = "✕",
                    fontSize = 16.sp,
                    color = Color(0xFFE53935),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onDeleteClick() }
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    return sdf.format(Date(timestamp))
}
