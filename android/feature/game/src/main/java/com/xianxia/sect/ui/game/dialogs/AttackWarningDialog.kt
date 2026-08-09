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
import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog

/**
 * AI宗门进攻预警弹窗（纯通知）：「xxx」即将进攻宗门，下月进攻。
 * 仅"知道了"按钮；无论是否点击，下月进攻照常发生。
 */
@Composable
internal fun AttackWarningDialog(
    warning: AttackWarning,
    onDismiss: () -> Unit,
    scrimEnabled: Boolean = true
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "进攻预警",
        mode = DialogMode.Half,
        scrollableContent = false,
        showCloseButton = true,
        dismissOnClickOutside = false,
        dismissOnBackPress = true,
        scrimEnabled = scrimEnabled
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "「${warning.attackerSectName}」即将进攻宗门",
                fontSize = 18.sp,
                color = Color.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "下个月将对我宗发起进攻，请提前做好准备",
                fontSize = 14.sp,
                color = Color.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            GameButton(
                text = "知道了",
                onClick = onDismiss,
                modifier = Modifier
                    .width(120.dp)
                    .height(56.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 按已展示标记过滤，逐个展示未看过的进攻预警。
 */
@Composable
internal fun AttackWarningDialogs(
    warnings: List<AttackWarning>,
    shownStageIds: List<String>,
    onDismissWarning: (AttackWarning) -> Unit,
    scrimEnabled: Boolean = true
) {
    val warning = warnings.firstOrNull { w ->
        "${w.warningId}:${w.stage.name}" !in shownStageIds
    }
    if (warning != null) {
        AttackWarningDialog(
            warning = warning,
            onDismiss = { onDismissWarning(warning) },
            scrimEnabled = scrimEnabled
        )
    }
}
