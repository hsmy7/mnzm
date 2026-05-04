package com.xianxia.sect.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import com.xianxia.sect.R
import com.xianxia.sect.core.model.*
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.components.FollowedTag
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.ui.theme.GameColors

@Composable
fun ReflectionCliffDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    onDismiss: () -> Unit,
    onExpelDisciple: (String) -> Unit = {}
) {
    val reflectingDisciples = disciples.filter { it.status == DiscipleStatus.REFLECTING }
    var showExpelConfirmDialog by remember { mutableStateOf<DiscipleAggregate?>(null) }

    CommonDialog(
        title = "监牢",
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "悔过自新，洗涤心灵",
                fontSize = 10.sp,
                color = Color(0xFF9C27B0)
            )

            if (reflectingDisciples.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "空无一人",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                        Text(
                            text = "监牢目前没有弟子在思过",
                            fontSize = 11.sp,
                            color = Color(0xFFCCCCCC)
                        )
                    }
                }
            } else {
                Text(
                    text = "当前思过弟子: ${reflectingDisciples.size}人",
                    fontSize = 11.sp,
                    color = Color.Black
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(reflectingDisciples) { disciple ->
                        ReflectingDiscipleCard(
                            disciple = disciple,
                            currentYear = gameData?.gameYear ?: 1,
                            onExpelClick = { showExpelConfirmDialog = disciple }
                        )
                    }
                }
            }
        }
    }

    showExpelConfirmDialog?.let { disciple ->
        Dialog(onDismissRequest = { showExpelConfirmDialog = null }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.bg_screen),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds
                )
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "确认驱逐",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "确定要驱逐弟子 ${disciple.name} 吗？此操作不可撤销。",
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GameButton(
                            text = "取消",
                            onClick = { showExpelConfirmDialog = null },
                            modifier = Modifier.width(ButtonSizes.StandardWidth)
                        )
                        GameButton(
                            text = "确认",
                            onClick = {
                                onExpelDisciple(disciple.id)
                                showExpelConfirmDialog = null
                            },
                            modifier = Modifier.width(ButtonSizes.StandardWidth)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReflectingDiscipleCard(
    disciple: DiscipleAggregate,
    currentYear: Int,
    onExpelClick: () -> Unit = {}
) {
    val startYear = disciple.statusData["reflectionStartYear"]?.toIntOrNull() ?: 1
    val endYear = disciple.statusData["reflectionEndYear"]?.toIntOrNull() ?: (startYear + 10)
    val remainingYears = (endYear - currentYear).coerceAtLeast(0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = disciple.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    if (disciple.isFollowed) {
                        FollowedTag()
                    }
                    Text(
                        text = disciple.core.genderSymbol,
                        fontSize = 11.sp,
                        color = if (disciple.gender == "male") Color(0xFF2196F3) else Color(0xFFE91E63)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = disciple.realmName,
                        fontSize = 10.sp,
                        color = Color.Black
                    )
                    Text(
                        text = "道德: ${disciple.morality}",
                        fontSize = 10.sp,
                        color = Color.Black
                    )
                }

                Text(
                    text = "剩余思过: ${remainingYears}年",
                    fontSize = 11.sp,
                    color = Color(0xFF9C27B0),
                    fontWeight = FontWeight.Medium
                )
            }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFE74C3C))
                            .clickable { onExpelClick() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "驱逐",
                            fontSize = 10.sp,
                            color = Color.White
                        )
                    }
                    Text(
                            text = "思过中",
                            fontSize = 10.sp,
                            color = Color.Black
                        )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "第${startYear}年入崖",
                    fontSize = 9.sp,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
private fun CommonDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        ) {
            Image(
                painter = painterResource(id = R.drawable.bg_screen),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds
            )
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    CloseButton(onClick = onDismiss)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    content()
                }
            }
        }
    }
}
