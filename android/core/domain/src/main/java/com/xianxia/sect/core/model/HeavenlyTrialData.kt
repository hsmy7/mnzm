package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

@Serializable
data class HeavenlyTrialSaveData(
    val highestClearedLevel: Int = -1,
    val levelClearCounts: List<Int> = MutableList(8) { 0 }
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
