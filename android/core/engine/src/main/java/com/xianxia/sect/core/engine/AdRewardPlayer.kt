package com.xianxia.sect.core.engine

/**
 * 激励视频广告播放接口。
 *
 * 由 [GameViewModel] 注入使用，[AdRewardPlayerImpl] 在 :app 模块中实现。
 * 职责分离：核心层定义接口，应用层实现广告 SDK 调用。
 */
fun interface AdRewardPlayer {
    /** 播放激励视频广告，成功后回调 [onReward]。 */
    fun playRewardedAd(spaceId: Long, onReward: () -> Unit)
}
