package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.WarningStage
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog

/**
 * AI宗门进攻谴责弹窗（进攻前6个月）。
 * 参考文明六 Denunciation 设计。
 */
@Composable
internal fun DenunciationDialog(
    warning: AttackWarning,
    currentSpiritStones: Long,
    onAppease: () -> Unit,
    onDismiss: () -> Unit
) {
    val canAppease = currentSpiritStones >=
        GameConfig.AIAttack.APPEASE_GIFT_SPIRIT_STONES

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "被${warning.attackerSectName}谴责",
        mode = DialogMode.Half,
        scrollableContent = false,
        showCloseButton = true,
        dismissOnClickOutside = false,
        dismissOnBackPress = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "「${warning.attackerSectName}」对你表达不满",
                fontSize = 18.sp,
                color = Color.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GameButton(
                    text = if (canAppease) "缓和关系" else "灵石不足",
                    onClick = onAppease,
                    enabled = canAppease,
                    modifier = Modifier
                        .width(120.dp)
                        .height(56.dp)
                )

                Spacer(modifier = Modifier.width(24.dp))

                GameButton(
                    text = "知道了",
                    onClick = onDismiss,
                    modifier = Modifier
                        .width(120.dp)
                        .height(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * AI宗门进攻战书弹窗（进攻前3个月）。
 * 参考文明六 War Declaration 设计。
 */
@Composable
internal fun WarDeclarationDialog(
    warning: AttackWarning,
    currentSpiritStones: Long,
    onAppease: () -> Unit,
    onBecomeVassal: () -> Unit,
    onDismiss: () -> Unit
) {
    val canAppease = currentSpiritStones >=
        GameConfig.AIAttack.APPEASE_GIFT_SPIRIT_STONES

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "战书",
        mode = DialogMode.Half,
        scrollableContent = false,
        showCloseButton = true,
        dismissOnClickOutside = false,
        dismissOnBackPress = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "「${warning.attackerSectName}」对你发起了进攻",
                fontSize = 18.sp,
                color = Color.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GameButton(
                    text = if (canAppease) "缓和关系" else "灵石不足",
                    onClick = onAppease,
                    enabled = canAppease,
                    modifier = Modifier
                        .width(100.dp)
                        .height(56.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                GameButton(
                    text = "附庸宗门",
                    onClick = onBecomeVassal,
                    modifier = Modifier
                        .width(100.dp)
                        .height(56.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                GameButton(
                    text = "知道了",
                    onClick = onDismiss,
                    modifier = Modifier
                        .width(100.dp)
                        .height(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 根据预警阶段选择展示对应的弹窗。
 */
@Composable
internal fun AttackWarningDialogs(
    warnings: List<AttackWarning>,
    shownStageIds: List<String>,
    currentSpiritStones: Long,
    onAppease: (AttackWarning) -> Unit,
    onBecomeVassal: (AttackWarning) -> Unit,
    onDismissWarning: (AttackWarning) -> Unit
) {
    val denunciation = warnings.firstOrNull { warning ->
        warning.stage == WarningStage.DENUNCIATION &&
            "${warning.warningId}:DENUNCIATION" !in shownStageIds
    }
    if (denunciation != null) {
        DenunciationDialog(
            warning = denunciation,
            currentSpiritStones = currentSpiritStones,
            onAppease = { onAppease(denunciation) },
            onDismiss = { onDismissWarning(denunciation) }
        )
        return
    }

    val warDeclaration = warnings.firstOrNull { warning ->
        warning.stage == WarningStage.WAR_DECLARATION &&
            "${warning.warningId}:WAR_DECLARATION" !in shownStageIds
    }
    if (warDeclaration != null) {
        WarDeclarationDialog(
            warning = warDeclaration,
            currentSpiritStones = currentSpiritStones,
            onAppease = { onAppease(warDeclaration) },
            onBecomeVassal = { onBecomeVassal(warDeclaration) },
            onDismiss = { onDismissWarning(warDeclaration) }
        )
    }
}
