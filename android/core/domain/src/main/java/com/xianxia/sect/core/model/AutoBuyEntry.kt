package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

/**
 * 自动购买条目 — 玩家在商人界面配置的自动购买列表项。
 *
 * 不存储购买数量，每次触发时购买商人库存的最大值。
 * 唯一键: "$itemName:$itemType:$rarity" 用于去重匹配。
 */
@Keep
@Serializable
data class AutoBuyEntry(
    val itemName: String,
    val itemType: String,
    val rarity: Int
)

/**
 * 自动购买目录条目 — 用于"新增物品"选择界面的数据。
 * 从 [MerchantAndRecruitService.buildMerchantItemPools] 构建，
 * 按 rarity 降序排列。
 */
data class AutoBuyCatalogItem(
    val name: String,
    val type: String,
    val rarity: Int
)
