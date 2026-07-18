package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.ui.components.BattleParticipantSlot
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog

/**
 * 释放后忠诚度变化提示框
 */
@Composable
fun LoyaltyChangeDialog(
    loyaltyChange: Int,
    onDismiss: () -> Unit
) {
    StandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "忠诚度 +$loyaltyChange",
        confirmLabel = "知道了",
        onConfirm = onDismiss
    )
}

@Composable
fun MarriageApprovalDialog(
    maleDisciple: Disciple,
    femaleDisciple: Disciple,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    StandardPromptDialog(
        onDismissRequest = onReject,
        title = "${maleDisciple.name}弟子与${femaleDisciple.name}弟子请求结婚",
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        customButtons = {
            GameButton(text = "同意", onClick = onApprove)
            Spacer(modifier = Modifier.width(8.dp))
            GameButton(text = "拒绝", onClick = onReject)
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BattleParticipantSlot(
                name = maleDisciple.name,
                realmName = maleDisciple.realmName,
                hp = 0,
                maxHp = 1,
                isAlive = true,
                portraitRes = maleDisciple.portraitRes,
                showHpBar = false
            )
            BattleParticipantSlot(
                name = femaleDisciple.name,
                realmName = femaleDisciple.realmName,
                hp = 0,
                maxHp = 1,
                isAlive = true,
                portraitRes = femaleDisciple.portraitRes,
                showHpBar = false
            )
        }
    }
}

