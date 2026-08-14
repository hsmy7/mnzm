package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS

@Composable
fun AutoManagementDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val policies = gameData?.sectPolicies

    var mineFocused by remember { mutableStateOf(policies?.autoMineFocused ?: false) }
    var mineRootCounts by remember { mutableStateOf(policies?.autoMineRootCounts ?: emptyList<Int>()) }
    var mineThreshold by remember { mutableStateOf((policies?.autoMineThreshold ?: 1).toString()) }

    var alchemyFocused by remember { mutableStateOf(policies?.autoAlchemyFocused ?: false) }
    var alchemyRootCounts by remember { mutableStateOf(policies?.autoAlchemyRootCounts ?: emptyList<Int>()) }
    var alchemyThreshold by remember { mutableStateOf((policies?.autoAlchemyThreshold ?: 1).toString()) }

    var forgeFocused by remember { mutableStateOf(policies?.autoForgeFocused ?: false) }
    var forgeRootCounts by remember { mutableStateOf(policies?.autoForgeRootCounts ?: emptyList<Int>()) }
    var forgeThreshold by remember { mutableStateOf((policies?.autoForgeThreshold ?: 1).toString()) }

    var singleResidenceFocused by remember { mutableStateOf(policies?.autoSingleResidenceFocused ?: false) }
    var singleResidenceRootCounts by remember { mutableStateOf(policies?.autoSingleResidenceRootCounts ?: emptyList<Int>()) }
    var singleResidenceThreshold by remember { mutableStateOf((policies?.autoSingleResidenceThreshold ?: 1).toString()) }

    var multiResidenceFocused by remember { mutableStateOf(policies?.autoMultiResidenceFocused ?: false) }
    var multiResidenceRootCounts by remember { mutableStateOf(policies?.autoMultiResidenceRootCounts ?: emptyList<Int>()) }
    var multiResidenceThreshold by remember { mutableStateOf((policies?.autoMultiResidenceThreshold ?: 1).toString()) }

    var plantFocused by remember { mutableStateOf(policies?.autoPlantFocused ?: false) }
    var plantRootCounts by remember { mutableStateOf(policies?.autoPlantRootCounts ?: emptyList<Int>()) }
    var plantThreshold by remember { mutableStateOf((policies?.autoPlantThreshold ?: 1).toString()) }

    val parsedThreshold: (String) -> Int = { it.toIntOrNull()?.coerceIn(1, 999) ?: 1 }

    val saveAll = {
        viewModel.setAutoAssignSettings(
            mineFocused, mineRootCounts, parsedThreshold(mineThreshold),
            alchemyFocused, alchemyRootCounts, parsedThreshold(alchemyThreshold),
            forgeFocused, forgeRootCounts, parsedThreshold(forgeThreshold),
            singleResidenceFocused, singleResidenceRootCounts, parsedThreshold(singleResidenceThreshold),
            multiResidenceFocused, multiResidenceRootCounts, parsedThreshold(multiResidenceThreshold),
            plantFocused, plantRootCounts, parsedThreshold(plantThreshold)
        )
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "自动管理",
        mode = DialogMode.Half,
        scrollableContent = false,
        // 含阈值输入框：冻结宿主窗口系统栏操作（荣耀X70键盘频闪根治）
        freezeSystemBars = true
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AutoAssignSection(
                title = "无视状态自动入住单人住所",
                attrLabel = "悟性 ≥",
                focused = singleResidenceFocused,
                rootCounts = singleResidenceRootCounts,
                threshold = singleResidenceThreshold,
                onFocusedToggle = {
                    singleResidenceFocused = !singleResidenceFocused
                    saveAll()
                },
                onRootToggle = { count ->
                    singleResidenceRootCounts = if (count in singleResidenceRootCounts)
                        singleResidenceRootCounts - count else singleResidenceRootCounts + count
                    saveAll()
                },
                onThresholdChange = {
                    singleResidenceThreshold = it
                    saveAll()
                }
            )

            AutoAssignSection(
                title = "无视状态自动入住多人住所",
                attrLabel = "悟性 ≥",
                focused = multiResidenceFocused,
                rootCounts = multiResidenceRootCounts,
                threshold = multiResidenceThreshold,
                onFocusedToggle = {
                    multiResidenceFocused = !multiResidenceFocused
                    saveAll()
                },
                onRootToggle = { count ->
                    multiResidenceRootCounts = if (count in multiResidenceRootCounts)
                        multiResidenceRootCounts - count else multiResidenceRootCounts + count
                    saveAll()
                },
                onThresholdChange = {
                    multiResidenceThreshold = it
                    saveAll()
                }
            )

            AutoAssignSection(
                title = "空闲弟子自动种植（灵植阁）",
                attrLabel = "灵植属性 ≥",
                focused = plantFocused,
                rootCounts = plantRootCounts,
                threshold = plantThreshold,
                onFocusedToggle = {
                    plantFocused = !plantFocused
                    saveAll()
                },
                onRootToggle = { count ->
                    plantRootCounts = if (count in plantRootCounts) plantRootCounts - count else plantRootCounts + count
                    saveAll()
                },
                onThresholdChange = {
                    plantThreshold = it
                    saveAll()
                }
            )

            AutoAssignSection(
                title = "空闲弟子自动采矿（灵矿场）",
                attrLabel = "采矿属性 ≥",
                focused = mineFocused,
                rootCounts = mineRootCounts,
                threshold = mineThreshold,
                onFocusedToggle = {
                    mineFocused = !mineFocused
                    saveAll()
                },
                onRootToggle = { count ->
                    mineRootCounts = if (count in mineRootCounts) mineRootCounts - count else mineRootCounts + count
                    saveAll()
                },
                onThresholdChange = {
                    mineThreshold = it
                    saveAll()
                }
            )

            AutoAssignSection(
                title = "空闲弟子自动炼丹（炼丹炉）",
                attrLabel = "炼丹属性 ≥",
                focused = alchemyFocused,
                rootCounts = alchemyRootCounts,
                threshold = alchemyThreshold,
                onFocusedToggle = {
                    alchemyFocused = !alchemyFocused
                    saveAll()
                },
                onRootToggle = { count ->
                    alchemyRootCounts = if (count in alchemyRootCounts) alchemyRootCounts - count else alchemyRootCounts + count
                    saveAll()
                },
                onThresholdChange = {
                    alchemyThreshold = it
                    saveAll()
                }
            )

            AutoAssignSection(
                title = "空闲弟子自动炼器（锻造坊）",
                attrLabel = "炼器属性 ≥",
                focused = forgeFocused,
                rootCounts = forgeRootCounts,
                threshold = forgeThreshold,
                onFocusedToggle = {
                    forgeFocused = !forgeFocused
                    saveAll()
                },
                onRootToggle = { count ->
                    forgeRootCounts = if (count in forgeRootCounts) forgeRootCounts - count else forgeRootCounts + count
                    saveAll()
                },
                onThresholdChange = {
                    forgeThreshold = it
                    saveAll()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AutoAssignSection(
    title: String,
    attrLabel: String,
    focused: Boolean,
    rootCounts: List<Int>,
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
                            num == null -> {
                                // 溢出或空输入时保留当前值不变
                                threshold
                            }
                            num < 1 -> "1"
                            num > 999 -> "999"
                            else -> num.toString()
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
