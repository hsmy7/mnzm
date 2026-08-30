package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.ui.components.CircularCheckbox
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.game.components.NumberInputPanel

/** 自动分配属性门槛上限（阈值输入钳制目标） */
private const val AUTO_ASSIGN_THRESHOLD_MAX = 999

/** 自动分配单项状态（AutoManagementDialog 拆分） */
private class AutoAssignSectionState(
    initialFocused: Boolean,
    initialRootCounts: List<Int>,
    initialThreshold: String
) {
    var focused by mutableStateOf(initialFocused)
    var rootCounts by mutableStateOf(initialRootCounts)
    var threshold by mutableStateOf(initialThreshold)
}

/** 自动管理对话框状态（AutoManagementDialog 拆分） */
private class AutoManagementState(policies: SectPolicies?) {
    val mine = AutoAssignSectionState(
        policies?.autoMineFocused ?: false,
        policies?.autoMineRootCounts ?: emptyList(),
        (policies?.autoMineThreshold ?: 1).toString()
    )
    val alchemy = AutoAssignSectionState(
        policies?.autoAlchemyFocused ?: false,
        policies?.autoAlchemyRootCounts ?: emptyList(),
        (policies?.autoAlchemyThreshold ?: 1).toString()
    )
    val forge = AutoAssignSectionState(
        policies?.autoForgeFocused ?: false,
        policies?.autoForgeRootCounts ?: emptyList(),
        (policies?.autoForgeThreshold ?: 1).toString()
    )
    val singleResidence = AutoAssignSectionState(
        policies?.autoSingleResidenceFocused ?: false,
        policies?.autoSingleResidenceRootCounts ?: emptyList(),
        (policies?.autoSingleResidenceThreshold ?: 1).toString()
    )
    val multiResidence = AutoAssignSectionState(
        policies?.autoMultiResidenceFocused ?: false,
        policies?.autoMultiResidenceRootCounts ?: emptyList(),
        (policies?.autoMultiResidenceThreshold ?: 1).toString()
    )
    val plant = AutoAssignSectionState(
        policies?.autoPlantFocused ?: false,
        policies?.autoPlantRootCounts ?: emptyList(),
        (policies?.autoPlantThreshold ?: 1).toString()
    )
}

@Composable
fun AutoManagementDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val policies = gameData?.sectPolicies
    val state = remember { AutoManagementState(policies) }

    fun parsedThreshold(value: String): Int = value.toIntOrNull()?.coerceIn(1, 999) ?: 1

    fun saveAll() {
        viewModel.setAutoAssignSettings(
            state.mine.focused, state.mine.rootCounts, parsedThreshold(state.mine.threshold),
            state.alchemy.focused, state.alchemy.rootCounts, parsedThreshold(state.alchemy.threshold),
            state.forge.focused, state.forge.rootCounts, parsedThreshold(state.forge.threshold),
            state.singleResidence.focused, state.singleResidence.rootCounts,
            parsedThreshold(state.singleResidence.threshold),
            state.multiResidence.focused, state.multiResidence.rootCounts,
            parsedThreshold(state.multiResidence.threshold),
            state.plant.focused, state.plant.rootCounts, parsedThreshold(state.plant.threshold)
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
            AutoAssignSectionBlock(
                title = "无视状态自动入住单人住所", attrLabel = "悟性 ≥",
                state = state.singleResidence,
                onChanged = { saveAll() }
            )

            AutoAssignSectionBlock(
                title = "无视状态自动入住多人住所", attrLabel = "悟性 ≥",
                state = state.multiResidence,
                onChanged = { saveAll() }
            )

            AutoAssignSectionBlock(
                title = "空闲弟子自动种植（灵植阁）", attrLabel = "灵植属性 ≥",
                state = state.plant,
                onChanged = { saveAll() }
            )

            AutoAssignSectionBlock(
                title = "空闲弟子自动采矿（灵矿场）", attrLabel = "采矿属性 ≥",
                state = state.mine,
                onChanged = { saveAll() }
            )

            AutoAssignSectionBlock(
                title = "空闲弟子自动炼丹（炼丹炉）", attrLabel = "炼丹属性 ≥",
                state = state.alchemy,
                onChanged = { saveAll() }
            )

            AutoAssignSectionBlock(
                title = "空闲弟子自动炼器（锻造坊）", attrLabel = "炼器属性 ≥",
                state = state.forge,
                onChanged = { saveAll() }
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 自动分配区块（AutoManagementDialog 拆分）：标题 + 勾选行 + 阈值输入 */
@Composable
private fun AutoAssignSectionBlock(
    title: String,
    attrLabel: String,
    state: AutoAssignSectionState,
    onChanged: () -> Unit
) {
    AutoAssignSection(
        title = title,
        attrLabel = attrLabel,
        focused = state.focused,
        rootCounts = state.rootCounts,
        threshold = state.threshold,
        onFocusedToggle = { state.focused = !state.focused; onChanged() },
        onRootToggle = { count ->
            state.rootCounts = toggleRootCount(state.rootCounts, count)
            onChanged()
        },
        onThresholdChange = { state.threshold = it; onChanged() }
    )
}

/** 灵根数量开关切换（AutoManagementDialog 拆分） */
private fun toggleRootCount(current: List<Int>, count: Int): List<Int> =
    if (count in current) current - count else current + count

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

        AutoAssignCheckRow(
            focused = focused,
            rootCounts = rootCounts,
            onFocusedToggle = onFocusedToggle,
            onRootToggle = onRootToggle
        )

        Spacer(modifier = Modifier.height(4.dp))

        AutoAssignThresholdField(
            attrLabel = attrLabel,
            threshold = threshold,
            onThresholdChange = onThresholdChange
        )
    }
}

/** 已关注 + 灵根数勾选行（AutoAssignSection 拆分） */
@Composable
private fun AutoAssignCheckRow(
    focused: Boolean,
    rootCounts: List<Int>,
    onFocusedToggle: () -> Unit,
    onRootToggle: (Int) -> Unit
) {
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
}

/**
 * 属性门槛输入行（AutoAssignSection 拆分）：点击阈值框弹出自绘数字面板
 * （NumberInputPanel，2026-09 IME 状态机根治：数字输入绕开系统 IME）。
 */
@Composable
private fun AutoAssignThresholdField(
    attrLabel: String,
    threshold: String,
    onThresholdChange: (String) -> Unit
) {
    var showPanel by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = attrLabel, fontSize = 12.sp, color = Color.Black)
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .width(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                .clickableWithSound(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { showPanel = true }
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = threshold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.Black
            )
        }
    }
    if (showPanel) {
        NumberInputPanel(
            initialValue = threshold.toIntOrNull()?.coerceIn(1, AUTO_ASSIGN_THRESHOLD_MAX) ?: 1,
            maxQuantity = AUTO_ASSIGN_THRESHOLD_MAX,
            onConfirm = { value ->
                onThresholdChange(value.toString())
                showPanel = false
            },
            onDismiss = { showPanel = false }
        )
    }
}
