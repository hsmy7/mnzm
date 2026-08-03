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
import androidx.compose.ui.platform.LocalView
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
    "empty" to SlotStyle(Color(0xFFDDDDDD), GameColors.CardBackground, GameColors.DividerGray),
    "filled" to SlotStyle(Color(0xFF4A90E2), Color(0xFFF0F7FF), Color(0xFF4A90E2))
)

private fun SaveSlot.resolveStyle(): SlotStyle {
    val key = if (isEmpty) "empty" else "filled"
    return slotStyles.getValue(key)
}

@Composable
@Suppress("LongParameterList", "LongMethod") // 屏幕级入口：状态+回调+对话框编排样板，拆分后仍超阈值
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
        SaveSelectContent(
            mode = mode,
            saveSlots = saveSlots,
            dateFormat = dateFormat,
            cloudSaveInfo = cloudSaveInfo,
            onBack = onBack,
            onSlotClick = { slot ->
                when {
                    slot.slot == 0 -> {
                        if (mode == SaveSelectMode.LOAD_SAVE &&
                            cloudSaveInfo?.hasSaveData == true
                        ) {
                            onCloudSaveLoad()
                        } else {
                            showCloudSaveInfo = true
                        }
                    }
                    mode == SaveSelectMode.NEW_GAME && slot.isEmpty -> {
                        showSectNameDialog = slot.slot
                        sectNameInput = ""
                        sectNameError = null
                    }
                    mode == SaveSelectMode.NEW_GAME -> showOverwriteConfirm = slot.slot
                    slot.isEmpty -> {
                        showSectNameDialog = slot.slot
                        sectNameInput = ""
                        sectNameError = null
                    }
                    else -> onLoadSlot(slot.slot)
                }
            },
            onDeleteClick = { showDeleteConfirm = it }
        )
    }

    SaveSelectDialogs(
        showOverwriteConfirm = showOverwriteConfirm,
        showDeleteConfirm = showDeleteConfirm,
        showCloudSaveInfo = showCloudSaveInfo,
        showSectNameDialog = showSectNameDialog,
        sectNameInput = sectNameInput,
        sectNameError = sectNameError,
        onOverwriteConfirm = { showOverwriteConfirm = null },
        onOverwriteCreate = { slot ->
            showOverwriteConfirm = null
            showSectNameDialog = slot
            sectNameInput = ""
            sectNameError = null
        },
        onDeleteDismiss = { showDeleteConfirm = null },
        onDeleteConfirm = { slot ->
            showDeleteConfirm = null
            onDeleteSlot(slot)
        },
        onCloudInfoDismiss = { showCloudSaveInfo = false },
        onSectNameDismiss = { showSectNameDialog = null },
        onSectNameChange = { newValue ->
            if (newValue.length <= 6) {
                sectNameInput = newValue
                sectNameError = newValue.takeIf { it.isNotBlank() }
                    ?.let { InputValidator.validateSectName(it) }
            }
        },
        onSectNameConfirm = { name ->
            showSectNameDialog?.let { onNewGame(it, name) }
            showSectNameDialog = null
        }
    )
}

/** 主内容区：标题行 + 槽位滚动列表 */
@Composable
@Suppress("LongParameterList") // 槽位渲染上下文 + 回调聚合，分组会破坏可读性
private fun SaveSelectContent(
    mode: SaveSelectMode,
    saveSlots: List<SaveSlot>,
    dateFormat: SimpleDateFormat,
    cloudSaveInfo: TapCloudSaveManager.CloudSaveInfo?,
    onBack: () -> Unit,
    onSlotClick: (SaveSlot) -> Unit,
    onDeleteClick: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp)
    ) {
        SaveSelectHeader(
            mode = mode,
            onBack = onBack
        )

        Spacer(modifier = Modifier.height(16.dp))

        SaveSlotList(
            modifier = Modifier.weight(1f),
            saveSlots = saveSlots,
            mode = mode,
            dateFormat = dateFormat,
            cloudSaveInfo = cloudSaveInfo,
            onSlotClick = onSlotClick,
            onDeleteClick = onDeleteClick
        )
    }
}

/** 返回 + 标题行 */
@Composable
private fun SaveSelectHeader(
    mode: SaveSelectMode,
    onBack: () -> Unit
) {
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
}

/** 存档槽位滚动列表 */
@Composable
@Suppress("LongParameterList") // 槽位渲染上下文参数（模式/格式/云存档信息）分组会破坏可读性
private fun SaveSlotList(
    modifier: Modifier = Modifier,
    saveSlots: List<SaveSlot>,
    mode: SaveSelectMode,
    dateFormat: SimpleDateFormat,
    cloudSaveInfo: TapCloudSaveManager.CloudSaveInfo?,
    onSlotClick: (SaveSlot) -> Unit,
    onDeleteClick: (Int) -> Unit
) {
    Column(
        modifier = modifier
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
                    onClick = { onSlotClick(slot) },
                    onDeleteClick = { onDeleteClick(slot.slot) }
                )
            }
        }
    }
}

/** 全部确认/输入对话框组 */
@Composable
@Suppress("LongParameterList") // 四类对话框的状态+回调聚合，拆分反而分散状态编排
private fun SaveSelectDialogs(
    showOverwriteConfirm: Int?,
    showDeleteConfirm: Int?,
    showCloudSaveInfo: Boolean,
    showSectNameDialog: Int?,
    sectNameInput: String,
    sectNameError: String?,
    onOverwriteConfirm: () -> Unit,
    onOverwriteCreate: (Int) -> Unit,
    onDeleteDismiss: () -> Unit,
    onDeleteConfirm: (Int) -> Unit,
    onCloudInfoDismiss: () -> Unit,
    onSectNameDismiss: () -> Unit,
    onSectNameChange: (String) -> Unit,
    onSectNameConfirm: (String) -> Unit
) {
    // 提示框：确认覆盖旧存档
    if (showOverwriteConfirm != null) {
        StandardPromptDialog(
            onDismissRequest = onOverwriteConfirm,
            title = "确认覆盖",
            text = "是否覆盖旧存档创建新游戏？",
            dismissLabel = "取消",
            onDismiss = onOverwriteConfirm,
            confirmLabel = "创建",
            onConfirm = {
                val slot = showOverwriteConfirm ?: return@StandardPromptDialog
                onOverwriteCreate(slot)
            }
        )
    }

    // 提示框：确认删除存档
    if (showDeleteConfirm != null) {
        StandardPromptDialog(
            onDismissRequest = onDeleteDismiss,
            title = "确认删除",
            text = "确定要删除存档吗？此操作不可撤销。",
            dismissLabel = "取消",
            onDismiss = onDeleteDismiss,
            confirmLabel = "删除",
            onConfirm = {
                val slot = showDeleteConfirm ?: return@StandardPromptDialog
                onDeleteConfirm(slot)
            }
        )
    }

    // 云存档信息对话框
    if (showCloudSaveInfo) {
        StandardPromptDialog(
            onDismissRequest = onCloudInfoDismiss,
            title = "☁ 云存档",
            text = "云存档可以将存档上传至云端，在其他设备继续游戏。\n\n请进入游戏后，在「设置」中管理云存档的上传和下载。",
            confirmLabel = "知道了",
            onConfirm = onCloudInfoDismiss,
            dismissOnClickOutside = true
        )
    }

    // 宗门名输入对话框
    if (showSectNameDialog != null) {
        SectNameInputDialog(
            sectNameInput = sectNameInput,
            sectNameError = sectNameError,
            onDismiss = onSectNameDismiss,
            onValueChange = onSectNameChange,
            onConfirm = onSectNameConfirm
        )
    }
}

/** 创建宗门名输入对话框（自动聚焦 + 键盘防频闪） */
@Composable
private fun SectNameInputDialog(
    sectNameInput: String,
    sectNameError: String?,
    onDismiss: () -> Unit,
    onValueChange: (String) -> Unit,
    onConfirm: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val dialogView = LocalView.current  // 在 Composable 上下文中捕获 View 引用
    InlineStandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "创建宗门",
        confirmLabel = "创建",
        dismissLabel = "取消",
        dismissOnClickOutside = false,
        onDismiss = onDismiss,
        onConfirm = {
            if (sectNameError == null) {
                val name = sectNameInput.trim().ifEmpty { "青云宗" }
                onConfirm(name)
            }
        },
        content = {
            LaunchedEffect(Unit) {
                // 等待 Dialog 布局完成后再请求焦点，避免在入场动画完成前弹出键盘
                // 使用 view.post 替代固定 delay(100)，适配不同设备动画速度差异
                dialogView.post { focusRequester.requestFocus() }
            }
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = sectNameInput,
                onValueChange = onValueChange,
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
                SlotIconBox(
                    slot = slot,
                    iconColor = style.icon
                )

                SlotContent(
                    slot = slot,
                    mode = mode,
                    dateFormat = dateFormat,
                    cloudSaveInfo = cloudSaveInfo
                )
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

/** 槽位图标：云存档入口（蓝）/ 本地槽位（灰） */
@Composable
private fun SlotIconBox(
    slot: SaveSlot,
    iconColor: Color
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (slot.slot == 0) Color(0xFF4A90E2) else iconColor
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
}

/** 槽位内容：云存档入口 / 本地存档 / 空槽位 三分支 */
@Composable
private fun SlotContent(
    slot: SaveSlot,
    mode: SaveSelectMode,
    dateFormat: SimpleDateFormat,
    cloudSaveInfo: TapCloudSaveManager.CloudSaveInfo?
) {
    if (slot.slot == 0) {
        CloudSlotContent(
            cloudSaveInfo = cloudSaveInfo,
            mode = mode
        )
    } else if (!slot.isEmpty) {
        LocalSlotContent(
            slot = slot,
            dateFormat = dateFormat
        )
    } else {
        Text(
            text = "空槽位 - 点击创建新游戏",
            fontSize = 16.sp,
            color = Color.Black
        )
    }
}

/** 云存档入口槽内容（三行格式与本地存档一致） */
@Composable
private fun CloudSlotContent(
    cloudSaveInfo: TapCloudSaveManager.CloudSaveInfo?,
    mode: SaveSelectMode
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (cloudSaveInfo?.hasSaveData == true) {
                    cloudSaveInfo?.sectName?.ifEmpty { "云存档" } ?: "云存档"
                } else "云存档",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (cloudSaveInfo?.hasSaveData == true)
                    Color.Black else Color(0xFF4A90E2)
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
}

/** 本地存档槽内容 */
@Composable
private fun LocalSlotContent(
    slot: SaveSlot,
    dateFormat: SimpleDateFormat
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
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
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    return sdf.format(Date(timestamp))
}
