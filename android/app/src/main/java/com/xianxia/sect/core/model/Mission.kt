package com.xianxia.sect.core.model

import com.xianxia.sect.core.GameConfig
import java.util.UUID

enum class MissionType {
    ESCORT,
    HUNT,
    PATROL,
    INVESTIGATE,
    GUARD_CITY;

    val displayName: String get() = when (this) {
        ESCORT -> "护送商队"
        HUNT -> "讨伐妖兽"
        PATROL -> "宗门巡逻"
        INVESTIGATE -> "调查事件"
        GUARD_CITY -> "镇守城池"
    }

    val description: String get() = when (this) {
        ESCORT -> "护送商队前往目的地"
        HUNT -> "讨伐作乱的妖兽"
        PATROL -> "在宗门周边巡逻采集"
        INVESTIGATE -> "调查异常事件"
        GUARD_CITY -> "镇守占领的城池"
    }
}

enum class MissionDifficulty {
    YELLOW,
    MYSTERIOUS,
    EARTH,
    HEAVEN;

    val displayName: String get() = when (this) {
        YELLOW -> "黄级"
        MYSTERIOUS -> "玄级"
        EARTH -> "地级"
        HEAVEN -> "天级"
    }

    val spawnChance: Double get() = when (this) {
        YELLOW -> 0.50
        MYSTERIOUS -> 0.43
        EARTH -> 0.06
        HEAVEN -> 0.01
    }

    val durationMonths: Int get() = when (this) {
        YELLOW -> 3
        MYSTERIOUS -> 7
        EARTH -> 36
        HEAVEN -> 58
    }

    val minRealm: Int get() = when (this) {
        YELLOW -> 9
        MYSTERIOUS -> 7
        EARTH -> 4
        HEAVEN -> 2
    }

    val allowedPositions: List<String> get() = when (this) {
        YELLOW -> listOf("外门弟子", "内门弟子")
        MYSTERIOUS -> listOf("外门弟子", "内门弟子")
        EARTH -> listOf("内门弟子")
        HEAVEN -> listOf("内门弟子")
    }

    companion object {
        fun fromSpawnChance(roll: Double): MissionDifficulty {
            var cumulative = 0.0
            for (difficulty in entries) {
                cumulative += difficulty.spawnChance
                if (roll < cumulative) return difficulty
            }
            return YELLOW
        }
    }
}

enum class RewardItemType {
    MANUAL,
    EQUIPMENT,
    PILL
}

enum class InvestigateOutcome {
    BEAST_RIOT,
    SECT_CONFLICT,
    DESTINED_CHILD,
    NOTHING_FOUND;

    val displayName: String get() = when (this) {
        BEAST_RIOT -> "妖兽作乱"
        SECT_CONFLICT -> "门派争斗"
        DESTINED_CHILD -> "天命之子"
        NOTHING_FOUND -> "一无所获"
    }

    companion object {
        fun random(): InvestigateOutcome {
            val roll = kotlin.random.Random.nextDouble()
            return when {
                roll < 0.40 -> BEAST_RIOT
                roll < 0.75 -> SECT_CONFLICT
                roll < 0.95 -> NOTHING_FOUND
                else -> DESTINED_CHILD
            }
        }
    }
}

data class MissionRewardConfig(
    val spiritStones: Int = 0,
    val extraItemChance: Double = 0.0,
    val extraItemCountMin: Int = 0,
    val extraItemCountMax: Int = 0,
    val extraItemMinRarity: Int = 1,
    val extraItemMaxRarity: Int = 2,
    val materialCountMin: Int = 0,
    val materialCountMax: Int = 0,
    val materialMinRarity: Int = 1,
    val materialMaxRarity: Int = 2,
    val herbCountMin: Int = 0,
    val herbCountMax: Int = 0,
    val herbMinRarity: Int = 1,
    val herbMaxRarity: Int = 1,
    val seedCountMin: Int = 0,
    val seedCountMax: Int = 0,
    val seedMinRarity: Int = 1,
    val seedMaxRarity: Int = 1
)

data class InvestigateRewardConfig(
    val outcome: InvestigateOutcome,
    val spiritStones: Int = 0,
    val materialCountMin: Int = 0,
    val materialCountMax: Int = 0,
    val materialMinRarity: Int = 1,
    val materialMaxRarity: Int = 1,
    val itemCountMin: Int = 0,
    val itemCountMax: Int = 0,
    val itemMinRarity: Int = 1,
    val itemMaxRarity: Int = 2,
    val discipleCount: Int = 0,
    val discipleSpiritRootCount: Int = 2
)

data class Mission(
    val id: String = UUID.randomUUID().toString(),
    val type: MissionType,
    val name: String,
    val description: String,
    val difficulty: MissionDifficulty,
    val minRealm: Int,
    val duration: Int,
    val rewards: MissionRewardConfig,
    val successRate: Double = 0.7,
    val createdYear: Int = 1,
    val createdMonth: Int = 1
) {
    val memberCount: Int = 5

    val realmRequirement: String get() = GameConfig.Realm.get(minRealm).name

    companion object {
        fun generateName(type: MissionType, difficulty: MissionDifficulty): String {
            return "${difficulty.displayName}${type.displayName}"
        }
    }
}

data class ActiveMission(
    val id: String = UUID.randomUUID().toString(),
    val missionId: String,
    val missionName: String,
    val missionType: MissionType,
    val difficulty: MissionDifficulty,
    val discipleIds: List<String>,
    val discipleNames: List<String>,
    val startYear: Int,
    val startMonth: Int,
    val duration: Int,
    val rewards: MissionRewardConfig,
    val successRate: Double,
    val investigateOutcome: InvestigateOutcome? = null
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

data class MissionSlotData(
    val index: Int = 0,
    val missionId: String? = null,
    val missionName: String = ""
) {
    val isInUse: Boolean get() = missionId != null
}
