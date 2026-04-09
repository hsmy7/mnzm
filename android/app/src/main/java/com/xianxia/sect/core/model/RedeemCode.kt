@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RedeemRewardType {
    SPIRIT_STONES,
    EQUIPMENT,
    MANUAL,
    PILL,
    MATERIAL,
    HERB,
    SEED,
    DISCIPLE,
    STARTER_PACK,
    MANUAL_PACK
}

@Serializable
data class DiscipleRewardConfig(
    val realm: Int = 9,
    val realmLayer: Int = 1,
    val spiritRootType: String? = null,
    val spiritRootCount: Int? = null,
    val talentIds: List<String> = emptyList(),
    val intelligence: Int? = null,
    val comprehension: Int? = null,
    val charm: Int? = null,
    val loyalty: Int? = null,
    val artifactRefining: Int? = null,
    val pillRefining: Int? = null,
    val spiritPlanting: Int? = null,
    val teaching: Int? = null,
    val morality: Int? = null,
    val minAge: Int = 16,
    val maxAge: Int = 25,
    val gender: String = "random"
)

@Serializable
data class RedeemCode(
    val code: String,
    val rewardType: RedeemRewardType,
    val quantity: Int = 1,
    val rarity: Int = 1,
    val maxUses: Int = 1,
    val usedCount: Int = 0,
    val expireYear: Int? = null,
    val expireMonth: Int? = null,
    val isEnabled: Boolean = true,
    val discipleConfig: DiscipleRewardConfig? = null
) {
    val isExhausted: Boolean
        get() = usedCount >= maxUses
}

@Serializable
data class RedeemResult(
    val success: Boolean,
    val message: String,
    val rewards: List<RewardSelectedItem> = emptyList(),
    val disciple: Disciple? = null,
    val disciples: List<Disciple> = emptyList()
)
