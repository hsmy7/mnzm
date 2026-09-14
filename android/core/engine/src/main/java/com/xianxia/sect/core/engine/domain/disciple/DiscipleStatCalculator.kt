package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.registry.PhysiqueEffects
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData

object DiscipleStatCalculator {
    // ---- 魔法数字命名常量 ----
    internal const val LAYER_MULTIPLIER = 0.1
    internal const val BASE_CRIT_RATE = 0.05
    internal const val MIN_CULTIVATION_PER_PHASE = 1.0
    internal const val BASE_MANUAL_SLOTS = 6
    internal const val ELDER_BONUS_PER_STEP = 0.01
    internal const val SOUL_POWER_DIVISOR = 20
    internal const val SOUL_POWER_MAX_STEPS = 5
    internal const val ELDER_TEACHING_BASELINE = 80
    internal const val MASTER_TEACHING_BASELINE = 60
    internal const val ELDER_TEACHING_RATE = 0.0025
    internal const val MASTER_TEACHING_RATE = 0.001
    internal const val ELDER_TEACHING_MAX_BONUS = 0.10
    internal const val MASTER_TEACHING_MAX_BONUS = 0.05

    // ---- 资质→修炼速度加成（80 基准每点 +1%，最多 +40%，乘区算法） ----
    internal const val APTITUDE_BASELINE = 80
    internal const val APTITUDE_BONUS_PER_POINT = 0.01
    internal const val APTITUDE_MAX_BONUS = 0.40
    // 列式入口资质默认值：必须与 DiscipleTables.DEFAULT_APTITUDE(50) 统一（CultivationRateEquivalenceTest 守护双入口等价）
    private const val DEFAULT_COLUMN_APTITUDE = 50

    /** 突破失败后气血/法力的剩余比例（修为清零外，HP/MP 打一折，玩家与 AI 共用） */
    const val BREAKTHROUGH_FAILURE_HP_MP_RATIO = 0.1

    /**
     * 血炼百分比上界（10.0 = 1000%）。防御存档篡改巨大有限值（如 1e9）导致属性
     * 饱和 Int.MAX 的"改存档即无敌"通道；游戏内单次血炼增量为 0.5%~30% 级，
     * 1000% 上界远高于合法累计。
     */
    internal const val MAX_BLOOD_REFINEMENT_PCT = 10.0

    /**
     * 战斗属性方差输入组（computeBaseStats 参数收拢——满足 detekt
     * LongParameterList 阈值约束）。
     */
    internal data class VarianceInputs(
        val hpVariance: Int,
        val mpVariance: Int,
        val physicalAttackVariance: Int,
        val magicAttackVariance: Int,
        val physicalDefenseVariance: Int,
        val magicDefenseVariance: Int,
        val speedVariance: Int
    )

    /** 技能属性输入组（同上参数收拢；含 3 个生产属性） */
    internal data class SkillInputs(
        val intelligence: Int,
        val charm: Int,
        val loyalty: Int,
        val comprehension: Int,
        val aptitude: Int,
        val teaching: Int,
        val morality: Int,
        val mining: Int,
        val spiritPlanting: Int,
        val artifactRefining: Int,
        val pillRefining: Int
    )

    /** 属性累加器：主属性合计 + 暴击率独立累加 */
    internal data class StatAccum(val total: DiscipleStats, val critRate: Double)

    /**
     * 列直读输入：每旬 HP/MP 恢复热点的最小列集。
     *
     * 对应 [DiscipleTables] 的 17 列（相对 assemble 的 ~90 列省 80%），
     * 数学等价于 [getFinalStats] 的 maxHp/maxMp（共用 [computeBaseHpMp] 公式）。
     */
    data class HpMpColumnInput(
        val realm: Int,
        /** 小层境界（1~9），默认 0 表示未知（按初层 1 回退）；Combatant 版实现为 realmLayer */
        val realmLayer: Int,
        val hpVariance: Int,
        val mpVariance: Int,
        val talentIds: List<String>,
        val affixIds: List<String>,
        val weaponId: String?,
        val armorId: String?,
        val bootsId: String?,
        val accessoryId: String?,
        val manualIds: List<String>,
        val pillEffectDuration: Int,
        val pillHpBonus: Int,
        val pillMpBonus: Int,
        val bloodRefinementPct: BloodRefinementPctTotal? = null
    )

    /**
     * 修炼速度乘区分组。
     *
     * 遵循"同类加算、异类乘算"原则，将 14 种加成归入 5 个独立乘区。
     * 每个乘区内部为加算，乘区之间为乘算。
     */
    data class CultivationSpeedZones(
        val aptitudeBonus: Double = 0.0,    // 资质乘区：天赋
        val resourceBonus: Double = 0.0,    // 资源乘区：功法+丹药+建筑
        val socialBonus: Double = 0.0,      // 社交乘区：师徒+传道+父母
        val statusBonus: Double = 0.0,      // 状态乘区：丧亲+寿命+政策
        val temporaryBonus: Double = 0.0,   // 临时乘区：丹药临时加速
    )

    /**
     * 修炼乘区计算的输入字段。
     *
     * 16 项原始参数分组为一个数据类，避免 detekt LongParameterList 违规；
     * 由 [buildCultivationZones] / [calculateCultivationPerPhaseColumn] 提取组装。
     */
    data class CultivationZoneInput(
        val mergedEffects: Map<String, Double>,
        val physiqueEffects: PhysiqueEffects,
        val manualIds: List<String>,
        val manuals: Map<String, ManualInstance>,
        val manualProficiencies: Map<String, ManualProficiencyData>,
        val buildingBonus: Double,
        val preachingElderBonus: Double,
        val preachingMastersBonus: Double,
        val parentCultivationBonus: Double,
        val masterDiscipleBonus: Double,
        val cultivationSubsidyBonus: Double,
        val griefCultivationSpeedPenalty: Double,
        val age: Int,
        val lifespan: Int,
        val temporaryBonus: Double,
        val aptitude: Int = DEFAULT_COLUMN_APTITUDE
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
        val talentIds: List<String>,
        val physiqueIds: List<String>,
        val affixIds: List<String>,
        val manualIds: List<String>,
        val age: Int,
        val lifespan: Int,
        val pillEffectDuration: Int,
        val pillCultivationSpeedBonus: Double,
        val aptitude: Int = DEFAULT_COLUMN_APTITUDE
    )

    /**
     * 突破概率乘区（Breakthrough Zone）。
     *
     * 遵循"乘区内加算、乘区间乘算"原则。
     * 基础概率作为 baseZone（本身就是概率值 0~1），其他乘区以 (1 + bonus) 形式乘算。
     *
     * 公式：baseZone × (1 + elderGuidance + selfBonus) × (1 - penalty) + adFlatBonus
     */
    data class BreakthroughZones(
        val baseZone: Double = 0.0,        // 基础概率（境界+灵根+层数）
        val elderGuidance: Double = 0.0,   // 长老指导乘区：内门+外门
        val selfBonus: Double = 0.0,       // 自身加成乘区：天赋+魂力+丹药+师徒
        val statusPenalty: Double = 0.0,   // 状态惩罚乘区：丧亲+寿命（正值 = 惩罚幅度）
        val adFlatBonus: Double = 0.0,     // 广告扁平加成（不经过乘区缩放，直接加在最终值上）
    )

    /** 突破乘区加成输入（参数分组，规避 LongParameterList） */
    internal data class BreakthroughZoneBonusInput(
        val innerElderComprehension: Int = 0,
        val outerElderComprehension: Int = 0,
        val selfComprehension: Int = 0,
        val pillBonus: Double = 0.0,
        val adBonus: Double = 0.0,
        val griefBreakthroughPenalty: Double = 0.0,
        val masterDiscipleBonus: Double = 0.0,
        val innerElderPositionBonus: Double = 0.0,
        val outerElderPositionBonus: Double = 0.0
    )

    data class BreakthroughBonusDetail(
        val baseChance: Double,
        val innerElderBonus: Double,
        val outerElderBonus: Double,
        /** @deprecated 突破加成已从天赋系统移除，此字段仅供旧存档显示，不参与计算 */
        val talentBonus: Double,
        val soulPowerBonus: Double,
        val pillBonus: Double,
        val adBonus: Double,
        val masterDiscipleBonus: Double,
        /** 弟子自身悟性突破率加成（悟性80基准每4点+1%，最多+10%） */
        val selfComprehensionBonus: Double,
        val griefPenalty: Double,
        val lifespanPenalty: Double,
        val total: Double
    )

    /**
     * 亲人逝世对修炼速度的惩罚比例：降低50%
     */
    const val GRIEF_CULTIVATION_SPEED_PENALTY = 0.50

    /**
     * 亲人逝世对突破率的惩罚比例：降低20%
     */
    const val GRIEF_BREAKTHROUGH_CHANCE_PENALTY = 0.20

    // ==================== 师徒加成 ====================

    /** 每位师父最多可收徒弟数 */
    const val MAX_APPRENTICES_PER_MASTER = 5

    /** 师徒大境界差每级提供的修炼速度加成：5% */
    const val MASTER_DISCIPLE_CULTIVATION_BONUS_PER_GAP = 0.05

    /** 师徒大境界差每级提供的突破率加成：3% */
    const val MASTER_DISCIPLE_BREAKTHROUGH_BONUS_PER_GAP = 0.03

    /** 寿命惩罚阈值：剩余寿命低于此比例时触发 */
    internal const val LIFESPAN_PENALTY_THRESHOLD = 0.20
    /** 每低于阈值1个百分点降低5%修炼速度 */
    internal const val LIFESPAN_CULTIVATION_PENALTY_PER_PCT = 0.05
    /** 每低于阈值1个百分点降低2%突破率 */
    internal const val LIFESPAN_BREAKTHROUGH_PENALTY_PER_PCT = 0.02

    /**
     * 计算剩余寿命百分比（0.0~1.0）
     * lifespan <= 0 时返回 1.0（无惩罚，避免除零）
     */
}
