package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.PositionBonus
import com.xianxia.sect.core.model.Talent
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

object TalentDatabase {

    val isInitialized: Boolean = true

    /** 将旧版 rarity 序号(1-6)映射为品级(1-3) */
    private fun talentGrade(indexRarity: Int): Int = when (indexRarity) {
        1, 2 -> 1
        3, 4 -> 2
        5, 6 -> 3
        else -> 1
    }

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
        WIN_GROWTH,
        POSITION_VICE_SECT_MASTER,
        POSITION_HERB_GARDEN,
        POSITION_ALCHEMY,
        POSITION_FORGE,
        POSITION_OUTER_ELDER,
        POSITION_PREACHING,
        POSITION_LAW_ENFORCEMENT,
        POSITION_INNER_ELDER,
        POSITION_RECRUITING,
        POSITION_CLOUD_PREACHING
    }

    /**
     * 已从新生成池中移除的旧天赋类型（定义保留供旧存档解析）。
     * - CULT_SPEED 迁移至 PhysiqueDatabase
     * - LIFESPAN/MANUAL_SLOT/WIN_GROWTH 迁移至 AffixDatabase
     * - BREAK_CHANCE 直接移除（突破概率不再受天赋影响）
     */
    private val DEPRECATED_TALENT_TYPES = setOf(
        TalentType.CULT_SPEED,
        TalentType.BREAK_CHANCE,
        TalentType.LIFESPAN,
        TalentType.MANUAL_SLOT,
        TalentType.WIN_GROWTH
    )

    data class TalentData(
        val id: String,
        val name: String,
        val description: String,
        val rarity: Int,
        val effects: Map<String, Double>,
        val isNegative: Boolean,
        val type: TalentType,
        val template: String,
        val positionBonus: PositionBonus? = null
    ) {
        fun toTalent(): Talent = Talent(id, name, description, rarity, effects, isNegative, positionBonus)
    }

    private data class CultSpeedConfig(val rarity: Int, val value: Double)
    private data class BreakChanceConfig(val rarity: Int, val value: Double)
    private data class LifespanConfig(val rarity: Int, val value: Double)
    private data class BattlePctConfig(val rarity: Int, val value: Double)
    private data class BaseFlatConfig(val rarity: Int, val value: Int)
    private data class PositionBonusConfig(val rarity: Int, val value: Double)

    // === 旧天赋配置（保留供旧存档解析，6 阶） ===
    private val cultSpeedConfigs = listOf(
        CultSpeedConfig(1, 0.06),
        CultSpeedConfig(2, 0.10),
        CultSpeedConfig(3, 0.15),
        CultSpeedConfig(4, 0.22),
        CultSpeedConfig(5, 0.25),
        CultSpeedConfig(6, 0.32)
    )

    private val breakChanceConfigs = listOf(
        BreakChanceConfig(1, 0.01),
        BreakChanceConfig(2, 0.015),
        BreakChanceConfig(3, 0.03),
        BreakChanceConfig(4, 0.04),
        BreakChanceConfig(5, 0.05),
        BreakChanceConfig(6, 0.07)
    )

    private val lifespanConfigs = listOf(
        LifespanConfig(1, 0.10),
        LifespanConfig(2, 0.16),
        LifespanConfig(3, 0.25),
        LifespanConfig(4, 0.35),
        LifespanConfig(5, 0.45),
        LifespanConfig(6, 0.60)
    )

    // === 新天赋配置（3 阶，重新设计梯度） ===

    /** 战斗属性百分比：物攻/法攻/物防/法防/速度（1阶 6% / 2阶 13% / 3阶 22%） */
    private val batAtkDefSpeedConfigs = listOf(
        BattlePctConfig(1, 0.06), BattlePctConfig(2, 0.13), BattlePctConfig(3, 0.22)
    )

    /** 气血/法力（1阶 10%/18% / 2阶 18%/30% — 气血用 10/18/30，法力独立配置） */
    private val batHpConfigs = listOf(
        BattlePctConfig(1, 0.10), BattlePctConfig(2, 0.18), BattlePctConfig(3, 0.30)
    )
    private val batMpConfigs = listOf(
        BattlePctConfig(1, 0.10), BattlePctConfig(2, 0.18), BattlePctConfig(3, 0.30)
    )

    /** 暴击率（1阶 4% / 2阶 8% / 3阶 14%） */
    private val batCritConfigs = listOf(
        BattlePctConfig(1, 0.04), BattlePctConfig(2, 0.08), BattlePctConfig(3, 0.14)
    )

    /** 基础属性扁平加成（1阶 4 / 2阶 10 / 3阶 18） */
    private val baseFlatConfigs = listOf(
        BaseFlatConfig(1, 4), BaseFlatConfig(2, 10), BaseFlatConfig(3, 18)
    )

    /** 职务职能效果加成（1阶 7% / 2阶 14% / 3阶 22%） */
    private val positionBonusConfigs = listOf(
        PositionBonusConfig(1, 0.07), PositionBonusConfig(2, 0.14), PositionBonusConfig(3, 0.22)
    )

    private val positiveTalentsData: List<TalentData> = buildList {
        // === 旧天赋定义（保留供旧存档解析，不在新生成池中） ===
        cultSpeedConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_cult_speed",
                name = "灵脉流转",
                description = "修炼速度+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = talentGrade(cfg.rarity),
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
                rarity = talentGrade(cfg.rarity),
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
                rarity = talentGrade(cfg.rarity),
                effects = mapOf("lifespan" to cfg.value),
                isNegative = false,
                type = TalentType.LIFESPAN,
                template = "lifespan"
            ))
        }
        add(TalentData(
            id = "r6_manual_slot",
            name = "天衍道藏",
            description = "功法槽位+1",
            rarity = 3,
            effects = mapOf("manualSlot" to 1.0),
            isNegative = false,
            type = TalentType.MANUAL_SLOT,
            template = "manual_slot"
        ))
        add(TalentData(
            id = "r6_win_growth",
            name = "百战通神",
            description = "每胜利一场战斗后，随机一个属性+1（无上限）",
            rarity = 3,
            effects = mapOf("winBattleRandomAttrPlus" to 1.0),
            isNegative = false,
            type = TalentType.WIN_GROWTH,
            template = "win_growth"
        ))

        // === 新天赋定义（3 阶，新生成池） ===

        // 战斗属性类天赋
        batAtkDefSpeedConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_phy_atk",
                name = "勇武",
                description = "物攻+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("physicalAttack" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_PHY_ATK,
                template = "bat_phy_atk"
            ))
        }
        batAtkDefSpeedConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_mag_atk",
                name = "神通",
                description = "法攻+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("magicAttack" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_MAG_ATK,
                template = "bat_mag_atk"
            ))
        }
        batAtkDefSpeedConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_phy_def",
                name = "铁骨",
                description = "物防+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("physicalDefense" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_PHY_DEF,
                template = "bat_phy_def"
            ))
        }
        batAtkDefSpeedConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_mag_def",
                name = "玄清",
                description = "法防+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
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
                name = "体健",
                description = "气血+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
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
                name = "气海",
                description = "法力+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("maxMp" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_MP,
                template = "bat_mp"
            ))
        }
        batAtkDefSpeedConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_bat_speed",
                name = "疾风",
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
                name = "锋锐",
                description = "暴击+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("critRate" to cfg.value),
                isNegative = false,
                type = TalentType.BAT_CRIT,
                template = "bat_crit"
            ))
        }

        // 基础属性类天赋
        baseFlatConfigs.forEach { cfg ->
            add(TalentData(
                id = "r${cfg.rarity}_base_int",
                name = "天慧",
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
                name = "仙姿",
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
                name = "赤诚",
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
                name = "顿悟",
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
                name = "天工",
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
                name = "天丹",
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
                name = "青帝",
                description = "灵植+${cfg.value}",
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
                name = "夫子",
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
                name = "仁心",
                description = "德行+${cfg.value}",
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
                name = "地眼",
                description = "采矿+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("miningFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = TalentType.BASE_MINING,
                template = "base_mining"
            ))
        }

        // 职务类天赋（担任职务时增强该职务职能效果）
        addPositionTalents(this)
    }

    private fun addPositionTalents(builder: MutableList<TalentData>) {
        val positionTemplates = listOf(
            Triple("vice_sect_master", "辅政之才", ElderSlotType.VICE_SECT_MASTER) to "政策效果加成",
            Triple("herb_garden", "灵田灵手", ElderSlotType.HERB_GARDEN) to "灵药成熟速度加成",
            Triple("alchemy", "丹道宗师", ElderSlotType.ALCHEMY) to "炼丹成功率加成",
            Triple("forge", "器道宗师", ElderSlotType.FORGE) to "炼器成功率加成",
            Triple("outer_elder", "外门栋梁", ElderSlotType.OUTER_ELDER) to "外门弟子突破指导加成",
            Triple("preaching", "传道大师", ElderSlotType.PREACHING) to "外门弟子传道修炼速度加成",
            Triple("law_enforcement", "执法金刚", ElderSlotType.LAW_ENFORCEMENT) to "叛逃/偷盗捕获率加成",
            Triple("inner_elder", "内门柱石", ElderSlotType.INNER_ELDER) to "内门弟子突破指导加成",
            Triple("recruiting", "招贤伯乐", ElderSlotType.RECRUITING) to "招募弟子数上限加成",
            Triple("cloud_preaching", "青云传道", ElderSlotType.CLOUD_PREACHING) to "内门弟子传道修炼速度加成"
        )
        val typeByTemplate = mapOf(
            "vice_sect_master" to TalentType.POSITION_VICE_SECT_MASTER,
            "herb_garden" to TalentType.POSITION_HERB_GARDEN,
            "alchemy" to TalentType.POSITION_ALCHEMY,
            "forge" to TalentType.POSITION_FORGE,
            "outer_elder" to TalentType.POSITION_OUTER_ELDER,
            "preaching" to TalentType.POSITION_PREACHING,
            "law_enforcement" to TalentType.POSITION_LAW_ENFORCEMENT,
            "inner_elder" to TalentType.POSITION_INNER_ELDER,
            "recruiting" to TalentType.POSITION_RECRUITING,
            "cloud_preaching" to TalentType.POSITION_CLOUD_PREACHING
        )
        positionTemplates.forEach { (key, bonusDesc) ->
            val (template, name, slotType) = key
            positionBonusConfigs.forEach { cfg ->
                builder.add(TalentData(
                    id = "r${cfg.rarity}_pos_$template",
                    name = name,
                    description = "$bonusDesc+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                    rarity = cfg.rarity,
                    effects = emptyMap(),
                    isNegative = false,
                    type = typeByTemplate[template] ?: error("Unknown talent template: $template"),
                    template = "pos_$template",
                    positionBonus = PositionBonus(slotType, cfg.value)
                ))
            }
        }
    }

    private val negativeTalentsData = listOf(
        TalentData(
            id = "neg_base_comprehension",
            name = "神识迟钝",
            description = "悟性/智力/传道 -8",
            rarity = 0,
            effects = mapOf("comprehensionFlat" to -8.0, "intelligenceFlat" to -8.0, "teachingFlat" to -8.0),
            isNegative = true,
            type = TalentType.BASE_COMP,
            template = "neg_base_comprehension"
        ),
        TalentData(
            id = "neg_base_craft",
            name = "百艺生疏",
            description = "炼器/炼丹/种植 -6",
            rarity = 0,
            effects = mapOf("artifactRefiningFlat" to -6.0, "pillRefiningFlat" to -6.0, "spiritPlantingFlat" to -6.0),
            isNegative = true,
            type = TalentType.BASE_ARTI,
            template = "neg_base_craft"
        ),
        TalentData(
            id = "neg_base_social",
            name = "心性偏执",
            description = "魅力/忠诚/道德 -6",
            rarity = 0,
            effects = mapOf("charmFlat" to -6.0, "loyaltyFlat" to -6.0, "moralityFlat" to -6.0),
            isNegative = true,
            type = TalentType.BASE_CHARM,
            template = "neg_base_social"
        ),
        TalentData(
            id = "neg_battle_offense",
            name = "怯战失锋",
            description = "物攻/法攻/暴击下降",
            rarity = 0,
            effects = mapOf("physicalAttack" to -0.10, "magicAttack" to -0.10, "critRate" to -0.02),
            isNegative = true,
            type = TalentType.BAT_PHY_ATK,
            template = "neg_battle_offense"
        ),
        TalentData(
            id = "neg_battle_survival",
            name = "体魄亏空",
            description = "生存属性下降",
            rarity = 0,
            effects = mapOf(
                "maxHp" to -0.15,
                "maxMp" to -0.08,
                "physicalDefense" to -0.10,
                "magicDefense" to -0.10,
                "speed" to -0.06
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

    /**
     * 按品阶返回正向（非负面）天赋列表，过滤退役天赋类型（[DEPRECATED_TALENT_TYPES]）。
     * 与 [generateTalentsForDisciple] 的生成池对齐——洗炼保底池（3 阶）经此取池，
     * 退役超模条目（如 r5/r6 寿命加成）不会经保底路径重新流入。
     */
    fun getPositiveByRarity(rarity: Int): List<Talent> = getByRarity(rarity)
        .filter { !it.isNegative && getTalentDataById(it.id)?.type !in DEPRECATED_TALENT_TYPES }

    fun getPositiveTalents(): List<Talent> = talents.values
        .filter { !it.isNegative }
        .filter { getTalentDataById(it.id)?.type !in DEPRECATED_TALENT_TYPES }

    fun getNegativeTalents(): List<Talent> = talents.values.filter { it.isNegative }

    fun generateRandomTalents(
        count: Int,
        maxRarity: Int = 3,
        random: kotlin.random.Random = kotlin.random.Random
    ): List<Talent> {
        val result = mutableListOf<Talent>()
        val availableTalents = allTalentsData.values
            .filter { it.rarity <= maxRarity && !it.isNegative && it.type !in DEPRECATED_TALENT_TYPES }
            .toMutableList()
        val selectedTemplates = mutableSetOf<String>()

        repeat(count) {
            if (availableTalents.isEmpty()) return result

            val filteredTalents = availableTalents.filter { it.template !in selectedTemplates }
            if (filteredTalents.isEmpty()) return result

            val selected = pickTalentByDistribution(filteredTalents, random)
            result.add(selected.toTalent())
            selectedTemplates.add(selected.template)
            availableTalents.removeAll { it.template == selected.template }
        }

        return result
    }

    /** 洗炼候选是否至少存在一条（无随机消耗，供引擎扣费前预检，防"扣费后无可抽条目"） */
    fun hasTalentCandidates(excludedTemplates: Set<String> = emptySet()): Boolean =
        allTalentsData.values
            .any { it.type !in DEPRECATED_TALENT_TYPES && it.template !in excludedTemplates }

    /**
     * 单次洗炼抽取一个天赋（品阶分布与生成一致：[rollTraitQuality] 四档）。
     *
     * [excludedTemplates] 过滤避免与保留槽位 template 冲突；池空（含全被排除）返回 null，
     * 调用方应先用 [hasTalentCandidates] 预检（扣费前），这里返回 null 仅是防御兜底。
     */
    fun rollSingleTalent(
        random: kotlin.random.Random = kotlin.random.Random,
        excludedTemplates: Set<String> = emptySet()
    ): TalentData? {
        val candidates = allTalentsData.values
            .filter { it.type !in DEPRECATED_TALENT_TYPES && it.template !in excludedTemplates }
        if (candidates.isEmpty()) return null
        return pickTalentByDistribution(candidates, random)
    }

    fun generateTalentsForDisciple(random: kotlin.random.Random = kotlin.random.Random): List<Talent> {
        val result = mutableListOf<Talent>()
        val availableTalents = allTalentsData.values
            .filter { it.type !in DEPRECATED_TALENT_TYPES }   // 负面经品阶概率抽取，不在此无条件滤除
            .toMutableList()
        val selectedTemplates = mutableSetOf<String>()

        val talentCount = rollTraitCount(random)  // 0-5 个（35/35/20/6/3/1）

        repeat(talentCount) {
            if (availableTalents.isEmpty()) return@repeat

            val filteredTalents = availableTalents.filter { it.template !in selectedTemplates }
            if (filteredTalents.isEmpty()) return@repeat

            val selected = pickTalentByDistribution(filteredTalents, random)
            result.add(selected.toTalent())
            selectedTemplates.add(selected.template)
            availableTalents.removeAll { it.template == selected.template }
        }

        return result
    }

    private fun pickTalentByDistribution(
        candidates: List<TalentData>,
        random: kotlin.random.Random
    ): TalentData {
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("candidates cannot be empty")
        }

        // 单次 nextDouble 消费四档：负面30% / 下品50% / 中品18% / 上品2%
        val quality = rollTraitQuality(random)

        if (quality == 0) {
            val negativeCandidates = candidates.filter { it.isNegative }
            if (negativeCandidates.isNotEmpty()) {
                return negativeCandidates.random(random)
            }
            // 负面池耗尽 → 品阶1兜底（不做重滚，保持固定 nextDouble 消费次数）
            return pickPositiveByRarity(candidates, 1, random)
        }
        return pickPositiveByRarity(candidates, quality, random)
    }

    private fun pickPositiveByRarity(
        candidates: List<TalentData>,
        targetRarity: Int,
        random: kotlin.random.Random
    ): TalentData {
        val positiveCandidates = candidates.filter { !it.isNegative }
        if (positiveCandidates.isEmpty()) return candidates.random(random)

        // 精确匹配优先；无精确匹配时取差值最小档（平局取较高档）
        return positiveCandidates.filter { it.rarity == targetRarity }
            .ifEmpty {
                val fallbackRarity = positiveCandidates.map { it.rarity }.distinct()
                    .minWithOrNull(compareBy<Int> { abs(it - targetRarity) }.thenByDescending { it })
                    ?: positiveCandidates.first().rarity
                positiveCandidates.filter { it.rarity == fallbackRarity }
            }
            .random(random)
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
        return TalentDisplayInfo(talent, talent.color)
    }
}

data class TalentDisplayInfo(
    val talent: Talent,
    val color: String
)
