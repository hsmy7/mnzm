package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

@Serializable
data class HeavenlyTrialSaveData(
    val highestClearedLevel: Int = -1,
    val levelClearCounts: List<Int> = MutableList(8) { 0 },
    val phase1ClearedLevels: List<Int> = emptyList(),
    val phase2ClearedLevels: List<Int> = emptyList(),
    val claimedRewardLevels: List<Int> = emptyList()
) {
    fun isPhase1Cleared(levelIndex: Int): Boolean =
        levelIndex in phase1ClearedLevels

    fun isPhase2Cleared(levelIndex: Int): Boolean =
        levelIndex in phase2ClearedLevels

    fun isLevelFullyCleared(levelIndex: Int): Boolean =
        isPhase1Cleared(levelIndex) && isPhase2Cleared(levelIndex)

    fun isRewardClaimed(levelIndex: Int): Boolean =
        levelIndex in claimedRewardLevels

    fun canClaimReward(levelIndex: Int): Boolean =
        isLevelFullyCleared(levelIndex) && !isRewardClaimed(levelIndex)
}

/**
 * 天道试炼通关奖励中，单个物品的定义。
 */
data class ClearRewardItem(
    val itemName: String,
    val quantity: Int,
    val itemType: String,
    val rarity: Int,
    val isRandom: Boolean = false
)

/**
 * 天道试炼某一关的通关奖励配置。
 */
data class HeavenlyTrialClearReward(
    val levelIndex: Int,
    val label: String,
    val items: List<ClearRewardItem>
)

/** 天道试炼 8 关首通奖励配置 */
val HEAVENLY_TRIAL_CLEAR_REWARDS: List<HeavenlyTrialClearReward> = listOf(
    HeavenlyTrialClearReward(0, "第一关", listOf(
        ClearRewardItem("灵石", 100_000, "spiritStones", 1),
        ClearRewardItem("凡品储物袋", 5, "storageBag", 1)
    )),
    HeavenlyTrialClearReward(1, "第二关", listOf(
        ClearRewardItem("灵石", 300_000, "spiritStones", 1),
        ClearRewardItem("灵品储物袋", 5, "storageBag", 2)
    )),
    HeavenlyTrialClearReward(2, "第三关", listOf(
        ClearRewardItem("灵石", 500_000, "spiritStones", 1),
        ClearRewardItem("宝品储物袋", 10, "storageBag", 3)
    )),
    HeavenlyTrialClearReward(3, "第四关", listOf(
        ClearRewardItem("灵石", 800_000, "spiritStones", 1),
        ClearRewardItem("宝品储物袋", 20, "storageBag", 3)
    )),
    HeavenlyTrialClearReward(4, "第五关", listOf(
        ClearRewardItem("随机玄品丹药", 5, "randomPill", 4, isRandom = true),
        ClearRewardItem("玄品储物袋", 5, "storageBag", 4)
    )),
    HeavenlyTrialClearReward(5, "第六关", listOf(
        ClearRewardItem("随机地品装备", 5, "randomEquipment", 5, isRandom = true),
        ClearRewardItem("玄品储物袋", 10, "storageBag", 4)
    )),
    HeavenlyTrialClearReward(6, "第七关", listOf(
        ClearRewardItem("随机地品功法", 5, "randomManual", 5, isRandom = true),
        ClearRewardItem("地品储物袋", 5, "storageBag", 5)
    )),
    HeavenlyTrialClearReward(7, "第八关", listOf(
        ClearRewardItem("随机天品功法", 5, "randomManual", 6, isRandom = true),
        ClearRewardItem("地品储物袋", 10, "storageBag", 5)
    ))
)

data class HeavenlyTrialLevelConfig(
    val levelIndex: Int,
    val label: String,
    val phase1Enemies: List<TrialEnemyDef>,
    val phase2Enemies: List<TrialEnemyDef>
)

data class TrialEnemyDef(
    val name: String,
    val realm: Int,
    val realmLayer: Int = 1,
    val beastType: String = "",
    val manualIds: List<String> = emptyList(),
    val equipmentIds: List<String> = emptyList(),
    val role: String = ""
) {
    val isBeast: Boolean get() = beastType.isNotEmpty()
}
