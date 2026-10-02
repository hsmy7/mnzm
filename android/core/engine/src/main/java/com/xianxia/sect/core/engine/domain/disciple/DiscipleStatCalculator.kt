package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData

object DiscipleStatCalculator {
    // ---- 魔法数字命名常量 ----
    internal const val LAYER_MULTIPLIER = 0.1
    /** 基础暴击率（暴击系统口径：全员 0% 起步，暴击只来自装备/功法/丹药加成；C++ kBaseCritRate 同值） */
    internal const val BASE_CRIT_RATE = 0.0
    internal const val MIN_CULTIVATION_PER_PHASE = 1.0
    internal const val BASE_MANUAL_SLOTS = 6
    internal const val ELDER_BONUS_PER_STEP = 0.01
    internal const val ELDER_TEACHING_BASELINE = 80
    internal const val MASTER_TEACHING_BASELINE = 60
    internal const val ELDER_TEACHING_RATE = 0.0025
    internal const val MASTER_TEACHING_RATE = 0.001
    internal const val ELDER_TEACHING_MAX_BONUS = 0.10
    internal const val MASTER_TEACHING_MAX_BONUS = 0.05

    /** 突破失败后气血/法力的剩余比例（修为清零外，HP/MP 打一折，玩家与 AI 共用） */
    const val BREAKTHROUGH_FAILURE_HP_MP_RATIO = 0.1

    /**
     * 战斗属性方差输入组（computeBaseStats 参数收拢——满足 detekt
     * LongParameterList 阈值约束）。
     */
    /** 单列口径（B1）：攻/防各一个方差 */
    internal data class VarianceInputs(
        val hpVariance: Int,
        val mpVariance: Int,
        val attackVariance: Int,
        val defenseVariance: Int,
        val speedVariance: Int
    )

    /** 技能属性输入组（同上参数收拢；含 3 个生产属性） */
    internal data class SkillInputs(
        val intelligence: Int,
        val charm: Int,
        val comprehension: Int,
        val teaching: Int,
        val morality: Int,
        val mining: Int,
        val spiritPlanting: Int,
        val artifactRefining: Int,
        val pillRefining: Int
    )

    /** 属性累加器：主属性合计 + 暴击率/暴击伤害加成独立累加 */
    internal data class StatAccum(
        val total: DiscipleStats,
        val critRate: Double,
        val critDamageBonus: Double = 0.0
    )

    /**
     * 列直读输入：每旬 HP/MP 恢复热点的最小列集。
     *
     * 对应 [DiscipleTables] 的最小列集，
     * 数学等价于 [getFinalStats] 的 maxHp/maxMp（共用 [computeBaseHpMp] 公式）。
     */
    data class HpMpColumnInput(
        val realm: Int,
        /** 小层境界（1~9），默认 0 表示未知（按初层 1 回退）；Combatant 版实现为 realmLayer */
        val realmLayer: Int,
        val hpVariance: Int,
        val mpVariance: Int,
        val headId: String?,
        val bodyId: String?,
        val handsId: String?,
        val feetId: String?,
        val manualIds: List<String>,
        val pillEffectDuration: Int,
        val pillHpBonus: Int,
        val pillMpBonus: Int
    )

    /**
     * 修炼速度乘区分组。
     *
     * 遵循"同类加算、异类乘算"原则，将各来源加成归入 5 个独立乘区。
     * 每个乘区内部为加算，乘区之间为乘算，**相乘顺序固定为
     * 资源→社交→状态→临时→星级**（浮点乘法不可交换，C++
     * `disciple.h::calculateCultivationPerPhase` 与列直读两版同序）。
     *
     * @property starBonus 星级乘区加成量（口径 A：`1★` 基线 ⇒ 0.0；每多一星 +5%）。
     *   由调用方按 `StarZone.cultivationBonus(gachaStarMap, templateId)` 传入，
     *   存量旧弟子与未解锁角色恒 0.0（不改任何既有速率期望）。
     */
    data class CultivationSpeedZones(
        val resourceBonus: Double = 0.0,    // 资源乘区：功法+丹药+建筑
        val socialBonus: Double = 0.0,      // 社交乘区：传道长老/师兄
        val statusBonus: Double = 0.0,      // 状态乘区：政策
        val temporaryBonus: Double = 0.0,   // 临时乘区：丹药临时加速
        val starBonus: Double = 0.0,        // 星级乘区：抽卡升星（1★ 基线 ⇒ 0）
    )

    /**
     * 修炼乘区计算的输入字段。
     *
     * 9 项原始参数分组为一个数据类，避免 detekt LongParameterList 违规；
     * 由 [buildCultivationZones] / [calculateCultivationPerPhaseColumn] 提取组装。
     */
    data class CultivationZoneInput(
        val manualIds: List<String>,
        val manuals: Map<String, ManualInstance>,
        val manualProficiencies: Map<String, ManualProficiencyData>,
        val buildingBonus: Double,
        val preachingElderBonus: Double,
        val preachingMastersBonus: Double,
        val cultivationSubsidyBonus: Double,
        val temporaryBonus: Double,
        val starBonus: Double = 0.0
    )

    /**
     * 列式修炼速率计算的输入字段。
     *
     * 由调用方从 [com.xianxia.sect.core.state.DiscipleTables] 列直读提取，
     * 避免为计算速率而 assemble 完整 Disciple 对象（每旬热点循环用）。
     */
    data class CultivationRateColumnInput(
        val realm: Int,
        val spiritRootCount: Int,
        val manualIds: List<String>,
        val pillEffectDuration: Int,
        val pillCultivationSpeedBonus: Double
    )

    /**
     * 突破概率乘区（Breakthrough Zone）。
     *
     * 遵循"乘区内加算、乘区间乘算"原则。
     * 基础概率作为 baseZone（本身就是概率值 0~1），其他乘区以 (1 + bonus) 形式乘算。
     *
     * 公式：baseZone × (1 + elderGuidance + selfBonus) + adFlatBonus
     */
    data class BreakthroughZones(
        val baseZone: Double = 0.0,        // 基础概率（境界+灵根+层数）
        val elderGuidance: Double = 0.0,   // 长老指导乘区：内门+外门
        val selfBonus: Double = 0.0,       // 自身加成乘区：丹药+悟性
        val adFlatBonus: Double = 0.0,     // 广告扁平加成（不经过乘区缩放，直接加在最终值上）
    )

    /** 突破乘区加成输入（参数分组，规避 LongParameterList） */
    internal data class BreakthroughZoneBonusInput(
        val innerElderComprehension: Int = 0,
        val outerElderComprehension: Int = 0,
        val selfComprehension: Int = 0,
        val pillBonus: Double = 0.0,
        val adBonus: Double = 0.0,
        val innerElderPositionBonus: Double = 0.0,
        val outerElderPositionBonus: Double = 0.0
    )

    data class BreakthroughBonusDetail(
        val baseChance: Double,
        val innerElderBonus: Double,
        val outerElderBonus: Double,
        val pillBonus: Double,
        val adBonus: Double,
        /** 弟子自身悟性突破率加成（悟性80基准每4点+1%，最多+10%） */
        val selfComprehensionBonus: Double,
        val total: Double
    )
}
