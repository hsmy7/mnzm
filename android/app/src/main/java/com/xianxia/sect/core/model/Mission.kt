package com.xianxia.sect.core.model

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class MissionDifficulty {
    SIMPLE,
    NORMAL,
    HARD,
    FORBIDDEN;

    val displayName: String get() = when (this) {
        SIMPLE -> "简单"
        NORMAL -> "普通"
        HARD -> "困难"
        FORBIDDEN -> "禁忌"
    }

    val spawnChance: Double get() = when (this) {
        SIMPLE -> 0.25
        NORMAL -> 0.12
        HARD -> 0.03
        FORBIDDEN -> 0.005
    }

    val durationMonths: Int get() = when (this) {
        SIMPLE -> 3
        NORMAL -> 7
        HARD -> 36
        FORBIDDEN -> 58
    }

    val allowedPositions: List<String> get() = when (this) {
        SIMPLE -> listOf("外门弟子")
        NORMAL -> listOf("外门弟子", "内门弟子")
        HARD -> listOf("内门弟子")
        FORBIDDEN -> listOf("内门弟子")
    }
}

@Serializable
enum class MissionTemplate {
    ESCORT,
    SUPPRESS_BEASTS;

    val displayName: String get() = when (this) {
        ESCORT -> "护送商队"
        SUPPRESS_BEASTS -> "妖兽作乱"
    }

    val description: String get() = when (this) {
        ESCORT -> "选择弟子护送商队前往目的地"
        SUPPRESS_BEASTS -> "随机3到8名筑基期妖兽（小层随机）于民间作乱"
    }

    val difficulty: MissionDifficulty get() = when (this) {
        ESCORT -> MissionDifficulty.SIMPLE
        SUPPRESS_BEASTS -> MissionDifficulty.SIMPLE
    }

    val requiredMemberCount: Int get() = 6

    val duration: Int get() = when (this) {
        ESCORT -> difficulty.durationMonths
        SUPPRESS_BEASTS -> 4
    }
}

@Serializable
data class MissionRewardConfig(
    val spiritStones: Int = 0,
    val spiritStonesMax: Int = 0,
    val materialCountMin: Int = 0,
    val materialCountMax: Int = 0,
    val materialMinRarity: Int = 1,
    val materialMaxRarity: Int = 2
)

@Serializable
data class Mission(
    val id: String = UUID.randomUUID().toString(),
    val template: MissionTemplate,
    val name: String,
    val description: String,
    val difficulty: MissionDifficulty,
    val duration: Int,
    val rewards: MissionRewardConfig,
    val createdYear: Int = 1,
    val createdMonth: Int = 1
) {
    val memberCount: Int get() = template.requiredMemberCount
}

@Serializable
data class ActiveMission(
    val id: String = UUID.randomUUID().toString(),
    val missionId: String,
    val missionName: String,
    val template: MissionTemplate,
    val difficulty: MissionDifficulty,
    val discipleIds: List<String>,
    val discipleNames: List<String>,
    val discipleRealms: List<String>,
    val startYear: Int,
    val startMonth: Int,
    val duration: Int,
    val rewards: MissionRewardConfig
) {
    val memberCount: Int get() = discipleIds.size

    fun getRemainingMonths(currentYear: Int, currentMonth: Int): Int {
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return (duration - elapsedMonths.toInt()).coerceAtLeast(0)
    }

    fun getProgressPercent(currentYear: Int, currentMonth: Int): Int {
        if (duration <= 0) return 100
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return ((elapsedMonths.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
    }

    fun isComplete(currentYear: Int, currentMonth: Int): Boolean {
        return getRemainingMonths(currentYear, currentMonth) <= 0
    }
}
