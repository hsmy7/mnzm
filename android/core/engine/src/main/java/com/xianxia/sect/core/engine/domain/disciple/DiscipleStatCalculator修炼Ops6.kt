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
