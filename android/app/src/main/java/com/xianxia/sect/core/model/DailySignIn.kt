package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

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
    val claimedDays: List<Int> = emptyList(),
    val currentMonth: Int = 0,
    val currentYear: Int = 0
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
