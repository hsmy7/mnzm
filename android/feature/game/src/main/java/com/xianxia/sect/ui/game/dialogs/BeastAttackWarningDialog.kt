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
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog

/**
 * 妖兽进攻预警弹窗（半屏）。
 * 玩家可选择上交灵石取消进攻，或迎战。
 */
@Composable
internal fun BeastAttackWarningDialog(
    attack: PendingBeastAttack,
    currentSpiritStones: Long,
    onPayTribute: () -> Unit,
    onFight: () -> Unit
) {
    val canPay = currentSpiritStones >= GameConfig.WorldMap.BEAST_TRIBUTE_MIN
    val tributeAmount = (currentSpiritStones *
        GameConfig.WorldMap.BEAST_TRIBUTE_RATIO).toLong()
        .coerceAtLeast(GameConfig.WorldMap.BEAST_TRIBUTE_MIN)

    UnifiedGameDialog(
        onDismissRequest = onFight,
        title = "妖兽来袭",
        mode = DialogMode.Half,
        scrollableContent = false,
        showCloseButton = false,
        dismissOnClickOutside = false,
        dismissOnBackPress = false
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
                color = Color.Black,
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

            Spacer(modifier = Modifier.weight(1f))

            // 按钮区域（居中靠下）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GameButton(
                    text = "上交宝物\n(${GameUtils.formatNumber(tributeAmount)}灵石)",
                    onClick = onPayTribute,
                    enabled = canPay,
                    modifier = Modifier
                        .width(120.dp)
                        .height(56.dp)
                )

                Spacer(modifier = Modifier.width(24.dp))

                GameButton(
                    text = "知道了",
                    onClick = onFight,
                    modifier = Modifier
                        .width(120.dp)
                        .height(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
