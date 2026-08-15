package com.xianxia.sect.analytics

import org.json.JSONObject

/**
 * 构建 TapDB `#ad_show` 事件属性（纯函数，无 SDK 依赖，可单测）。
 *
 * 字段对齐 TapDB 官方文档（客户端接入-广告变现收入）：
 * `#ad_union_type`/`#ad_placement_id`/`#ad_source_id`/`#ad_type`/`#ad_network` 可选，
 * `#ecpm`（预估 eCPM、单位分）与 `currency_type`（ISO 4217）必需。
 */
object AdRevenueEventBuilder {

    private const val KEY_UNION_TYPE = "#ad_union_type"
    private const val KEY_PLACEMENT_ID = "#ad_placement_id"
    private const val KEY_AD_TYPE = "#ad_type"
    private const val KEY_AD_NETWORK = "#ad_network"
    private const val KEY_ECPM = "#ecpm"
    private const val KEY_CURRENCY = "currency_type"

    /** 构建事件属性 JSON；adNetwork 为空时省略可选字段 `#ad_network` */
    fun build(config: AdRevenueConfig): JSONObject {
        val json = JSONObject()
        json.put(KEY_UNION_TYPE, config.unionType)
        json.put(KEY_PLACEMENT_ID, config.spaceId.toString())
        json.put(KEY_AD_TYPE, config.adType)
        config.adNetwork?.let { json.put(KEY_AD_NETWORK, it) }
        json.put(KEY_ECPM, config.estimatedEcpmFen)
        json.put(KEY_CURRENCY, config.currency)
        return json
    }
}
