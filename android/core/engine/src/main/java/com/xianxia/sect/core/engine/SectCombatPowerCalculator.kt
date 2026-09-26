package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.StarZone
import com.xianxia.sect.core.engine.domain.disciple.getPermanentBaseStats

/**
 * 宗门战力计算器。
 *
 * 统一玩家和 AI 宗门的战力计算：
 * - 基于永久基础属性（境界基础 × 方差 × 层数）
 * - 不包含装备、功法、临时丹药等临时加成
 * - 玩家与 AI 使用完全相同的公式
 *
 * 公式：
 *   战力 = ((物攻 + 法攻) × 5 + 气血 × 4 + (物防 + 法防) × 3 + 速度 × 2) × 星级乘数
 *
 * 星级乘区（口径 A）由 [StarZone] 单点提供：六维加权和**整体**乘 `battleMult`
 * 后向零截断——逐属性乘会逐字段截断，与 C++ `discipleCombatPowerWithStar` 失配。
 */
object SectCombatPowerCalculator {

    /**
     * 根据 [DiscipleStats] 计算单个弟子的战力基线值（星级乘区之前）。
     *
     * 与 C++ `discipleCombatPower` 逐位同式的纯公式孪生体：金标用例与妖兽共用，
     * 带星级的入口是 [calculateDiscipleCombatPowerWithStar]。
     *
     * @param stats 弟子的永久基础属性
     * @return 战力值
     */
    fun calculateDiscipleCombatPower(stats: DiscipleStats): Long {
        return (stats.physicalAttack.toLong() + stats.magicAttack.toLong()) * 5L +
               stats.maxHp.toLong() * 4L +
               (stats.physicalDefense.toLong() + stats.magicDefense.toLong()) * 3L +
               stats.speed.toLong() * 2L
    }

    /**
     * 战力基线值叠加星级乘区（口径 A：`1★` 为基线，每多一星 +8%）。
     *
     * 计算顺序固定为「先求六维加权和、再整体乘 [StarZone.battleMult]、最后向零
     * 截断」——与 C++ `discipleCombatPowerWithStar` 的
     * `static_cast<int64_t>(static_cast<double>(base) * battleMult)` 逐位一致；
     * 逐属性乘会逐字段截断而失配。`star <= 1` 恒为基线值。
     *
     * @param stats 弟子的永久基础属性
     * @param star 原始星级（0 = 未解锁/存量旧弟子，1 = 基线）
     * @return 含星级乘区的战力值
     */
    fun calculateDiscipleCombatPowerWithStar(stats: DiscipleStats, star: Int): Long =
        (calculateDiscipleCombatPower(stats) * StarZone(star).battleMult).toLong()

    /**
     * 计算妖兽战力。
     *
     * 使用与弟子统一的公式：
     *   战力 = (物攻 + 法攻) × 5 + 气血 × 4 + (物防 + 法防) × 3 + 速度 × 2
     *
     * 此方法接收妖兽生成时已含随机方差的最终属性，确保地图显示战力等于战斗实际战力。
     *
     * @param maxHp 妖兽最大气血
     * @param physicalAttack 物理攻击
     * @param magicAttack 法术攻击（妖兽物攻=法攻）
     * @param physicalDefense 物理防御
     * @param magicDefense 法术防御（妖兽物防=法防）
     * @param speed 速度
     * @return 妖兽战力值
     */
    fun calculateBeastCombatPower(
        maxHp: Int,
        physicalAttack: Int,
        magicAttack: Int,
        physicalDefense: Int,
        magicDefense: Int,
        speed: Int
    ): Long {
        val hp = maxHp.coerceAtLeast(0)
        val patk = physicalAttack.coerceAtLeast(0)
        val matk = magicAttack.coerceAtLeast(0)
        val pdef = physicalDefense.coerceAtLeast(0)
        val mdef = magicDefense.coerceAtLeast(0)
        val spd = speed.coerceAtLeast(0)
        return (patk.toLong() + matk.toLong()) * 5L +
               hp.toLong() * 4L +
               (pdef.toLong() + mdef.toLong()) * 3L +
               spd.toLong() * 2L
    }

    /**
     * 使用永久基础属性计算单个弟子的战力（含星级乘区）。
     * 玩家和 AI 弟子都使用完全相同的公式和输入数据。
     *
     * 星级由调用方解析后显式传入（无默认值）：AI 宗门弟子与存量旧弟子恒为 0 星，
     * 也必须写出该入参，避免后续调用点漏传而静默丢失星级加成。
     *
     * @param aggregate 弟子聚合数据
     * @param star 原始星级（解析见 [StarZone.starOf]）
     * @return 战力值
     */
    fun calculateDisciplePower(aggregate: DiscipleAggregate, star: Int): Long {
        val stats = DiscipleStatCalculator.getPermanentBaseStats(aggregate)
        return calculateDiscipleCombatPowerWithStar(stats, star)
    }

    /**
     * 宗门总战力 = 存活弟子永久基础属性战力之和（玩家/AI 同一公式）。
     *
     * 不包含装备、功法、临时丹药等临时加成。
     *
     * 星级逐弟子按 `templateId` 从 `GameData.gachaStarMap` 反查（稀疏账本：
     * 未解锁与存量旧弟子 0 星 ⇒ ×1.00）。
     *
     * @param disciples 弟子列表
     * @param gachaStarMap 星级账本（纯 AI 名册等非玩家名册可显式传空表）
     * @return 宗门总战力
     */
    fun calculateSectPower(disciples: List<Disciple>, gachaStarMap: Map<String, Int>): Long =
        disciples.filter { it.isAlive }.sumOf {
            calculateDisciplePower(it.toAggregate(), StarZone.starOf(gachaStarMap, it.templateId))
        }

    /**
     * 为弟子的战力值计算缓存指纹。
     *
     * 仅包含影响永久基础属性的字段：
     * - 境界和层数
     * - 六维方差
     *
     * 不包含：装备、功法、丹药（这些不影响战力计算）。
     *
     * 星级同样不在指纹内：指纹与 C++ `sectPowerFingerprint` 逐位对拍，加项会整体
     * 位移既有指纹值。缓存失效由持有方把 star 与指纹并列作判据
     * （`cached.fingerprint == fp && cached.star == star`）。
     */
    fun computeFingerprint(aggregate: DiscipleAggregate): Int {
        var result = 1
        result = 31 * result + aggregate.realm
        result = 31 * result + aggregate.realmLayer
        result = 31 * result + aggregate.hpVariance
        result = 31 * result + aggregate.physicalAttackVariance
        result = 31 * result + aggregate.magicAttackVariance
        result = 31 * result + aggregate.physicalDefenseVariance
        result = 31 * result + aggregate.magicDefenseVariance
        result = 31 * result + aggregate.speedVariance
        return result
    }
}
