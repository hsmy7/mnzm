package com.xianxia.sect.core.util

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.engine.domain.battle.Combatant

/**
 * 战斗伤害乘区（Damage Zone）。
 *
 * 遵循"乘区内加算、乘区间乘算"原则。
 * 各乘区含义：
 * - attackBuffs：攻防 Buff 对攻击力的影响（同类加算；由调用方按攻击类型注入 physical/magic 桶）
 * - physicalAttackBuffs / magicAttackBuffs：物理/魔法攻击 Buff 分桶（原合并加算会
 *   导致物理加成误加到魔法攻击上）
 * - damageAmplification：增伤乘区（DAMAGE_BOOST 等）
 * - damageReduction：减伤乘区（DAMAGE_REDUCTION 等）
 *
 * 体质独立乘算因子（与 buff 乘区分开，独立乘算）：
 * - physiqueDamageAmplification：进攻方体质伤害加成
 * - physiqueCritDamageBonus：进攻方体质暴击伤害加成
 * - physiqueDamageReduction：防守方体质减伤
 * - physiqueDefenseBonus：防守方体质防御加成
 *
 * 词条独立乘算因子（与 buff、体质分开，各自独立乘算）：
 * - affixDamageAmplification：进攻方词条伤害加成
 * - affixCritDamageBonus：进攻方词条暴击伤害加成
 * - affixDamageReduction：防守方词条减伤
 * - affixDefenseBonus：防守方词条防御加成
 *
 * 境界压制独立乘算因子（与 buff/体质/词条分开，独立乘算，不进任何加算乘区被稀释）：
 * - realmGapDamageAmplification：进攻方境界压制伤害加成（每高 1 小层 +30%）
 * - realmGapDamageReduction：防守方境界压制减伤（每高 1 小层 +30%，封顶 100%）
 * - majorRealmDamageAmplification：进攻方跨大境界增伤（每高 1 大境界 +100%，累加不封顶）
 */
data class DamageZones(
    val attackBuffs: Double = 0.0,
    // 物理/魔法攻击 Buff 分桶（buildDamageZones 填充；调用方按攻击类型注入 attackBuffs）
    val physicalAttackBuffs: Double = 0.0,
    val magicAttackBuffs: Double = 0.0,
    val damageAmplification: Double = 0.0,
    val damageReduction: Double = 0.0,
    // 体质独立乘算因子（与 buff 乘区分开）
    val physiqueDamageAmplification: Double = 0.0,
    val physiqueCritDamageBonus: Double = 0.0,
    val physiqueDamageReduction: Double = 0.0,
    val physiqueDefenseBonus: Double = 0.0,
    // 词条独立乘算因子（与 buff、体质分开，各自独立乘算）
    val affixDamageAmplification: Double = 0.0,
    val affixCritDamageBonus: Double = 0.0,
    val affixDamageReduction: Double = 0.0,
    val affixDefenseBonus: Double = 0.0,
    // 境界压制独立乘算因子（buildDamageZones 按层差填充；与 buff 乘区分开，独立乘算不衰减）
    val realmGapDamageAmplification: Double = 0.0,
    val realmGapDamageReduction: Double = 0.0,
    /** 跨大境界增伤因子（每高 1 大境界 +100%，累加不封顶；仅增伤方向） */
    // 跨大境界增伤因子（每高 1 大境界 +100%，独立乘算，与小层境界压制因子可叠加）
    val majorRealmDamageAmplification: Double = 0.0,
)

object BattleCalculator {
    // ── 技能选择概率常量 ──
    internal const val PROB_SUPPORT_LOW_HP = 0.80
    internal const val PROB_CONTROL_UNCONTROLLED = 0.60
    internal const val PROB_AOE_MANY_ENEMIES = 0.70
    internal const val PROB_TARGET_LOW_HP = 0.70
    internal const val PROB_TARGET_HIGH_THREAT = 0.50
    internal const val PROB_TARGET_LOW_DEFENSE = 0.40
    internal const val LOW_HP_THRESHOLD = 0.30
    internal const val LOW_MP_THRESHOLD = 0.30
    internal const val AOE_MIN_ENEMIES = 3

    // ── 境界压制斩杀常量 ──
    /** 每个大境界包含的小层数（所有境界 maxLayers 均为 9，见 GameConfig.Realm.CONFIGS） */
    internal const val LAYERS_PER_REALM = 9

    /**
     * 带 RNG 的便捷入口 — 使用 BATTLE 分区 RNG。
     * 业务逻辑入口（BattleSystem/AISectAttackManager）应通过此参数注入确定 RNG。
     */
    fun withRng(rng: DeterministicRng): BattleCalculatorWithRng = BattleCalculatorWithRng(rng)

    /**
     * 带确定性 RNG 的 BattleCalculator 封装。
     * 所有随机操作使用传入的 [rng]，确保存档/读档后随机序列一致。
     */
    class BattleCalculatorWithRng(internal val rng: DeterministicRng) {
        fun calculateDamageVariance(): Double = BattleCalculator.calculateDamageVariance(rng)
        fun calculateDamage(attacker: CombatantStats, defender: CombatantStats, skillDamageMultiplier: Double = 1.0,
            isPhysicalAttack: Boolean? = null, skillName: String? = null, skillHits: Int = 1,
                dodgeChanceModifier: Double = 0.5, zones: DamageZones = DamageZones()): DamageResult =
            BattleCalculator.calculateDamage(attacker, defender, skillDamageMultiplier, isPhysicalAttack, skillName,
                skillHits, dodgeChanceModifier, zones, rng)
        fun calculateCombatantDamage(attacker: Combatant, defender: Combatant, skill: CombatSkill? = null,
            damageModifier: Double = 1.0, zones: DamageZones? = null,
                enableInstantKill: Boolean = false): DamageResult =
            BattleCalculator.calculateCombatantDamage(attacker, defender, skill, damageModifier, zones,
                enableInstantKill, rng)
        fun selectSkill(combatant: Combatant, enemies: List<Combatant>, allies: List<Combatant>,
            isSilenced: Boolean): CombatSkill? =
            BattleCalculator.selectSkill(combatant, enemies, allies, isSilenced, rng)
        fun selectTarget(attacker: Combatant, targets: List<Combatant>): Combatant =
            BattleCalculator.selectTarget(attacker, targets, rng)
    }

    /**
     * 从 Combatant 的 Buff 列表构建战斗乘区。
     *
     * @param attacker 进攻方（提供 attackBuffs/damageAmplification + 体质伤害加成/暴伤）
     * @param defender 防守方（可选，提供 damageReduction + 体质减伤/防御加成）
     * @param extraAmplification 外部额外增伤（如政策加成），直接加到 damageAmplification 乘区
     */
    fun buildDamageZones(attacker: Combatant, defender: Combatant? = null,
        extraAmplification: Double = 0.0): DamageZones {
        // 物理/魔法攻击 Buff 分桶求和：避免物理加成误加到魔法攻击。
        // 单次 O(B) when 分桶累加——遍历序与累加序逐位一致，数学等价。
        var physBoost = 0.0
        var physReduce = 0.0
        var magBoost = 0.0
        var magReduce = 0.0
        var dmgBoost = 0.0
        for (buff in attacker.buffs) {
            when (buff.type) {
                BuffType.PHYSICAL_ATTACK_BOOST -> physBoost += buff.value
                BuffType.PHYSICAL_ATTACK_REDUCE -> physReduce += buff.value
                BuffType.MAGIC_ATTACK_BOOST -> magBoost += buff.value
                BuffType.MAGIC_ATTACK_REDUCE -> magReduce += buff.value
                BuffType.DAMAGE_BOOST -> dmgBoost += buff.value
                else -> {}
            }
        }
        var dmgReduce = 0.0
        defender?.buffs?.forEach { buff ->
            if (buff.type == BuffType.DAMAGE_REDUCTION) dmgReduce += buff.value
        }
        // 境界压制因子：按攻击方/防守方小层差距计算（独立乘算，不进乘区）
        val realmGap = realmGapFactorsOf(attacker, defender)

        return DamageZones(
            // attackBuffs 由调用方（calculateCombatantDamage/estimateDamage）按攻击类型注入对应分桶
            attackBuffs = 0.0,
            physicalAttackBuffs = physBoost - physReduce,
            magicAttackBuffs = magBoost - magReduce,
            damageAmplification = dmgBoost + extraAmplification,
            damageReduction = dmgReduce,
            // 体质独立乘算因子：进攻方提供伤害加成/暴伤，防守方提供减伤/防御
            physiqueDamageAmplification = attacker.physique.damageAmplification,
            physiqueCritDamageBonus = attacker.physique.critDamageBonus,
            physiqueDamageReduction = defender?.physique?.damageReduction ?: 0.0,
            physiqueDefenseBonus = defender?.physique?.defenseBonus ?: 0.0,
            // 词条独立乘算因子：进攻方提供伤害加成/暴伤，防守方提供减伤/防御
            affixDamageAmplification = attacker.affix.damageAmplification,
            affixCritDamageBonus = attacker.affix.critDamageBonus,
            affixDamageReduction = defender?.affix?.damageReduction ?: 0.0,
            affixDefenseBonus = defender?.affix?.defenseBonus ?: 0.0,
            realmGapDamageAmplification = realmGap.damageAmplification,
            realmGapDamageReduction = realmGap.damageReduction,
            majorRealmDamageAmplification = realmGap.majorRealmDamageAmplification,
        )
    }

    /**
     * 乘区法核心伤害计算。
     *
     * 公式：
     *   effectiveAtk × skillMult × (1 - 防御减伤率)
     *   × critMult × physiqueCritMult × affixCritMult
     *   × (1 + 增伤) × (1 + 体质增伤) × (1 + 词条增伤) × (1 + 境界压制增伤) × (1 + 大境界增伤)
     *   × (1 - 减伤) × (1 - 体质减伤) × (1 - 词条减伤) × (1 - 境界压制减伤)
     *   × 波动
     *
     * 其中：
     * - effectiveAtk = rawAttack × (1 + attackBuffs)
     * - 防御减伤率 = effectiveDefense / (effectiveDefense + DEFENSE_CONSTANT)
     *   effectiveDefense 已叠加体质/词条防御加成（各自独立乘算）
     * - critMult = 暴击时 (1 + 基础暴伤)，非暴击时 1.0
     * - physiqueCritMult = 暴击时 (1 + 体质暴伤加成)，非暴击时 1.0（独立乘算，仅暴击生效）
     * - affixCritMult = 暴击时 (1 + 词条暴伤加成)，非暴击时 1.0（独立乘算，仅暴击生效）
     * - 境界压制增伤/减伤：独立乘算因子（不进任何加算乘区被稀释），
     *   由 buildDamageZones 按双方小层差距填充，至多一个因子生效
     * - 大境界增伤：独立乘算因子（不进任何加算乘区被稀释），每高 1 大境界 +100%（累加不封顶），
     *   仅增伤方向，与小层境界压制因子独立乘算可叠加
     */
    fun calculateFinalDamage(
        rawAttack: Int,
        defense: Int,
        skillMultiplier: Double,
        zones: DamageZones,
        isCrit: Boolean,
        variance: Double
    ): Int {
        val effectiveAttack = rawAttack * (1.0 + zones.attackBuffs)
        // 防御乘区：(1 - 体质防御加成) × (1 - 词条防御加成)，二者独立乘算
        val effectiveDefense = defense *
            (1.0 - zones.physiqueDefenseBonus).coerceAtLeast(0.0) *
            (1.0 - zones.affixDefenseBonus).coerceAtLeast(0.0)
        val reduction = effectiveDefense / (effectiveDefense + GameConfig.Battle.DEFENSE_CONSTANT)
        val preCritDamage = effectiveAttack * skillMultiplier * (1.0 - reduction)
        val critMult = if (isCrit) {
            1.0 + GameConfig.Battle.CRIT_BASE_MULTIPLIER
        } else {
            1.0
        }
        // 体质暴伤为额外加成，独立乘算，仅暴击时生效
        val physiqueCritMult = if (isCrit) (1.0 + zones.physiqueCritDamageBonus) else 1.0
        // 词条暴伤为额外加成，独立乘算，仅暴击时生效
        val affixCritMult = if (isCrit) (1.0 + zones.affixCritDamageBonus) else 1.0
        return (preCritDamage * critMult * physiqueCritMult * affixCritMult
            * (1.0 + zones.damageAmplification)
            * (1.0 + zones.physiqueDamageAmplification)
            * (1.0 + zones.affixDamageAmplification)
            * (1.0 + zones.realmGapDamageAmplification)
            * (1.0 + zones.majorRealmDamageAmplification)
            * (1.0 - zones.damageReduction)
            * (1.0 - zones.physiqueDamageReduction)
            * (1.0 - zones.affixDamageReduction)
            * (1.0 - zones.realmGapDamageReduction)
            * variance
        ).toInt().coerceAtLeast(GameConfig.Battle.MIN_DAMAGE)
    }

    /**
     * 计算攻击方与防守方之间的境界压制因子（无防守方时返回中性因子）。
     */
    private fun realmGapFactorsOf(attacker: Combatant, defender: Combatant?): RealmGapFactors =
        defender?.let {
            calculateRealmGapFactors(attacker.realm, attacker.realmLayer, it.realm, it.realmLayer)
        } ?: RealmGapFactors()

    fun calculateDamageVariance(rng: DeterministicRng): Double {
        val variancePercent = rng.nextDouble() * GameConfig.Battle.DAMAGE_VARIANCE_PERCENT * 2 - GameConfig.Battle
            .DAMAGE_VARIANCE_PERCENT
        val roundedVariancePercent = (variancePercent * 10).toInt() / 10.0
        return 1.0 + roundedVariancePercent / 100.0
    }

    data class DamageResult(
        val damage: Int,
        val isCrit: Boolean,
        val isPhysical: Boolean,
        val isDodged: Boolean = false,
        val skillName: String? = null,
        val hits: Int = 1,
        val isInstantKill: Boolean = false
    )

    data class DotResult(
        val combatant: Combatant,
        val damage: Int,
        val newHp: Int
    )

    data class SupportResult(
        val healAmount: Int,
        val healedIds: List<String>,
        val teamBuffs: Map<String, List<CombatBuff>>,
        val turnAdvancePercent: Double = 0.0,
        val healType: HealType = HealType.HP
    )

    interface CombatantStats {
        val physicalAttack: Int
        val magicAttack: Int
        val physicalDefense: Int
        val magicDefense: Int
        val speed: Int
        val critRate: Double
        val realm: Int
        val element: String
        /** 含 buff 的有效暴击率，默认实现返回基础暴击率 */
        val effectiveCritRate: Double get() = critRate
        /** 小层境界（1~9），默认 0 表示未知（按初层 1 回退）；Combatant 版实现为 realmLayer */
        val realmLayer: Int get() = 0
    }

    /**
     * 基础伤害计算入口（接收 CombatantStats 接口，无 buffs 字段）。
     *
     * 注意：此入口默认 zones 为空，不应用体质/词条等独立乘算因子。
     * 生产环境应优先使用 [calculateCombatantDamage]（接收 Combatant，自动构建 zones）。
     * 此入口保留主要用于测试场景。
     *
     * 注意：传入的 `zones` 应为"不含境界因子"的空乘区（默认 [DamageZones] 即可）——
     * 本入口会把境界三因子（含大境界因子）以加法注入 zones，若传入已含境界因子的 zones
     * （如 [buildDamageZones] 产物）会造成因子二次叠加（仅测试路径可达）。
     */
    fun calculateDamage(
        attacker: CombatantStats,
        defender: CombatantStats,
        skillDamageMultiplier: Double = 1.0,
        isPhysicalAttack: Boolean? = null,
        skillName: String? = null,
        skillHits: Int = 1,
        dodgeChanceModifier: Double = 0.5,
        zones: DamageZones = DamageZones(),
        rng: DeterministicRng
    ): DamageResult {
        val dodgeChance = calculateDodgeChance(attacker, defender, dodgeChanceModifier)
        if (rng.nextDouble() < dodgeChance) {
            return DamageResult(
                damage = 0,
                isCrit = false,
                isPhysical = isPhysicalAttack ?: true,
                isDodged = true,
                skillName = skillName,
                hits = skillHits
            )
        }

        val usePhysical = isPhysicalAttack ?: (attacker.physicalAttack >= attacker.magicAttack)
        val attack = if (usePhysical) attacker.physicalAttack else attacker.magicAttack
        val defense = if (usePhysical) defender.physicalDefense else defender.magicDefense

        val isCrit = rng.nextDouble() < attacker.effectiveCritRate
        // 境界压制因子独立乘算（不进乘区），注入 zones 的独立因子槽位
        val realmGap = calculateRealmGapFactors(
            attacker.realm, attacker.realmLayer, defender.realm, defender.realmLayer
        )
        val zonesWithRealmGap = zones.copy(
            realmGapDamageAmplification = zones.realmGapDamageAmplification + realmGap.damageAmplification,
            realmGapDamageReduction = zones.realmGapDamageReduction + realmGap.damageReduction,
            majorRealmDamageAmplification = zones.majorRealmDamageAmplification + realmGap.majorRealmDamageAmplification
        )
        val variance = calculateDamageVariance(rng)

        val finalDamage = calculateFinalDamage(
            rawAttack = attack,
            defense = defense,
            skillMultiplier = skillDamageMultiplier,
            zones = zonesWithRealmGap,
            isCrit = isCrit,
            variance = variance
        )

        return DamageResult(
            damage = finalDamage,
            isCrit = isCrit,
            isPhysical = usePhysical,
            isDodged = false,
            skillName = skillName,
            hits = skillHits
        )
    }

    /** 境界压制三因子（小层增伤 + 小层减伤 + 大境界增伤），与 buff/体质/词条乘区独立乘算 */
    data class RealmGapFactors(
        val damageAmplification: Double = 0.0,
        val damageReduction: Double = 0.0,
        /** 跨大境界增伤因子（每高 1 大境界 +100%，累加不封顶；仅增伤方向） */
        val majorRealmDamageAmplification: Double = 0.0
    )

    fun calculateCombatantDamage(
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill? = null,
        damageModifier: Double = 1.0,
        zones: DamageZones? = null,
        enableInstantKill: Boolean = false,
        rng: DeterministicRng
    ): DamageResult {
        val isSkillAttack = skill != null
        return tryInstantKill(attacker, defender, skill, enableInstantKill)
            ?: tryDodge(attacker, defender, skill, isSkillAttack, rng)
            ?: computeDamagePipeline(attacker, defender, skill, damageModifier, zones, isSkillAttack, rng)
    }

    /** 斩杀前置检查（calculateCombatantDamage 提取）：触发斩杀跳过全部伤害计算，无 RNG 消耗 */
    private fun tryInstantKill(
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill?,
        enableInstantKill: Boolean
    ): DamageResult? {
        if (!enableInstantKill || !checkInstantKill(attacker.realm, defender.realm, attacker.realmLayer,
            defender.realmLayer)) {
            return null
        }
        val isPhysical = if (skill != null) skill.damageType == DamageType.PHYSICAL
            else attacker.physicalAttack >= attacker.magicAttack
        return DamageResult(
            // maxHp 篡改为 0/负时钳制为 0，避免负伤害显示
            damage = defender.maxHp.coerceAtLeast(0),
            isCrit = false,
            isPhysical = isPhysical,
            isDodged = false,
            skillName = skill?.name,
            hits = skill?.hits ?: 1,
            isInstantKill = true
        )
    }

    /** 正常伤害管线（calculateCombatantDamage 提取）：暴击抽数 → 波动抽数 → 分桶注入 → 段数钳制 */
    private fun computeDamagePipeline(
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill?,
        damageModifier: Double,
        zones: DamageZones?,
        isSkillAttack: Boolean,
        rng: DeterministicRng
    ): DamageResult {
        val isPhysical = if (isSkillAttack) skill?.damageType == DamageType.PHYSICAL ?: true
        else attacker.physicalAttack >= attacker.magicAttack
        val attack = if (isPhysical) attacker.physicalAttack else attacker.magicAttack
        val defense = if (isPhysical) defender.effectivePhysicalDefense else defender.effectiveMagicDefense

        val isCrit = rng.nextDouble() < attacker.effectiveCritRate
        val skillMultiplier = skill?.damageMultiplier ?: 1.0
        val variance = calculateDamageVariance(rng)

        val baseZones = zones ?: buildDamageZones(attacker, defender)
        // 攻击 Buff 按攻击类型注入分桶（物理/魔法互不干扰）
        val damageZones = baseZones.copy(
            attackBuffs = baseZones.attackBuffs +
                (if (isPhysical) baseZones.physicalAttackBuffs else baseZones.magicAttackBuffs),
            // damageModifier 相当于一个额外的全局增伤/减伤乘区
            damageAmplification = baseZones.damageAmplification + (damageModifier - 1.0)
        )

        // 多段技能总伤害 = 单段伤害 × 段数（与 estimateDamage 的 AI 估算一致）。
        // hits 篡改为 0/负值时钳制为 1（否则 0 伤害/负伤害回血），
        // Long 乘法防 Int 溢出回绕（单段伤害 × 段数超过 Int.MAX 时钳制到 Int.MAX）
        val safeHits = (skill?.hits ?: 1).coerceAtLeast(1)
        val finalDamage = (calculateFinalDamage(
            rawAttack = attack,
            defense = defense,
            skillMultiplier = skillMultiplier,
            zones = damageZones,
            isCrit = isCrit,
            variance = variance
        ).toLong() * safeHits)
            .coerceIn(GameConfig.Battle.MIN_DAMAGE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()

        return DamageResult(
            damage = finalDamage,
            isCrit = isCrit,
            isPhysical = isPhysical,
            isDodged = false,
            skillName = skill?.name,
            hits = skill?.hits ?: 1
        )
    }

    /**
     * 确定性伤害估算（无随机数），供 AI 决策使用（斩杀判断等）。
     * 使用期望暴击率代替随机暴击，不含闪避概率，不含伤害波动。
     * 使用乘区法公式计算。
     */
    fun estimateDamage(
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill,
        zones: DamageZones? = null,
        damageModifier: Double = 1.0
    ): Int {
        val isPhysical = skill.damageType == DamageType.PHYSICAL
        val atk = if (isPhysical)
            attacker.physicalAttack
        else attacker.magicAttack
        val def = if (isPhysical)
            defender.effectivePhysicalDefense
        else defender.effectiveMagicDefense
        // 攻击 Buff 按攻击类型注入分桶（与 calculateCombatantDamage 实际伤害一致）
        val baseZones = zones ?: buildDamageZones(attacker, defender)
        val damageZones = baseZones.copy(
            attackBuffs = baseZones.attackBuffs +
                (if (isPhysical) baseZones.physicalAttackBuffs else baseZones.magicAttackBuffs),
            // damageModifier 注入（与 calculateCombatantDamage 同式），
            // 严苛训练 +5% 时 AI 决策估算与实际伤害一致
            damageAmplification = baseZones.damageAmplification + (damageModifier - 1.0)
        )

        // 期望暴击：体质暴伤与词条暴伤均为独立乘算因子，仅暴击时生效
        // avgCritMult = (1 - p) × 1.0 + p × (1 + 基础暴伤) × (1 + 体质暴伤加成) × (1 + 词条暴伤加成)
        val buffCritMult = 1.0 + GameConfig.Battle.CRIT_BASE_MULTIPLIER
        val physiqueCritMult = 1.0 + damageZones.physiqueCritDamageBonus
        val affixCritMult = 1.0 + damageZones.affixCritDamageBonus
        val critRate = attacker.effectiveCritRate.coerceIn(0.0, 1.0)
        val avgCritMult = (1.0 - critRate) * 1.0 + critRate * buffCritMult * physiqueCritMult * affixCritMult

        val penFactor = (1.0 - damageZones.physiqueDefenseBonus).coerceAtLeast(0.0) *
            (1.0 - damageZones.affixDefenseBonus).coerceAtLeast(0.0)
        val effectiveDef = def * penFactor
        val reduction = effectiveDef /
            (effectiveDef + GameConfig.Battle.DEFENSE_CONSTANT)

        val preCritDmg = atk.toDouble() * (1.0 + damageZones.attackBuffs) *
            skill.damageMultiplier * (1.0 - reduction)
        val rawDmg = preCritDmg * avgCritMult *
            (1.0 + damageZones.damageAmplification) *
            (1.0 + damageZones.physiqueDamageAmplification) *
            (1.0 + damageZones.affixDamageAmplification) *
            (1.0 + damageZones.realmGapDamageAmplification) *
            (1.0 + damageZones.majorRealmDamageAmplification) *
            (1.0 - damageZones.damageReduction) *
            (1.0 - damageZones.physiqueDamageReduction) *
            (1.0 - damageZones.affixDamageReduction) *
            (1.0 - damageZones.realmGapDamageReduction) * skill.hits
        return rawDmg.toInt()
            .coerceAtLeast(GameConfig.Battle.MIN_DAMAGE)
    }

    /**
     * 跨境界斩杀判定（境界压制必杀）。
     *
     * realm 数值越小境界越高（0=仙人，9=炼气）。总小层差距 = 大境界差×每境界层数 + 层数差
     * （攻击方层数越高越强，差距增大；防御方层数越高越强，差距缩小）。
     * 攻击方比防御方高 [GameConfig.Battle.RealmGap.INSTANT_KILL_GAP] 个以上大境界（层数微调）时触发斩杀。
     *
     * realm/realmLayer 经存档篡改可越界，Int 运算会溢出回绕
     * （realmLayer=Int.MAX_VALUE 误斩秒杀任意目标、巨大 realm 漏斩），
     * 故使用 Long 中间运算 + safeRealm/safeLayer 钳制。
     *
     * @param attackerRealm 攻击方境界（数值越小境界越高）
     * @param defenderRealm 防御方境界（数值越小境界越高）
     */
    fun checkInstantKill(attackerRealm: Int, defenderRealm: Int, attackerLayer: Int, defenderLayer: Int): Boolean {
        val gap = (safeRealm(defenderRealm).toLong() - safeRealm(attackerRealm).toLong()) * LAYERS_PER_REALM +
            (safeLayer(attackerLayer).toLong() - safeLayer(defenderLayer).toLong())
        return gap > GameConfig.Battle.RealmGap.INSTANT_KILL_GAP * LAYERS_PER_REALM
    }

    data class ShieldResult(
        val absorbed: Int,
        val remainingDamage: Int,
        val shieldBuff: CombatBuff? = null,
        val remainingShield: Int = 0
    )
}
