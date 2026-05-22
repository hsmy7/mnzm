package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xianxia.sect.R
import com.xianxia.sect.core.engine.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.ui.components.GameButton

/**
 * 弟子脱离宗门提示框
 */
@Composable
fun DiscipleDesertionDialog(
    disciple: Disciple,
    onDismiss: () -> Unit
) {
    val config = LocalConfiguration.current
    val dialogWidth = (config.screenWidthDp * 0.4f).dp
    val dialogHeight = (config.screenHeightDp * 0.45f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .width(dialogWidth).height(dialogHeight)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.dialog_box),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${disciple.name}脱离了宗门",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                Box(modifier = Modifier.weight(1f)) {
                    DiscipleSnapshotCard(disciple = disciple)
                }

                GameButton(
                    text = "知道了",
                    onClick = onDismiss
                )
            }
        }
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
    val config = LocalConfiguration.current
    val dialogWidth = (config.screenWidthDp * 0.4f).dp
    val dialogHeight = (config.screenHeightDp * 0.45f).dp
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

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .width(dialogWidth).height(dialogHeight)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.dialog_box),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${disciple.name}偷盗被捕",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDiscipleClick(disciple.id) }
                ) {
                    DiscipleSnapshotCard(disciple = disciple)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    GameButton(
                        text = "驱逐",
                        onClick = onExpel
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    GameButton(
                        text = "押入监牢",
                        onClick = onImprison,
                        enabled = hasPrison
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    GameButton(
                        text = "释放",
                        onClick = {
                            loyaltyResult = onRelease()
                        }
                    )
                }
            }
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
    val config = LocalConfiguration.current
    val dialogWidth = (config.screenWidthDp * 0.4f).dp
    val dialogHeight = (config.screenHeightDp * 0.45f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .width(dialogWidth).height(dialogHeight)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.dialog_box),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "忠诚度 +$loyaltyChange",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )

                GameButton(
                    text = "知道了",
                    onClick = onDismiss
                )
            }
        }
    }
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
