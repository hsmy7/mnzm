package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.components.CircularCheckbox
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.theme.ButtonSizes

@Composable
fun DiscipleManagementDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val initialPillFocused = gameData?.breakthroughAutoPillFocused ?: false
    val initialPillRootCounts = gameData?.breakthroughAutoPillRootCounts ?: emptySet()
    val initialEquipFocused = gameData?.autoEquipFromWarehouseFocused ?: false
    val initialEquipRootCounts = gameData?.autoEquipFromWarehouseRootCounts ?: emptySet()
    val initialLearnFocused = gameData?.autoLearnFromWarehouseFocused ?: false
    val initialLearnRootCounts = gameData?.autoLearnFromWarehouseRootCounts ?: emptySet()

    var pillFocused by remember { mutableStateOf(initialPillFocused) }
    var pillRootCounts by remember { mutableStateOf(initialPillRootCounts) }
    var equipFocused by remember { mutableStateOf(initialEquipFocused) }
    var equipRootCounts by remember { mutableStateOf(initialEquipRootCounts) }
    var learnFocused by remember { mutableStateOf(initialLearnFocused) }
    var learnRootCounts by remember { mutableStateOf(initialLearnRootCounts) }

    val hasChanges = pillFocused != initialPillFocused || pillRootCounts != initialPillRootCounts ||
            equipFocused != initialEquipFocused || equipRootCounts != initialEquipRootCounts ||
            learnFocused != initialLearnFocused || learnRootCounts != initialLearnRootCounts
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val saveAndDismiss = {
        viewModel.setBreakthroughAutoPillSettings(pillFocused, pillRootCounts)
        viewModel.setAutoEquipSettings(equipFocused, equipRootCounts)
        viewModel.setAutoLearnSettings(learnFocused, learnRootCounts)
        onDismiss()
    }

    val handleClose = {
        if (hasChanges) {
            showUnsavedDialog = true
        } else {
            onDismiss()
        }
    }

    UnifiedGameDialog(
        onDismissRequest = handleClose,
        title = "弟子管理",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: 自动使用突破丹
                AutoUseSection(
                    title = "弟子突破时自动使用仓库中突破丹药（优先高品阶）",
                    focused = pillFocused,
                    rootCounts = pillRootCounts,
                    onFocusedToggle = { pillFocused = !pillFocused },
                    onRootToggle = { count ->
                        pillRootCounts = if (count in pillRootCounts) pillRootCounts - count else pillRootCounts + count
                    }
                )

                // Section 2: 自动装备
                AutoUseSection(
                    title = "弟子自动装备仓库中符合境界的装备（优先高品阶，只装备不更换）",
                    focused = equipFocused,
                    rootCounts = equipRootCounts,
                    onFocusedToggle = { equipFocused = !equipFocused },
                    onRootToggle = { count ->
                        equipRootCounts = if (count in equipRootCounts) equipRootCounts - count else equipRootCounts + count
                    }
                )

                // Section 3: 自动学习功法
                AutoUseSection(
                    title = "弟子自动学习仓库中符合境界的功法（优先高品阶，只学习不更换）",
                    focused = learnFocused,
                    rootCounts = learnRootCounts,
                    onFocusedToggle = { learnFocused = !learnFocused },
                    onRootToggle = { count ->
                        learnRootCounts = if (count in learnRootCounts) learnRootCounts - count else learnRootCounts + count
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GameButton(
                        text = "关闭",
                        onClick = handleClose,
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                    GameButton(
                        text = "保存",
                        onClick = saveAndDismiss,
                        enabled = hasChanges,
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                }
            }

            if (showUnsavedDialog) {
                StandardPromptDialog(
                    onDismissRequest = { showUnsavedDialog = false },
                    title = "未保存更改",
                    text = "您所做的更改尚未保存，若直接退出则视为取消更改",
                    confirmLabel = "保存",
                    onConfirm = {
                        showUnsavedDialog = false
                        saveAndDismiss()
                    },
                    dismissLabel = "关闭",
                    onDismiss = {
                        showUnsavedDialog = false
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun AutoUseSection(
    title: String,
    focused: Boolean,
    rootCounts: Set<Int>,
    onFocusedToggle: () -> Unit,
    onRootToggle: (Int) -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "已关注", fontSize = 12.sp, color = Color.Black)
                Spacer(modifier = Modifier.width(2.dp))
                CircularCheckbox(checked = focused, onToggle = onFocusedToggle)
            }
            Spacer(modifier = Modifier.width(6.dp))
            SPIRIT_ROOT_FILTER_OPTIONS.forEachIndexed { index, (count, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = label, fontSize = 12.sp, color = Color.Black)
                    Spacer(modifier = Modifier.width(2.dp))
                    CircularCheckbox(checked = count in rootCounts, onToggle = { onRootToggle(count) })
                }
                if (index < SPIRIT_ROOT_FILTER_OPTIONS.size - 1) {
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
        }
    }
}
