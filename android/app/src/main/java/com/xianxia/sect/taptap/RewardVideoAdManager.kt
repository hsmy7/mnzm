package com.xianxia.sect.taptap

import android.app.Activity
import android.util.Log
import com.tapsdk.tapad.group.DirichletAdManager
import com.tapsdk.tapad.group.DirichletAdNative
import com.tapsdk.tapad.group.DirichletAdRequest

/**
 * Dirichlet 聚合 SDK 激励视频广告管理器（com.tapsdk.tapad.group API）
 *
 * 负责激励视频广告的加载、展示、回调和资源释放。
 * 使用 [DirichletAdNative.showRewardVideoAutoAd] 自动加载展示合一 API（官方推荐），
 * 替代旧 SDK 的 loadAd → onAdCached → showAd 两段式链路。
 */
object RewardVideoAdManager {

    private const val TAG = "RewardVideoAdManager"

    /** 预热默认数量：提前加载 1 条广告素材 */
    private const val PRELOAD_COUNT = 1

    private var adNative: DirichletAdNative? = null

    // 回调接口
    private var callback: RewardVideoCallback? = null

    interface RewardVideoCallback {
        /** 广告错误（加载失败/展示失败统一入口） */
        fun onAdError(code: Int, message: String) {}
        /** 广告已展示 */
        fun onAdShow() {}
        /** 广告已关闭 */
        fun onAdClose() {}
        /** 激励验证回调 - 在此决定是否发放奖励 */
        fun onRewardVerify(
            rewardVerify: Boolean,
            rewardAmount: Int,
            rewardName: String,
            code: Int,
            msg: String
        ) {}
        /** 广告被点击 */
        fun onAdClick() {}
    }

    fun setCallback(callback: RewardVideoCallback) {
        this.callback = callback
    }

    fun removeCallback() {
        this.callback = null
    }

    /**
     * 展示激励视频广告（自动加载，展示合一）
     *
     * @param activity Activity 上下文
     * @param spaceId 广告位 ID（聚合后台获取）
     * @param rewardName 奖品名称
     * @param rewardAmount 奖品数量
     */
    fun showAd(
        activity: Activity,
        spaceId: Long,
        rewardName: String,
        rewardAmount: Int
    ) {
        val request = DirichletAdRequest.Builder()
            .withSpaceId(spaceId)
            .withRewardName(rewardName)
            .withRewardAmount(rewardAmount)
            .build()

        createAdNative(activity).showRewardVideoAutoAd(
            request,
            activity,
            object : DirichletAdNative.RewardVideoAutoAdListener {
                override fun onError(code: Int, message: String) {
                    Log.e(TAG, "激励视频广告错误: spaceId=$spaceId, code=$code, message=$message")
                    callback?.onAdError(code, message)
                }

                override fun onAdShow() {
                    Log.d(TAG, "激励广告已展示: spaceId=$spaceId")
                    callback?.onAdShow()
                }

                override fun onAdClose() {
                    Log.d(TAG, "激励广告已关闭: spaceId=$spaceId")
                    callback?.onAdClose()
                }

                override fun onRewardVerify(
                    rewardVerify: Boolean,
                    rewardAmount: Int,
                    rewardName: String,
                    code: Int,
                    msg: String
                ) {
                    Log.d(
                        TAG,
                        "激励验证: verify=$rewardVerify, amount=$rewardAmount, name=$rewardName, code=$code, msg=$msg"
                    )
                    callback?.onRewardVerify(rewardVerify, rewardAmount, rewardName, code, msg)
                }

                override fun onAdClick() {
                    Log.d(TAG, "激励广告被点击: spaceId=$spaceId")
                    callback?.onAdClick()
                }
            }
        )
    }

    /**
     * 预热广告：提前加载广告素材，减少后续展示等待时间
     *
     * @param activity Activity 上下文
     * @param spaceId 广告位 ID
     */
    fun preLoad(activity: Activity, spaceId: Long) {
        val request = DirichletAdRequest.Builder()
            .withSpaceId(spaceId)
            .build()
        createAdNative(activity).preLoad(request, PRELOAD_COUNT)
        Log.d(TAG, "广告预热: spaceId=$spaceId")
    }

    /** 释放广告加载器，在 Activity 销毁时调用 */
    fun destroyAd() {
        adNative = null
        callback = null
    }

    private fun createAdNative(activity: Activity): DirichletAdNative {
        val existing = adNative
        if (existing != null) return existing
        return DirichletAdManager.get().createAdNative(activity).also { adNative = it }
    }
}
