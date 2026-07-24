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
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.theme.GameColors

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
    onDismiss: () -> Unit,
    discipleTables: DiscipleTables? = null
) {
    var selectedTaskId by remember { mutableIntStateOf(1) }
    val selectedTask = remember(selectedTaskId, allTasks) {
        allTasks.find { it.id == selectedTaskId }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5DC))    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ======== 顶部栏 ========
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF5F5DC))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "修仙引导",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
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
                    color = GameColors.Border,
                    modifier = Modifier.fillMaxHeight(),
                    thickness = 1.dp
                )

                // ---- 中栏：任务详情 ----
                TaskDetailColumn(
                    selectedTask = selectedTask,
                    gameData = gameData,
                    discipleTables = discipleTables,
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                )

                // 分隔线
                VerticalDivider(
                    color = GameColors.Border,
                    modifier = Modifier.fillMaxHeight(),
                    thickness = 1.dp
                )

                // ---- 右栏：奖励 ----
                RewardColumn(
                    selectedTask = selectedTask,
                    isClaimed = selectedTask?.let { it.id in claimedRewardIds } ?: false,
                    isCompleted = selectedTask?.let { task ->
                        task.conditions.all { condition -> condition.isMet(gameData, discipleTables) }
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
        modifier = modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp)
    ) {
        items(allTasks, key = { it.id }) { task ->
            val isSelected = task.id == selectedTaskId
            val isCompleted = task.id in claimedRewardTaskIds
            val prefix = if (isCompleted) "✓ " else "○ "
            val prefixColor = if (isCompleted) GameColors.Success else Color(0xFF999999)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) Color(0xFFE9E4DF) else Color.Transparent
                    )
                    .clickable { onTaskSelected(task.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = prefix,
                    fontSize = 13.sp,
                    color = prefixColor
                )
                Text(
                    text = task.name,
                    fontSize = 13.sp,
                    color = Color.Black,
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
    discipleTables: DiscipleTables?,
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
                color = Color(0xFF999999)
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
                color = Color(0xFF333333),
                lineHeight = 22.sp,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        }

        // 描述与条件之间的分隔线
        HorizontalDivider(
            color = GameColors.Border,
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
                    color = Color(0xFF999999)
                )
            } else {
                LazyColumn {
                    items(selectedTask.conditions) { condition ->
                        ConditionItem(
                            condition = condition,
                            gameData = gameData,
                            discipleTables = discipleTables
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
    gameData: GameData,
    discipleTables: DiscipleTables?
) {
    val isMet = condition.isMet(gameData, discipleTables)
    val progressText = condition.progressText(gameData)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
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
                color = Color(0xFF999999)
            )
            return@Box
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 奖励物品卡片（含物品图标、名称、数量角标）
            UnifiedItemCard(
                data = ItemCardData(
                    id = "guide_reward_${selectedTask.id}",
                    name = selectedTask.rewardItemName,
                    rarity = 1,
                    quantity = selectedTask.rewardItemQuantity,
                    isBag = true
                ),
                size = 80.dp,
                showQuantity = true,
                onClick = {}
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
