package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

enum class SignInDayState {
    FUTURE,
    TODAY_UNCLAIMED,
    TODAY_CLAIMED,
    PAST_CLAIMED,
    MISSED
}

@Keep
@Serializable
data class SignInState(
    @ProtoPacked @ProtoNumber(1) val claimedDays: List<Int> = emptyList(),
    @ProtoNumber(2) val currentMonth: Int = 0,
    @ProtoNumber(3) val currentYear: Int = 0,
    @ProtoPacked @ProtoNumber(4) val claimedMilestones: List<Int> = emptyList()
)

@Keep
@Serializable
data class DailySignInReward(
    val weekday: Int,
    val itemName: String,
    val quantity: Int,
    val type: String,
    val rarity: Int
)

/**
 * 签到里程碑奖励（累计签到满N天可领取）
 */
@Keep
@Serializable
data class MilestoneReward(
    val day: Int,
    val itemName: String,
    val quantity: Int,
    val type: String,
    val rarity: Int
)
