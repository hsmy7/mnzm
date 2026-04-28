package com.xianxia.sect.core.registry

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Talent
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

object TalentDatabase {

    val isInitialized: Boolean = true

    private const val NEGATIVE_TALENT_CHANCE = 0.14

    private val POSITIVE_RARITY_DISTRIBUTION = listOf(
        1 to 0.419,
        2 to 0.272,
        3 to 0.168,
        4 to 0.084,
        5 to 0.037,
        6 to 0.020
    )

    enum class TalentType {
        CULT_SPEED,
        BREAK_CHANCE,
        LIFESPAN,
        BAT_PHY_ATK,
        BAT_MAG_ATK,
        BAT_PHY_DEF,
        BAT_MAG_DEF,
        BAT_HP,
        BAT_MP,
        BAT_SPEED,
        BAT_CRIT,
        BASE_INT,
        BASE_CHARM,
        BASE_LOYAL,
        BASE_COMP,
        BASE_ARTI,
        BASE_PILL,
        BASE_PLANT,
        BASE_TEACH,
        BASE_MORAL,
        BASE_MINING,
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
        val type: TalentType,
        val template: String
    ) {
        fun toTalent(): Talent = Talent(id, name, description, rarity, effects, isNegative)
    }

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

    private data class CultSpeedConfig(val rarity: Int, val value: Double)
    private data class BreakChanceConfig(val rarity: Int, val value: Double)
    private data class LifespanConfig(val rarity: Int, val value: Double)
    private data class BattlePctConfig(val rarity: Int, val value: Double)
    private data class BaseFlatConfig(val rarity: Int, val value: Int)

    private val cultSpeedConfigs = listOf(
        CultSpeedConfig(1, 0.08),
        CultSpeedConfig(2, 0.14),
        CultSpeedConfig(3, 0.22),
        CultSpeedConfig(4, 0.31),
        CultSpeedConfig(5, 0.42),
        CultSpeedConfig(6, 0.56)
    )

    private val breakChanceConfigs = listOf(
        BreakChanceConfig(1, 0.01),
        BreakChanceConfig(2, 0.02),
        BreakChanceConfig(3, 0.03),
        BreakChanceConfig(4, 0.05),
        BreakChanceConfig(5, 0.07),
        BreakChanceConfig(6, 0.10)
    )

    private val lifespanConfigs = listOf(
        LifespanConfig(1, 0.12),
        LifespanConfig(2, 0.20),
        LifespanConfig(3, 0.32),
        LifespanConfig(4, 0.46),
        LifespanConfig(5, 0.64),
        LifespanConfig(6, 0.88)
    )

    private val batPhyAtkConfigs = listOf(
        BattlePctConfig(1, 0.05), BattlePctConfig(2, 0.08), BattlePctConfig(3, 0.12),
        BattlePctConfig(4, 0.17), BattlePctConfig(5, 0.23), BattlePctConfig(6, 0.30)
    )
    private val batMagAtkConfigs = batPhyAtkConfigs
    private val batPhyDefConfigs = batPhyAtkConfigs
    private val batMagDefConfigs = batPhyAtkConfigs

    private val batHpConfigs = listOf(
        BattlePctConfig(1, 0.08), BattlePctConfig(2, 0.12), BattlePctConfig(3, 0.18),
        BattlePctConfig(4, 0.25), BattlePctConfig(5, 0.34), BattlePctConfig(6, 0.45)
    )
    private val batMpConfigs = listOf(
        BattlePctConfig(1, 0.04), BattlePctConfig(2, 0.06), BattlePctConfig(3, 0.09),
        BattlePctConfig(4, 0.13), BattlePctConfig(5, 0.17), BattlePctConfig(6, 0.23)
    )
    private val batSpeedConfigs = listOf(
        BattlePctConfig(1, 0.03), BattlePctConfig(2, 0.05), BattlePctConfig(3, 0.07),
        BattlePctConfig(4, 0.10), BattlePctConfig(5, 0.13), BattlePctConfig(6, 0.17)
    )
    private val batCritConfigs = listOf(
        BattlePctConfig(1, 0.008), BattlePctConfig(2, 0.012), BattlePctConfig(3, 0.018),
        BattlePctConfig(4, 0.026), BattlePctConfig(5, 0.036), BattlePctConfig(6, 0.050)
    )

    private val baseFlatConfigs = listOf(
        BaseFlatConfig(1, 2), BaseFlatConfig(2, 4), BaseFlatConfig(3, 6),
        BaseFlatConfig(4, 9), BaseFlatConfig(5, 13), BaseFlatConfig(6, 18)
    )

    private val positiveTalentsData: List<TalentData> = buildList {
        cultSpeedConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_cult_speed",
                name = "灵脉流转",
                description = "修炼速度+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("cultivationSpeed" to cfg.value),
                isNegative = false,
                type = TalentType.CULT_SPEED,
                template = "cult_speed"
            ))
        }
        breakChanceConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_break_chance",
                name = "悟道通玄",
                description = "突破概率+${String.format(Locale.ROOT, "%.1f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("breakthroughChance" to cfg.value),
                isNegative = false,
                type = TalentType.BREAK_CHANCE,
                template = "break_chance"
            ))
        }
        lifespanConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_lifespan",
                name = "寿元绵长",
                description = "寿命+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("lifespan" to cfg.value),
                isNegative = false,
                type = TalentType.LIFESPAN,
                template = "lifespan"
            ))
        }
        batPhyAtkConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_phy_atk",
                name = "力破千军",
                description = "物理攻击+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("physicalAttack" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_PHY_ATK,
                template = "bat_phy_atk"
            ))
        }
        batMagAtkConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_mag_atk",
                name = "法贯长虹",
                description = "法术攻击+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("magicAttack" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_MAG_ATK,
                template = "bat_mag_atk"
            ))
        }
        batPhyDefConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_phy_def",
                name = "铁壁铜墙",
                description = "物理防御+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("physicalDefense" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_PHY_DEF,
                template = "bat_phy_def"
            ))
        }
        batMagDefConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_mag_def",
                name = "灵护元神",
                description = "法术防御+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("magicDefense" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_MAG_DEF,
                template = "bat_mag_def"
            ))
        }
        batHpConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_hp",
                name = "气血充盈",
                description = "生命上限+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("maxHp" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_HP,
                template = "bat_hp"
            ))
        }
        batMpConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_mp",
                name = "灵海浩瀚",
                description = "法力上限+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("maxMp" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_MP,
                template = "bat_mp"
            ))
        }
        batSpeedConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_speed",
                name = "身轻如燕",
                description = "速度+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("speed" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_SPEED,
                template = "bat_speed"
            ))
        }
        batCritConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_crit",
                name = "杀意凛然",
                description = "暴击率+${String.format(Locale.ROOT, "%.1f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("critRate" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_CRIT,
                template = "bat_crit"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_int",
                name = "天资聪颖",
                description = "智力+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("intelligenceFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_INT,
                template = "base_int"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_charm",
                name = "风华绝代",
                description = "魅力+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("charmFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_CHARM,
                template = "base_charm"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_loyal",
                name = "赤胆忠心",
                description = "忠诚+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("loyaltyFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_LOYAL,
                template = "base_loyal"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_comp",
                name = "慧根深植",
                description = "悟性+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("comprehensionFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_COMP,
                template = "base_comp"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_arti",
                name = "巧夺天工",
                description = "炼器+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("artifactRefiningFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_ARTI,
                template = "base_arti"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_pill",
                name = "丹心妙手",
                description = "炼丹+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("pillRefiningFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_PILL,
                template = "base_pill"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_plant",
                name = "灵植亲和",
                description = "种植+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("spiritPlantingFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_PLANT,
                template = "base_plant"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_teach",
                name = "循循善诱",
                description = "传道+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("teachingFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_TEACH,
                template = "base_teach"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_moral",
                name = "正气浩然",
                description = "道德+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("moralityFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_MORAL,
                template = "base_moral"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_mining",
                name = "地脉感应",
                description = "采矿+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("miningFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_MINING,
                template = "base_mining"
            ))
        }

        add(TalentData(
            id = "r6_manual_slot",
            name = "天衍道藏",
            description = "功法槽位+1",
            rarity = 6,
            effects = mapOf("manualSlot" to 1.0),
            isNegative = false,
            type = TalentType.MANUAL_SLOT,
            template = "manual_slot"
        ))

        add(TalentData(
            id = "r6_win_growth",
            name = "百战通神",
            description = "每胜利一场战斗后，随机一个属性+1（无上限）",
            rarity = 6,
            effects = mapOf("winBattleRandomAttrPlus" to 1.0),
            isNegative = false,
            type = TalentType.WIN_GROWTH,
            template = "win_growth"
        ))
    }

    private val negativeTalentsData = listOf(
        TalentData(
            id = "neg_cultivation",
            name = "灵脉紊乱",
            description = "修炼速度-28%，突破概率-5%",
            rarity = 3,
            effects = mapOf("cultivationSpeed" to -0.28, "breakthroughChance" to -0.05),
            isNegative = true,
            type = TalentType.CULT_SPEED,
            template = "neg_cultivation"
        ),
        TalentData(
            id = "neg_lifespan",
            name = "寿元亏损",
            description = "寿命-35%",
            rarity = 3,
            effects = mapOf("lifespan" to -0.35),
            isNegative = true,
            type = TalentType.LIFESPAN,
            template = "neg_lifespan"
        ),
        TalentData(
            id = "neg_base_comprehension",
            name = "神识迟钝",
            description = "悟性/智力/传道 -14",
            rarity = 3,
            effects = mapOf("comprehensionFlat" to -14.0, "intelligenceFlat" to -14.0, "teachingFlat" to -14.0),
            isNegative = true,
            type = TalentType.BASE_COMP,
            template = "neg_base_comprehension"
        ),
        TalentData(
            id = "neg_base_craft",
            name = "百艺生疏",
            description = "炼器/炼丹/种植 -12",
            rarity = 3,
            effects = mapOf("artifactRefiningFlat" to -12.0, "pillRefiningFlat" to -12.0, "spiritPlantingFlat" to -12.0),
            isNegative = true,
            type = TalentType.BASE_ARTI,
            template = "neg_base_craft"
        ),
        TalentData(
            id = "neg_base_social",
            name = "心性偏执",
            description = "魅力/忠诚/道德 -12",
            rarity = 3,
            effects = mapOf("charmFlat" to -12.0, "loyaltyFlat" to -12.0, "moralityFlat" to -12.0),
            isNegative = true,
            type = TalentType.BASE_CHARM,
            template = "neg_base_social"
        ),
        TalentData(
            id = "neg_battle_offense",
            name = "怯战失锋",
            description = "物攻/法攻/暴击下降",
            rarity = 3,
            effects = mapOf("physicalAttack" to -0.18, "magicAttack" to -0.18, "critRate" to -0.03),
            isNegative = true,
            type = TalentType.BAT_PHY_ATK,
            template = "neg_battle_offense"
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
            type = TalentType.BAT_HP,
            template = "neg_battle_survival"
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
        val availableTalents = allTalentsData.values.filter { it.rarity <= maxRarity && !it.isNegative }.toMutableList()
        val selectedTemplates = mutableSetOf<String>()

        repeat(count) {
            if (availableTalents.isEmpty()) return result

            val filteredTalents = availableTalents.filter { it.template !in selectedTemplates }
            if (filteredTalents.isEmpty()) return result

            val selected = pickTalentByDistribution(filteredTalents)
            result.add(selected.toTalent())
            selectedTemplates.add(selected.template)
            availableTalents.removeAll { it.template == selected.template }
        }

        return result
    }

    fun generateTalentsForDisciple(): List<Talent> {
        val result = mutableListOf<Talent>()
        val availableTalents = allTalentsData.values.filter { !it.isNegative }.toMutableList()
        val selectedTemplates = mutableSetOf<String>()

        val talentCount = Random.nextInt(0, 6)

        repeat(talentCount) {
            if (availableTalents.isEmpty()) return@repeat

            val filteredTalents = availableTalents.filter { it.template !in selectedTemplates }
            if (filteredTalents.isEmpty()) return@repeat

            val selected = pickTalentByDistribution(filteredTalents)
            result.add(selected.toTalent())
            selectedTemplates.add(selected.template)
            availableTalents.removeAll { it.template == selected.template }
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
