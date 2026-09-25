package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.MutableGameState

// ── DiscipleStatCalculator 拆分域 1/6（行为零变更） ──

/** 突破失败后气血/法力的剩余比例（修为清零外，HP/MP 打一折，玩家与 AI 共用） */

internal fun DiscipleStatCalculator.safeLayerMult(realmLayer: Int): Double =
    (1.0 + (realmLayer - 1) * LAYER_MULTIPLIER).coerceAtLeast(0.0)

/** 方差乘区防御：篡改方差为极端负值时钳制非负（1 + 方差/100 为负会产生负属性）。 */

internal fun DiscipleStatCalculator.safeVarianceMultiplier(variance: Int): Double =
    (1.0 + variance / 100.0).coerceAtLeast(0.0)

/**
 * 战斗回写 clamp 上限。
 *
 * 各战斗回写路径统一走本函数取含装备/功法最终 maxHp/maxMp 上限。
 *
 * @param state 可变游戏状态（读装备/功法/熟练度）
 * @param disciple 目标弟子
 * @return (最终 maxHp, 最终 maxMp)
 */

fun DiscipleStatCalculator.battleWritebackMaxHpMp(
    state: MutableGameState,
    disciple: Disciple
): Pair<Int, Int> {
    val stats = getFinalStats(
        disciple,
        state.equipmentInstances.associateBy { it.id },
        state.manualInstances.associateBy { it.id },
        state.gameData.manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap()
    )
    return Pair(stats.maxHp, stats.maxMp)
}
