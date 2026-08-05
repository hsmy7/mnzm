package com.xianxia.sect.taptap

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xianxia.sect.taptap.TapTapLeaderboardApi.LeaderboardApiException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 排行榜管理器：上报节流 + 榜单拉取编排。
 *
 * - 上报与查询均要求 TapTap 登录；未登录时上报静默跳过、查询返回 NeedLogin。
 * - 上报失败仅记日志次日重试（不阻塞游戏）。
 * - 错误经 [LeaderboardResult] 返回，不抛裸异常。
 */
@Singleton
class LeaderboardManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudApi: LeaderboardCloudApi,
    private val loginBridge: TapTapLoginBridge
) {

    companion object {
        private const val TAG = "LeaderboardManager"
        private const val PREFS_NAME = "leaderboard_prefs"
        private const val KEY_LAST_UPLOAD_DATE = "last_upload_date"
        private const val KEY_LAST_UPLOADED_POWER = "last_uploaded_power"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前是否具备云端榜条件（已登录 + 已初始化） */
    fun isCloudAvailable(): Boolean = loginBridge.isLoggedIn()

    /**
     * 节流上报当前宗门战斗力（打开排行榜 / 每日首次进游戏两处触发共用）。
     * 未登录或节流判定跳过时返回 false；上报成功返回 true；失败返回 false（仅日志）。
     */
    @Suppress("ReturnCount")
    // 未登录/节流/成功三出口为守卫风格（与 SaveLoadViewModel 同模式）
    suspend fun uploadIfNeeded(power: Long): Boolean {
        if (!loginBridge.isLoggedIn()) {
            Log.d(TAG, "未登录，跳过排行榜上报")
            return false
        }
        val today = LeaderboardUploadPolicy.formatDate(System.currentTimeMillis())
        val lastDate = prefs.getString(KEY_LAST_UPLOAD_DATE, null)
        val lastPower = if (prefs.contains(KEY_LAST_UPLOADED_POWER)) {
            prefs.getLong(KEY_LAST_UPLOADED_POWER, 0L)
        } else {
            null
        }
        if (!LeaderboardUploadPolicy.shouldUpload(power, lastPower, lastDate, today)) {
            Log.d(TAG, "节流跳过上报（今日同战力）")
            return false
        }
        val success = cloudApi.submitStatistic(power)
        if (success) {
            prefs.edit()
                .putString(KEY_LAST_UPLOAD_DATE, today)
                .putLong(KEY_LAST_UPLOADED_POWER, power)
                .apply()
            Log.d(TAG, "排行榜上报成功: power=$power")
        } else {
            Log.w(TAG, "排行榜上报失败（次日重试）")
        }
        return success
    }

    /**
     * 拉取玩家排行榜：榜单第一页 + 我的排名合并。
     * 未登录 → NeedLogin；榜单与我的排名均空 → Empty；其余错误 → Error。
     */
    @Suppress("TooGenericExceptionCaught")
    // Exception：SDK/网络异常的防御性兜底，统一收敛为可展示的 LeaderboardResult
    suspend fun fetchLeaderboard(): LeaderboardResult {
        if (!loginBridge.isLoggedIn()) return LeaderboardResult.NeedLogin
        return try {
            val entries = cloudApi.fetchTop()
            val myRanking = runCatching { cloudApi.fetchCurrentPlayerScore() }
                .getOrNull()
                .also { e -> if (e == null) Log.d(TAG, "我的排名未上榜或查询失败") }
            if (entries.isEmpty() && myRanking == null) {
                LeaderboardResult.Empty
            } else {
                LeaderboardResult.Success(entries, myRanking)
            }
        } catch (e: LeaderboardApiException) {
            when (e.code) {
                LeaderboardApiExceptionCodes.NOT_LOGGED_IN -> LeaderboardResult.NeedLogin
                LeaderboardApiExceptionCodes.PERIOD_EXPIRED ->
                    LeaderboardResult.Error("本周期排行榜已结束，请等待下个周期")
                LeaderboardApiExceptionCodes.ID_NOT_FOUND ->
                    LeaderboardResult.Error("排行榜不存在，请稍后重试")
                else -> LeaderboardResult.Error(e.message ?: LeaderboardConstants.UNAVAILABLE_MESSAGE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "拉取排行榜异常", e)
            LeaderboardResult.Error(e.message ?: LeaderboardConstants.UNAVAILABLE_MESSAGE)
        }
    }
}
