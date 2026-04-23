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

    val minRealm: Int get() = when (this) {
        SIMPLE -> 9
        NORMAL -> 7
        HARD -> 5
        FORBIDDEN -> 3
    }

    val allowedDiscipleTypes: List<String> get() = when (this) {
        SIMPLE -> listOf("outer")
        NORMAL -> listOf("outer", "inner")
        HARD -> listOf("inner")
        FORBIDDEN -> listOf("inner")
    }

    val enemyRealmMin: Int get() = when (this) {
        SIMPLE -> 9
        NORMAL -> 7
        HARD -> 5
        FORBIDDEN -> 3
    }

    val enemyRealmMax: Int get() = when (this) {
        SIMPLE -> 8
        NORMAL -> 6
        HARD -> 4
        FORBIDDEN -> 2
    }
}

@Serializable
enum class MissionType {
    NO_COMBAT,
    COMBAT_REQUIRED,
    COMBAT_RANDOM
}

@Serializable
enum class EnemyType {
    BEAST,
    HUMAN
}

@Serializable
enum class MissionTemplate {
    ESCORT_CARAVAN,
    PATROL_TERRITORY,
    DELIVER_SUPPLIES,
    SUPPRESS_LOW_BEASTS,
    CLEAR_BANDITS,
    EXPLORE_ABANDONED_MINE,

    ESCORT_SPIRIT_CARAVAN,
    INVESTIGATE_ANOMALY,
    DELIVER_PILLS,
    SUPPRESS_JINDAN_BEASTS,
    DESTROY_MAGIC_OUTPOST,
    EXPLORE_ANCIENT_CAVE,

    ESCORT_IMMORTAL_ENVOY,
    REPAIR_ANCIENT_FORMATION,
    SEARCH_MISSING_ELDER,
    SUPPRESS_HUASHEN_BEAST_KING,
    DESTROY_MAGIC_BRANCH,
    EXPLORE_ANCIENT_BATTLEFIELD,

    ESCORT_RELIC_ARTIFACT,
    SEAL_SPATIAL_RIFT,
    SEARCH_SECRET_REALM_CLUE,
    SUPPRESS_ANCIENT_FIEND,
    DESTROY_MAGIC_HEADQUARTERS,
    EXPLORE_CORE_BATTLEFIELD;

    val displayName: String get() = when (this) {
        ESCORT_CARAVAN -> "护送商队"
        PATROL_TERRITORY -> "巡查领地"
        DELIVER_SUPPLIES -> "运送物资"
        SUPPRESS_LOW_BEASTS -> "镇压低阶妖兽"
        CLEAR_BANDITS -> "清缴山匪"
        EXPLORE_ABANDONED_MINE -> "探索废弃矿洞"

        ESCORT_SPIRIT_CARAVAN -> "护送灵石商队"
        INVESTIGATE_ANOMALY -> "调查灵气异常"
        DELIVER_PILLS -> "运送珍贵丹药"
        SUPPRESS_JINDAN_BEASTS -> "镇压金丹妖兽群"
        DESTROY_MAGIC_OUTPOST -> "剿灭魔修哨站"
        EXPLORE_ANCIENT_CAVE -> "探索古修士洞府"

        ESCORT_IMMORTAL_ENVOY -> "护送仙宗使者"
        REPAIR_ANCIENT_FORMATION -> "修复上古阵法"
        SEARCH_MISSING_ELDER -> "搜寻失踪长老"
        SUPPRESS_HUASHEN_BEAST_KING -> "镇压化神妖王"
        DESTROY_MAGIC_BRANCH -> "剿灭魔道分舵"
        EXPLORE_ANCIENT_BATTLEFIELD -> "探索上古战场"

        ESCORT_RELIC_ARTIFACT -> "护送远古遗迹出土灵物"
        SEAL_SPATIAL_RIFT -> "封印空间裂隙"
        SEARCH_SECRET_REALM_CLUE -> "搜寻远古秘境线索"
        SUPPRESS_ANCIENT_FIEND -> "镇压合体期上古凶兽"
        DESTROY_MAGIC_HEADQUARTERS -> "剿灭魔道总坛外围"
        EXPLORE_CORE_BATTLEFIELD -> "探索仙魔古战场核心"
    }

    val description: String get() = when (this) {
        ESCORT_CARAVAN -> "护送凡人商队穿越安全区域，商队支付护送酬劳"
        PATROL_TERRITORY -> "巡查宗门周边领地，清理路障、标记危险区域，获得宗门津贴"
        DELIVER_SUPPLIES -> "将宗门采集的普通矿石运送到附近城镇，换取灵石"
        SUPPRESS_LOW_BEASTS -> "4-10只炼气到筑基期妖兽骚扰村庄，前往镇压"
        CLEAR_BANDITS -> "4-8名炼气到筑基期山匪盘踞要道，劫掠过往行人"
        EXPLORE_ABANDONED_MINE -> "探索废弃灵矿洞搜寻残余矿石，洞内可能有妖兽栖息"

        ESCORT_SPIRIT_CARAVAN -> "护送载有灵石的商队前往邻城，商队支付高额护送酬劳"
        INVESTIGATE_ANOMALY -> "调查某地灵气异常波动原因，记录数据提交宗门获得研究津贴"
        DELIVER_PILLS -> "将宗门炼制的丹药运送到盟友宗门，盟友支付运费和谢礼"
        SUPPRESS_JINDAN_BEASTS -> "4-10只金丹到元婴期妖兽作乱，袭击凡人城镇"
        DESTROY_MAGIC_OUTPOST -> "4-8名金丹到元婴期魔修建立前哨据点，威胁宗门周边安全"
        EXPLORE_ANCIENT_CAVE -> "发现古修士洞府，搜寻遗留物品，可能触发洞府守护禁制"

        ESCORT_IMMORTAL_ENVOY -> "护送其他仙宗重要使者穿越险地，仙宗支付护送酬劳"
        REPAIR_ANCIENT_FORMATION -> "修复宗门领地内的上古守护阵法，宗门发放修缮津贴"
        SEARCH_MISSING_ELDER -> "搜寻宗门在外失踪的长老下落，找到线索获得悬赏"
        SUPPRESS_HUASHEN_BEAST_KING -> "4-10只化神到炼虚期妖王率领兽潮攻城，前往镇压"
        DESTROY_MAGIC_BRANCH -> "4-8名化神到炼虚期魔修坐镇魔道分舵，威胁方圆千里"
        EXPLORE_ANCIENT_BATTLEFIELD -> "探索上古仙魔战场遗迹搜寻遗留物资，可能遭遇游荡的战魂"

        ESCORT_RELIC_ARTIFACT -> "护送从远古遗迹出土的灵物前往安全地点，委托方支付高额酬劳"
        SEAL_SPATIAL_RIFT -> "封印宗门领地上空出现的空间裂隙，宗门发放封印津贴和材料补偿"
        SEARCH_SECRET_REALM_CLUE -> "搜寻传说中的远古秘境入口线索，找到线索获得宗门悬赏"
        SUPPRESS_ANCIENT_FIEND -> "4-10只合体到大乘期上古凶兽挣脱封印，四处肆虐"
        DESTROY_MAGIC_HEADQUARTERS -> "4-8名合体到大乘期魔修长老镇守魔道总坛外围防线"
        EXPLORE_CORE_BATTLEFIELD -> "探索仙魔古战场最核心区域搜寻遗留物资，九死一生"
    }

    val difficulty: MissionDifficulty get() = when (this) {
        ESCORT_CARAVAN, PATROL_TERRITORY, DELIVER_SUPPLIES,
        SUPPRESS_LOW_BEASTS, CLEAR_BANDITS, EXPLORE_ABANDONED_MINE -> MissionDifficulty.SIMPLE

        ESCORT_SPIRIT_CARAVAN, INVESTIGATE_ANOMALY, DELIVER_PILLS,
        SUPPRESS_JINDAN_BEASTS, DESTROY_MAGIC_OUTPOST, EXPLORE_ANCIENT_CAVE -> MissionDifficulty.NORMAL

        ESCORT_IMMORTAL_ENVOY, REPAIR_ANCIENT_FORMATION, SEARCH_MISSING_ELDER,
        SUPPRESS_HUASHEN_BEAST_KING, DESTROY_MAGIC_BRANCH, EXPLORE_ANCIENT_BATTLEFIELD -> MissionDifficulty.HARD

        ESCORT_RELIC_ARTIFACT, SEAL_SPATIAL_RIFT, SEARCH_SECRET_REALM_CLUE,
        SUPPRESS_ANCIENT_FIEND, DESTROY_MAGIC_HEADQUARTERS, EXPLORE_CORE_BATTLEFIELD -> MissionDifficulty.FORBIDDEN
    }

    val missionType: MissionType get() = when (this) {
        ESCORT_CARAVAN, PATROL_TERRITORY, DELIVER_SUPPLIES,
        ESCORT_SPIRIT_CARAVAN, INVESTIGATE_ANOMALY, DELIVER_PILLS,
        ESCORT_IMMORTAL_ENVOY, REPAIR_ANCIENT_FORMATION, SEARCH_MISSING_ELDER,
        ESCORT_RELIC_ARTIFACT, SEAL_SPATIAL_RIFT, SEARCH_SECRET_REALM_CLUE -> MissionType.NO_COMBAT

        SUPPRESS_LOW_BEASTS, CLEAR_BANDITS,
        SUPPRESS_JINDAN_BEASTS, DESTROY_MAGIC_OUTPOST,
        SUPPRESS_HUASHEN_BEAST_KING, DESTROY_MAGIC_BRANCH,
        SUPPRESS_ANCIENT_FIEND, DESTROY_MAGIC_HEADQUARTERS -> MissionType.COMBAT_REQUIRED

        EXPLORE_ABANDONED_MINE, EXPLORE_ANCIENT_CAVE,
        EXPLORE_ANCIENT_BATTLEFIELD, EXPLORE_CORE_BATTLEFIELD -> MissionType.COMBAT_RANDOM
    }

    val triggerChance: Double get() = when (this) {
        EXPLORE_ABANDONED_MINE -> 0.40
        EXPLORE_ANCIENT_CAVE -> 0.50
        EXPLORE_ANCIENT_BATTLEFIELD -> 0.60
        EXPLORE_CORE_BATTLEFIELD -> 0.70
        else -> 0.0
    }

    val enemyType: EnemyType get() = when (this) {
        SUPPRESS_LOW_BEASTS, SUPPRESS_JINDAN_BEASTS,
        SUPPRESS_HUASHEN_BEAST_KING, SUPPRESS_ANCIENT_FIEND -> EnemyType.BEAST

        CLEAR_BANDITS, DESTROY_MAGIC_OUTPOST,
        DESTROY_MAGIC_BRANCH, DESTROY_MAGIC_HEADQUARTERS -> EnemyType.HUMAN

        EXPLORE_ABANDONED_MINE, EXPLORE_ANCIENT_CAVE,
        EXPLORE_ANCIENT_BATTLEFIELD, EXPLORE_CORE_BATTLEFIELD -> EnemyType.BEAST

        else -> EnemyType.BEAST
    }

    val requiredMemberCount: Int get() = 6

    val duration: Int get() = when (this) {
        ESCORT_CARAVAN -> 3
        PATROL_TERRITORY -> 3
        DELIVER_SUPPLIES -> 3
        SUPPRESS_LOW_BEASTS -> 4
        CLEAR_BANDITS -> 4
        EXPLORE_ABANDONED_MINE -> 4

        ESCORT_SPIRIT_CARAVAN -> 7
        INVESTIGATE_ANOMALY -> 7
        DELIVER_PILLS -> 7
        SUPPRESS_JINDAN_BEASTS -> 8
        DESTROY_MAGIC_OUTPOST -> 8
        EXPLORE_ANCIENT_CAVE -> 8

        ESCORT_IMMORTAL_ENVOY -> 36
        REPAIR_ANCIENT_FORMATION -> 36
        SEARCH_MISSING_ELDER -> 36
        SUPPRESS_HUASHEN_BEAST_KING -> 40
        DESTROY_MAGIC_BRANCH -> 40
        EXPLORE_ANCIENT_BATTLEFIELD -> 40

        ESCORT_RELIC_ARTIFACT -> 58
        SEAL_SPATIAL_RIFT -> 58
        SEARCH_SECRET_REALM_CLUE -> 58
        SUPPRESS_ANCIENT_FIEND -> 64
        DESTROY_MAGIC_HEADQUARTERS -> 64
        EXPLORE_CORE_BATTLEFIELD -> 64
    }

    val beastCountRange: IntRange get() = when (this) {
        SUPPRESS_LOW_BEASTS, SUPPRESS_JINDAN_BEASTS,
        SUPPRESS_HUASHEN_BEAST_KING, SUPPRESS_ANCIENT_FIEND,
        EXPLORE_ABANDONED_MINE, EXPLORE_ANCIENT_CAVE,
        EXPLORE_ANCIENT_BATTLEFIELD, EXPLORE_CORE_BATTLEFIELD -> 4..10
        else -> 4..10
    }

    val humanCountRange: IntRange get() = when (this) {
        CLEAR_BANDITS, DESTROY_MAGIC_OUTPOST,
        DESTROY_MAGIC_BRANCH, DESTROY_MAGIC_HEADQUARTERS -> 4..8
        else -> 4..8
    }
}

@Serializable
data class MissionRewardConfig(
    val spiritStones: Int = 0,
    val spiritStonesMax: Int = 0,
    val materialCountMin: Int = 0,
    val materialCountMax: Int = 0,
    val materialMinRarity: Int = 1,
    val materialMaxRarity: Int = 2,
    val pillCountMin: Int = 0,
    val pillCountMax: Int = 0,
    val pillMinRarity: Int = 1,
    val pillMaxRarity: Int = 1,
    val equipmentChance: Double = 0.0,
    val equipmentMinRarity: Int = 1,
    val equipmentMaxRarity: Int = 1,
    val manualChance: Double = 0.0,
    val manualMinRarity: Int = 1,
    val manualMaxRarity: Int = 1,
    val baseSpiritStones: Int = 0,
    val baseMaterialCountMin: Int = 0,
    val baseMaterialCountMax: Int = 0,
    val baseMaterialMinRarity: Int = 1,
    val baseMaterialMaxRarity: Int = 1
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
    val missionType: MissionType = MissionType.NO_COMBAT,
    val enemyType: EnemyType = EnemyType.BEAST,
    val triggerChance: Double = 0.0,
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
    val rewards: MissionRewardConfig,
    val missionType: MissionType = MissionType.NO_COMBAT,
    val enemyType: EnemyType = EnemyType.BEAST,
    val triggerChance: Double = 0.0
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
