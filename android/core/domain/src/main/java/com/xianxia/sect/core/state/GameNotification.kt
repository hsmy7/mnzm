package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple

sealed interface GameNotification {
    /** 招募失败 */
    data class RecruitFailed(val reason: String) : GameNotification
}
