package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
fun AutoManagementDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val policies = gameData?.sectPolicies

    var mineFocused by remember { mutableStateOf(policies?.autoMineFocused ?: false) }
    var mineRootCounts by remember { mutableStateOf(policies?.autoMineRootCounts ?: emptySet<Int>()) }
    var mineThreshold by remember { mutableStateOf((policies?.autoMineThreshold ?: 1).toString()) }

    var plantFocused by remember { mutableStateOf(policies?.autoPlantFocused ?: false) }
    var plantRootCounts by remember { mutableStateOf(policies?.autoPlantRootCounts ?: emptySet<Int>()) }
    var plantThreshold by remember { mutableStateOf((policies?.autoPlantThreshold ?: 1).toString()) }

    var alchemyFocused by remember { mutableStateOf(policies?.autoAlchemyFocused ?: false) }
    var alchemyRootCounts by remember { mutableStateOf(policies?.autoAlchemyRootCounts ?: emptySet<Int>()) }
    var alchemyThreshold by remember { mutableStateOf((policies?.autoAlchemyThreshold ?: 1).toString()) }

    var forgeFocused by remember { mutableStateOf(policies?.autoForgeFocused ?: false) }
    var forgeRootCounts by remember { mutableStateOf(policies?.autoForgeRootCounts ?: emptySet<Int>()) }
    var forgeThreshold by remember { mutableStateOf((policies?.autoForgeThreshold ?: 1).toString()) }

    val hasChanges = mineFocused != (policies?.autoMineFocused ?: false) ||
            mineRootCounts != (policies?.autoMineRootCounts ?: emptySet<Int>()) ||
            mineThreshold != (policies?.autoMineThreshold ?: 1).toString() ||
            plantFocused != (policies?.autoPlantFocused ?: false) ||
            plantRootCounts != (policies?.autoPlantRootCounts ?: emptySet<Int>()) ||
            plantThreshold != (policies?.autoPlantThreshold ?: 1).toString() ||
            alchemyFocused != (policies?.autoAlchemyFocused ?: false) ||
            alchemyRootCounts != (policies?.autoAlchemyRootCounts ?: emptySet<Int>()) ||
            alchemyThreshold != (policies?.autoAlchemyThreshold ?: 1).toString() ||
            forgeFocused != (policies?.autoForgeFocused ?: false) ||
            forgeRootCounts != (policies?.autoForgeRootCounts ?: emptySet<Int>()) ||
            forgeThreshold != (policies?.autoForgeThreshold ?: 1).toString()

    var showUnsavedDialog by remember { mutableStateOf(false) }

    val saveAndDismiss = {
        viewModel.setAutoAssignSettings(
            mineFocused, mineRootCounts, mineThreshold.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            plantFocused, plantRootCounts, plantThreshold.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            alchemyFocused, alchemyRootCounts, alchemyThreshold.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            forgeFocused, forgeRootCounts, forgeThreshold.toIntOrNull()?.coerceAtLeast(1) ?: 1
        )
        onDismiss()
    }

    val handleClose = {
        if (hasChanges) showUnsavedDialog = true
        else onDismiss()
    }

    UnifiedGameDialog(
        onDismissRequest = handleClose,
        title = "自动管理",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AutoAssignSection(
                    title = "空闲弟子自动采矿（灵矿场）",
                    attrLabel = "采矿属性 ≥",
                    focused = mineFocused,
                    rootCounts = mineRootCounts,
                    threshold = mineThreshold,
                    onFocusedToggle = { mineFocused = !mineFocused },
                    onRootToggle = { count ->
                        mineRootCounts = if (count in mineRootCounts) mineRootCounts - count else mineRootCounts + count
                    },
                    onThresholdChange = { mineThreshold = it }
                )

                AutoAssignSection(
                    title = "空闲弟子自动种植（灵植阁）",
                    attrLabel = "灵植属性 ≥",
                    focused = plantFocused,
                    rootCounts = plantRootCounts,
                    threshold = plantThreshold,
                    onFocusedToggle = { plantFocused = !plantFocused },
                    onRootToggle = { count ->
                        plantRootCounts = if (count in plantRootCounts) plantRootCounts - count else plantRootCounts + count
                    },
                    onThresholdChange = { plantThreshold = it }
                )

                AutoAssignSection(
                    title = "空闲弟子自动炼丹（炼丹炉）",
                    attrLabel = "炼丹属性 ≥",
                    focused = alchemyFocused,
                    rootCounts = alchemyRootCounts,
                    threshold = alchemyThreshold,
                    onFocusedToggle = { alchemyFocused = !alchemyFocused },
                    onRootToggle = { count ->
                        alchemyRootCounts = if (count in alchemyRootCounts) alchemyRootCounts - count else alchemyRootCounts + count
                    },
                    onThresholdChange = { alchemyThreshold = it }
                )

                AutoAssignSection(
                    title = "空闲弟子自动炼器（锻造坊）",
                    attrLabel = "炼器属性 ≥",
                    focused = forgeFocused,
                    rootCounts = forgeRootCounts,
                    threshold = forgeThreshold,
                    onFocusedToggle = { forgeFocused = !forgeFocused },
                    onRootToggle = { count ->
                        forgeRootCounts = if (count in forgeRootCounts) forgeRootCounts - count else forgeRootCounts + count
                    },
                    onThresholdChange = { forgeThreshold = it }
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
private fun AutoAssignSection(
    title: String,
    attrLabel: String,
    focused: Boolean,
    rootCounts: Set<Int>,
    threshold: String,
    onFocusedToggle: () -> Unit,
    onRootToggle: (Int) -> Unit,
    onThresholdChange: (String) -> Unit
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
                    CircularCheckbox(
                        checked = count in rootCounts,
                        onToggle = { onRootToggle(count) }
                    )
                }
                if (index < SPIRIT_ROOT_FILTER_OPTIONS.size - 1) {
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = attrLabel, fontSize = 12.sp, color = Color.Black)
            Spacer(modifier = Modifier.width(4.dp))
            BasicTextField(
                value = threshold,
                onValueChange = { v ->
                    val filtered = v.filter { it.isDigit() }
                    val num = filtered.toIntOrNull()
                    onThresholdChange(
                        when {
                            num == null -> "1"
                            num < 1 -> "1"
                            else -> filtered
                        }
                    )
                },
                modifier = Modifier.width(40.dp)
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = Color.Black, textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}
