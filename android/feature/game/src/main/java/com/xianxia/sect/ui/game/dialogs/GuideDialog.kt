package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.guide.GuideCondition
import com.xianxia.sect.core.model.guide.GuideTask
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton

/**
 * 全屏修仙引导教程对话框。
 *
 * 布局：
 * - 顶部栏：标题 "修仙引导" + CloseButton
 * - 三栏等分，1dp 灰色竖线分隔
 *   - 左栏 (weight 0.2)：任务列表（LazyColumn）
 *   - 中栏 (weight 0.6)：任务详情，上 50% 描述文本 + 下 50% 条件进度列表
 *   - 右栏 (weight 0.2)：奖励展示与领取按钮
 */
@Composable
fun GuideDialog(
    gameData: GameData,
    claimedRewardIds: Set<Int>,
    allTasks: List<GuideTask>,
    onClaimReward: (taskId: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTaskId by remember { mutableIntStateOf(1) }
    val selectedTask = remember(selectedTaskId, allTasks) {
        allTasks.find { it.id == selectedTaskId }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC1A1A2E))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ======== 顶部栏 ========
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A2E))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "修仙引导",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                CloseButton(onClick = onDismiss)
            }

            // ======== 三栏内容 ========
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                // ---- 左栏：任务列表 ----
                TaskListColumn(
                    allTasks = allTasks,
                    claimedRewardTaskIds = claimedRewardIds,
                    selectedTaskId = selectedTaskId,
                    onTaskSelected = { selectedTaskId = it },
                    modifier = Modifier
                        .weight(0.2f)
                        .fillMaxHeight()
                )

                // 分隔线
                VerticalDivider(
                    color = Color.Gray,
                    modifier = Modifier.fillMaxHeight(),
                    thickness = 1.dp
                )

                // ---- 中栏：任务详情 ----
                TaskDetailColumn(
                    selectedTask = selectedTask,
                    gameData = gameData,
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                )

                // 分隔线
                VerticalDivider(
                    color = Color.Gray,
                    modifier = Modifier.fillMaxHeight(),
                    thickness = 1.dp
                )

                // ---- 右栏：奖励 ----
                RewardColumn(
                    selectedTask = selectedTask,
                    isClaimed = selectedTask?.let { it.id in claimedRewardIds } ?: false,
                    isCompleted = selectedTask?.let { task ->
                        task.conditions.all { condition -> condition.isMet(gameData) }
                    } ?: false,
                    onClaimReward = { taskId ->
                        onClaimReward(taskId)
                    },
                    modifier = Modifier
                        .weight(0.2f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

// ==================== 左栏：任务列表 ====================

@Composable
private fun TaskListColumn(
    allTasks: List<GuideTask>,
    claimedRewardTaskIds: Set<Int>,
    selectedTaskId: Int,
    onTaskSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.padding(vertical = 8.dp)
    ) {
        items(allTasks, key = { it.id }) { task ->
            val isSelected = task.id == selectedTaskId
            val isCompleted = task.id in claimedRewardTaskIds

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) Color(0x334B6E8E) else Color.Transparent
                    )
                    .clickable { onTaskSelected(task.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.name,
                    fontSize = 13.sp,
                    color = if (isCompleted) Color(0xFF4CAF50) else Color(0xFFE53935),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ==================== 中栏：任务详情 ====================

@Composable
private fun TaskDetailColumn(
    selectedTask: GuideTask?,
    gameData: GameData,
    modifier: Modifier = Modifier
) {
    if (selectedTask == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "请选择一个任务",
                fontSize = 14.sp,
                color = Color(0xFFAAAAAA)
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---- 上 50%：任务描述 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .padding(16.dp)
        ) {
            Text(
                text = selectedTask.description,
                fontSize = 14.sp,
                color = Color.White,
                lineHeight = 22.sp,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        }

        // 描述与条件之间的分隔线
        HorizontalDivider(
            color = Color(0xFF555555),
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp
        )

        // ---- 下 50%：条件列表 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
                .padding(16.dp)
        ) {
            if (selectedTask.conditions.isEmpty()) {
                Text(
                    text = "无条件限制",
                    fontSize = 13.sp,
                    color = Color(0xFFAAAAAA)
                )
            } else {
                LazyColumn {
                    items(selectedTask.conditions) { condition ->
                        ConditionItem(
                            condition = condition,
                            gameData = gameData
                        )
                    }
                }
            }
        }
    }
}

// ==================== 单行条件 ====================

@Composable
private fun ConditionItem(
    condition: GuideCondition,
    gameData: GameData
) {
    val isMet = condition.isMet(gameData)
    val progressText = condition.progressText(gameData)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = if (isMet) "✓ " else "□ "
        val textColor = if (isMet) Color(0xFF4CAF50) else Color(0xFFE53935)

        Text(
            text = icon + condition.label + " " + progressText,
            fontSize = 13.sp,
            color = textColor
        )
    }
}

// ==================== 右栏：奖励 ====================

@Composable
private fun RewardColumn(
    selectedTask: GuideTask?,
    isClaimed: Boolean,
    isCompleted: Boolean,
    onClaimReward: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (selectedTask == null) {
            Text(
                text = "请选择一个任务",
                fontSize = 13.sp,
                color = Color(0xFFAAAAAA)
            )
            return@Box
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 奖励物品名称
            Text(
                text = selectedTask.rewardItemName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 奖励数量
            Text(
                text = "×${selectedTask.rewardItemQuantity}",
                fontSize = 14.sp,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 领取按钮
            val buttonText: String
            val buttonEnabled: Boolean

            if (isClaimed) {
                buttonText = "已领取"
                buttonEnabled = false
            } else if (isCompleted) {
                buttonText = "领取"
                buttonEnabled = true
            } else {
                buttonText = "领取"
                buttonEnabled = false
            }

            GameButton(
                text = buttonText,
                onClick = { onClaimReward(selectedTask.id) },
                enabled = buttonEnabled
            )
        }
    }
}
