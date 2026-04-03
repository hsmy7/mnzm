package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

/**
 * 经济与交易状态
 *
 * 包含旅行商人商品、玩家上架商品、交易堂买卖列表、交易日志等经济系统数据。
 * 从 GameData 上帝对象中拆分出来，降低 copy() 时的复制开销。
 */
@Serializable
data class EconomicState(
    val travelingMerchantItems: List<MerchantItem> = emptyList(),
    val merchantLastRefreshYear: Int = 0,
    val merchantRefreshCount: Int = 0,
    val playerListedItems: List<MerchantItem> = emptyList()
)
