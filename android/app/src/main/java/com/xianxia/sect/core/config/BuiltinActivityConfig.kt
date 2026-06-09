package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.ActivityDef
import com.xianxia.sect.core.model.ActivityStatus
import com.xianxia.sect.core.model.ActivityType

object BuiltinActivityConfig {

    fun getAllActivities(): List<ActivityDef> = listOf(
        ActivityDef(
            id = "daily_sign_in",
            name = "每日签到",
            description = "每日签到领取奖励，奖励按星期几循环发放",
            type = ActivityType.DAILY,
            startTime = 0,
            endTime = 0,
            rewardPreview = emptyList(),
            status = ActivityStatus.ACTIVE,
            sortOrder = 0
        )
    )
}
