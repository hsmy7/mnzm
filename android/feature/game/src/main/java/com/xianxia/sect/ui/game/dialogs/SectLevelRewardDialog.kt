package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.config.SectLevelRewardConfig
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.toItemCardData
import com.xianxia.sect.ui.components.getRarityColor
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.theme.GameColors

/**
 * 宗门等级每周奖励领取半屏界面。
 *
 * - 物品卡片居中排列，随机物品显示 ? 图标
 * - 领取后关闭对话框，奖励通过 RewardCardHost 飞出动画展示
 */
@Composable
fun SectLevelRewardDialog(
    level: Int,
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val rewardClaimable by viewModel.sectLevelRewardClaimable.collectAsStateWithLifecycle()
    var hasClaimed by remember { mutableStateOf(false) }

    val rewardCards = remember(level) {
        SectLevelRewardConfig.getRewardCards(level)
    }

    // 领取后关闭对话框触发飞出动画
    LaunchedEffect(hasClaimed) {
        if (hasClaimed) {
            onDismiss()
        }
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "${SectLevel.levelName(level)}每周奖励",
        mode = DialogMode.Half,
        scrollableContent = false
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (rewardCards.isEmpty()) {
                    Text(
                        text = "暂无奖励配置",
                        fontSize = 14.sp,
                        color = Color(0xFF999999)
                    )
                } else {
                    // 奖励物品卡片 — 居中排列
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        rewardCards.forEach { card ->
                            val isRandom = card.itemType == "beastMaterial" &&
                                    card.itemName.startsWith("随机")
                            if (isRandom) {
                                RandomRewardCard(card = card)
                            } else {
                                UnifiedItemCard(
                                    data = card.toItemCardData(),
                                    showQuantity = true,
                                    modifier = Modifier.padding(4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 领取按钮 / 已领取文本
                val alreadyClaimed = hasClaimed || !rewardClaimable
                if (alreadyClaimed) {
                    Text(
                        text = "已领取",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = GameColors.Success
                    )
                } else {
                    GameButton(
                        text = "领取",
                        onClick = {
                            viewModel.claimSectLevelReward(level)
                            hasClaimed = true
                        }
                    )
                }
            }
        }
    }
}

/**
 * 随机物品卡片 — 与 UnifiedItemCard 布局一致，精灵图区域显示 ? 图标。
 */
@Composable
private fun RandomRewardCard(card: RewardCardItem) {
    val rarityColor = getRarityColor(card.rarity)
    Box(
        modifier = Modifier.padding(4.dp).size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .border(2.dp, GameColors.Border, RoundedCornerShape(6.dp))
        ) {
            // 精灵图区域（稀有度颜色背景，? 居中，数量右下角）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(rarityColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "?",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                // 数量（右下角）
                Text(
                    text = GameUtils.formatNumber(card.quantity),
                    fontSize = 8.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 3.dp, bottom = 2.dp)
                )
            }
            // 名称区域（白底黑字）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = card.itemName,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
            }
        }
    }
}
