package com.xianxia.sect.taptap

/**
 * 排行榜云端 API 抽象，隔离 tap-leaderboard SDK 实现，便于 fake 单测。
 * 全部方法为挂起函数，错误经异常外泄由调用方收敛（SDK 实现内部已 try/catch 兜底）。
 */
interface LeaderboardCloudApi {

    /**
     * 提交当前玩家宗门战斗力（服务端保留更高分，天然只增不减）。
     * 返回是否提交成功；未登录/服务异常返回 false（不抛异常）。
     */
    suspend fun submitStatistic(power: Long): Boolean

    /**
     * 拉取总榜第一页（服务端分页，nextPage 由实现内部丢弃——首屏 50 条内足够）。
     * 返回条目按排名降序；网络/服务异常时抛异常由 [LeaderboardManager] 收敛。
     */
    suspend fun fetchTop(): List<LeaderboardEntry>

    /**
     * 拉取当前玩家在榜单中的分数与名次；未上榜返回 null。
     * 未登录时抛异常（由 [LeaderboardManager] 映射为 NeedLogin）。
     */
    suspend fun fetchCurrentPlayerScore(): LeaderboardEntry?
}
