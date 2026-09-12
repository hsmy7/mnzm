package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.AffixCombatEffects
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlotType
import kotlin.math.roundToInt

// ── DiscipleStatCalculator 拆分域 2/7（行为零变更） ──
internal fun DiscipleStatCalculator.getAffixCombatEffects(aggregate: DiscipleAggregate): AffixCombatEffects =
    getAffixCombatEffects(aggregate.affixIds)

internal fun DiscipleStatCalculator.getAffixCombatEffects(affixIds: List<String>): AffixCombatEffects {
    val effects = AffixDatabase.calculateAffixEffects(affixIds)
    return AffixCombatEffects(
        damageAmplification = effects["damageAmplification"] ?: 0.0,
        critDamageBonus = effects["critDamageBonus"] ?: 0.0,
        damageReduction = effects["damageReduction"] ?: 0.0,
        defenseBonus = effects["defenseBonus"] ?: 0.0,
    )
}

/** 合并天赋+词条的 effects map（用于 baseStats 计算） */

internal fun DiscipleStatCalculator.getMergedEffects(disciple: Disciple): Map<String, Double> =
    mergeEffects(getTalentEffects(disciple), getAffixEffects(disciple))

internal fun DiscipleStatCalculator.getMergedEffects(aggregate: DiscipleAggregate): Map<String, Double> =
    mergeEffects(getTalentEffects(aggregate), getAffixEffects(aggregate))

internal fun DiscipleStatCalculator.mergeEffects(
    talentEffects: Map<String, Double>,
    affixEffects: Map<String, Double>
): Map<String, Double> {
    val merged = mutableMapOf<String, Double>()
    talentEffects.forEach { (k, v) -> merged[k] = (merged[k] ?: 0.0) + v }
    affixEffects.forEach { (k, v) -> merged[k] = (merged[k] ?: 0.0) + v }
    return merged
}

// ==================== 职务加成查询 ====================

/**
 * 统计弟子（天赋+词条）中指定 slotType 的 PositionBonus 总和。
 * 用于担任职务时增强该职务职能效果（乘算因子）。
 */

fun DiscipleStatCalculator.getPositionEffectBonus(disciple: Disciple, slotType: ElderSlotType): Double =
    getPositionEffectBonus(disciple.talentIds, disciple.affixIds, slotType)

fun DiscipleStatCalculator.getPositionEffectBonus(
    aggregate: DiscipleAggregate, slotType: ElderSlotType
): Double =
    getPositionEffectBonus(aggregate.talentIds, aggregate.affixIds, slotType)

/** 列式重载：从原始 talentIds/affixIds 计算职务加成（无 Disciple 组装） */

fun DiscipleStatCalculator.getPositionEffectBonus(
    talentIds: List<String>,
    affixIds: List<String>,
    slotType: ElderSlotType
): Double {
    val talentBonus = talentIds.mapNotNull { TalentDatabase.getById(it) }
        .filter { !it.isNegative }
        .filter { it.positionBonus?.slotType == slotType }
        .sumOf { it.positionBonus?.effectBonus ?: 0.0 }
    val affixBonus = AffixDatabase.aggregatePositionBonus(affixIds, slotType)
    return talentBonus + affixBonus
}

// ==================== 基础属性 ====================

/**
 * maxHp/maxMp 基础值计算。
 *
 * 公式：基础 × 方差乘区 × 层数乘区 × (1 + 天赋% + 血炼%)。
 * 对象版 [computeBaseStats] 与列直读版 [getMaxHpMpColumn] 共用本实现，杜绝公式漂移。
 *
 * @return (maxHp, maxMp)
 */

internal fun DiscipleStatCalculator.computeBaseHpMp(
    realm: Int,
    realmLayer: Int,
    hpVariance: Int,
    mpVariance: Int,
    talentEffects: Map<String, Double>,
    bloodRefinementPct: BloodRefinementPctTotal? = null
): Pair<Int, Int> {
    val realmConfig = GameConfig.Realm.get(realm)
    val layerMult = safeLayerMult(realmLayer)

    // 血炼百分比乘区与天赋同乘区加算（防御存档篡改：负数/NaN/Infinity 归零，上界 10.0 防巨大值饱和）
    fun safeBrPct(pct: Double): Double =
        pct.coerceIn(0.0, MAX_BLOOD_REFINEMENT_PCT).takeIf { it.isFinite() } ?: 0.0
    val hpBonus = (talentEffects["maxHp"] ?: 0.0) + safeBrPct(bloodRefinementPct?.hpBonusPct ?: 0.0)
    val mpBonus = talentEffects["maxMp"] ?: 0.0

    val hpVar = safeVarianceMultiplier(hpVariance)
    val mpVar = safeVarianceMultiplier(mpVariance)
    val maxHp = (realmConfig.baseHp * hpVar * layerMult * (1.0 + hpBonus)).roundToInt()
    val maxMp = (realmConfig.baseMp * mpVar * layerMult * (1.0 + mpBonus)).roundToInt()
    return Pair(maxHp, maxMp)
}

/**
 * 战斗属性方差输入组（computeBaseStats 参数收拢——满足 detekt
 * LongParameterList 阈值约束）。
 */

internal fun DiscipleStatCalculator.safeBrPct(pct: Double): Double =
    pct.coerceIn(0.0, MAX_BLOOD_REFINEMENT_PCT).takeIf { it.isFinite() } ?: 0.0

/** 天赋加成读取：缺字段按 0.0 */

internal fun DiscipleStatCalculator.talentBonus(talentEffects: Map<String, Double>, key: String): Double =
    talentEffects[key] ?: 0.0

/** 天赋 Flat 读取：缺字段按 0.0 截断为 Int */

internal fun DiscipleStatCalculator.talentFlat(talentEffects: Map<String, Double>, key: String): Int =
    (talentEffects[key] ?: 0.0).toInt()

/** 血炼百分比读取：无记录/缺字段按 0.0 */

internal fun DiscipleStatCalculator.brPct(
    br: BloodRefinementPctTotal?,
    selector: (BloodRefinementPctTotal) -> Double?
): Double = safeBrPct(br?.let(selector) ?: 0.0)
