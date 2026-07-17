package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStats

/**
 * 宗门战力计算器。
 *
 * 统一玩家和 AI 宗门的战力计算：
 * - 基于永久基础属性（境界基础 × 方差 × 层数 × (1 + 天赋% + 血炼%)）
 * - 不包含装备、功法、临时丹药等临时加成
 * - 玩家与 AI 使用完全相同的公式
 *
 * 公式：
 *   战力 = (物攻 + 法攻) × 5 + (物防 + 法防) × 3 + 气血 × 4 + 速度 × 2
 */
object SectCombatPowerCalculator {

    /**
     * 根据 [DiscipleStats] 计算单个弟子的战力值。
     *
     * @param stats 弟子的永久基础属性（含天赋 + 血炼乘区）
     * @return 战力值
     */
    fun calculateDiscipleCombatPower(stats: DiscipleStats): Long {
        return (stats.physicalAttack.toLong() + stats.magicAttack.toLong()) * 5L +
               stats.maxHp.toLong() * 4L +
               (stats.physicalDefense.toLong() + stats.magicDefense.toLong()) * 3L +
               stats.speed.toLong() * 2L
    }

    /**
     * 使用永久基础属性计算单个弟子的战力。
     *
     * 统一了之前分离的玩家和 AI 处理路径。
     * 玩家和 AI 弟子都使用完全相同的公式和输入数据。
     *
     * @param aggregate 弟子聚合数据
     * @param bloodRefinementPct 血炼百分比累计记录，若该弟子无血炼则为 null
     * @return 战力值
     */
    fun calculateDisciplePower(
        aggregate: DiscipleAggregate,
        bloodRefinementPct: BloodRefinementPctTotal? = null
    ): Long {
        val stats = DiscipleStatCalculator.getPermanentBaseStats(aggregate, bloodRefinementPct)
        return calculateDiscipleCombatPower(stats)
    }

    /**
     * 为弟子的战力值计算缓存指纹。
     *
     * 仅包含影响永久基础属性的字段：
     * - 境界和层数
     * - 方差
     * - 天赋 ID
     * - 血炼百分比
     *
     * 不包含：装备、功法、丹药（这些不影响战力计算）。
     */
    fun computeFingerprint(
        aggregate: DiscipleAggregate,
        bloodRefinementPct: BloodRefinementPctTotal? = null
    ): Int {
        var result = 1
        result = 31 * result + aggregate.realm
        result = 31 * result + aggregate.realmLayer
        result = 31 * result + aggregate.hpVariance
        result = 31 * result + aggregate.physicalAttackVariance
        result = 31 * result + aggregate.magicAttackVariance
        result = 31 * result + aggregate.physicalDefenseVariance
        result = 31 * result + aggregate.magicDefenseVariance
        result = 31 * result + aggregate.speedVariance
        result = 31 * result + aggregate.talentIds.hashCode()
        if (bloodRefinementPct != null) {
            result = 31 * result + bloodRefinementPct.hpBonusPct.hashCode()
            result = 31 * result + bloodRefinementPct.physicalAttackBonusPct.hashCode()
            result = 31 * result + bloodRefinementPct.magicAttackBonusPct.hashCode()
            result = 31 * result + bloodRefinementPct.physicalDefenseBonusPct.hashCode()
            result = 31 * result + bloodRefinementPct.magicDefenseBonusPct.hashCode()
            result = 31 * result + bloodRefinementPct.speedBonusPct.hashCode()
        }
        return result
    }
}