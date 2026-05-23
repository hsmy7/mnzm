package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.engine.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.StandardPromptDialog

/**
 * 弟子脱离宗门提示框
 */
@Composable
fun DiscipleDesertionDialog(
    disciple: Disciple,
    onDismiss: () -> Unit
) {
    StandardPromptDialog(
        onDismissRequest = onDismiss,
        title = "${disciple.name}脱离了宗门",
        confirmLabel = "知道了",
        onConfirm = onDismiss
    ) {
        DiscipleSnapshotCard(disciple = disciple)
    }
}

/**
 * 弟子偷盗被捕提示框
 */
@Composable
fun DiscipleTheftCaughtDialog(
    disciple: Disciple,
    hasPrison: Boolean,
    onExpel: () -> Unit,
    onImprison: () -> Unit,
    onRelease: () -> Int,
    onDiscipleClick: (String) -> Unit,
    onLoyaltyDismissed: () -> Unit
) {
    var loyaltyResult by remember { mutableStateOf<Int?>(null) }

    if (loyaltyResult != null) {
        LoyaltyChangeDialog(
            loyaltyChange = loyaltyResult!!,
            onDismiss = {
                loyaltyResult = null
                onLoyaltyDismissed()
            }
        )
    }

    StandardPromptDialog(
        onDismissRequest = {},
        title = "${disciple.name}偷盗被捕",
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        customButtons = {
            GameButton(text = "驱逐", onClick = onExpel)
            Spacer(modifier = Modifier.width(8.dp))
            GameButton(text = "押入监牢", onClick = onImprison, enabled = hasPrison)
            Spacer(modifier = Modifier.width(8.dp))
            GameButton(text = "释放", onClick = { loyaltyResult = onRelease() })
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onDiscipleClick(disciple.id) }
        ) {
            DiscipleSnapshotCard(disciple = disciple)
        }
    }
}

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
private fun DiscipleSnapshotCard(disciple: Disciple) {
    val baseStats = remember(disciple.id) { DiscipleStatCalculator.getBaseStats(disciple) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Text(
            text = disciple.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "境界: ${disciple.realm}层${disciple.realmLayer}",
            fontSize = 14.sp,
            color = Color.Black
        )
        Text(
            text = "灵根: ${disciple.spiritRootType}",
            fontSize = 14.sp,
            color = Color.Black
        )
        Text(
            text = "忠诚: ${baseStats.loyalty}",
            fontSize = 14.sp,
            color = Color.Black
        )
        Text(
            text = "道德: ${baseStats.morality}",
            fontSize = 14.sp,
            color = Color.Black
        )
    }
}
