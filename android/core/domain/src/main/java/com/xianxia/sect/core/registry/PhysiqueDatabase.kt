package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.Physique
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * 体质数据库
 *
 * 体质仅包含：修炼速度加成 + 战斗伤害特殊加成（独立乘算因子）。
 * 不含面板战斗属性（物攻/法攻/防/气血/法力/速度/暴击）——这些归天赋/词条。
 *
 * 稀有度：1-3 阶（下品/中品/上品），负面 rarity=0 统一灰色。
 * 生成数量：0-5 个（35/35/20/6/3/1 加权，见 [DISCIPLE_TRAIT_COUNT_DISTRIBUTION]）。
 */
object PhysiqueDatabase {

    val isInitialized: Boolean = true

    enum class PhysiqueType {
        CULT_SPEED,          // 修炼速度
        DAMAGE_AMP,          // 伤害加成（独立乘算）
        DAMAGE_REDUCTION,    // 减伤（独立乘算）
        CRIT_DAMAGE,         // 暴击伤害加成（独立乘算，仅暴击生效）
        DEFENSE_BONUS,       // 防御加成（独立乘算）
        HYBRID_OFFENSE,      // 混合进攻（伤害加成 + 暴击伤害，均独立乘算）
        HYBRID_DEFENSE       // 混合防御（减伤 + 防御加成，均独立乘算）
    }

    data class PhysiqueData(
        val id: String,
        val name: String,
        val description: String,
        val rarity: Int,
        val cultivationSpeedBonus: Double,
        val damageAmplification: Double,
        val damageReduction: Double,
        val critDamageBonus: Double,
        val defenseBonus: Double,
        val isNegative: Boolean,
        val type: PhysiqueType,
        val template: String
    ) {
        fun toPhysique(): Physique = Physique(
            id = id,
            name = name,
            description = description,
            rarity = rarity,
            cultivationSpeedBonus = cultivationSpeedBonus,
            damageAmplification = damageAmplification,
            damageReduction = damageReduction,
            critDamageBonus = critDamageBonus,
            defenseBonus = defenseBonus,
            isNegative = isNegative
        )
    }

    private data class CultSpeedCfg(val rarity: Int, val value: Double)
    private data class DmgAmpCfg(val rarity: Int, val value: Double)
    private data class DmgReduceCfg(val rarity: Int, val value: Double)
    private data class CritDmgCfg(val rarity: Int, val value: Double)
    private data class DefCfg(val rarity: Int, val value: Double)
    private data class HybridOffCfg(val rarity: Int, val amp: Double, val crit: Double)
    private data class HybridDefCfg(val rarity: Int, val reduce: Double, val def: Double)

    // === 3 阶数值梯度 ===

    /** 修炼速度加成（进入 aptitudeBonus 乘区）：1阶 8% / 2阶 16% / 3阶 28% */
    private val cultSpeedConfigs = listOf(
        CultSpeedCfg(1, 0.08), CultSpeedCfg(2, 0.16), CultSpeedCfg(3, 0.28)
    )

    /** 伤害加成（独立乘算因子）：1阶 5% / 2阶 11% / 3阶 20% */
    private val dmgAmpConfigs = listOf(
        DmgAmpCfg(1, 0.05), DmgAmpCfg(2, 0.11), DmgAmpCfg(3, 0.20)
    )

    /** 减伤（独立乘算因子）：1阶 4% / 2阶 9% / 3阶 16% */
    private val dmgReduceConfigs = listOf(
        DmgReduceCfg(1, 0.04), DmgReduceCfg(2, 0.09), DmgReduceCfg(3, 0.16)
    )

    /** 暴击伤害加成（独立乘算，仅暴击生效）：1阶 10% / 2阶 22% / 3阶 38% */
    private val critDmgConfigs = listOf(
        CritDmgCfg(1, 0.10), CritDmgCfg(2, 0.22), CritDmgCfg(3, 0.38)
    )

    /** 防御加成（独立乘算因子）：1阶 6% / 2阶 13% / 3阶 22% */
    private val defConfigs = listOf(
        DefCfg(1, 0.06), DefCfg(2, 0.13), DefCfg(3, 0.22)
    )

    /** 混合进攻（伤害加成 + 暴击伤害） */
    private val hybridOffConfigs = listOf(
        HybridOffCfg(1, 0.03, 0.06),
        HybridOffCfg(2, 0.07, 0.14),
        HybridOffCfg(3, 0.12, 0.24)
    )

    /** 混合防御（减伤 + 防御加成） */
    private val hybridDefConfigs = listOf(
        HybridDefCfg(1, 0.02, 0.04),
        HybridDefCfg(2, 0.05, 0.08),
        HybridDefCfg(3, 0.09, 0.14)
    )

    private val positivePhysiquesData: List<PhysiqueData> = buildList {
        // 修炼速度类
        cultSpeedConfigs.forEach { cfg ->
            add(PhysiqueData(
                id = "r${cfg.rarity}_phys_cult_speed",
                name = "灵脉天成",
                description = "修炼速度+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                cultivationSpeedBonus = cfg.value,
                damageAmplification = 0.0,
                damageReduction = 0.0,
                critDamageBonus = 0.0,
                defenseBonus = 0.0,
                isNegative = false,
                type = PhysiqueType.CULT_SPEED,
                template = "phys_cult_speed"
            ))
        }

        // 伤害加成类
        dmgAmpConfigs.forEach { cfg ->
            add(PhysiqueData(
                id = "r${cfg.rarity}_phys_dmg_amp",
                name = "九阳真身",
                description = "伤害加成+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                cultivationSpeedBonus = 0.0,
                damageAmplification = cfg.value,
                damageReduction = 0.0,
                critDamageBonus = 0.0,
                defenseBonus = 0.0,
                isNegative = false,
                type = PhysiqueType.DAMAGE_AMP,
                template = "phys_dmg_amp"
            ))
        }

        // 减伤类
        dmgReduceConfigs.forEach { cfg ->
            add(PhysiqueData(
                id = "r${cfg.rarity}_phys_dmg_reduce",
                name = "金身不坏",
                description = "减伤+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                cultivationSpeedBonus = 0.0,
                damageAmplification = 0.0,
                damageReduction = cfg.value,
                critDamageBonus = 0.0,
                defenseBonus = 0.0,
                isNegative = false,
                type = PhysiqueType.DAMAGE_REDUCTION,
                template = "phys_dmg_reduce"
            ))
        }

        // 暴击伤害类
        critDmgConfigs.forEach { cfg ->
            add(PhysiqueData(
                id = "r${cfg.rarity}_phys_crit_dmg",
                name = "天眼通",
                description = "暴击伤害+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                cultivationSpeedBonus = 0.0,
                damageAmplification = 0.0,
                damageReduction = 0.0,
                critDamageBonus = cfg.value,
                defenseBonus = 0.0,
                isNegative = false,
                type = PhysiqueType.CRIT_DAMAGE,
                template = "phys_crit_dmg"
            ))
        }

        // 防御加成类
        defConfigs.forEach { cfg ->
            add(PhysiqueData(
                id = "r${cfg.rarity}_phys_defense",
                name = "玄铁体质",
                description = "防御加成+${String.format(Locale.ROOT, "%.0f", cfg.value * 100)}%",
                rarity = cfg.rarity,
                cultivationSpeedBonus = 0.0,
                damageAmplification = 0.0,
                damageReduction = 0.0,
                critDamageBonus = 0.0,
                defenseBonus = cfg.value,
                isNegative = false,
                type = PhysiqueType.DEFENSE_BONUS,
                template = "phys_defense"
            ))
        }

        // 混合进攻类
        hybridOffConfigs.forEach { cfg ->
            add(PhysiqueData(
                id = "r${cfg.rarity}_phys_hybrid_off",
                name = "战魔之体",
                description = "伤害加成+${String.format(Locale.ROOT, "%.0f", cfg.amp * 100)}%，暴击伤害+${String.format(Locale.ROOT, "%.0f", cfg.crit * 100)}%",
                rarity = cfg.rarity,
                cultivationSpeedBonus = 0.0,
                damageAmplification = cfg.amp,
                damageReduction = 0.0,
                critDamageBonus = cfg.crit,
                defenseBonus = 0.0,
                isNegative = false,
                type = PhysiqueType.HYBRID_OFFENSE,
                template = "phys_hybrid_off"
            ))
        }

        // 混合防御类
        hybridDefConfigs.forEach { cfg ->
            add(PhysiqueData(
                id = "r${cfg.rarity}_phys_hybrid_def",
                name = "磐石体质",
                description = "减伤+${String.format(Locale.ROOT, "%.0f", cfg.reduce * 100)}%，防御加成+${String.format(Locale.ROOT, "%.0f", cfg.def * 100)}%",
                rarity = cfg.rarity,
                cultivationSpeedBonus = 0.0,
                damageAmplification = 0.0,
                damageReduction = cfg.reduce,
                critDamageBonus = 0.0,
                defenseBonus = cfg.def,
                isNegative = false,
                type = PhysiqueType.HYBRID_DEFENSE,
                template = "phys_hybrid_def"
            ))
        }
    }

    private val negativePhysiquesData = listOf(
        PhysiqueData(
            id = "neg_phys_cult",
            name = "经脉堵塞",
            description = "修炼速度-20%",
            rarity = 0,
            cultivationSpeedBonus = -0.20,
            damageAmplification = 0.0,
            damageReduction = 0.0,
            critDamageBonus = 0.0,
            defenseBonus = 0.0,
            isNegative = true,
            type = PhysiqueType.CULT_SPEED,
            template = "neg_phys_cult"
        ),
        PhysiqueData(
            id = "neg_phys_defense",
            name = "体弱多病",
            description = "减伤-10%，防御加成-15%",
            rarity = 0,
            cultivationSpeedBonus = 0.0,
            damageAmplification = 0.0,
            damageReduction = -0.10,
            critDamageBonus = 0.0,
            defenseBonus = -0.15,
            isNegative = true,
            type = PhysiqueType.HYBRID_DEFENSE,
            template = "neg_phys_defense"
        ),
        PhysiqueData(
            id = "neg_phys_offense",
            name = "灵根残缺",
            description = "伤害加成-12%，暴击伤害-20%",
            rarity = 0,
            cultivationSpeedBonus = 0.0,
            damageAmplification = -0.12,
            damageReduction = 0.0,
            critDamageBonus = -0.20,
            defenseBonus = 0.0,
            isNegative = true,
            type = PhysiqueType.HYBRID_OFFENSE,
            template = "neg_phys_offense"
        )
    )

    private val allPhysiquesData: Map<String, PhysiqueData> = buildMap {
        positivePhysiquesData.forEach { put(it.id, it) }
        negativePhysiquesData.forEach { put(it.id, it) }
    }

    val physiques: Map<String, Physique> = allPhysiquesData.mapValues { it.value.toPhysique() }

    fun getById(id: String): Physique? = physiques[id]

    fun getPhysiqueDataById(id: String): PhysiqueData? = allPhysiquesData[id]

    fun getByRarity(rarity: Int): List<Physique> = physiques.values.filter { it.rarity == rarity }

    fun getPositivePhysiques(): List<Physique> = physiques.values.filter { !it.isNegative }

    fun getNegativePhysiques(): List<Physique> = physiques.values.filter { it.isNegative }

    fun getPhysiquesByIds(physiqueIds: List<String>): List<Physique> =
        physiqueIds.mapNotNull { physiques[it] }

    /** 聚合体质效果：返回各独立乘算因子的总和 */
    fun aggregatePhysiqueEffects(physiqueIds: List<String>): PhysiqueEffects {
        val list = physiqueIds.mapNotNull { allPhysiquesData[it] }
        return PhysiqueEffects(
            cultivationSpeedBonus = list.sumOf { it.cultivationSpeedBonus },
            damageAmplification = list.sumOf { it.damageAmplification },
            damageReduction = list.sumOf { it.damageReduction },
            critDamageBonus = list.sumOf { it.critDamageBonus },
            defenseBonus = list.sumOf { it.defenseBonus }
        )
    }

    fun generateForDisciple(random: kotlin.random.Random = kotlin.random.Random): List<Physique> {
        val result = mutableListOf<Physique>()
        val available = allPhysiquesData.values.toMutableList()   // 负面经品阶概率抽取，不在此无条件滤除
        val selectedTemplates = mutableSetOf<String>()

        val count = rollTraitCount(random)  // 0-5 个（35/35/20/6/3/1）

        repeat(count) {
            if (available.isEmpty()) return@repeat

            val filtered = available.filter { it.template !in selectedTemplates }
            if (filtered.isEmpty()) return@repeat

            val selected = pickByDistribution(filtered, random)
            result.add(selected.toPhysique())
            selectedTemplates.add(selected.template)
            available.removeAll { it.template == selected.template }
        }

        return result
    }

    private fun pickByDistribution(
        candidates: List<PhysiqueData>,
        random: kotlin.random.Random
    ): PhysiqueData {
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
        candidates: List<PhysiqueData>,
        targetRarity: Int,
        random: kotlin.random.Random
    ): PhysiqueData {
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

/** 体质聚合效果：各独立乘算因子的总和 */
data class PhysiqueEffects(
    val cultivationSpeedBonus: Double = 0.0,
    val damageAmplification: Double = 0.0,
    val damageReduction: Double = 0.0,
    val critDamageBonus: Double = 0.0,
    val defenseBonus: Double = 0.0
)
