package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.config.SectLevelRewardConfig
import com.xianxia.sect.core.config.UpgradeConditionState
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.theme.GameColors
import com.xianxia.sect.ui.components.clickableWithSound

/**
 * 宗门等级详情半屏界面。
 *
 * 布局：
 * - 区域 1（居中上）：等级名称 + 左右翻页箭头
 * - 区域 2（居中）  ：升级条件（复选框 + 条件文本）+ 升级按钮
 * - 右下角         ：奖励按钮（仅当前等级可见，红点驱动可领取状态）
 */
@Composable
fun SectLevelDetailDialog(
    gameData: GameData,
    aliveDisciples: List<DiscipleAggregate>,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val playerLevel by viewModel.playerSectLevel.collectAsStateWithLifecycle()
    val rewardClaimable by viewModel.sectLevelRewardClaimable.collectAsStateWithLifecycle()

    // 当前浏览的等级（0-3），默认显示玩家当前等级
    var viewedLevel by remember(playerLevel) { mutableIntStateOf(playerLevel) }

    // 奖励子界面控制
    var showRewardDialog by remember { mutableStateOf(false) }

    // 计算升级条件状态（仅当 viewedLevel == playerLevel 时显示）
    val upgradeConditions = remember(viewedLevel, playerLevel, aliveDisciples, gameData) {
        computeUpgradeConditions(
            viewedLevel = viewedLevel,
            playerLevel = playerLevel,
            aliveDisciples = aliveDisciples,
            gameData = gameData
        )
    }

    val allConditionsMet = upgradeConditions.isNotEmpty() && upgradeConditions.all { it.isMet }
    val isViewingCurrentLevel = viewedLevel == playerLevel

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "宗门等级",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        SectLevelDialogContent(
            contentData = SectLevelContentData(
                viewedLevel = viewedLevel,
                playerLevel = playerLevel,
                upgradeConditions = upgradeConditions,
                allConditionsMet = allConditionsMet,
                isViewingCurrentLevel = isViewingCurrentLevel,
                rewardClaimable = rewardClaimable
            ),
            onPrevious = { viewedLevel-- },
            onNext = { viewedLevel++ },
            onUpgrade = { viewModel.upgradeSectLevel() },
            onRewardClick = { showRewardDialog = true }
        )
    }

    // 奖励子界面
    if (showRewardDialog) {
        SectLevelRewardDialog(
            level = playerLevel,
            viewModel = viewModel,
            onDismiss = { showRewardDialog = false }
        )
    }
}

/** 宗门等级详情内容数据（SectLevelDetailDialog 拆分） */
private data class SectLevelContentData(
    val viewedLevel: Int,
    val playerLevel: Int,
    val upgradeConditions: List<UpgradeConditionState>,
    val allConditionsMet: Boolean,
    val isViewingCurrentLevel: Boolean,
    val rewardClaimable: Boolean
)

/** 宗门等级详情内容（SectLevelDetailDialog 拆分）：区域 1 翻页 + 区域 2 条件 + 右下角奖励按钮 */
@Composable
private fun SectLevelDialogContent(
    contentData: SectLevelContentData,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onUpgrade: () -> Unit,
    onRewardClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ======== 区域 1: 等级名称 + 翻页箭头 ========
            SectLevelHeaderRow(
                viewedLevel = contentData.viewedLevel,
                playerLevel = contentData.playerLevel,
                onPrevious = onPrevious,
                onNext = onNext
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ======== 区域 2: 升级条件 + 升级按钮 ========
            SectLevelUpgradeSection(
                viewedLevel = contentData.viewedLevel,
                playerLevel = contentData.playerLevel,
                upgradeConditions = contentData.upgradeConditions,
                allConditionsMet = contentData.allConditionsMet,
                onUpgrade = onUpgrade
            )

            Spacer(modifier = Modifier.height(80.dp)) // 底部留白给奖励按钮
        }

        // ======== 奖励按钮（右下角） ========
        if (contentData.isViewingCurrentLevel) {
            SectLevelRewardButton(
                rewardClaimable = contentData.rewardClaimable,
                onRewardClick = onRewardClick
            )
        }
    }
}

/** 升级条件状态计算（SectLevelDetailDialog 拆分）：仅当前等级且未满级时计算 */
private fun computeUpgradeConditions(
    viewedLevel: Int,
    playerLevel: Int,
    aliveDisciples: List<DiscipleAggregate>,
    gameData: GameData
): List<UpgradeConditionState> =
    if (viewedLevel == playerLevel && viewedLevel < SectLevel.TOP) {
        val targetLevel = viewedLevel + 1
        val highestRealm = aliveDisciples.filter { it.isAlive }
            .minOfOrNull { it.realm } ?: 9
        val occupiedSectLevels = gameData.worldMapSects
            .filter { it.isPlayerOccupied }
            .map { it.level }
        SectLevelRewardConfig.getUpgradeConditionStates(
            targetLevel, highestRealm, occupiedSectLevels
        )
    } else {
        emptyList()
    }

/** 等级名称 + 左右翻页箭头（SectLevelDetailDialog 拆分） */
@Composable
private fun SectLevelHeaderRow(
    viewedLevel: Int,
    playerLevel: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 左箭头
        val canGoLeft = viewedLevel > SectLevel.SMALL
        Text(
            text = "◀",
            fontSize = 24.sp,
            color = if (canGoLeft) Color.Black else Color.Gray,
            modifier = Modifier
                .clickableWithSound(enabled = canGoLeft) {
                    if (canGoLeft) onPrevious()
                }
                .padding(8.dp)
        )

        Spacer(modifier = Modifier.width(24.dp))

        // 等级名称
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = SectLevel.levelName(viewedLevel),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            if (viewedLevel > playerLevel) {
                Text(
                    text = "🔒 未解锁",
                    fontSize = 12.sp,
                    color = Color(0xFF999999)
                )
            }
        }

        Spacer(modifier = Modifier.width(24.dp))

        // 右箭头
        val canGoRight = viewedLevel < SectLevel.TOP
        Text(
            text = "▶",
            fontSize = 24.sp,
            color = if (canGoRight) Color.Black else Color.Gray,
            modifier = Modifier
                .clickableWithSound(enabled = canGoRight) {
                    if (canGoRight) onNext()
                }
                .padding(8.dp)
        )
    }
}

/** 升级条件 + 晋升按钮（SectLevelDetailDialog 拆分） */
@Composable
private fun SectLevelUpgradeSection(
    viewedLevel: Int,
    playerLevel: Int,
    upgradeConditions: List<UpgradeConditionState>,
    allConditionsMet: Boolean,
    onUpgrade: () -> Unit
) {
    val isAtMaxLevel = playerLevel >= SectLevel.TOP
    if (viewedLevel == playerLevel && viewedLevel < SectLevel.TOP) {
        val targetLevel = viewedLevel + 1
        Text(
            text = "晋升至${SectLevel.levelName(targetLevel)}的条件",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (upgradeConditions.isEmpty()) {
            Text(
                text = "无法晋升（配置缺失）",
                fontSize = 12.sp,
                color = GameColors.Error
            )
        } else {
            upgradeConditions.forEach { condition ->
                ConditionRow(condition = condition)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 升级按钮
        GameButton(
            text = "晋升",
            onClick = onUpgrade,
            enabled = allConditionsMet
        )
    } else if (viewedLevel == playerLevel && isAtMaxLevel) {
        Text(
            text = "已达最高等级",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = GameColors.Success
        )
    } else if (viewedLevel > playerLevel) {
        Text(
            text = "需先达到${SectLevel.levelName(viewedLevel)}方可查看升级条件",
            fontSize = 12.sp,
            color = Color(0xFF999999)
        )
    }
}

/** 每周奖励按钮（右下角，含红点）（SectLevelDetailDialog 拆分） */
@Composable
private fun BoxScope.SectLevelRewardButton(
    rewardClaimable: Boolean,
    onRewardClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 12.dp, bottom = 12.dp)
    ) {
        GameButton(
            text = "每周奖励",
            onClick = onRewardClick,
            width = 90.dp,
            height = 38.dp
        )
        // 红点
        if (rewardClaimable) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(8.dp)
                    .background(Color.Red, CircleShape)
            )
        }
    }
}

/**
 * 单行升级条件：文本 + 勾选框居中排列。
 */
@Composable
private fun ConditionRow(condition: UpgradeConditionState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = condition.description,
            fontSize = 13.sp,
            color = if (condition.isMet) Color(0xFF2E7D32) else Color(0xFFC62828)
        )
        Spacer(modifier = Modifier.width(8.dp))
        // 勾选框
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (!condition.isMet) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
            if (condition.isMet) {
                Text(
                    text = "✓",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GameColors.Success
                )
            }
        }
    }
}

