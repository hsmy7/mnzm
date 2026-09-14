package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.PhysiqueEffects
import com.xianxia.sect.core.registry.AffixCombatEffects
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.engine.service.lifespanGainForRealm
import com.xianxia.sect.core.state.MutableGameState

// ── DiscipleStatCalculator 拆分域 1/7（行为零变更） ──
internal fun DiscipleStatCalculator.aptitudeCultivationBonus(aptitude: Int): Double =
    ((aptitude - APTITUDE_BASELINE).coerceAtLeast(0) * APTITUDE_BONUS_PER_POINT)
        .coerceAtMost(APTITUDE_MAX_BONUS)

/** 突破失败后气血/法力的剩余比例（修为清零外，HP/MP 打一折，玩家与 AI 共用） */

internal fun DiscipleStatCalculator.safeLayerMult(realmLayer: Int): Double =
    (1.0 + (realmLayer - 1) * LAYER_MULTIPLIER).coerceAtLeast(0.0)

/** 方差乘区防御：篡改方差为极端负值时钳制非负（1 + 方差/100 为负会产生负属性）。 */

internal fun DiscipleStatCalculator.safeVarianceMultiplier(variance: Int): Double =
    (1.0 + variance / 100.0).coerceAtLeast(0.0)

/**
 * 战斗回写 clamp 上限（含血炼口径）。
 *
 * 血炼进战斗后战斗内 maxHp/maxMp 含血炼；各回写路径若仍用基础上限
 * （disciple.maxHp 或 tables.baseHps）clamp，会把含血炼上限下的血量
 * 反向压低（削血）。所有战斗回写路径统一走本函数取上限。
 *
 * @param state 可变游戏状态（读装备/功法/熟练度/血炼记录）
 * @param disciple 目标弟子
 * @return (含血炼最终 maxHp, 含血炼最终 maxMp)
 */

fun DiscipleStatCalculator.battleWritebackMaxHpMp(
    state: MutableGameState,
    disciple: Disciple
): Pair<Int, Int> {
    val stats = getFinalStats(
        disciple,
        state.equipmentInstances.associateBy { it.id },
        state.manualInstances.associateBy { it.id },
        state.gameData.manualProficiencies[disciple.id]?.associateBy { it.manualId } ?: emptyMap(),
        state.gameData.bloodRefinementPctTotals[disciple.id]
    )
    return Pair(stats.maxHp, stats.maxMp)
}

/**
 * 突破大境界成功后的寿命增益（对齐玩家 DiscipleBreakthroughHandler 算法）。
 *
 * 境界基准增益 + （天赋 + 词条）寿命加成（加成的整数部分），供玩家与 AI 突破共用。
 *
 * 增益口径含词条：出生（DiscipleFactory）与突破两口径一致，均含词条加成；
 * 漏词条会使带"延年"词条弟子突破后 lifespan 恒低于特质加成水平，
 * 被 AgeLifespanRule 截断死循环导致永生。
 *
 * @param newRealm 突破后的新境界
 * @param talentIds 弟子天赋 ID 列表
 * @param affixIds 弟子词条 ID 列表
 * @return 寿命增益值
 */

fun DiscipleStatCalculator.calculateBreakthroughLifespanGain(
    newRealm: Int,
    talentIds: List<String>,
    affixIds: List<String>
): Int {
    val baseGain = lifespanGainForRealm(newRealm)
    val lifespanTalentBonus =
        (TalentDatabase.calculateTalentEffects(talentIds)["lifespan"] ?: 0.0) +
            (AffixDatabase.calculateAffixEffects(affixIds)["lifespan"] ?: 0.0)
    return if (lifespanTalentBonus != 0.0) {
        baseGain + (baseGain * lifespanTalentBonus).toInt()
    } else {
        baseGain
    }
}

// ==================== 天赋效果 ====================

internal fun DiscipleStatCalculator.computeTalentEffects(talentIds: List<String>): Map<String, Double> {
    val effects = mutableMapOf<String, Double>()
    val talents = TalentDatabase.getTalentsByIds(talentIds)
    talents.forEach { talent ->
        talent.effects.forEach { (key, value) ->
            effects[key] = (effects[key] ?: 0.0) + value
        }
    }
    return effects
}

fun DiscipleStatCalculator.getTalentEffects(disciple: Disciple): Map<String, Double> =
    computeTalentEffects(disciple.talentIds)

fun DiscipleStatCalculator.getTalentEffects(aggregate: DiscipleAggregate): Map<String, Double> =
    computeTalentEffects(aggregate.talentIds)

// ==================== 体质效果 ====================

fun DiscipleStatCalculator.getPhysiqueEffects(disciple: Disciple): PhysiqueEffects =
    PhysiqueDatabase.aggregatePhysiqueEffects(disciple.physiqueIds)

fun DiscipleStatCalculator.getPhysiqueEffects(aggregate: DiscipleAggregate): PhysiqueEffects =
    PhysiqueDatabase.aggregatePhysiqueEffects(aggregate.physiqueIds)

// ==================== 词条效果 ====================

fun DiscipleStatCalculator.getAffixEffects(disciple: Disciple): Map<String, Double> =
    AffixDatabase.calculateAffixEffects(disciple.affixIds)

fun DiscipleStatCalculator.getAffixEffects(aggregate: DiscipleAggregate): Map<String, Double> =
    AffixDatabase.calculateAffixEffects(aggregate.affixIds)

/**
 * 提取词条的战斗特殊加成（独立乘算因子）。
 * 与体质独立乘算因子分开，各自独立乘算。
 */

internal fun DiscipleStatCalculator.getAffixCombatEffects(disciple: Disciple): AffixCombatEffects =
    getAffixCombatEffects(disciple.affixIds)
