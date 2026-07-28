package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.ui.components.CircularCheckbox
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS
import com.xianxia.sect.ui.theme.ButtonSizes

@Composable
fun SectManagementDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    var showDaoCompanionManagement by remember { mutableStateOf(false) }
    var showDiscipleManagement by remember { mutableStateOf(false) }
    var showAutoManagement by remember { mutableStateOf(false) }

    val currentPrisonerFilter = gameData?.prisonerSpiritRootFilter ?: emptySet()

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "宗门管理",
        mode = DialogMode.Half,
        scrollableContent = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 选项区域：俘虏弟子管理 ──
            Text(
                text = "俘虏弟子管理",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.Black
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SPIRIT_ROOT_FILTER_OPTIONS.forEachIndexed { index, (count, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = label, fontSize = 12.sp, color = Color.Black)
                        Spacer(modifier = Modifier.padding(start = 2.dp))
                        CircularCheckbox(
                            checked = count in currentPrisonerFilter,
                            onToggle = { viewModel.togglePrisonerFilter(count) }
                        )
                    }
                    if (index < SPIRIT_ROOT_FILTER_OPTIONS.size - 1) {
                        Spacer(modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.padding(top = 8.dp))

            // ── 管理区域：管理按钮（FlowRow 响应式换行）──
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GameButton(
                    text = "道侣管理",
                    onClick = { showDaoCompanionManagement = true },
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
                GameButton(
                    text = "弟子管理",
                    onClick = { showDiscipleManagement = true },
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
                GameButton(
                    text = "自动管理",
                    onClick = { showAutoManagement = true },
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
            }
        }
    }

    // 子对话框
    if (showDaoCompanionManagement) {
        DaoCompanionManagementDialog(
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { showDaoCompanionManagement = false }
        )
    }
    if (showDiscipleManagement) {
        DiscipleManagementDialog(
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { showDiscipleManagement = false }
        )
    }
    if (showAutoManagement) {
        AutoManagementDialog(
            gameData = gameData,
            viewModel = viewModel,
            onDismiss = { showAutoManagement = false }
        )
    }
}
