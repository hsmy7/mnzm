package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.Arrangement
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
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.CircularCheckbox
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.SPIRIT_ROOT_FILTER_OPTIONS

@Composable
fun DaoCompanionManagementDialog(
    gameData: GameData?,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val initialBanned = gameData?.daoCompanionBannedRootCounts ?: emptySet()
    val initialConsentRequired = gameData?.daoCompanionConsentRequired ?: false

    var bannedRootCounts by remember { mutableStateOf(initialBanned) }
    var consentRequired by remember { mutableStateOf(initialConsentRequired) }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "道侣管理",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Row 1: "禁止结婚" + 5 root count checkboxes
            Text(
                text = "禁止结婚",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SPIRIT_ROOT_FILTER_OPTIONS.forEachIndexed { index, (count, label) ->
                    RootCountCheckboxRow(
                        label = label,
                        checked = count in bannedRootCounts,
                        onToggle = {
                            val newRootCounts = if (count in bannedRootCounts) {
                                bannedRootCounts - count
                            } else {
                                bannedRootCounts + count
                            }
                            bannedRootCounts = newRootCounts
                            viewModel.setDaoCompanionBannedRootCounts(newRootCounts)
                        }
                    )
                    if (index < SPIRIT_ROOT_FILTER_OPTIONS.size - 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
            }

            // Row 2: "结婚需同意" + checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    text = "结婚需同意",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.width(4.dp))

                CircularCheckbox(
                    checked = consentRequired,
                    onToggle = {
                        val newValue = !consentRequired
                        consentRequired = newValue
                        viewModel.setDaoCompanionConsentRequired(newValue)
                    }
                )
            }
        }
    }
}

@Composable
private fun RootCountCheckboxRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.Black
        )

        Spacer(modifier = Modifier.width(2.dp))

        CircularCheckbox(checked = checked, onToggle = onToggle)
    }
}

// CircularCheckbox extracted to com.xianxia.sect.ui.components.CircularCheckbox
