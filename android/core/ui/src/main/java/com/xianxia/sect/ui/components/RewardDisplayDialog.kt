package com.xianxia.sect.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.mergeRewardCards

/**
 * 通用奖励展示小屏界面 — 先展示物品卡片，玩家确认后播放入队动效。
 *
 * 使用方式：
 * ```kotlin
 * RewardDisplayDialog(
 *     title = "发放成功",
 *     cards = rewardCards,
 *     onConfirm = { /* 入队卡片开始动效 */ }
 * )
 * ```
 */
@Composable
fun RewardDisplayDialog(
    title: String,
    cards: List<RewardCardItem>,
    confirmLabel: String = "确定",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit = onConfirm
) {
    SmallScreenDialog(
        onDismissRequest = onDismiss,
        title = title,
        footer = {
            GameButton(text = confirmLabel, onClick = onConfirm)
        }
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            // 展示前聚合：多个相同物品堆叠为一张 xN 卡片，与飞出动画保持一致
            cards.mergeRewardCards().forEach { card ->
                UnifiedItemCard(
                    data = card.toItemCardData(),
                    showQuantity = true
                )
            }
        }
    }
}

/** 将奖励卡片转换为 [ItemCardData]，供 [UnifiedItemCard] 渲染精灵图 */
fun RewardCardItem.toItemCardData(): ItemCardData = ItemCardData(
    name = itemName,
    rarity = rarity,
    quantity = quantity,
    type = itemType,
    isPill = itemType == "pill",
    isHerb = itemType == "herb",
    isMaterial = itemType in listOf("material", "beastMaterial"),
    isSeed = itemType == "seed",
    spiritStoneGrade = if (itemType == "spiritStones") com.xianxia.sect.core.model.SpiritStoneGrade.LOW else null,
    isBag = itemType == "storageBag",
    isManual = itemType == "manual"
)
