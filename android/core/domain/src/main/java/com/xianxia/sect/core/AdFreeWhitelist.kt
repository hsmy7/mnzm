package com.xianxia.sect.core

/**
 * 免广告特权白名单。
 *
 * 替换原生 C++ [AdFreePrivilege] 类，纯 Kotlin 实现。
 * 白名单中的玩家跳过广告播放、无视冷却和每日次数限制。
 *
 * 白名单列表在 [GameConfig.Whitelist.AD_FREE_UNION_IDS] 中硬编码维护。
 */
object AdFreeWhitelist {

    @Volatile
    private var currentUnionId: String? = null

    /**
     * 初始化当前用户身份。
     * 在 GameActivity 创建时调用。
     */
    fun initialize(unionId: String?) {
        currentUnionId = unionId
    }

    /**
     * 当前用户是否在白名单中。
     */
    fun isCurrentUserPrivileged(): Boolean {
        val id = currentUnionId ?: return false
        return id in GameConfig.Whitelist.AD_FREE_UNION_IDS
    }

    /**
     * 当前用户是否是指定 unionId。
     *
     * 用于单用户专属运营活动（如专属邮件）的精确判定，
     * 与 [isCurrentUserPrivileged]（白名单群体判定）互补。
     *
     * @param unionId 目标用户的 TapTap unionId
     */
    fun isCurrentUser(unionId: String): Boolean {
        return currentUnionId == unionId
    }
}
