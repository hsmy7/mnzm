package com.xianxia.sect.core.engine.domain.disciple

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
