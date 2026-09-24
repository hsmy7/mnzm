package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate

// ── DiscipleStatCalculator 拆分域 6/6（行为零变更） ──
@Suppress("UnusedParameter") // innerElder: 重载签名对称：与姊妹重载保持一致形参面
fun DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
    aggregate: DiscipleAggregate,
    innerElder: Disciple? = null,
    qingyunPreachingElder: Disciple? = null,
    qingyunPreachingMasters: List<Disciple> = emptyList()
): Double {
    val (elderBonus, mastersBonus) = calculatePreachingBonus(
        aggregate = aggregate,
        targetDiscipleType = TYPE_INNER,
        preachingElder = qingyunPreachingElder,
        preachingMasters = qingyunPreachingMasters
    )
    return elderBonus + mastersBonus
}

// ==================== 血炼系统属性加成 ====================

/**
 * 根据血种随机选择属性（50/50），返回属性key。
 *
 * @param bloodType 血种类型
 * @param rngManager 确定性 PRNG 管理器，确保存档/读档一致性。
 */

fun DiscipleStatCalculator.randomBloodRefineStat(
    bloodType: String, rngManager: com.xianxia.sect.core.util.GameRngManager
): String {
    val rule = BeastMaterialDatabase.BLOOD_RULES[bloodType] ?: return ""
    val rng = rngManager.getRng(com.xianxia.sect.core.util.RngPartition.BREAKTHROUGH)
    val choice = rng.nextInt(2) == 0
    return if (choice) rule.statA else rule.statB
}

/**
 * 获取 CombatAttributes 中指定 stat key 的 base 值。
 */

fun DiscipleStatCalculator.getBaseStatValue(
    combat: com.xianxia.sect.core.model.CombatAttributes,
    statKey: String): Int = when (statKey
) {
    "speed" -> combat.baseSpeed
    "hp" -> combat.baseHp
    "physicalAttack" -> combat.basePhysicalAttack
    "magicAttack" -> combat.baseMagicAttack
    "physicalDefense" -> combat.basePhysicalDefense
    "magicDefense" -> combat.baseMagicDefense
    else -> 0
}

// ==================== 血炼百分比乘区（替代旧绝对值单利计算） ====================

/**
 * 从血炼百分比累计记录中读取指定属性的累计百分比。
 */

fun DiscipleStatCalculator.getAccumulatedPct(
    total: BloodRefinementPctTotal?,
    statKey: String
): Double {
    if (total == null) return 0.0
    return when (statKey) {
        "speed" -> total.speedBonusPct
        "hp" -> total.hpBonusPct
        "physicalAttack" -> total.physicalAttackBonusPct
        "magicAttack" -> total.magicAttackBonusPct
        "physicalDefense" -> total.physicalDefenseBonusPct
        "magicDefense" -> total.magicDefenseBonusPct
        else -> 0.0
    }
}

/**
 * 将本次血炼百分比累加到累计记录中，返回更新后的记录。
 */

fun DiscipleStatCalculator.addPctToTotal(
    total: BloodRefinementPctTotal,
    statKey: String,
    pct: Double
): BloodRefinementPctTotal {
    return when (statKey) {
        "speed" -> total.copy(speedBonusPct = total.speedBonusPct + pct)
        "hp" -> total.copy(hpBonusPct = total.hpBonusPct + pct)
        "physicalAttack" -> total.copy(physicalAttackBonusPct = total.physicalAttackBonusPct + pct)
        "magicAttack" -> total.copy(magicAttackBonusPct = total.magicAttackBonusPct + pct)
        "physicalDefense" -> total.copy(physicalDefenseBonusPct = total.physicalDefenseBonusPct + pct)
        "magicDefense" -> total.copy(magicDefenseBonusPct = total.magicDefenseBonusPct + pct)
        else -> total
    }
}

/**
 * 获取属性显示名称
 */

fun DiscipleStatCalculator.getStatDisplayName(statKey: String): String = when (statKey) {
    "speed" -> "速度"
    "hp" -> "气血"
    "physicalAttack" -> "物攻"
    "magicAttack" -> "法攻"
    "physicalDefense" -> "物防"
    "magicDefense" -> "法防"
    else -> statKey
}

// ==================== 师徒加成 ====================

/** 徒弟与师父的大境界差（负差按 0 计） */

fun DiscipleStatCalculator.getMasterDiscipleRealmGap(discipleRealm: Int, masterRealm: Int): Int =
    (discipleRealm - masterRealm - 1).coerceAtLeast(0)

/**
 * 计算徒弟从师父处获得的修炼速度加成（已乘以 gap）。
 */

fun DiscipleStatCalculator.getMasterDiscipleCultivationBonus(discipleRealm: Int, masterRealm: Int): Double =
    getMasterDiscipleRealmGap(discipleRealm, masterRealm) * MASTER_DISCIPLE_CULTIVATION_BONUS_PER_GAP

/**
 * 计算徒弟从师父处获得的突破率加成（已乘以 gap）。
 */

fun DiscipleStatCalculator.getMasterDiscipleBreakthroughBonus(discipleRealm: Int, masterRealm: Int): Double =
    getMasterDiscipleRealmGap(discipleRealm, masterRealm) * MASTER_DISCIPLE_BREAKTHROUGH_BONUS_PER_GAP
