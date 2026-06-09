package com.xianxia.sect.ui.game.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.R
import com.xianxia.sect.core.model.ActivityDef
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.game.ActivityViewModel
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.theme.GameColors

private val PanelBg = Color(0xFFF6EBD5)

@Composable
fun ActivityDialog(
    viewModel: ActivityViewModel,
    gameViewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val selectedActivityId by viewModel.selectedActivityId.collectAsStateWithLifecycle()
    val selectedActivity = activities.find { it.id == selectedActivityId }

    BackHandler(onBack = onDismiss)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GameColors.PageBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "活动",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    CloseButton(onClick = onDismiss)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.2f)
                            .fillMaxHeight()
                            .padding(end = 4.dp)
                    ) {
                        if (activities.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                items(activities, key = { it.id }) { activity ->
                                    ActivityCard(
                                        activity = activity,
                                        isSelected = activity.id == selectedActivityId,
                                        onClick = { viewModel.selectActivity(activity.id) }
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFBDBDBD))
                    )

                    Column(
                        modifier = Modifier
                            .weight(0.8f)
                            .fillMaxHeight()
                            .padding(start = 8.dp)
                    ) {
                        if (selectedActivity != null) {
                            when (selectedActivity.id) {
                                "daily_sign_in" -> DailySignInPanel(viewModel = gameViewModel)
                                else -> ActivityDetailPanel(activity = selectedActivity)
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "暂无活动",
                                    fontSize = 12.sp,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: ActivityDef,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.bg_dialog_mail),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0x33FFD700))
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = activity.name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActivityDetailPanel(activity: ActivityDef) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PanelBg, RoundedCornerShape(4.dp))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(activity.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            val startDate = formatActivityDate(activity.startTime)
            val endDate = formatActivityDate(activity.endTime)
            Text("活动时间：$startDate - $endDate", fontSize = 10.sp, color = Color.Black)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp)
        ) {
            Text(activity.description, fontSize = 12.sp, color = Color.Black)

            if (activity.rewardPreview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("奖励预览", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                Spacer(modifier = Modifier.height(4.dp))
                activity.rewardPreview.forEach { reward ->
                    Text(
                        "${reward.name} x${reward.quantity}",
                        fontSize = 10.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

private fun formatActivityDate(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = epochMillis
    val year = calendar.get(java.util.Calendar.YEAR)
    val month = calendar.get(java.util.Calendar.MONTH) + 1
    val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
    return "$year.${month.toString().padStart(2, '0')}.${day.toString().padStart(2, '0')}"
}
