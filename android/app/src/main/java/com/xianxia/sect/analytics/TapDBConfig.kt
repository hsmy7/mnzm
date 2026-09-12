package com.xianxia.sect.analytics

/**
 * TapDB 数据分析配置（运行时开关）。
 *
 * 总开关与广告收入模式均可在未来 RemoteConfig 绑定后迁移为 `analytics.*` 键
 * （rules/commercialization.md RemoteConfig 规范：本地默认值兜底，配置缺失回退本地值）。
 */
object TapDBConfig {

    /** 埋点总开关：仅控制事件上报（trackEvent / #ad_show），不影响账号登录与游玩时长等 TapDB 基础 BI */
    @Volatile
    var analyticsEnabled: Boolean = true

    /** 广告收入上报模式：客户端（默认）| 服务端（预留）| 关闭 */
    @Volatile
    var adRevenueMode: AdRevenueMode = AdRevenueMode.CLIENT_ONLY
}

/** 广告变现收入上报模式（TapDB 官方要求客户端/服务端二选一，避免重复统计） */
enum class AdRevenueMode {
    /** 客户端 SDK 上报 #ad_show（当前模式） */
    CLIENT_ONLY,

    /** 服务端 REST 上报（预留：自建后端接入后切换，见 knowledge-base 服务端接入契约） */
    SERVER_ONLY,

    /** 关闭广告收入上报 */
    DISABLED
}
