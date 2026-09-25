package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.GameConfig
import kotlin.math.roundToInt

// ── DiscipleStatCalculator 拆分域 2/6（行为零变更） ──

// ==================== 基础属性 ====================

/**
 * maxHp/maxMp 基础值计算。
 *
 * 公式：基础 × 方差乘区 × 层数乘区。
 * 对象版 [computeBaseStats] 与列直读版 [getMaxHpMpColumn] 共用本实现，杜绝公式漂移。
 *
 * @return (maxHp, maxMp)
 */

internal fun DiscipleStatCalculator.computeBaseHpMp(
    realm: Int,
    realmLayer: Int,
    hpVariance: Int,
    mpVariance: Int
): Pair<Int, Int> {
    val realmConfig = GameConfig.Realm.get(realm)
    val layerMult = safeLayerMult(realmLayer)

    val hpVar = safeVarianceMultiplier(hpVariance)
    val mpVar = safeVarianceMultiplier(mpVariance)
    val maxHp = (realmConfig.baseHp * hpVar * layerMult).roundToInt()
    val maxMp = (realmConfig.baseMp * mpVar * layerMult).roundToInt()
    return Pair(maxHp, maxMp)
}
