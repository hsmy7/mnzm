package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.game.GameViewModel
import java.util.Locale

/**
 * 玉符说明对话框：玩法说明 + 红色倒计时（距下次获得玉符剩余时间）。
 *
 * 倒计时由 [GameViewModel.jadeSymbolState]（1Hz 节流流）驱动，
 * 无本地计时器；拿满单日上限后显示"今日已达上限"。
 */
@Composable
internal fun JadeSymbolDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val jadeState by viewModel.jadeSymbolState.collectAsStateWithLifecycle()
    StandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "玉符",
        text = "游戏时长每过20分钟获得1玉符，单日最多获得30玉符",
        confirmLabel = "知道了",
        scrimEnabled = false,
        dismissOnClickOutside = true
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        if (jadeState.capped) {
            Text(
                text = "今日已达上限",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Red
            )
        } else {
            val minutes = jadeState.remainingMs / 60_000L
            val seconds = (jadeState.remainingMs % 60_000L) / 1_000L
            Text(
                text = String.format(Locale.US, "距离下次获得玉符 %02d:%02d", minutes, seconds),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Red
            )
        }
    }
}
