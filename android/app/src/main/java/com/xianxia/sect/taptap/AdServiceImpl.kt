package com.xianxia.sect.taptap

import android.app.Activity
import android.util.Log
import com.xianxia.sect.core.engine.service.AdPurpose
import com.xianxia.sect.core.engine.service.AdService
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 激励视频广告服务实现。
 *
 * 委托给 [RewardVideoAdManager] 执行实际的广告加载和展示。
 * Activity 引用由 [attachActivity] 注入（GameActivity 在 onCreate 时调用）。
 *
 * ## 线程安全
 * [activityRef] 为 @Volatile，确保跨线程可见性。
 * [isLoadingAd] 串行化广告请求，防止全局回调覆盖（对抗性审查 #1/#3）。
 * 幂等守卫通过 [AtomicBoolean] 实现（防止 onRewardVerify 多次回调）。
 */
@Singleton
class AdServiceImpl @Inject constructor() : AdService {

    companion object {
        private const val TAG = "AdServiceImpl"
    }

    @Volatile private var activityRef: Activity? = null
    @Volatile private var isLoadingAd: Boolean = false

    /** 由 GameActivity 在 onCreate 时调用，注入 Activity 引用。 */
    fun attachActivity(activity: Activity) {
        this.activityRef = activity
    }

    /** 由 GameActivity 在 onDestroy 时调用，释放 Activity 引用。 */
    fun detachActivity() {
        this.activityRef = null
    }

    override fun watchAd(purpose: AdPurpose, onReward: () -> Unit) {
        val activity = activityRef
        if (activity == null) {
            Log.w(TAG, "watchAd skipped: activityRef is null")
            return
        }
        // 串行化广告请求：防止并发调用导致全局回调覆盖
        if (isLoadingAd) {
            Log.d(TAG, "watchAd skipped: previous ad still loading")
            return
        }
        isLoadingAd = true

        val (rewardName, rewardAmount, spaceId) = when (purpose) {
            AdPurpose.BREAKTHROUGH_BONUS -> Triple("奖励", 1, 1056479L)
            AdPurpose.MERCHANT_REFRESH -> Triple("商人刷新次数", 3, 1059500L)
        }

        val rewardClaimed = AtomicBoolean(false)

        RewardVideoAdManager.setCallback(
            object : RewardVideoAdManager.RewardVideoCallback {
                override fun onRewardVerify(
                    rewardVerify: Boolean,
                    rewardAmount: Int,
                    rewardName: String,
                    code: Int,
                    msg: String
                ) {
                    if (!rewardVerify || activity.isFinishing || activity.isDestroyed) return
                    if (!rewardClaimed.compareAndSet(false, true)) return
                    onReward()
                }

                override fun onAdCached() {
                    RewardVideoAdManager.showAd(activity)
                }

                override fun onAdClose() {
                    isLoadingAd = false
                    RewardVideoAdManager.removeCallback()
                }

                override fun onAdLoadError(code: Int, message: String) {
                    isLoadingAd = false
                    Log.e(TAG, "Ad load failed: code=$code, message=$message")
                }

                override fun onVideoError() {
                    isLoadingAd = false
                    Log.e(TAG, "Ad video playback error")
                }
            }
        )

        try {
            RewardVideoAdManager.loadAd(
                activity = activity,
                rewardName = rewardName,
                rewardAmount = rewardAmount,
                spaceId = spaceId
            )
        } catch (e: Exception) {
            isLoadingAd = false
            Log.e(TAG, "loadAd threw exception", e)
            RewardVideoAdManager.removeCallback()
        }
    }
}
