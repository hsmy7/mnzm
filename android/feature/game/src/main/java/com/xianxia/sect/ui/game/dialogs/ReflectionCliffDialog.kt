package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.morality
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.PortraitDiscipleCard
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.game.dialogs.shared.ScrollableInfoDialog



@Composable
fun ReflectionCliffDialog(
    disciples: List<DiscipleAggregate>,
    gameData: GameData?,
    onDismiss: () -> Unit,
    onExpelDisciple: (String) -> Unit = {},
    onReleaseDisciple: (String) -> Unit = {}
) {
    val reflectingDisciples = disciples.filter { it.status == DiscipleStatus.REFLECTING }
    var showExpelConfirmDialog by remember { mutableStateOf<DiscipleAggregate?>(null) }

    ScrollableInfoDialog(
        title = "监牢",
        onDismiss = onDismiss
    ) {
        ReflectionCliffContent(
            reflectingDisciples = reflectingDisciples,
            gameData = gameData,
            onReleaseDisciple = onReleaseDisciple,
            onExpel = { showExpelConfirmDialog = it }
        )
    }

    showExpelConfirmDialog?.let { disciple ->
        ReflectionExpelConfirmDialog(
            disciple = disciple,
            onExpel = {
                onExpelDisciple(disciple.id)
                showExpelConfirmDialog = null
            },
            onDismiss = { showExpelConfirmDialog = null }
        )
    }
}

/** 监牢主体内容区：标语 + 空态或思过弟子网格 */
@Composable
private fun ReflectionCliffContent(
    reflectingDisciples: List<DiscipleAggregate>,
    gameData: GameData?,
    onReleaseDisciple: (String) -> Unit,
    onExpel: (DiscipleAggregate) -> Unit
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
            ReflectionEmptyState()
        } else {
            Text(
                text = "当前思过弟子: ${reflectingDisciples.size}人",
                fontSize = 11.sp,
                color = Color.Black
            )
            ReflectionDiscipleGrid(
                reflectingDisciples = reflectingDisciples,
                gameData = gameData,
                onReleaseDisciple = onReleaseDisciple,
                onExpel = onExpel
            )
        }
    }
}

/** 无思过弟子空态 */
@Composable
private fun ReflectionEmptyState() {
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
                color = GameColors.DividerGray
            )
        }
    }
}

/** 思过弟子网格：卡片 + 释放/驱逐操作 */
@Composable
private fun ReflectionDiscipleGrid(
    reflectingDisciples: List<DiscipleAggregate>,
    gameData: GameData?,
    onReleaseDisciple: (String) -> Unit,
    onExpel: (DiscipleAggregate) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(reflectingDisciples, key = { it.id }, contentType = { "disciple" }) { disciple ->
            val startYear = disciple.statusData["reflectionStartYear"]?.toIntOrNull() ?: 1
            val endYear = disciple.statusData["reflectionEndYear"]?.toIntOrNull() ?: (startYear + 10)
            val remainingYears = (endYear - (gameData?.gameYear ?: 1)).coerceAtLeast(0)
            PortraitDiscipleCard(
                disciple = disciple,
                extraAttributes = listOf("道德" to disciple.morality, "思过" to remainingYears),
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF27AE60))
                                .clickable { onReleaseDisciple(disciple.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(text = "释放", fontSize = 10.sp, color = Color.White)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFE74C3C))
                                .clickable { onExpel(disciple) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(text = "驱逐", fontSize = 10.sp, color = Color.White)
                        }
                    }
                },
                onClick = {}
            )
        }
    }
}

/** 驱逐确认弹窗 */
@Composable
private fun ReflectionExpelConfirmDialog(
    disciple: DiscipleAggregate,
    onExpel: () -> Unit,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "确认驱逐",
        mode = DialogMode.Half
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "确定要驱逐弟子 ${disciple.name} 吗？此操作不可撤销。",
                fontSize = 12.sp,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GameButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
                GameButton(
                    text = "确认",
                    onClick = onExpel,
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
            }
        }
    }
}
