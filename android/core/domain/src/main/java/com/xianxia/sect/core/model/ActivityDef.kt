package com.xianxia.sect.core.model

enum class ActivityType {
    DAILY,
    WEEKLY,
    LIMITED,
    SEASONAL,
    BEGINNER,
    SPECIAL
}

enum class ActivityStatus {
    UPCOMING,
    ACTIVE,
    CLAIMABLE,
    EXPIRED
}

data class RewardPreviewItem(
    val itemId: String,
    val name: String,
    val quantity: Int,
    val rarity: Int
)

data class ActivityDef(
    val id: String,
    val name: String,
    val description: String,
    val type: ActivityType,
    val startTime: Long,
    val endTime: Long,
    val rewardPreview: List<RewardPreviewItem>,
    val status: ActivityStatus,
    val sortOrder: Int,
    val iconRes: String? = null
)
