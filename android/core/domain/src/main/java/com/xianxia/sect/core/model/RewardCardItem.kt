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

/**
 * 合并奖励卡片：按 (itemType, itemName, rarity) 视为同一物品，quantity 求和，
 * 相同物品只保留一张卡片——多个相同物品堆叠为一张 "xN" 卡片一次飞出，
 * 而非逐张卡片依次飞出。
 *
 * 合并规则：
 * - 保留每组首次出现条目的 [RewardCardItem.id]，保证 Compose `key(id)` 稳定，
 *   奖励卡片动画播放中追加同物品时不触发重建、不打断动画
 * - 保持首次出现顺序（LinkedHashMap 迭代序）
 * - 数量求和做 Int 溢出饱和保护（与仓库数量上限一致，仅防御极端发放）
 *
 * 用于奖励卡片入队（[com.xianxia.sect.core.state.GameStateStore.enqueueRewardCards]）
 * 与确认前展示（RewardDisplayDialog）两处统一聚合入口。
 */
fun List<RewardCardItem>.mergeRewardCards(): List<RewardCardItem> {
    if (size <= 1) return this
    val merged = LinkedHashMap<Triple<String, String, Int>, RewardCardItem>(size)
    for (card in this) {
        val key = Triple(card.itemType, card.itemName, card.rarity)
        val existing = merged[key]
        if (existing == null) {
            merged[key] = card
        } else {
            merged[key] = existing.copy(
                quantity = (existing.quantity.toLong() + card.quantity)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            )
        }
    }
    return merged.values.toList()
}
