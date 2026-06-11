package com.xianxia.sect.core.model

import java.util.UUID

/**
 * 物品获得卡片数据，用于驱动屏幕中央的奖励卡片动效。
 *
 * 精灵图由 UI 层通过 [com.xianxia.sect.ui.components.getRewardSprite]
 * 在渲染时根据 itemType/itemName/rarity 查找。
 */
data class RewardCardItem(
    val id: String = UUID.randomUUID().toString(),
    val itemName: String,
    val itemType: String,
    val rarity: Int,
    val quantity: Int
)
