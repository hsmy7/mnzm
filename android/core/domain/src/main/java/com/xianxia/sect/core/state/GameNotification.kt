package com.xianxia.sect.core.state


sealed interface GameNotification {
    /** 招募失败 */
    data class RecruitFailed(val reason: String) : GameNotification
}
