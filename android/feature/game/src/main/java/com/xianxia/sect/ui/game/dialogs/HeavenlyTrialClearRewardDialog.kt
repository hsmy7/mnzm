package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.HEAVENLY_TRIAL_CLEAR_REWARDS
import com.xianxia.sect.core.model.HeavenlyTrialSaveData
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.ItemCardData
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.UnifiedItemCard
import com.xianxia.sect.ui.components.getRarityColor

private val PanelBg = Color(0xFFF6EBD5)
private val DividerGray = Color(0xFFBDBDBD)

@Composable
fun HeavenlyTrialClearRewardDialog(
    trialState: HeavenlyTrialSaveData,
    claimableLevels: List<Int>,
    onClaim: (levelIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "通关奖励",
        mode = DialogMode.Half,
        scrollableContent = true
    ) {
        // 所有奖励共用一张纯色背景
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelBg, RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HEAVENLY_TRIAL_CLEAR_REWARDS.forEachIndexed { index, reward ->
                    val isCleared = trialState.isLevelFullyCleared(reward.levelIndex)
                    val isClaimed = trialState.isRewardClaimed(reward.levelIndex)
                    val canClaim = isCleared && !isClaimed
                    ClearRewardRow(
                        label = "通关${reward.label}",
                        items = reward.items,
                        isCleared = isCleared,
                        canClaim = canClaim,
                        onClaim = { onClaim(reward.levelIndex) }
                    )
                    // 关卡之间 1dp 灰色横线（最后一项不加）
                    if (index < HEAVENLY_TRIAL_CLEAR_REWARDS.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(DividerGray)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClearRewardRow(
    label: String,
    items: List<com.xianxia.sect.core.model.ClearRewardItem>,
    isCleared: Boolean,
    canClaim: Boolean,
    onClaim: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 关卡标签
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.width(72.dp)
        )

        // 奖励卡片
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { item ->
                Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(6.dp))) {
                    if (item.isRandom) {
                        // 随机物品：稀有度底色 + "?" 文字 + 底部名称条
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    getRarityColor(item.rarity),
                                    RoundedCornerShape(6.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "?",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        // 底部名称条（clip 在父 Box 限定范围内）
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(14.dp)
                                .background(
                                    Color.White,
                                    RoundedCornerShape(
                                        bottomStart = 6.dp,
                                        bottomEnd = 6.dp
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.itemName,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 1.dp)
                            )
                        }
                    } else {
                        val cardData = ItemCardData(
                            name = item.itemName,
                            rarity = item.rarity,
                            quantity = item.quantity,
                            type = item.itemType,
                            isSpiritStone = item.itemType == "spiritStones",
                            isBag = item.itemType == "storageBag"
                        )
                        UnifiedItemCard(
                            data = cardData,
                            showQuantity = true
                        )
                    }
                    // 红点
                    if (canClaim) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .size(7.dp)
                                .background(Color.Red, CircleShape)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 领取按钮
        if (isCleared && !canClaim) {
            // 已领取：灰色不可点击
            GameButton(
                text = "已领取",
                onClick = {},
                enabled = false
            )
        } else {
            // 未通关或可领取：文字统一为"领取"，可点击性由 canClaim 控制
            GameButton(
                text = "领取",
                onClick = onClaim,
                enabled = canClaim
            )
        }
    }
}
