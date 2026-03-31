package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class GiftPreferenceType {
    NONE,
    EQUIPMENT,
    MANUAL,
    PILL,
    SPIRIT_STONE;

    val displayName: String
        get() = when (this) {
            NONE -> "无"
            EQUIPMENT -> "装备"
            MANUAL -> "功法"
            PILL -> "丹药"
            SPIRIT_STONE -> "灵石"
        }
}
