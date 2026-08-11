package com.xianxia.sect.core.engine.service


/**
 * 广告服务接口 — 激励视频广告的统一入口。
 *
 * 实现类在 [:app] 模块（[com.xianxia.sect.taptap] 包），
 * 通过 Hilt [@Binds] 注入到 ViewModel。
 *
 * ## 设计说明
 * - 新增广告类型只需在 [AdPurpose] 中追加枚举值
 * - 实现类内部持有 Activity 引用（由 GameActivity 在 onCreate 时注入）
 * - 免广告特权用户不触发广告播放，直接调用 [onReward]
 */
interface AdService {

    /**
     * 观看激励视频广告。
     *
     * @param purpose 广告用途（决定奖励内容、广告位 ID 等参数）
     * @param onReward 奖励发放回调（广告验证通过后调用）
     */
    fun watchAd(
        purpose: AdPurpose,
        onReward: () -> Unit
    )

    /**
     * 个性化广告是否开启。
     *
     * 合规要求（TapADN SDK 合规使用说明）：App 内必须提供退出个性化广告能力，
     * 退出后广告数量不变、相关度降低。
     *
     * @return true 表示接收个性化广告推荐（默认）
     */
    fun isPersonalizedAdsEnabled(): Boolean

    /**
     * 设置个性化广告开关。
     *
     * @param enabled true 允许个性化广告，false 退出个性化广告
     */
    fun setPersonalizedAdsEnabled(enabled: Boolean)
}

/**
 * 激励视频广告用途枚举。
 * 新增广告类型在此追加枚举值，无需改 ViewModel 或创建新回调字段。
 */
enum class AdPurpose {
    /** 观看广告获得玉符 */
    JADE_SYMBOL_BONUS
}
