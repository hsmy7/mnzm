package com.xianxia.sect.taptap

import android.util.Log
import com.taptap.sdk.leaderboard.androidx.TapTapLeaderboard
import com.taptap.sdk.leaderboard.callback.ITapTapLeaderboardResponseCallback
import com.taptap.sdk.leaderboard.data.request.LeaderboardCollection
import com.taptap.sdk.leaderboard.data.request.SubmitScoresRequest
import com.taptap.sdk.leaderboard.data.response.LeaderboardScoresResponse
import com.taptap.sdk.leaderboard.data.response.SubmitScoresResponse
import com.taptap.sdk.leaderboard.data.response.UserScoreResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * tap-leaderboard-androidx SDK 实现（唯一接触 SDK 的文件）。
 *
 * API 签名（2026-08-05 反编译 tap-leaderboard-androidx:4.10.5 验证）：
 * - 提交：submitScores(ScoreItem 列表, ITapTapLeaderboardResponseCallback<SubmitScoresResponse>)
 * - 榜单：loadLeaderboardScores(id, LeaderboardCollection.PUBLIC, nextPage, periodToken, 回调)
 * - 我的分数：loadCurrentPlayerLeaderboardScore(id, LeaderboardCollection.PUBLIC, periodToken, 回调)
 * 错误码：500000 周期过期 / 500001 ID 未找到 / 500002 参数错误 /
 * 500101 未授权 / 500102 未登录 / 500199 未知
 *
 * 全部调用 try/catch 兜底 + 日志：业务永不因 SDK 异常崩溃（与 TapCloudSaveManager 同策略）。
 */
class TapTapLeaderboardApi @Inject constructor(
    private val loginBridge: TapTapLoginBridge
) : LeaderboardCloudApi {

    companion object {
        private const val TAG = "TapTapLeaderboardApi"

        /** 服务端返回 0 起始名次时归一为 1（真机验证后按需修正） */
        private const val MIN_DISPLAY_RANK = 1

        /** 昵称兜底 */
        private const val FALLBACK_NAME = "神秘修士"
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    // ReturnCount：登录守卫/零值守卫/结果三出口为守卫风格；Exception：SDK 防御性兜底（同 TapCloudSaveManager 策略）
    override suspend fun submitStatistic(power: Long): Boolean {
        if (power <= 0L) return false
        if (!loginBridge.isLoggedIn()) return false
        return try {
            suspendCancellableCoroutine { cont ->
                TapTapLeaderboard.submitScores(
                    scores = listOf(
                        SubmitScoresRequest.ScoreItem(LeaderboardConstants.LEADERBOARD_ID, power)
                    ),
                    callback = object : ITapTapLeaderboardResponseCallback<SubmitScoresResponse> {
                        override fun onSuccess(data: SubmitScoresResponse) {
                            cont.resume(true)
                        }

                        override fun onFailure(code: Int, message: String) {
                            Log.w(TAG, "提交分数失败: code=$code, message=$message")
                            cont.resume(false)
                        }
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "提交分数异常", e)
            false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    // Exception：SDK 回调桥接的防御性兜底，异常转为可映射的 LeaderboardApiException
    override suspend fun fetchTop(): List<LeaderboardEntry> {
        if (!loginBridge.isLoggedIn()) {
            throw LeaderboardApiException(LeaderboardApiExceptionCodes.NOT_LOGGED_IN, "用户未登录")
        }
        val response = suspendCancellableCoroutine<LeaderboardScoresResponse> { cont ->
            try {
                TapTapLeaderboard.loadLeaderboardScores(
                    leaderboardId = LeaderboardConstants.LEADERBOARD_ID,
                    leaderboardCollection = LeaderboardCollection.PUBLIC,
                    nextPage = null,
                    periodToken = null,
                    callback = object : ITapTapLeaderboardResponseCallback<LeaderboardScoresResponse> {
                        override fun onSuccess(data: LeaderboardScoresResponse) {
                            cont.resume(data)
                        }

                        override fun onFailure(code: Int, message: String) {
                            cont.resumeWithException(LeaderboardApiException(code, message))
                        }
                    }
                )
            } catch (e: Exception) {
                cont.resumeWithException(LeaderboardApiException(-1, e.message ?: "调用排行榜 API 异常"))
            }
        }
        return response.scores.map { it.toEntry() }
    }

    @Suppress("TooGenericExceptionCaught")
    // Exception：SDK 回调桥接的防御性兜底，异常转为可映射的 LeaderboardApiException
    override suspend fun fetchCurrentPlayerScore(): LeaderboardEntry? {
        if (!loginBridge.isLoggedIn()) {
            throw LeaderboardApiException(LeaderboardApiExceptionCodes.NOT_LOGGED_IN, "用户未登录")
        }
        val response = suspendCancellableCoroutine<UserScoreResponse> { cont ->
            try {
                TapTapLeaderboard.loadCurrentPlayerLeaderboardScore(
                    leaderboardId = LeaderboardConstants.LEADERBOARD_ID,
                    leaderboardCollection = LeaderboardCollection.PUBLIC,
                    periodToken = null,
                    callback = object : ITapTapLeaderboardResponseCallback<UserScoreResponse> {
                        override fun onSuccess(data: UserScoreResponse) {
                            cont.resume(data)
                        }

                        override fun onFailure(code: Int, message: String) {
                            cont.resumeWithException(LeaderboardApiException(code, message))
                        }
                    }
                )
            } catch (e: Exception) {
                cont.resumeWithException(LeaderboardApiException(-1, e.message ?: "调用排行榜 API 异常"))
            }
        }
        return response.currentUserScore?.toEntry(isMe = true)
    }

    /** Score → LeaderboardEntry 映射（rank/score 可空，归一化与兜底） */
    private fun com.taptap.sdk.leaderboard.data.response.common.Score.toEntry(isMe: Boolean = false): LeaderboardEntry {
        val rawRank = (rank ?: 0L).toInt()
        return LeaderboardEntry(
            rank = if (rawRank < MIN_DISPLAY_RANK) MIN_DISPLAY_RANK else rawRank,
            name = user?.name?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME,
            avatarUrl = null,
            power = (score ?: 0L).coerceAtLeast(0L),
            isMe = isMe
        )
    }

    /** 错误码携带异常（内部用，Manager 收敛映射） */
    class LeaderboardApiException(val code: Int, message: String) : Exception(message)
}
