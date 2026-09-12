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
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.theme.GameColors

/**
 * 妖兽进攻预警弹窗（半屏，纯通知）。
 * 仅"知道了"按钮；无论是否点击，下月结算自动执行防守战（弹窗自动关闭）。
 */
@Composable
internal fun BeastAttackWarningDialog(
    attack: PendingBeastAttack,
    onDismiss: () -> Unit,
    scrimEnabled: Boolean = true
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "妖兽来袭",
        mode = DialogMode.Half,
        scrimEnabled = scrimEnabled,
        scrollableContent = false,
        showCloseButton = false,
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
            // 描述文字（居中）
            Text(
                text = "妖兽朝【${attack.targetSectName}】移动",
                fontSize = 20.sp,
                color = GameColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = attack.beastLevel.beastName.ifEmpty { "妖兽" },
                fontSize = 18.sp,
                color = Color(0xFFD32F2F),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "妖兽将于下月对我宗发起进攻，请提前做好准备",
                fontSize = 14.sp,
                color = GameColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            // 按钮区域（居中靠下）：仅"知道了"，纯关闭弹窗不取消排期攻击
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
