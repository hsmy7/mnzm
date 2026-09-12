package com.xianxia.sect.analytics

/**
 * 广告位变现收入上报配置（TapDB `#ad_show` 事件参数）。
 *
 * [estimatedEcpmFen] 为「预估 eCPM 价格、单位分」：Dirichlet(TapADN) SDK 无客户端 eCPM
 * 回调（5.1.2.3 反编译核实），运营需从 ADN「数据报表」获知各广告位均价后更新本值；
 * 默认 0 表示暂未配置估算（如实上报，不伪造收入）。未来迁移 RemoteConfig（`analytics.ad_ecpm_*` 键）。
 */
data class AdRevenueConfig(
    val spaceId: Long,
    val adType: String,
    val unionType: String,
    val adNetwork: String?,
    val estimatedEcpmFen: Long,
    val currency: String
) {
    companion object {
        /** 玉符激励视频广告位（ADN 后台广告位 ID） */
        private const val JADE_SYMBOL_SPACE_ID = 1061442L

        /** 广告类型：激励视频（TapDB 枚举 reward/banner/native/interstitial/splash） */
        private const val AD_TYPE_REWARD = "reward"

        /** 广告聚合平台：Dirichlet 不在 TapDB 枚举（topon/gromore/admore）内，归入 other */
        private const val UNION_TYPE_OTHER = "other"

        /** 货币类型：人民币（ISO 4217） */
        private const val CURRENCY_CNY = "CNY"

        /**
         * 按广告位 ID 取上报配置；未登记广告位返回 null（不上报）。
         * 新增广告位：在此追加分支并同步知识库事件字典。
         */
        fun forSpaceId(spaceId: Long): AdRevenueConfig? = when (spaceId) {
            JADE_SYMBOL_SPACE_ID -> AdRevenueConfig(
                spaceId = spaceId,
                adType = AD_TYPE_REWARD,
                unionType = UNION_TYPE_OTHER,
                adNetwork = null,
                estimatedEcpmFen = 0L,
                currency = CURRENCY_CNY
            )
            else -> null
        }
    }
}
