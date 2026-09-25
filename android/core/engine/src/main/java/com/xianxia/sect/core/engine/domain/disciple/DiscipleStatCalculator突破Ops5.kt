package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.BreakthroughBonusDetail
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator.BreakthroughZones

// ── DiscipleStatCalculator 拆分域 5/6（行为零变更） ──
fun DiscipleStatCalculator.calculateBreakthroughChance(zones: BreakthroughZones): Double {
    val positiveMult = 1.0 + zones.elderGuidance + zones.selfBonus
    val base = zones.baseZone * positiveMult
    // adFlatBonus 为扁平加法，不过乘区，确保广告观看后固定增加
    return (base + zones.adFlatBonus).coerceIn(0.0, 1.0)
}

/**
 * 悟性突破率加成（弟子自身与内外门长老共用同一公式）。
 * 悟性80基准，每高4点+1%，最多+10%。
 */

internal fun DiscipleStatCalculator.comprehensionBreakthroughBonus(comprehension: Int): Double {
    if (comprehension < GameConfig.PolicyConfig.ELDER_SKILL_BASELINE) {
        return 0.0
    }
    val steps =
        (comprehension - GameConfig.PolicyConfig.ELDER_SKILL_BASELINE) /
        GameConfig.PolicyConfig.ELDER_BONUS_DIVISOR
    return steps.coerceAtMost(
        GameConfig.PolicyConfig.ELDER_BREAKTHROUGH_MAX_STEPS
    ) * ELDER_BONUS_PER_STEP
}

/**
 * 计算突破概率（Disciple 版本便捷入口）。
 */

// innerElderPositionBonus/outerElderPositionBonus 恒 0：职位特质数值源已随三表下线，
// 形参保留以维持重载签名对称与 C++ BreakthroughChanceInput 字段面一致
@Suppress("UnusedParameter")
fun DiscipleStatCalculator.getBreakthroughChance(
    disciple: Disciple,
    innerElderComprehension: Int = 0,
    outerElderComprehension: Int = 0,
    pillBonus: Double = 0.0,
    adBonus: Double = 0.0,
    masterDiscipleBonus: Double = 0.0,
    innerElderPositionBonus: Double = 0.0,
    outerElderPositionBonus: Double = 0.0
): Double {
    if (disciple.realm < 0) return 0.0
    val zones = buildBreakthroughZones(
        disciple, innerElderComprehension, outerElderComprehension,
        pillBonus, adBonus, masterDiscipleBonus
    )
    return calculateBreakthroughChance(zones)
}

/**
 * 计算突破概率（DiscipleAggregate 版本便捷入口）。
 */

// innerElderPositionBonus/outerElderPositionBonus 恒 0：职位特质数值源已随三表下线，
// 形参保留以维持重载签名对称与 C++ BreakthroughChanceInput 字段面一致
@Suppress("UnusedParameter")
fun DiscipleStatCalculator.getBreakthroughChance(
    aggregate: DiscipleAggregate,
    innerElderComprehension: Int = 0,
    outerElderComprehension: Int = 0,
    pillBonus: Double = 0.0,
    adBonus: Double = 0.0,
    masterDiscipleBonus: Double = 0.0,
    innerElderPositionBonus: Double = 0.0,
    outerElderPositionBonus: Double = 0.0
): Double {
    if (aggregate.realm < 0) return 0.0
    val zones = buildBreakthroughZones(
        aggregate, innerElderComprehension, outerElderComprehension,
        pillBonus, adBonus, masterDiscipleBonus
    )
    return calculateBreakthroughChance(zones)
}

fun DiscipleStatCalculator.getBreakthroughBonusDetail(
    aggregate: DiscipleAggregate,
    innerElderComprehension: Int = 0,
    outerElderComprehension: Int = 0,
    pillBonus: Double = 0.0,
    adBonus: Double = 0.0,
    masterDiscipleBonus: Double = 0.0
): BreakthroughBonusDetail {
    if (aggregate.realm < 0) return BreakthroughBonusDetail(
        0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0
    )
    val zones = buildBreakthroughZones(
        aggregate, innerElderComprehension, outerElderComprehension,
        pillBonus, adBonus, masterDiscipleBonus
    )
    val total = calculateBreakthroughChance(zones)
    return BreakthroughBonusDetail(
        baseChance = zones.baseZone,
        innerElderBonus = comprehensionBreakthroughBonus(innerElderComprehension),
        outerElderBonus = comprehensionBreakthroughBonus(outerElderComprehension),
        pillBonus = pillBonus,
        adBonus = adBonus,
        masterDiscipleBonus = masterDiscipleBonus,
        selfComprehensionBonus = comprehensionBreakthroughBonus(aggregate.getBaseStats().comprehension),
        total = total
    )
}

// ==================== 功法/灵根槽位 ====================

/** 功法槽位上限为固定值（槽位加成随特质系统下线） */

@Suppress("UnusedParameter") // disciple/aggregate: 重载签名对称：与姊妹重载保持一致形参面
fun DiscipleStatCalculator.getMaxManualSlots(disciple: Disciple): Int =
    BASE_MANUAL_SLOTS

@Suppress("UnusedParameter") // aggregate: 重载签名对称：与姊妹重载保持一致形参面
fun DiscipleStatCalculator.getMaxManualSlots(aggregate: DiscipleAggregate): Int =
    BASE_MANUAL_SLOTS

// ==================== 传道加成 ====================

internal fun DiscipleStatCalculator.computePreachingBonus(
    discipleType: String,
    realm: Int,
    targetDiscipleType: String,
    preachingElder: Disciple?,
    preachingMasters: List<Disciple>
): Pair<Double, Double> {
    if (discipleType != targetDiscipleType) return 0.0 to 0.0

    var elderBonus = 0.0
    var mastersBonus = 0.0

    if (preachingElder != null && preachingElder.isAlive) {
        val elderTeaching = getBaseStats(preachingElder).teaching
        if (realm >= preachingElder.realm && elderTeaching >= ELDER_TEACHING_BASELINE) {
            elderBonus = ((elderTeaching - ELDER_TEACHING_BASELINE) * ELDER_TEACHING_RATE)
                .coerceAtMost(ELDER_TEACHING_MAX_BONUS)
        }
    }

    preachingMasters.filter { it.isAlive }.forEach { master ->
        val masterTeaching = getBaseStats(master).teaching
        if (realm >= master.realm && masterTeaching >= MASTER_TEACHING_BASELINE) {
            val bonus = ((masterTeaching - MASTER_TEACHING_BASELINE) * MASTER_TEACHING_RATE)
                .coerceAtMost(MASTER_TEACHING_MAX_BONUS)
            mastersBonus += bonus
        }
    }

    return elderBonus to mastersBonus
}

fun DiscipleStatCalculator.calculatePreachingBonus(
    disciple: Disciple,
    targetDiscipleType: String,
    preachingElder: Disciple?,
    preachingMasters: List<Disciple>
): Pair<Double, Double> = computePreachingBonus(
    discipleType = disciple.discipleType,
    realm = disciple.realm,
    targetDiscipleType = targetDiscipleType,
    preachingElder = preachingElder,
    preachingMasters = preachingMasters
)

fun DiscipleStatCalculator.calculatePreachingBonus(
    aggregate: DiscipleAggregate,
    targetDiscipleType: String,
    preachingElder: Disciple?,
    preachingMasters: List<Disciple>
): Pair<Double, Double> = computePreachingBonus(
    discipleType = aggregate.discipleType,
    realm = aggregate.realm,
    targetDiscipleType = targetDiscipleType,
    preachingElder = preachingElder,
    preachingMasters = preachingMasters
)

@Suppress("UnusedParameter") // innerElder: 重载签名对称：与姊妹重载保持一致形参面
fun DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
    disciple: Disciple,
    innerElder: Disciple? = null,
    qingyunPreachingElder: Disciple? = null,
    qingyunPreachingMasters: List<Disciple> = emptyList()
): Double {
    val (elderBonus, mastersBonus) = calculatePreachingBonus(
        disciple = disciple,
        targetDiscipleType = TYPE_INNER,
        preachingElder = qingyunPreachingElder,
        preachingMasters = qingyunPreachingMasters
    )
    return elderBonus + mastersBonus
}
