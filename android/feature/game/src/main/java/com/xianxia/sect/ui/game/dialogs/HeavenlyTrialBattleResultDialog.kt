package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun HeavenlyTrialBattleResultDialog(
    won: Boolean,
    durationSeconds: Long,
    totalRounds: Int,
    onDismiss: () -> Unit
) {
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = if (won) "战斗胜利" else "战斗失败",
        mode = DialogMode.Half,
        titleColor = if (won) GameColors.Gold else GameColors.Error,
        showCloseButton = false,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "用时 %02d分%02d秒  总回合 %d".format(minutes, seconds, totalRounds),
                fontSize = 14.sp,
                color = Color.Black
            )
            Spacer(Modifier.height(5.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(1.dp)
                    .background(Color.Gray)
            )
            Spacer(Modifier.weight(1f))
            GameButton("确认", onClick = onDismiss)
            Spacer(Modifier.height(16.dp))
        }
    }
}
