package com.xianxia.sect.taptap

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tapsdk.tapad.constants.Constants
import com.tapsdk.tapad.group.DirichletSdk
import com.xianxia.sect.core.AdFreeWhitelist
import com.xianxia.sect.core.engine.service.AdPurpose
import com.xianxia.sect.core.engine.service.AdService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 激励视频广告服务实现（Dirichlet 聚合 SDK）。
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
class AdServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AdService {

    companion object {
        private const val TAG = "AdServiceImpl"
        private const val PREFS_NAME = "ad_settings"
        private const val KEY_PERSONALIZED_ADS = "personalized_ads_enabled"
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
        // 免广告特权用户直接发放奖励，跳过广告加载和播放
        if (AdFreeWhitelist.isCurrentUserPrivileged()) {
            onReward()
            return
        }

        val activity = activityRef
        when {
            activity == null -> Log.w(TAG, "watchAd skipped: activityRef is null")
            isLoadingAd -> Log.d(TAG, "watchAd skipped: previous ad still loading")
            else -> startAdLoading(activity, purpose, onReward)
        }
    }

    private fun startAdLoading(
        activity: Activity,
        purpose: AdPurpose,
        onReward: () -> Unit
    ) {
        isLoadingAd = true

        val (rewardName, rewardAmount, spaceId) = when (purpose) {
            AdPurpose.BREAKTHROUGH_BONUS -> Triple("奖励", 1, 1056479L)
            AdPurpose.MERCHANT_REFRESH -> Triple("商人刷新次数", 3, 1059500L)
            AdPurpose.JADE_SYMBOL_BONUS -> Triple("玉符", 3, 1061442L)
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

                override fun onAdError(code: Int, message: String) {
                    isLoadingAd = false
                    Log.e(TAG, "Ad error: code=$code, message=$message")
                    RewardVideoAdManager.removeCallback()
                }

                override fun onAdClose() {
                    isLoadingAd = false
                    RewardVideoAdManager.removeCallback()
                }
            }
        )

        try {
            RewardVideoAdManager.showAd(
                activity = activity,
                spaceId = spaceId,
                rewardName = rewardName,
                rewardAmount = rewardAmount
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            isLoadingAd = false
            Log.e(TAG, "showAd threw exception", e)
            RewardVideoAdManager.removeCallback()
        }
    }

    override fun isPersonalizedAdsEnabled(): Boolean =
        prefs().getBoolean(KEY_PERSONALIZED_ADS, true)

    override fun setPersonalizedAdsEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_PERSONALIZED_ADS, enabled).apply()
        applyPersonalizationSetting()
    }

    /**
     * 将持久化的个性化广告偏好同步到 SDK。
     *
     * 需在 [DirichletSdk.init] 完成后调用（SDK 未初始化时静默失败，不崩溃）。
     * 开关切换时由 [setPersonalizedAdsEnabled] 内部再次调用。
     */
    fun applyPersonalizationSetting() {
        val enabled = isPersonalizedAdsEnabled()
        runCatching {
            DirichletSdk.putMediaGlobalSettings(
                Constants.Personalization.PERSONAL_ADS_TYPE,
                if (enabled) {
                    Constants.Personalization.PERSONAL_ADS_TYPE_ALLOW
                } else {
                    Constants.Personalization.PERSONAL_ADS_TYPE_LIMIT
                }
            )
        }.onFailure {
            Log.e(TAG, "putMediaGlobalSettings failed", it)
        }
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
