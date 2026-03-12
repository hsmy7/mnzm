package com.xianxia.sect.core.data

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Talent
import kotlin.math.abs
import kotlin.random.Random

object TalentDatabase {

    private const val NEGATIVE_TALENT_CHANCE = 0.14

    // User-confirmed: rarity distribution inside positive pool.
    private val POSITIVE_RARITY_DISTRIBUTION = listOf(
        1 to 0.419,
        2 to 0.272,
        3 to 0.168,
        4 to 0.084,
        5 to 0.037,
        6 to 0.020
    )

    enum class TalentType {
        CULTIVATION,
        LIFESPAN,
        BASE_COMPREHENSION,
        BASE_CRAFT,
        BASE_SOCIAL,
        BATTLE_OFFENSE,
        BATTLE_SURVIVAL,
        MANUAL_SLOT,
        WIN_GROWTH
    }

    data class TalentData(
        val id: String,
        val name: String,
        val description: String,
        val rarity: Int,
        val effects: Map<String, Double>,
        val isNegative: Boolean,
        val type: TalentType
    ) {
        fun toTalent(): Talent = Talent(id, name, description, rarity, effects, isNegative)
    }

    private data class GradeTalentConfig(
        val rarity: Int,
        val cultivationSpeed: Double,
        val breakthroughChance: Double,
        val lifespan: Double,
        val baseFlat: Int,
        val offenseAtk: Double,
        val offenseCrit: Double,
        val survivalHp: Double,
        val survivalDef: Double,
        val survivalSpeed: Double
    )

    private val gradeConfigs = listOf(
        GradeTalentConfig(1, 0.08, 0.01, 0.12, 2, 0.05, 0.008, 0.08, 0.05, 0.03),
        GradeTalentConfig(2, 0.14, 0.02, 0.20, 4, 0.08, 0.012, 0.12, 0.08, 0.05),
        GradeTalentConfig(3, 0.22, 0.03, 0.32, 6, 0.12, 0.018, 0.18, 0.12, 0.07),
        GradeTalentConfig(4, 0.31, 0.05, 0.46, 9, 0.17, 0.026, 0.25, 0.17, 0.10),
        GradeTalentConfig(5, 0.42, 0.07, 0.64, 13, 0.23, 0.036, 0.34, 0.23, 0.13),
        GradeTalentConfig(6, 0.56, 0.10, 0.88, 18, 0.30, 0.050, 0.45, 0.30, 0.17)
    )

    private fun rarityPrefix(rarity: Int): String {
        return when (rarity) {
            1 -> "凡品"
            2 -> "灵品"
            3 -> "宝品"
            4 -> "玄品"
            5 -> "地品"
            6 -> "天品"
            else -> "凡品"
        }
    }

    private val positiveTalentsData: List<TalentData> = buildList {
        gradeConfigs.forEach { cfg ->
            add(
                TalentData(
                    id = "r${cfg.rarity}_cultivation",
                    name = "灵脉流转",
                    description = "修炼速度+${String.format("%.1f", cfg.cultivationSpeed * 100)}%，突破概率+${String.format("%.1f", cfg.breakthroughChance * 100)}%",
                    rarity = cfg.rarity,
                    effects = mapOf(
                        "cultivationSpeed" to cfg.cultivationSpeed,
                        "breakthroughChance" to cfg.breakthroughChance
                    ),
                    isNegative = false,
                    type = TalentType.CULTIVATION
                )
            )

            add(
                TalentData(
                    id = "r${cfg.rarity}_lifespan",
                    name = "寿元绵长",
                    description = "寿命+${String.format("%.1f", cfg.lifespan * 100)}%",
                    rarity = cfg.rarity,
                    effects = mapOf("lifespan" to cfg.lifespan),
                    isNegative = false,
                    type = TalentType.LIFESPAN
                )
            )

            add(
                TalentData(
                    id = "r${cfg.rarity}_base_comprehension",
                    name = "慧根通明",
                    description = "悟性/智力/教导 +${cfg.baseFlat}",
                    rarity = cfg.rarity,
                    effects = mapOf(
                        "comprehensionFlat" to cfg.baseFlat.toDouble(),
                        "intelligenceFlat" to cfg.baseFlat.toDouble(),
                        "teachingFlat" to cfg.baseFlat.toDouble()
                    ),
                    isNegative = false,
                    type = TalentType.BASE_COMPREHENSION
                )
            )

            add(
                TalentData(
                    id = "r${cfg.rarity}_base_craft",
                    name = "百艺精修",
                    description = "炼器/炼丹/种植 +${cfg.baseFlat}",
                    rarity = cfg.rarity,
                    effects = mapOf(
                        "artifactRefiningFlat" to cfg.baseFlat.toDouble(),
                        "pillRefiningFlat" to cfg.baseFlat.toDouble(),
                        "spiritPlantingFlat" to cfg.baseFlat.toDouble()
                    ),
                    isNegative = false,
                    type = TalentType.BASE_CRAFT
                )
            )

            add(
                TalentData(
                    id = "r${cfg.rarity}_base_social",
                    name = "心性坚韧",
                    description = "魅力/忠诚/道德 +${cfg.baseFlat}",
                    rarity = cfg.rarity,
                    effects = mapOf(
                        "charmFlat" to cfg.baseFlat.toDouble(),
                        "loyaltyFlat" to cfg.baseFlat.toDouble(),
                        "moralityFlat" to cfg.baseFlat.toDouble()
                    ),
                    isNegative = false,
                    type = TalentType.BASE_SOCIAL
                )
            )

            add(
                TalentData(
                    id = "r${cfg.rarity}_battle_offense",
                    name = "锋芒毕露",
                    description = "物攻/法攻提升，暴击率提高",
                    rarity = cfg.rarity,
                    effects = mapOf(
                        "physicalAttack" to cfg.offenseAtk,
                        "magicAttack" to cfg.offenseAtk,
                        "critRate" to cfg.offenseCrit
                    ),
                    isNegative = false,
                    type = TalentType.BATTLE_OFFENSE
                )
            )

            add(
                TalentData(
                    id = "r${cfg.rarity}_battle_survival",
                    name = "坚壁不摧",
                    description = "生存属性全面提升",
                    rarity = cfg.rarity,
                    effects = mapOf(
                        "maxHp" to cfg.survivalHp,
                        "maxMp" to (cfg.survivalHp * 0.5),
                        "physicalDefense" to cfg.survivalDef,
                        "magicDefense" to cfg.survivalDef,
                        "speed" to cfg.survivalSpeed
                    ),
                    isNegative = false,
                    type = TalentType.BATTLE_SURVIVAL
                )
            )
        }

        add(
            TalentData(
                id = "r6_manual_slot_plus",
                name = "天衍道藏",
                description = "功法槽位+1",
                rarity = 6,
                effects = mapOf("manualSlot" to 1.0),
                isNegative = false,
                type = TalentType.MANUAL_SLOT
            )
        )

        add(
            TalentData(
                id = "r6_battle_win_growth",
                name = "百战通神",
                description = "每胜利一场战斗后，随机一个属性+1（无上限）",
                rarity = 6,
                effects = mapOf("winBattleRandomAttrPlus" to 1.0),
                isNegative = false,
                type = TalentType.WIN_GROWTH
            )
        )
    }

    private val negativeTalentsData = listOf(
        TalentData(
            id = "neg_cultivation",
            name = "灵脉紊乱",
            description = "修炼速度-28%，突破概率-5%",
            rarity = 3,
            effects = mapOf("cultivationSpeed" to -0.28, "breakthroughChance" to -0.05),
            isNegative = true,
            type = TalentType.CULTIVATION
        ),
        TalentData(
            id = "neg_lifespan",
            name = "寿元亏损",
            description = "寿命-35%",
            rarity = 3,
            effects = mapOf("lifespan" to -0.35),
            isNegative = true,
            type = TalentType.LIFESPAN
        ),
        TalentData(
            id = "neg_base_comprehension",
            name = "神识迟钝",
            description = "悟性/智力/教导 -14",
            rarity = 3,
            effects = mapOf("comprehensionFlat" to -14.0, "intelligenceFlat" to -14.0, "teachingFlat" to -14.0),
            isNegative = true,
            type = TalentType.BASE_COMPREHENSION
        ),
        TalentData(
            id = "neg_base_craft",
            name = "百艺生疏",
            description = "炼器/炼丹/种植 -12",
            rarity = 3,
            effects = mapOf("artifactRefiningFlat" to -12.0, "pillRefiningFlat" to -12.0, "spiritPlantingFlat" to -12.0),
            isNegative = true,
            type = TalentType.BASE_CRAFT
        ),
        TalentData(
            id = "neg_base_social",
            name = "心性偏执",
            description = "魅力/忠诚/道德 -12",
            rarity = 3,
            effects = mapOf("charmFlat" to -12.0, "loyaltyFlat" to -12.0, "moralityFlat" to -12.0),
            isNegative = true,
            type = TalentType.BASE_SOCIAL
        ),
        TalentData(
            id = "neg_battle_offense",
            name = "怯战失锋",
            description = "物攻/法攻/暴击下降",
            rarity = 3,
            effects = mapOf("physicalAttack" to -0.18, "magicAttack" to -0.18, "critRate" to -0.03),
            isNegative = true,
            type = TalentType.BATTLE_OFFENSE
        ),
        TalentData(
            id = "neg_battle_survival",
            name = "体魄亏空",
            description = "生存属性下降",
            rarity = 3,
            effects = mapOf(
                "maxHp" to -0.24,
                "maxMp" to -0.12,
                "physicalDefense" to -0.16,
                "magicDefense" to -0.16,
                "speed" to -0.10
            ),
            isNegative = true,
            type = TalentType.BATTLE_SURVIVAL
        )
    )

    private val allTalentsData: Map<String, TalentData> = buildMap {
        positiveTalentsData.forEach { put(it.id, it) }
        negativeTalentsData.forEach { put(it.id, it) }
    }

    val talents: Map<String, Talent> = allTalentsData.mapValues { it.value.toTalent() }

    fun getById(id: String): Talent? = talents[id]

    fun getTalentDataById(id: String): TalentData? = allTalentsData[id]

    fun getByRarity(rarity: Int): List<Talent> = talents.values.filter { it.rarity == rarity }

    fun getPositiveTalents(): List<Talent> = talents.values.filter { !it.isNegative }

    fun getNegativeTalents(): List<Talent> = talents.values.filter { it.isNegative }

    fun generateRandomTalents(count: Int, maxRarity: Int = 6): List<Talent> {
        val result = mutableListOf<Talent>()
        val availableTalents = allTalentsData.values.filter { it.rarity <= maxRarity }.toMutableList()
        val selectedTypes = mutableSetOf<TalentType>()

        repeat(count) {
            if (availableTalents.isEmpty()) return result

            val filteredTalents = availableTalents.filter { it.type !in selectedTypes }
            if (filteredTalents.isEmpty()) return result

            val selected = pickTalentByDistribution(filteredTalents)
            result.add(selected.toTalent())
            selectedTypes.add(selected.type)
            availableTalents.removeAll { it.type == selected.type }
        }

        return result
    }

    fun generateTalentsForDisciple(): List<Talent> {
        val result = mutableListOf<Talent>()
        val availableTalents = allTalentsData.values.toMutableList()
        val selectedTypes = mutableSetOf<TalentType>()

        val talentCount = Random.nextInt(1, 6)

        repeat(talentCount) {
            if (availableTalents.isEmpty()) return@repeat

            val filteredTalents = availableTalents.filter { it.type !in selectedTypes }
            if (filteredTalents.isEmpty()) return@repeat

            val selected = pickTalentByDistribution(filteredTalents)
            result.add(selected.toTalent())
            selectedTypes.add(selected.type)
            availableTalents.removeAll { it.type == selected.type }
        }

        return result
    }

    private fun pickTalentByDistribution(candidates: List<TalentData>): TalentData {
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("candidates cannot be empty")
        }

        val negativeCandidates = candidates.filter { it.isNegative }
        if (negativeCandidates.isNotEmpty() && Random.nextDouble() < NEGATIVE_TALENT_CHANCE) {
            return negativeCandidates.random()
        }

        val positiveCandidates = candidates.filter { !it.isNegative }
        if (positiveCandidates.isEmpty()) {
            return candidates.random()
        }

        val targetRarity = pickRarityByDistribution()
        val exactCandidates = positiveCandidates.filter { it.rarity == targetRarity }
        if (exactCandidates.isNotEmpty()) {
            return exactCandidates.random()
        }

        val fallbackRarity = positiveCandidates.map { it.rarity }.distinct()
            .minWithOrNull(compareBy<Int> { abs(it - targetRarity) }.thenByDescending { it })
            ?: positiveCandidates.first().rarity

        return positiveCandidates.filter { it.rarity == fallbackRarity }.random()
    }

    private fun pickRarityByDistribution(): Int {
        val roll = Random.nextDouble()
        var cumulative = 0.0
        for ((rarity, probability) in POSITIVE_RARITY_DISTRIBUTION) {
            cumulative += probability
            if (roll <= cumulative) {
                return rarity
            }
        }
        return POSITIVE_RARITY_DISTRIBUTION.last().first
    }

    fun getTalentsByIds(talentIds: List<String>): List<Talent> {
        return talentIds.mapNotNull { talents[it] }
    }

    fun calculateTalentEffects(talentIds: List<String>): Map<String, Double> {
        val effects = mutableMapOf<String, Double>()

        talentIds.forEach { id ->
            val talent = talents[id] ?: return@forEach
            talent.effects.forEach { (key, value) ->
                effects[key] = (effects[key] ?: 0.0) + value
            }
        }

        return effects
    }

    fun getTalentDisplayInfo(talentId: String): TalentDisplayInfo? {
        val talent = talents[talentId] ?: return null
        val color = if (talent.isNegative) "#000000" else GameConfig.Rarity.getColor(talent.rarity)
        return TalentDisplayInfo(talent, color)
    }
}

data class TalentDisplayInfo(
    val talent: Talent,
    val color: String
)
