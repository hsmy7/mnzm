package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.Affix
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.model.PositionBonus
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * 词条数据库
 *
 * 词条为通用加成池，覆盖所有加成类型：
 * - 弟子基础属性（扁平加成）
 * - 战斗属性（百分比加成）
 * - 职务加成（PositionBonus）
 * - 战斗伤害特殊加成（独立乘算因子，通过 effects map 传递）
 * - 修炼速度加成
 * - 寿命加成
 * - 功法槽加成
 * - 战斗成长加成
 *
 * 稀有度：1-3 阶（下品/中品/上品），负面 rarity=0 统一灰色。
 * 生成数量：0-5 个（35/35/20/6/3/1 加权，见 [DISCIPLE_TRAIT_COUNT_DISTRIBUTION]）。
 *
 * 战斗伤害特殊加成（damageAmplification/damageReduction/critDamageBonus/defenseBonus）
 * 通过 effects map 传递，由 DiscipleStatCalculator 聚合后注入 BattleCalculator 作为独立乘算因子。
 */
object AffixDatabase {

    val isInitialized: Boolean = true

    enum class AffixType {
        BASE_FLAT,           // 基础属性扁平加成
        BAT_PCT,             // 战斗属性百分比加成
        CULT_SPEED,          // 修炼速度加成
        LIFESPAN,            // 寿命加成
        MANUAL_SLOT,         // 功法槽加成
        WIN_GROWTH,          // 战斗成长加成
        DAMAGE_AMP,          // 伤害加成（独立乘算）
        DAMAGE_REDUCTION,    // 减伤（独立乘算）
        CRIT_DAMAGE,         // 暴击伤害加成（独立乘算）
        DEFENSE_BONUS,       // 防御加成（独立乘算）
        POSITION             // 职务加成
    }

    data class AffixData(
        val id: String,
        val name: String,
        val description: String,
        val rarity: Int,
        val effects: Map<String, Double>,
        val isNegative: Boolean,
        val type: AffixType,
        val template: String,
        val positionBonus: PositionBonus? = null
    ) {
        fun toAffix(): Affix = Affix(
            id = id,
            name = name,
            description = description,
            rarity = rarity,
            effects = effects,
            isNegative = isNegative,
            positionBonus = positionBonus
        )
    }

    private data class BaseFlatCfg(val rarity: Int, val value: Int)
    private data class BatPctCfg(val rarity: Int, val value: Double)
    private data class CultSpeedCfg(val rarity: Int, val value: Double)
    private data class LifespanCfg(val rarity: Int, val value: Double)
    private data class DmgAmpCfg(val rarity: Int, val value: Double)
    private data class DmgReduceCfg(val rarity: Int, val value: Double)
    private data class CritDmgCfg(val rarity: Int, val value: Double)
    private data class DefCfg(val rarity: Int, val value: Double)
    private data class PositionCfg(val rarity: Int, val value: Double)

    // === 3 阶数值梯度 ===

    /** 基础属性扁平加成：1阶 3 / 2阶 7 / 3阶 12 */
    private val baseFlatConfigs = listOf(
        BaseFlatCfg(1, 3), BaseFlatCfg(2, 7), BaseFlatCfg(3, 12)
    )

    /** 战斗属性百分比：1阶 4% / 2阶 9% / 3阶 16% */
    private val batPctConfigs = listOf(
        BatPctCfg(1, 0.04), BatPctCfg(2, 0.09), BatPctCfg(3, 0.16)
    )

    /** 修炼速度：1阶 5% / 2阶 11% / 3阶 20% */
    private val cultSpeedConfigs = listOf(
        CultSpeedCfg(1, 0.05), CultSpeedCfg(2, 0.11), CultSpeedCfg(3, 0.20)
    )

    /** 寿命：1阶 8% / 2阶 16% / 3阶 28% */
    private val lifespanConfigs = listOf(
        LifespanCfg(1, 0.08), LifespanCfg(2, 0.16), LifespanCfg(3, 0.28)
    )

    /** 伤害加成（独立乘算）：1阶 3% / 2阶 7% / 3阶 13% */
    private val dmgAmpConfigs = listOf(
        DmgAmpCfg(1, 0.03), DmgAmpCfg(2, 0.07), DmgAmpCfg(3, 0.13)
    )

    /** 减伤（独立乘算）：1阶 3% / 2阶 6% / 3阶 11% */
    private val dmgReduceConfigs = listOf(
        DmgReduceCfg(1, 0.03), DmgReduceCfg(2, 0.06), DmgReduceCfg(3, 0.11)
    )

    /** 暴击伤害加成（独立乘算）：1阶 6% / 2阶 14% / 3阶 24% */
    private val critDmgConfigs = listOf(
        CritDmgCfg(1, 0.06), CritDmgCfg(2, 0.14), CritDmgCfg(3, 0.24)
    )

    /** 防御加成（独立乘算）：1阶 4% / 2阶 9% / 3阶 15% */
    private val defConfigs = listOf(
        DefCfg(1, 0.04), DefCfg(2, 0.09), DefCfg(3, 0.15)
    )

    /** 职务职能效果加成：1阶 6% / 2阶 12% / 3阶 20% */
    private val positionConfigs = listOf(
        PositionCfg(1, 0.06), PositionCfg(2, 0.12), PositionCfg(3, 0.20)
    )

    private val positiveAffixesData: List<AffixData> = buildList {
        // 基础属性扁平加成词条（智力/悟性/魅力等）
        baseFlatConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_base_int",
                name = "聪慧",
                description = "智力+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("intelligenceFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = AffixType.BASE_FLAT,
                template = "aff_base_int"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_base_comp",
                name = "灵慧",
                description = "悟性+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("comprehensionFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = AffixType.BASE_FLAT,
                template = "aff_base_comp"
            ))
        }
        baseFlatConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_base_charm",
                name = "风采",
                description = "魅力+${cfg.value}",
                rarity = cfg.rarity,
                effects = mapOf("charmFlat" to cfg.value.toDouble()),
                isNegative = false,
                type = AffixType.BASE_FLAT,
                template = "aff_base_charm"
            ))
        }

        // 战斗属性百分比词条（物攻/法攻/气血/速度）
        batPctConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_bat_atk",
                name = "锐利",
                description = "物攻+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("physicalAttack" to cfg.value, "magicAttack" to cfg.value),
                isNegative = false,
                type = AffixType.BAT_PCT,
                template = "aff_bat_atk"
            ))
        }
        batPctConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_bat_hp",
                name = "厚甲",
                description = "气血+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("maxHp" to cfg.value),
                isNegative = false,
                type = AffixType.BAT_PCT,
                template = "aff_bat_hp"
            ))
        }
        batPctConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_bat_speed",
                name = "轻盈",
                description = "速度+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("speed" to cfg.value),
                isNegative = false,
                type = AffixType.BAT_PCT,
                template = "aff_bat_speed"
            ))
        }

        // 修炼速度词条
        cultSpeedConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_cult_speed",
                name = "悟道",
                description = "修炼速度+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("cultivationSpeed" to cfg.value),
                isNegative = false,
                type = AffixType.CULT_SPEED,
                template = "aff_cult_speed"
            ))
        }

        // 寿命词条
        lifespanConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_lifespan",
                name = "延年",
                description = "寿命+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("lifespan" to cfg.value),
                isNegative = false,
                type = AffixType.LIFESPAN,
                template = "aff_lifespan"
            ))
        }

        // 功法槽词条（仅 3 阶）
        add(AffixData(
            id = "r3_aff_manual_slot",
            name = "道藏",
            description = "功法槽位+1",
            rarity = 3,
            effects = mapOf("manualSlot" to 1.0),
            isNegative = false,
            type = AffixType.MANUAL_SLOT,
            template = "aff_manual_slot"
        ))

        // 战斗成长词条（仅 3 阶）
        add(AffixData(
            id = "r3_aff_win_growth",
            name = "战悟",
            description = "每胜利一场战斗后，随机一个属性+1（无上限）",
            rarity = 3,
            effects = mapOf("winBattleRandomAttrPlus" to 1.0),
            isNegative = false,
            type = AffixType.WIN_GROWTH,
            template = "aff_win_growth"
        ))

        // 伤害加成词条（独立乘算）
        dmgAmpConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_dmg_amp",
                name = "煞气",
                description = "伤害加成+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("damageAmplification" to cfg.value),
                isNegative = false,
                type = AffixType.DAMAGE_AMP,
                template = "aff_dmg_amp"
            ))
        }

        // 减伤词条（独立乘算）
        dmgReduceConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_dmg_reduce",
                name = "护体",
                description = "减伤+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("damageReduction" to cfg.value),
                isNegative = false,
                type = AffixType.DAMAGE_REDUCTION,
                template = "aff_dmg_reduce"
            ))
        }

        // 暴击伤害词条（独立乘算）
        critDmgConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_crit_dmg",
                name = "破军",
                description = "暴击伤害+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("critDamageBonus" to cfg.value),
                isNegative = false,
                type = AffixType.CRIT_DAMAGE,
                template = "aff_crit_dmg"
            ))
        }

        // 防御加成词条（独立乘算）
        defConfigs.forEach { cfg ->
            add(AffixData(
                id = "r${cfg.rarity}_aff_defense",
                name = "坚壁",
                description = "防御加成+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                effects = mapOf("defenseBonus" to cfg.value),
                isNegative = false,
                type = AffixType.DEFENSE_BONUS,
                template = "aff_defense"
            ))
        }

        // 职务加成词条
        addPositionAffixes(this)
    }

    private fun addPositionAffixes(builder: MutableList<AffixData>) {
        val positionTemplates = listOf(
            Triple("vice_sect_master", "辅政", ElderSlotType.VICE_SECT_MASTER) to "政策效果加成",
            Triple("herb_garden", "灵田", ElderSlotType.HERB_GARDEN) to "灵药成熟速度加成",
            Triple("alchemy", "丹道", ElderSlotType.ALCHEMY) to "炼丹成功率加成",
            Triple("forge", "器道", ElderSlotType.FORGE) to "炼器成功率加成",
            Triple("outer_elder", "外门", ElderSlotType.OUTER_ELDER) to "外门弟子突破指导加成",
            Triple("preaching", "传道", ElderSlotType.PREACHING) to "外门弟子传道修炼速度加成",
            Triple("law_enforcement", "执法", ElderSlotType.LAW_ENFORCEMENT) to "叛逃/偷盗捕获率加成",
            Triple("inner_elder", "内门", ElderSlotType.INNER_ELDER) to "内门弟子突破指导加成",
            Triple("recruiting", "招贤", ElderSlotType.RECRUITING) to "招募弟子数上限加成",
            Triple("cloud_preaching", "青云", ElderSlotType.CLOUD_PREACHING) to "内门弟子传道修炼速度加成"
        )
        positionTemplates.forEach { (key, bonusDesc) ->
            val (template, name, slotType) = key
            positionConfigs.forEach { cfg ->
                builder.add(AffixData(
                    id = "r${cfg.rarity}_aff_pos_$template",
                    name = "${name}之印",
                    description = "$bonusDesc+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                    rarity = cfg.rarity,
                    effects = emptyMap(),
                    isNegative = false,
                    type = AffixType.POSITION,
                    template = "aff_pos_$template",
                    positionBonus = PositionBonus(slotType, cfg.value)
                ))
            }
        }
    }

    private val negativeAffixesData = listOf(
        AffixData(
            id = "neg_aff_base",
            name = "愚钝",
            description = "智力/悟性/魅力 -5",
            rarity = 0,
            effects = mapOf(
                "intelligenceFlat" to -5.0,
                "comprehensionFlat" to -5.0,
                "charmFlat" to -5.0
            ),
            isNegative = true,
            type = AffixType.BASE_FLAT,
            template = "neg_aff_base"
        ),
        AffixData(
            id = "neg_aff_battle",
            name = "虚弱",
            description = "物攻/法攻/气血 -8%",
            rarity = 0,
            effects = mapOf(
                "physicalAttack" to -0.08,
                "magicAttack" to -0.08,
                "maxHp" to -0.08
            ),
            isNegative = true,
            type = AffixType.BAT_PCT,
            template = "neg_aff_battle"
        ),
        AffixData(
            id = "neg_aff_lifespan",
            name = "夭折",
            description = "寿命-15%",
            rarity = 0,
            effects = mapOf("lifespan" to -0.15),
            isNegative = true,
            type = AffixType.LIFESPAN,
            template = "neg_aff_lifespan"
        )
    )

    private val allAffixesData: Map<String, AffixData> = buildMap {
        positiveAffixesData.forEach { put(it.id, it) }
        negativeAffixesData.forEach { put(it.id, it) }
    }

    val affixes: Map<String, Affix> = allAffixesData.mapValues { it.value.toAffix() }

    fun getById(id: String): Affix? = affixes[id]

    fun getAffixDataById(id: String): AffixData? = allAffixesData[id]

    fun getByRarity(rarity: Int): List<Affix> = affixes.values.filter { it.rarity == rarity }

    fun getPositiveAffixes(): List<Affix> = affixes.values.filter { !it.isNegative }

    fun getNegativeAffixes(): List<Affix> = affixes.values.filter { it.isNegative }

    fun getAffixesByIds(affixIds: List<String>): List<Affix> =
        affixIds.mapNotNull { affixes[it] }

    /** 聚合词条效果：返回 effects map（战斗伤害特殊加成 key 也在此 map 中） */
    fun calculateAffixEffects(affixIds: List<String>): Map<String, Double> {
        val effects = mutableMapOf<String, Double>()
        affixIds.forEach { id ->
            val affix = affixes[id] ?: return@forEach
            affix.effects.forEach { (key, value) ->
                effects[key] = (effects[key] ?: 0.0) + value
            }
        }
        return effects
    }

    /** 聚合指定 slotType 的职务加成总和（来自天赋+词条） */
    fun aggregatePositionBonus(affixIds: List<String>, slotType: ElderSlotType): Double {
        return affixIds.mapNotNull { allAffixesData[it] }
            .filter { it.positionBonus?.slotType == slotType }
            .sumOf { it.positionBonus?.effectBonus ?: 0.0 }
    }

    /** 洗炼候选是否至少存在一条（无随机消耗，供引擎扣费前预检，防"扣费后无可抽条目"） */
    fun hasAffixCandidates(excludedTemplates: Set<String> = emptySet()): Boolean =
        allAffixesData.values.any { it.template !in excludedTemplates }

    /**
     * 单次洗炼抽取一个词条（品阶分布与生成一致：[rollTraitQuality] 四档）。
     *
     * [excludedTemplates] 过滤避免与保留槽位 template 冲突；池空（含全被排除）返回 null，
     * 调用方应先用 [hasAffixCandidates] 预检（扣费前），这里返回 null 仅是防御兜底。
     */
    fun rollSingleAffix(
        random: kotlin.random.Random = kotlin.random.Random,
        excludedTemplates: Set<String> = emptySet()
    ): AffixData? {
        val candidates = allAffixesData.values.filter { it.template !in excludedTemplates }
        if (candidates.isEmpty()) return null
        return pickByDistribution(candidates, random)
    }

    fun generateForDisciple(random: kotlin.random.Random = kotlin.random.Random): List<Affix> {
        val result = mutableListOf<Affix>()
        val available = allAffixesData.values.toMutableList()   // 负面经品阶概率抽取，不在此无条件滤除
        val selectedTemplates = mutableSetOf<String>()

        val count = rollTraitCount(random)  // 0-5 个（35/35/20/6/3/1）

        repeat(count) {
            if (available.isEmpty()) return@repeat

            val filtered = available.filter { it.template !in selectedTemplates }
            if (filtered.isEmpty()) return@repeat

            val selected = pickByDistribution(filtered, random)
            result.add(selected.toAffix())
            selectedTemplates.add(selected.template)
            available.removeAll { it.template == selected.template }
        }

        return result
    }

    private fun pickByDistribution(
        candidates: List<AffixData>,
        random: kotlin.random.Random
    ): AffixData {
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
        candidates: List<AffixData>,
        targetRarity: Int,
        random: kotlin.random.Random
    ): AffixData {
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
}

/** 词条战斗特殊加成聚合效果：各独立乘算因子的总和（与体质分开，各自独立乘算） */
data class AffixCombatEffects(
    val damageAmplification: Double = 0.0,
    val critDamageBonus: Double = 0.0,
    val damageReduction: Double = 0.0,
    val defenseBonus: Double = 0.0
)
