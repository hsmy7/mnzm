package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS

@Composable
fun DiscipleManagementDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "弟子管理",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: 自动使用突破丹
            AutoPillSection(gameData = gameData, viewModel = viewModel)
            // Section 2: 自动装备
            AutoEquipSection(gameData = gameData, viewModel = viewModel)
            // Section 3: 自动学习功法
            AutoLearnSection(gameData = gameData, viewModel = viewModel)
        }
    }
}

/** 自动使用突破丹设置区：聚焦开关 + 灵根数过滤 */
@Composable
private fun AutoPillSection(gameData: GameData?, viewModel: GameViewModel) {
    var pillFocused by remember { mutableStateOf(gameData?.breakthroughAutoPillFocused ?: false) }
    var pillRootCounts by remember { mutableStateOf(gameData?.breakthroughAutoPillRootCounts ?: emptySet()) }

    AutoUseSection(
        title = "弟子突破时自动使用突破丹药（优先高品阶，含储物袋）",
        focused = pillFocused,
        rootCounts = pillRootCounts,
        onFocusedToggle = {
            val new = !pillFocused
            pillFocused = new
            viewModel.autoAssign.setBreakthroughAutoPillSettings(new, pillRootCounts)
        },
        onRootToggle = { count ->
            val new = if (count in pillRootCounts) pillRootCounts - count else pillRootCounts + count
            pillRootCounts = new
            viewModel.autoAssign.setBreakthroughAutoPillSettings(pillFocused, new)
        }
    )
}

/** 自动装备设置区：聚焦开关 + 灵根数过滤 */
@Composable
private fun AutoEquipSection(gameData: GameData?, viewModel: GameViewModel) {
    var equipFocused by remember { mutableStateOf(gameData?.autoEquipFromWarehouseFocused ?: false) }
    var equipRootCounts by remember { mutableStateOf(gameData?.autoEquipFromWarehouseRootCounts ?: emptySet()) }

    AutoUseSection(
        title = "弟子自动装备符合境界的装备（优先高品阶，含储物袋，自动更换更高品阶）",
        focused = equipFocused,
        rootCounts = equipRootCounts,
        onFocusedToggle = {
            val new = !equipFocused
            equipFocused = new
            viewModel.autoAssign.setAutoEquipSettings(new, equipRootCounts)
        },
        onRootToggle = { count ->
            val new = if (count in equipRootCounts) equipRootCounts - count else equipRootCounts + count
            equipRootCounts = new
            viewModel.autoAssign.setAutoEquipSettings(equipFocused, new)
        }
    )
}

/** 自动学习功法设置区：聚焦开关 + 灵根数过滤 */
@Composable
private fun AutoLearnSection(gameData: GameData?, viewModel: GameViewModel) {
    var learnFocused by remember { mutableStateOf(gameData?.autoLearnFromWarehouseFocused ?: false) }
    var learnRootCounts by remember { mutableStateOf(gameData?.autoLearnFromWarehouseRootCounts ?: emptySet()) }

    AutoUseSection(
        title = "弟子自动学习符合境界的功法（优先高品阶，含储物袋，自动更换更高品阶）",
        focused = learnFocused,
        rootCounts = learnRootCounts,
        onFocusedToggle = {
            val new = !learnFocused
            learnFocused = new
            viewModel.autoAssign.setAutoLearnSettings(new, learnRootCounts)
        },
        onRootToggle = { count ->
            val new = if (count in learnRootCounts) learnRootCounts - count else learnRootCounts + count
            learnRootCounts = new
            viewModel.autoAssign.setAutoLearnSettings(learnFocused, new)
        }
    )
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
