package com.xianxia.sect.taptap

import android.app.Activity
import android.util.Log
import com.tapsdk.tapad.AdRequest
import com.tapsdk.tapad.TapAdManager
import com.tapsdk.tapad.TapAdNative
import com.tapsdk.tapad.TapRewardVideoAd

/**
 * Dirichlet Ad SDK 激励视频广告管理器
 *
 * 负责激励视频广告的加载、展示、回调和资源释放。
 */
object RewardVideoAdManager {

    private const val TAG = "RewardVideoAdManager"

    // 广告位 ID（Dirichlet Ad 后台获取）
    private const val SPACE_ID: Long = 1056479

    private var tapAdNative: TapAdNative? = null
    private var rewardVideoAd: TapRewardVideoAd? = null
    private var isAdLoading = false

    // 回调接口
    private var callback: RewardVideoCallback? = null

    interface RewardVideoCallback {
        /** 广告加载失败 */
        fun onAdLoadError(code: Int, message: String) {}
        /** 广告加载成功 */
        fun onAdLoaded() {}
        /** 广告素材缓存完成（建议在此回调后展示广告，体验更好） */
        fun onAdCached() {}
        /** 广告已展示 */
        fun onAdShow() {}
        /** 广告已关闭 */
        fun onAdClose() {}
        /** 视频播放完成 */
        fun onVideoComplete() {}
        /** 视频播放出错 */
        fun onVideoError() {}
        /** 激励验证回调 - 在此决定是否发放奖励 */
        fun onRewardVerify(rewardVerify: Boolean, rewardAmount: Int, rewardName: String, code: Int, msg: String) {}
        /** 用户跳过了视频 */
        fun onSkippedVideo() {}
        /** 广告被点击 */
        fun onAdClick() {}
        /** 广告有效曝光 */
        fun onAdValidShow() {}
    }

    fun setCallback(callback: RewardVideoCallback) {
        this.callback = callback
    }

    fun removeCallback() {
        this.callback = null
    }

    /**
     * 加载激励视频广告
     *
     * @param activity Activity 上下文
     * @param userId 用户 ID（用于 S2S 验证，如不需要可传空）
     * @param rewardName 奖品名称
     * @param rewardAmount 奖品数量
     * @param extraInfo 附加信息（用于 S2S 验证，如不需要可传空）
     */
    fun loadAd(
        activity: Activity,
        userId: String = "",
        rewardName: String = "奖励",
        rewardAmount: Int = 1,
        extraInfo: String = ""
    ) {
        if (isAdLoading) {
            Log.d(TAG, "广告正在加载中，请勿重复请求")
            return
        }

        // 释放上一次的广告资源
        destroyAd()

        isAdLoading = true

        // 创建广告加载器（一个 Activity 中只需创建一个 TapAdNative 对象）
        tapAdNative = TapAdManager.get().createAdNative(activity)

        // 构建广告请求
        val adRequestBuilder = AdRequest.Builder()
            .withSpaceId(SPACE_ID)
            .withRewardName(rewardName)
            .withRewardAmount(rewardAmount)

        if (userId.isNotEmpty()) {
            adRequestBuilder.withUserId(userId)
        }
        if (extraInfo.isNotEmpty()) {
            adRequestBuilder.withExtra1(extraInfo)
        }

        val adRequest = adRequestBuilder.build()

        tapAdNative?.loadRewardVideoAd(adRequest, object : TapAdNative.RewardVideoAdListener {
            override fun onError(code: Int, message: String) {
                isAdLoading = false
                Log.e(TAG, "激励视频广告加载失败: code=$code, message=$message")
                callback?.onAdLoadError(code, message)
            }

            override fun onRewardVideoAdLoad(ad: TapRewardVideoAd) {
                isAdLoading = false
                this@RewardVideoAdManager.rewardVideoAd = ad
                Log.d(TAG, "激励视频广告加载成功")
                callback?.onAdLoaded()
            }

            override fun onRewardVideoCached(ad: TapRewardVideoAd) {
                this@RewardVideoAdManager.rewardVideoAd = ad
                Log.d(TAG, "激励视频广告素材缓存完成")
                callback?.onAdCached()
            }
        })
    }

    /**
     * 展示激励视频广告
     *
     * @param activity Activity 上下文
     * @return 是否成功展示（广告未加载或已失效时返回 false）
     */
    fun showAd(activity: Activity): Boolean {
        val ad = rewardVideoAd
        if (ad == null) {
            Log.w(TAG, "广告未加载，无法展示")
            return false
        }

        // 注册交互事件监听
        ad.setRewardAdInteractionListener(object : TapRewardVideoAd.RewardAdInteractionListener {
            override fun onAdShow(ad: TapRewardVideoAd) {
                Log.d(TAG, "激励广告已展示")
                callback?.onAdShow()
            }

            override fun onAdClose(ad: TapRewardVideoAd) {
                Log.d(TAG, "激励广告已关闭")
                callback?.onAdClose()
            }

            override fun onVideoComplete(ad: TapRewardVideoAd) {
                Log.d(TAG, "视频播放结束")
                callback?.onVideoComplete()
            }

            override fun onVideoError(ad: TapRewardVideoAd) {
                Log.e(TAG, "视频播放出错")
                callback?.onVideoError()
            }

            override fun onRewardVerify(
                ad: TapRewardVideoAd,
                rewardVerify: Boolean,
                rewardAmount: Int,
                rewardName: String,
                code: Int,
                msg: String
            ) {
                Log.d(TAG, "激励验证: verify=$rewardVerify, amount=$rewardAmount, name=$rewardName, code=$code, msg=$msg")
                callback?.onRewardVerify(rewardVerify, rewardAmount, rewardName, code, msg)
            }

            override fun onSkippedVideo(ad: TapRewardVideoAd) {
                Log.d(TAG, "用户跳过了视频")
                callback?.onSkippedVideo()
            }

            override fun onAdClick(ad: TapRewardVideoAd) {
                Log.d(TAG, "激励广告被点击")
                callback?.onAdClick()
            }

            override fun onAdValidShow(ad: TapRewardVideoAd) {
                Log.d(TAG, "激励广告有效曝光")
                callback?.onAdValidShow()
            }
        })

        ad.showRewardVideoAd(activity)
        return true
    }

    /** 广告是否已加载且可用 */
    fun isAdReady(): Boolean = rewardVideoAd != null

    /** 释放广告资源，在 Activity 销毁时调用 */
    fun destroyAd() {
        rewardVideoAd = null
        tapAdNative = null
        isAdLoading = false
    }
}
