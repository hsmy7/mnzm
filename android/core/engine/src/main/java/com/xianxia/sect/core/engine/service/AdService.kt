package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService

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
}

/**
 * 激励视频广告用途枚举。
 * 新增广告类型在此追加枚举值，无需改 ViewModel 或创建新回调字段。
 */
enum class AdPurpose {
    /** 突破修炼奖励广告 */
    BREAKTHROUGH_BONUS,
    /** 商人手动刷新次数广告 */
    MERCHANT_REFRESH
}
