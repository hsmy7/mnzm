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
 * 各乘区含义（属性单列口径，装备重构 B1 方案 §15.2/§15.6.1）：
 * - physicalAttackBuffs / magicAttackBuffs：**类型增伤** buff 分桶（原物法攻
 *   buff 语义迁移；PHYSICAL_ATTACK_BOOST → 物理类型增伤；法术桶为退役段，
 *   MAGIC 类型已随五行化退役，仅存档兼容——元素伤害 buff 无对应 BuffType，
 *   元素加成全部走 Combatant 六路固有桶）
 * - physicalDefenseBuffs / magicDefenseBuffs：**类型减伤** buff 分桶（守方，同上）
 * - typeDamageBonus / typeDamageReduction：类型通道结算位（六路固有桶按本次
 *   伤害类型选桶 + buff 桶合并后的最终值；calculateFinalDamage 消费。
 *   十二桶全 0.0 时与无类型通道的基准公式逐位一致，S19）
 * - damageAmplification：增伤乘区（DAMAGE_BOOST 等）
 * - damageReduction：减伤乘区（DAMAGE_REDUCTION 等）
 *
 * 境界压制独立乘算因子（与 buff 分开，独立乘算，不进任何加算乘区被稀释）：
 * - realmGapDamageAmplification：进攻方境界压制伤害加成（每高 1 小层 +30%）
 * - realmGapDamageReduction：防守方境界压制减伤（每高 1 小层 +30%，封顶 100%）
 * - majorRealmDamageAmplification：进攻方跨大境界增伤（每高 1 大境界 +100%，累加不封顶）
 */
data class DamageZones(
    // 物理/法术类型增伤 buff 分桶（buildDamageZones 填充；物理桶进 typeDamageBonus，
    // 法术桶为退役段——元素伤害加成走 Combatant 六路固有桶，不经 buff 分桶）
    val physicalAttackBuffs: Double = 0.0,
    val magicAttackBuffs: Double = 0.0,
    // 物理/法术类型减伤 buff 分桶（守方，buildDamageZones 填充；同上退役口径）
    val physicalDefenseBuffs: Double = 0.0,
    val magicDefenseBuffs: Double = 0.0,
    val damageAmplification: Double = 0.0,
    val damageReduction: Double = 0.0,
    // 类型通道结算位：选桶合并后的最终值（六路固有桶 + buff 桶），默认 0.0 时与基准公式逐位一致
    val typeDamageBonus: Double = 0.0,
    val typeDamageReduction: Double = 0.0,
    // 境界压制独立乘算因子（buildDamageZones 按层差填充；与 buff 乘区分开，独立乘算不衰减）
    val realmGapDamageAmplification: Double = 0.0,
    val realmGapDamageReduction: Double = 0.0,
    /** 跨大境界增伤因子（每高 1 大境界 +100%，累加不封顶；仅增伤方向） */
    // 跨大境界增伤因子（每高 1 大境界 +100%，独立乘算，与小层境界压制因子可叠加）
    val majorRealmDamageAmplification: Double = 0.0,
)

@Suppress("TooManyFunctions") // 伤害入口三臂（CombatantStats 版 / Combatant 版 / 估算）+ 概率常量：文件级函数数为公式域拆分的固有形态
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
            damageType: DamageType? = null, skillName: String? = null, skillHits: Int = 1,
                dodgeChanceModifier: Double = 0.5, zones: DamageZones = DamageZones()): DamageResult =
            BattleCalculator.calculateDamage(attacker, defender, skillDamageMultiplier, damageType, skillName,
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
     * @param attacker 进攻方（提供类型增伤 buff 分桶 + 固有类型增伤桶、damageAmplification + 体质伤害加成/暴伤）
     * @param defender 防守方（可选，提供类型减伤 buff 分桶 + 固有类型减伤桶、damageReduction + 防御加成）
     * @param extraAmplification 外部额外增伤（如政策加成），直接加到 damageAmplification 乘区
     */
    @Suppress("CyclomaticComplexMethod") // buff 八类 + 守方五类分桶：when 分桶清单，拆分遮蔽"逐类注入"完整性
    fun buildDamageZones(attacker: Combatant, defender: Combatant? = null,
        extraAmplification: Double = 0.0): DamageZones {
        // 类型增伤/减伤 buff 分桶求和：物法攻 buff（BOOST/REDUCE）迁移为类型增伤语义、
        // 物法防 buff 迁移为类型减伤语义（B1 §15.2 改动点③）。
        // 单次 O(B) when 分桶累加——遍历序与累加序逐位一致，数学等价。
        var physBoost = 0.0
        var physReduce = 0.0
        var magBoost = 0.0
        var magReduce = 0.0
        var physDefBoost = 0.0
        var physDefReduce = 0.0
        var magDefBoost = 0.0
        var magDefReduce = 0.0
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
            when (buff.type) {
                BuffType.DAMAGE_REDUCTION -> dmgReduce += buff.value
                BuffType.PHYSICAL_DEFENSE_BOOST -> physDefBoost += buff.value
                BuffType.PHYSICAL_DEFENSE_REDUCE -> physDefReduce += buff.value
                BuffType.MAGIC_DEFENSE_BOOST -> magDefBoost += buff.value
                BuffType.MAGIC_DEFENSE_REDUCE -> magDefReduce += buff.value
                else -> {}
            }
        }
        // 境界压制因子：按攻击方/防守方小层差距计算（独立乘算，不进乘区）
        val realmGap = realmGapFactorsOf(attacker, defender)

        return DamageZones(
            physicalAttackBuffs = physBoost - physReduce,
            magicAttackBuffs = magBoost - magReduce,
            physicalDefenseBuffs = physDefBoost - physDefReduce,
            magicDefenseBuffs = magDefBoost - magDefReduce,
            damageAmplification = dmgBoost + extraAmplification,
            damageReduction = dmgReduce,
            realmGapDamageAmplification = realmGap.damageAmplification,
            realmGapDamageReduction = realmGap.damageReduction,
            majorRealmDamageAmplification = realmGap.majorRealmDamageAmplification,
        )
    }

    /**
     * 类型通道选桶合并（六路固有桶 + buff 桶 → zones 结算位；双端同序同式）。
     *
     * 六类型按 [DamageType] 一一索引选桶（物理/五行各自独立通道，互不串扰）；
     * 物理桶附加物理 buff 分桶（PHYSICAL_ATTACK_BOOST 等八类 buff 语义不变）；
     * 五行类型无 buff 分桶（元素加成全部来自固有桶），退役段 [DamageType.MAGIC]
     * 无固有桶、仅遗留法术 buff 桶（新代码不再产出 MAGIC 类型伤害）。
     */
    private fun mergeTypeChannels(zones: DamageZones, attacker: Combatant, defender: Combatant,
        damageType: DamageType): DamageZones = zones.copy(
        typeDamageBonus = zones.typeDamageBonus +
            attacker.typeDamageBonusOf(damageType) +
            (if (damageType == DamageType.PHYSICAL) zones.physicalAttackBuffs
            else if (damageType == DamageType.MAGIC) zones.magicAttackBuffs else 0.0),
        typeDamageReduction = zones.typeDamageReduction +
            defender.typeDamageReductionOf(damageType) +
            (if (damageType == DamageType.PHYSICAL) zones.physicalDefenseBuffs
            else if (damageType == DamageType.MAGIC) zones.magicDefenseBuffs else 0.0)
    )

    /**
     * 乘区法核心伤害计算。
     *
     * 公式：
     *   effectiveAtk × skillMult × (1 - 防御减伤率)
     *   × critMult × physiqueCritMult × affixCritMult
     *   × (1 + 增伤 + 类型增伤) × (1 + 体质增伤) × (1 + 词条增伤) × (1 + 境界压制增伤) × (1 + 大境界增伤)
     *   × (1 - 减伤 - 类型减伤) × (1 - 体质减伤) × (1 - 词条减伤) × (1 - 境界压制减伤)
     *   × 波动
     *
     * 其中（属性单列口径，B1 方案 §15.2）：
     * - effectiveAtk = rawAttack × (1 + attackBuffs)
     * - 防御减伤率 = defense / (defense + DEFENSE_CONSTANT)（单列，与类型无关）
     * - 类型增伤/减伤 = zones.typeDamageBonus / typeDamageReduction（类型通道结算位，
     *   默认 0.0 时与无类型通道的基准公式逐位一致，S19）
     * - critMult = 暴击时 (1 + 基础暴伤 + 攻击方暴伤加成)，非暴击时 1.0
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
        variance: Double,
        critDamageBonus: Double = 0.0
    ): Int {
        val effectiveAttack = rawAttack.toDouble()
        val reduction = defense / (defense + GameConfig.Battle.DEFENSE_CONSTANT)
        val preCritDamage = effectiveAttack * skillMultiplier * (1.0 - reduction)
        val critMult = if (isCrit) {
            1.0 + GameConfig.Battle.CRIT_BASE_MULTIPLIER + critDamageBonus
        } else {
            1.0
        }
        return (preCritDamage * critMult
            * (1.0 + zones.damageAmplification + zones.typeDamageBonus)
            * (1.0 + zones.realmGapDamageAmplification)
            * (1.0 + zones.majorRealmDamageAmplification)
            * (1.0 - zones.damageReduction - zones.typeDamageReduction)
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
        /** 本次伤害的类型（普攻恒 PHYSICAL、技能按功法元素；6 活跃值之一） */
        val damageType: DamageType,
        val isDodged: Boolean = false,
        val skillName: String? = null,
        val hits: Int = 1,
        val isInstantKill: Boolean = false
    ) {
        /** 兼容视图：物理类型判定（= [damageType] 是否 [DamageType.PHYSICAL]） */
        val isPhysical: Boolean get() = damageType.isPhysical
    }

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
        val attack: Int
        val defense: Int
        /** 普攻伤害类型（**角色配置驱动**：当前全部角色设定为物理；实现默认物理，
         *  未来法术/五行普攻角色改配此字段即可，架构无需改动） */
        val innateDamageType: DamageType get() = DamageType.PHYSICAL
        val speed: Int
        val critRate: Double
        /** 暴击伤害加成（暴击时 critMult = 1 + 基础暴伤 + 本字段；Combatant 装配值，默认 0） */
        val critDamageBonus: Double get() = 0.0
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
        damageType: DamageType? = null,
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
                damageType = damageType ?: attacker.innateDamageType,
                isDodged = true,
                skillName = skillName,
                hits = skillHits
            )
        }

        // 普攻（无显式类型）按攻击方配置的普攻伤害类型（当前角色全部设定物理）
        val resolvedType = damageType ?: attacker.innateDamageType
        val attack = attacker.attack
        val defense = defender.defense

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
            variance = variance,
            critDamageBonus = attacker.critDamageBonus
        )

        return DamageResult(
            damage = finalDamage,
            isCrit = isCrit,
            damageType = resolvedType,
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
        return DamageResult(
            // maxHp 篡改为 0/负时钳制为 0，避免负伤害显示
            damage = defender.maxHp.coerceAtLeast(0),
            isCrit = false,
            damageType = skill?.damageType ?: attacker.innateDamageType,
            isDodged = false,
            skillName = skill?.name,
            hits = skill?.hits ?: 1,
            isInstantKill = true
        )
    }

    /** 正常伤害管线（calculateCombatantDamage 提取）：暴击抽数 → 波动抽数 → 类型通道合并 → 段数钳制 */
    private fun computeDamagePipeline(
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill?,
        damageModifier: Double,
        zones: DamageZones?,
        isSkillAttack: Boolean,
        rng: DeterministicRng
    ): DamageResult {
        // 技能按功法自带元素（6 值）；普攻按攻击方配置的普攻伤害类型
        //（innateDamageType，**角色配置驱动**——当前全部角色设定物理，非架构恒等式）
        val damageType = if (isSkillAttack) skill?.damageType ?: DamageType.PHYSICAL
        else attacker.innateDamageType
        val attack = attacker.attack
        val defense = defender.defense

        val isCrit = rng.nextDouble() < attacker.effectiveCritRate
        val skillMultiplier = skill?.damageMultiplier ?: 1.0
        val variance = calculateDamageVariance(rng)

        val baseZones = zones ?: buildDamageZones(attacker, defender)
        // 类型通道选桶合并（六路固有桶 + 物理/法术 buff 分桶 → 结算位）；
        // damageModifier 相当于一个额外的全局增伤/减伤乘区
        val damageZones = baseZones
            .let { mergeTypeChannels(it, attacker, defender, damageType) }
            .copy(
                // damageModifier 注入（与 estimateDamage 同式），
                // 严苛训练 +5% 时 AI 决策估算与实际伤害一致
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
            variance = variance,
            critDamageBonus = attacker.critDamageBonus
        ).toLong() * safeHits)
            .coerceIn(GameConfig.Battle.MIN_DAMAGE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()

        return DamageResult(
            damage = finalDamage,
            isCrit = isCrit,
            damageType = damageType,
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
        val damageType = skill.damageType
        val atk = attacker.attack
        val def = defender.defense
        // 类型通道选桶合并（六路固有桶 + buff 分桶 → 结算位；与实际伤害一致）
        val baseZones = zones ?: buildDamageZones(attacker, defender)
        val damageZones = mergeTypeChannels(baseZones, attacker, defender, damageType).copy(
            // damageModifier 注入（与 calculateCombatantDamage 同式），
            // 严苛训练 +5% 时 AI 决策估算与实际伤害一致
            damageAmplification = baseZones.damageAmplification + (damageModifier - 1.0)
        )

        // 期望暴击：avgCritMult = (1 - p) × 1.0 + p × (1 + 基础暴伤 + 暴伤加成)（与 C++ 同式）
        val buffCritMult = 1.0 + GameConfig.Battle.CRIT_BASE_MULTIPLIER + attacker.critDamageBonus
        val critRate = attacker.effectiveCritRate.coerceIn(0.0, 1.0)
        val avgCritMult = (1.0 - critRate) * 1.0 + critRate * buffCritMult

        val reduction = def /
            (def + GameConfig.Battle.DEFENSE_CONSTANT)

        val preCritDmg = atk.toDouble() *
            skill.damageMultiplier * (1.0 - reduction)
        val rawDmg = preCritDmg * avgCritMult *
            (1.0 + damageZones.damageAmplification + damageZones.typeDamageBonus) *
            (1.0 + damageZones.realmGapDamageAmplification) *
            (1.0 + damageZones.majorRealmDamageAmplification) *
            (1.0 - damageZones.damageReduction - damageZones.typeDamageReduction) *
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
