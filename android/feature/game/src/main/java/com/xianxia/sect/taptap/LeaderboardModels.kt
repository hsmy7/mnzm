package com.xianxia.sect.taptap

/**
 * 排行榜常量与数据模型。
 *
 * 排行榜 ID 由用户在 TapTap 开发者中心（游戏服务 → 排行榜）创建后提供，
 * 客户端通过该 ID 提交分数与拉取榜单。
 */
object LeaderboardConstants {
    /** 宗门战斗力排行榜 ID（开发者中心创建，勿改） */
    const val LEADERBOARD_ID = "fqrr4yx4ggmx8r504l"

    /** 我的排名周边查询半径（loadPlayerCenteredScores 的 maxCount） */
    const val AROUND_ME_MAX_COUNT = 1

    /** 节流日期格式（yyyy-MM-dd） */
    const val DATE_PATTERN = "yyyy-MM-dd"

    /** 排行榜服务未就绪（TapTap SDK 未初始化/登录不可用）时展示的兜底文案 */
    const val UNAVAILABLE_MESSAGE = "排行榜服务暂不可用，请稍后再试"
}

/** tap-leaderboard SDK 错误码（官方文档《错误处理》章节） */
object LeaderboardApiExceptionCodes {
    /** 排行榜周期已过期 */
    const val PERIOD_EXPIRED = 500000

    /** 排行榜 ID 未找到 */
    const val ID_NOT_FOUND = 500001

    /** 用户未登录 */
    const val NOT_LOGGED_IN = 500102
}

/** 云端榜单（玩家排行）单条记录 */
data class LeaderboardEntry(
    /** 展示名次（1 起始；服务端返回 0 时归一为 1，真机验证后按需修正） */
    val rank: Int,
    /** 玩家昵称（TapTap 昵称，缺失时兜底"神秘修士"） */
    val name: String,
    /** 头像 URL（可选，当前 UI 未使用，保留供扩展） */
    val avatarUrl: String? = null,
    /** 宗门战斗力 */
    val power: Long,
    /** 是否当前玩家 */
    val isMe: Boolean = false
)

/** 本地榜（天下宗门）单条记录 */
data class LocalLeaderboardEntry(
    val sectId: String,
    val name: String,
    val power: Long,
    /** 是否玩家宗门（本地榜高亮标记） */
    val isPlayer: Boolean
)

/** 云端榜操作结果（不抛裸异常，UI 按类型分派状态） */
sealed class LeaderboardResult {
    /** 成功：榜单 + 我的排名（我的排名未上榜时为 null） */
    data class Success(
        val entries: List<LeaderboardEntry>,
        val myRanking: LeaderboardEntry?
    ) : LeaderboardResult()

    /** 榜单为空（无玩家上榜） */
    data object Empty : LeaderboardResult()

    /** 需要 TapTap 登录 */
    data object NeedLogin : LeaderboardResult()

    /** 网络/服务错误，[message] 可展示 */
    data class Error(val message: String) : LeaderboardResult()
}
