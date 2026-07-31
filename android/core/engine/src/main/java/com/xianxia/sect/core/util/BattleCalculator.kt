package com.xianxia.sect.core.util

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.engine.domain.battle.Combatant

/**
 * 战斗伤害乘区（Damage Zone）。
 *
 * 遵循"乘区内加算、乘区间乘算"原则。
 * 各乘区含义：
 * - attackBuffs：攻防 Buff 对攻击力的影响（同类加算）
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
 */
data class DamageZones(
    val attackBuffs: Double = 0.0,
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
)

object BattleCalculator {
    // ── 技能选择概率常量 ──
    private const val PROB_SUPPORT_LOW_HP = 0.80
    private const val PROB_CONTROL_UNCONTROLLED = 0.60
    private const val PROB_AOE_MANY_ENEMIES = 0.70
    private const val PROB_TARGET_LOW_HP = 0.70
    private const val PROB_TARGET_HIGH_THREAT = 0.50
    private const val PROB_TARGET_LOW_DEFENSE = 0.40
    private const val LOW_HP_THRESHOLD = 0.30
    private const val LOW_MP_THRESHOLD = 0.30
    private const val AOE_MIN_ENEMIES = 3

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
        fun calculateDamage(attacker: CombatantStats, defender: CombatantStats, skillDamageMultiplier: Double = 1.0, isPhysicalAttack: Boolean? = null, skillName: String? = null, skillHits: Int = 1, dodgeChanceModifier: Double = 0.5, zones: DamageZones = DamageZones()): DamageResult =
            BattleCalculator.calculateDamage(attacker, defender, skillDamageMultiplier, isPhysicalAttack, skillName, skillHits, dodgeChanceModifier, zones, rng)
        fun calculateCombatantDamage(attacker: Combatant, defender: Combatant, skill: CombatSkill? = null, damageModifier: Double = 1.0, zones: DamageZones? = null, enableInstantKill: Boolean = false): DamageResult =
            BattleCalculator.calculateCombatantDamage(attacker, defender, skill, damageModifier, zones, enableInstantKill, rng)
        fun selectSkill(combatant: Combatant, enemies: List<Combatant>, allies: List<Combatant>, isSilenced: Boolean): CombatSkill? =
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
    fun buildDamageZones(attacker: Combatant, defender: Combatant? = null, extraAmplification: Double = 0.0): DamageZones {
        val atkBoost = attacker.buffs
            .filter { it.type == BuffType.PHYSICAL_ATTACK_BOOST || it.type == BuffType.MAGIC_ATTACK_BOOST }
            .sumOf { it.value }
        val atkReduce = attacker.buffs
            .filter { it.type == BuffType.PHYSICAL_ATTACK_REDUCE || it.type == BuffType.MAGIC_ATTACK_REDUCE }
            .sumOf { it.value }
        val dmgBoost = attacker.buffs
            .filter { it.type == BuffType.DAMAGE_BOOST }
            .sumOf { it.value }
        val dmgReduce = defender?.buffs
            ?.filter { it.type == BuffType.DAMAGE_REDUCTION }
            ?.sumOf { it.value } ?: 0.0

        return DamageZones(
            attackBuffs = atkBoost - atkReduce,
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
        )
    }

    /**
     * 乘区法核心伤害计算。
     *
     * 公式：
     *   effectiveAtk × skillMult × (1 - 防御减伤率) × realmGap
     *   × critMult × physiqueCritMult × affixCritMult
     *   × (1 + 增伤) × (1 + 体质增伤) × (1 + 词条增伤)
     *   × (1 - 减伤) × (1 - 体质减伤) × (1 - 词条减伤)
     *   × 波动
     *
     * 其中：
     * - effectiveAtk = rawAttack × (1 + attackBuffs)
     * - 防御减伤率 = effectiveDefense / (effectiveDefense + DEFENSE_CONSTANT)
     *   effectiveDefense 已叠加体质/词条防御加成（各自独立乘算）
     * - critMult = 暴击时 (1 + 基础暴伤)，非暴击时 1.0
     * - physiqueCritMult = 暴击时 (1 + 体质暴伤加成)，非暴击时 1.0（独立乘算，仅暴击生效）
     * - affixCritMult = 暴击时 (1 + 词条暴伤加成)，非暴击时 1.0（独立乘算，仅暴击生效）
     */
    fun calculateFinalDamage(
        rawAttack: Int,
        defense: Int,
        skillMultiplier: Double,
        realmGapMultiplier: Double,
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
        val preCritDamage = effectiveAttack * skillMultiplier * (1.0 - reduction) * realmGapMultiplier
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
            * (1.0 - zones.damageReduction)
            * (1.0 - zones.physiqueDamageReduction)
            * (1.0 - zones.affixDamageReduction)
            * variance
        ).toInt().coerceAtLeast(GameConfig.Battle.MIN_DAMAGE)
    }

    fun calculateDamageVariance(rng: DeterministicRng): Double {
        val variancePercent = rng.nextDouble() * GameConfig.Battle.DAMAGE_VARIANCE_PERCENT * 2 - GameConfig.Battle.DAMAGE_VARIANCE_PERCENT
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
    }

    /**
     * 基础伤害计算入口（接收 CombatantStats 接口，无 buffs 字段）。
     *
     * 注意：此入口默认 zones 为空，不应用体质/词条等独立乘算因子。
     * 生产环境应优先使用 [calculateCombatantDamage]（接收 Combatant，自动构建 zones）。
     * 此入口保留主要用于测试场景。
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
        val realmGapMultiplier = calculateRealmGapMultiplier(attacker.realm, defender.realm)
        val variance = calculateDamageVariance(rng)

        val finalDamage = calculateFinalDamage(
            rawAttack = attack,
            defense = defense,
            skillMultiplier = skillDamageMultiplier,
            realmGapMultiplier = realmGapMultiplier,
            zones = zones,
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

    fun calculateDodgeChance(
        attacker: CombatantStats,
        defender: CombatantStats,
        modifier: Double = 0.5
    ): Double {
        val speedDiff = attacker.speed - defender.speed
        val totalSpeed = (attacker.speed + defender.speed).coerceAtLeast(1)
        return (speedDiff.toDouble() / totalSpeed * modifier).coerceIn(0.0, GameConfig.Battle.MAX_DODGE_CHANCE)
    }

    fun calculateRealmGapMultiplier(
        attackerRealm: Int, defenderRealm: Int,
        damageBonusPerRealm: Double = GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_REALM,
        damagePenaltyPerRealm: Double = GameConfig.Battle.RealmGap.DAMAGE_PENALTY_PER_REALM
    ): Double {
        val gap = attackerRealm - defenderRealm
        if (gap == 0) return 1.0

        val absGap = kotlin.math.abs(gap)

        return if (gap < 0) {
            1.0 + absGap * damageBonusPerRealm
        } else {
            (1.0 - absGap * damagePenaltyPerRealm).coerceAtLeast(0.0)
        }
    }

    fun generateBattleMessage(
        attackerName: String,
        targetName: String,
        result: DamageResult
    ): String {
        if (result.isDodged) {
            return "$targetName 闪避了 $attackerName 的攻击！"
        }

        val damageType = if (result.isPhysical) "物理" else "法术"
        val skillPrefix = result.skillName?.let { "使用[$it] " } ?: ""
        var message = "$attackerName ${skillPrefix}对 $targetName 造成 ${result.damage} 点${damageType}伤害"

        if (result.isCrit) message += "（暴击！）"
        if (result.hits > 1) message += "（${result.hits}连击）"

        return message
    }

    fun calculateCombatantDamage(
        attacker: Combatant,
        defender: Combatant,
        skill: CombatSkill? = null,
        damageModifier: Double = 1.0,
        zones: DamageZones? = null,
        enableInstantKill: Boolean = false,
        rng: DeterministicRng
    ): DamageResult {
        // 斩杀前置检查：触发斩杀则跳过所有伤害计算，直接返回满血伤害
        if (enableInstantKill && checkInstantKill(attacker.realm, defender.realm, attacker.realmLayer, defender.realmLayer)) {
            val isPhysical = if (skill != null) skill.damageType == DamageType.PHYSICAL
                else attacker.physicalAttack >= attacker.magicAttack
            return DamageResult(
                damage = defender.maxHp,
                isCrit = false,
                isPhysical = isPhysical,
                isDodged = false,
                skillName = skill?.name,
                hits = skill?.hits ?: 1,
                isInstantKill = true
            )
        }

        val isSkillAttack = skill != null
        val dodgeModifier = if (isSkillAttack) 0.3 else 0.5
        val maxDodgeChance = if (isSkillAttack) GameConfig.Battle.MAX_SKILL_DODGE_CHANCE else GameConfig.Battle.MAX_DODGE_CHANCE
        val dodgeChance = calculateCombatantDodgeChance(attacker, defender, dodgeModifier, maxDodgeChance)

        if (rng.nextDouble() < dodgeChance) {
            return DamageResult(
                damage = 0,
                isCrit = false,
                isPhysical = if (isSkillAttack) skill?.damageType == DamageType.PHYSICAL ?: true else attacker.physicalAttack >= attacker.magicAttack,
                isDodged = true,
                skillName = skill?.name,
                hits = skill?.hits ?: 1
            )
        }

        val isPhysical = if (isSkillAttack) skill?.damageType == DamageType.PHYSICAL ?: true else attacker.physicalAttack >= attacker.magicAttack
        val attack = if (isPhysical) attacker.physicalAttack else attacker.magicAttack
        val defense = if (isPhysical) defender.effectivePhysicalDefense else defender.effectiveMagicDefense

        val isCrit = rng.nextDouble() < attacker.effectiveCritRate
        val skillMultiplier = skill?.damageMultiplier ?: 1.0
        val realmGapMultiplier = calculateRealmGapMultiplier(attacker.realm, defender.realm)
        val variance = calculateDamageVariance(rng)

        val damageZones = zones ?: buildDamageZones(attacker, defender)

        val finalDamage = calculateFinalDamage(
            rawAttack = attack,
            defense = defense,
            skillMultiplier = skillMultiplier,
            realmGapMultiplier = realmGapMultiplier,
            zones = damageZones.copy(
                // damageModifier 相当于一个额外的全局增伤/减伤乘区
                damageAmplification =
                    damageZones.damageAmplification + (damageModifier - 1.0)
            ),
            isCrit = isCrit,
            variance = variance
        )

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
        zones: DamageZones? = null
    ): Int {
        val isPhysical = skill.damageType == DamageType.PHYSICAL
        val atk = if (isPhysical)
            attacker.physicalAttack
        else attacker.magicAttack
        val def = if (isPhysical)
            defender.effectivePhysicalDefense
        else defender.effectiveMagicDefense
        val realmGap = calculateRealmGapMultiplier(
            attacker.realm, defender.realm
        )
        val damageZones = zones ?: buildDamageZones(attacker, defender)

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
            skill.damageMultiplier * (1.0 - reduction) * realmGap
        val rawDmg = preCritDmg * avgCritMult *
            (1.0 + damageZones.damageAmplification) *
            (1.0 + damageZones.physiqueDamageAmplification) *
            (1.0 + damageZones.affixDamageAmplification) *
            (1.0 - damageZones.damageReduction) *
            (1.0 - damageZones.physiqueDamageReduction) *
            (1.0 - damageZones.affixDamageReduction) * skill.hits
        return rawDmg.toInt()
            .coerceAtLeast(GameConfig.Battle.MIN_DAMAGE)
    }

    private fun calculateCombatantDodgeChance(
        attacker: Combatant,
        defender: Combatant,
        modifier: Double,
        maxDodgeChance: Double = GameConfig.Battle.MAX_DODGE_CHANCE
    ): Double {
        val speedDiff = attacker.effectiveSpeed - defender.effectiveSpeed
        val totalSpeed = (attacker.effectiveSpeed + defender.effectiveSpeed).coerceAtLeast(1)
        return (speedDiff.toDouble() / totalSpeed * modifier).coerceIn(0.0, maxDodgeChance)
    }

    fun selectSkill(
        combatant: Combatant,
        enemies: List<Combatant>,
        allies: List<Combatant>,
        isSilenced: Boolean,
        rng: DeterministicRng
    ): CombatSkill? {
        if (isSilenced) return null
        if (combatant.skills.isEmpty()) return null

        val availableSkills = combatant.skills.filter {
            it.currentCooldown == 0 && combatant.mp >= it.mpCost
        }
        if (availableSkills.isEmpty()) return null

        val supportSkills = availableSkills.filter { it.skillType == SkillType.SUPPORT }
        val attackSkills = availableSkills.filter { it.skillType == SkillType.ATTACK }

        val lowHpAllies = allies.filter { it.hpPercent < LOW_HP_THRESHOLD }
        if (lowHpAllies.isNotEmpty() && supportSkills.isNotEmpty() && rng.nextDouble() < PROB_SUPPORT_LOW_HP) {
            return supportSkills.first()
        }

        val controlSkills = attackSkills.filter { skill ->
            val localBuffType = skill.buffType
            localBuffType != null && skill.buffDuration > 0 && localBuffType.isDebuff &&
                localBuffType in setOf(BuffType.STUN, BuffType.FREEZE, BuffType.SILENCE, BuffType.TAUNT)
        }
        val uncontrolledEnemies = enemies.filter { enemy -> !enemy.hasControlEffect }
        if (uncontrolledEnemies.isNotEmpty() && controlSkills.isNotEmpty() && rng.nextDouble() < PROB_CONTROL_UNCONTROLLED) {
            return controlSkills.first()
        }

        val aoeSkills = attackSkills.filter { it.isAoe }
        if (enemies.size >= AOE_MIN_ENEMIES && aoeSkills.isNotEmpty() && rng.nextDouble() < PROB_AOE_MANY_ENEMIES) {
            return aoeSkills.maxByOrNull { it.damageMultiplier }
        }

        if (combatant.mpPercent < LOW_MP_THRESHOLD && attackSkills.isNotEmpty()) {
            val cheapSkill = attackSkills.minByOrNull { it.mpCost }
            if (cheapSkill != null && combatant.mp >= cheapSkill.mpCost * 2) {
                return cheapSkill
            }
            return null
        }

        if (attackSkills.isNotEmpty()) {
            return attackSkills.maxByOrNull { it.damageMultiplier / it.mpCost.coerceAtLeast(1) }
        }

        return availableSkills.firstOrNull()
    }

    fun selectTarget(attacker: Combatant, targets: List<Combatant>, rng: DeterministicRng): Combatant {
        val lowHpTargets = targets.filter { it.hpPercent < LOW_HP_THRESHOLD }
        if (lowHpTargets.isNotEmpty() && rng.nextDouble() < PROB_TARGET_LOW_HP) {
            return lowHpTargets[rng.nextInt(lowHpTargets.size)]
        }

        val highThreatTargets = targets.filter { target ->
            target.skills.isNotEmpty() && target.effectivePhysicalAttack > attacker.effectivePhysicalDefense
        }
        if (highThreatTargets.isNotEmpty() && rng.nextDouble() < PROB_TARGET_HIGH_THREAT) {
            return highThreatTargets[rng.nextInt(highThreatTargets.size)]
        }

        val lowDefenseTargets = targets.filter { target ->
            val avgDefense = (target.effectivePhysicalDefense + target.effectiveMagicDefense) / 2.0
            avgDefense < attacker.effectivePhysicalAttack * 0.5
        }
        if (lowDefenseTargets.isNotEmpty() && rng.nextDouble() < PROB_TARGET_LOW_DEFENSE) {
            return lowDefenseTargets[rng.nextInt(lowDefenseTargets.size)]
        }

        return targets[rng.nextInt(targets.size)]
    }

    fun processDotEffects(combatants: List<Combatant>): List<DotResult> {
        val results = mutableListOf<DotResult>()

        for (combatant in combatants) {
            val poisonBuffs = combatant.buffs.filter { it.type == BuffType.POISON && it.remainingDuration > 0 }
            val burnBuffs = combatant.buffs.filter { it.type == BuffType.BURN && it.remainingDuration > 0 }

            var dotDamage = 0
            poisonBuffs.forEach { buff ->
                val realmMultiplier = calculateRealmGapMultiplier(buff.sourceRealm, combatant.realm)
                dotDamage += (combatant.maxHp * buff.value * realmMultiplier).toInt()
            }
            burnBuffs.forEach { buff ->
                val realmMultiplier = calculateRealmGapMultiplier(buff.sourceRealm, combatant.realm)
                dotDamage += (combatant.maxHp * buff.value * realmMultiplier).toInt()
            }
            dotDamage = dotDamage.coerceAtLeast(1)

            if (dotDamage > 0) {
                val newHp = maxOf(0, combatant.hp - dotDamage)
                results.add(DotResult(combatant, dotDamage, newHp))
            }
        }

        return results
    }

    fun executeSupportSkill(
        caster: Combatant,
        allies: List<Combatant>,
        skill: CombatSkill,
        allCombatants: List<Combatant> = emptyList()
    ): SupportResult {
        val targets = when (skill.targetScope) {
            "team" -> allies
            "ally" -> emptyList() // Single ally resolved by caller
            else -> listOf(caster)
        }

        var healAmount = 0
        var healFixedAmount = 0
        val teamBuffs = mutableMapOf<String, List<CombatBuff>>()

        // Percentage healing
        if (skill.healPercent > 0) {
            healAmount = if (skill.healType == HealType.MP) {
                (caster.maxMp * skill.healPercent).toInt()
            } else {
                (caster.maxHp * skill.healPercent).toInt()
            }
        }

        // Fixed-value healing
        if (skill.healFixed > 0) {
            healFixedAmount = skill.healFixed
        }

        // Shield buff
        if (skill.shieldPercent > 0 && skill.buffDuration > 0) {
            val shieldBuff = CombatBuff(
                type = BuffType.SHIELD,
                value = skill.shieldPercent,
                remainingDuration = skill.buffDuration
            )
            for (member in targets) {
                teamBuffs[member.id] = listOf(shieldBuff)
            }
        }

        // Damage share buff
        if (skill.damageSharePercent > 0 && skill.buffDuration > 0) {
            val shareBuff = CombatBuff(
                type = BuffType.DAMAGE_SHARE,
                value = skill.damageSharePercent,
                remainingDuration = skill.buffDuration
            )
            for (member in targets) {
                teamBuffs[member.id] = listOf(shareBuff)
            }
        }

        // Legacy single buffType
        if (skill.buffType != null && skill.buffDuration > 0) {
            val skillBuffType = skill.buffType
            if (skillBuffType != null) {
            val buff = CombatBuff(
                type = skillBuffType,
                value = skill.buffValue,
                remainingDuration = skill.buffDuration
            )
            for (member in targets) {
                teamBuffs[member.id] = listOf(buff)
            }
            }
        }

        // Multi-buff list
        for ((buffType, buffValue, buffDuration) in skill.buffs) {
            val buff = CombatBuff(
                type = buffType,
                value = buffValue,
                remainingDuration = buffDuration
            )
            for (member in targets) {
                val existing = teamBuffs[member.id] ?: emptyList()
                teamBuffs[member.id] = existing + buff
            }
        }

        val totalHeal = healAmount + healFixedAmount
        return SupportResult(
            healAmount = totalHeal,
            healedIds = if (totalHeal > 0) targets.map { it.id } else emptyList(),
            teamBuffs = teamBuffs,
            turnAdvancePercent = skill.turnAdvancePercent,
            healType = skill.healType
        )
    }

    fun updateCombatantCooldowns(combatant: Combatant, usedSkill: CombatSkill): Combatant {
        val updatedSkills = combatant.skills.map { skill ->
            if (skill.name == usedSkill.name) {
                skill.copy(currentCooldown = skill.cooldown)
            } else {
                skill.copy(currentCooldown = maxOf(0, skill.currentCooldown - 1))
            }
        }
        val existingBuffs = combatant.buffs
            .map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }
            .filter { it.remainingDuration > 0 }
        return combatant.copy(
            mp = combatant.mp - usedSkill.mpCost,
            skills = updatedSkills,
            buffs = existingBuffs
        )
    }

    fun updateCombatantBuffsOnly(combatant: Combatant): Combatant {
        val newBuffs = combatant.buffs
            .map { it.copy(remainingDuration = maxOf(0, it.remainingDuration - 1)) }
            .filter { it.remainingDuration > 0 }
        return combatant.copy(buffs = newBuffs)
    }

    fun checkInstantKill(attackerRealm: Int, defenderRealm: Int, attackerLayer: Int, defenderLayer: Int): Boolean {
        val MAX_MINOR_LAYERS = 9
        // 总小层差距 = 大境界差×9 - 层数差（防御方层数越高越强，差距缩小）
        val gap = (defenderRealm - attackerRealm) * MAX_MINOR_LAYERS -
            (defenderLayer - attackerLayer)
        return gap > MAX_MINOR_LAYERS
    }

    /**
     * Calculate shield absorption. Returns (absorbed, remaining damage).
     * Shield absorbs damage before HP deduction. Multiple shields take the max value.
     */
    fun calculateShieldAbsorption(defender: Combatant, incomingDamage: Int): ShieldResult {
        val shieldBuff = defender.buffs
            .filter { it.type == BuffType.SHIELD && it.remainingDuration > 0 }
            .maxByOrNull { it.value }
            ?: return ShieldResult(0, incomingDamage)

        val shieldValue = (defender.maxHp * shieldBuff.value).toInt()
        val absorbed = minOf(shieldValue, incomingDamage)
        val remaining = incomingDamage - absorbed
        val newShieldValue = shieldValue - absorbed

        return ShieldResult(
            absorbed = absorbed,
            remainingDamage = remaining,
            shieldBuff = shieldBuff,
            remainingShield = newShieldValue
        )
    }

    /**
     * Apply damage share redistribution.
     * Returns a map of (combatantId -> extraDamageToTake) from sharing.
     */
    fun calculateDamageShare(
        targetId: String,
        targetSide: CombatantSide,
        incomingDamage: Int,
        team: List<Combatant>,
        beasts: List<Combatant>
    ): Map<String, Int> {
        val extraDamage = mutableMapOf<String, Int>()
        val allies = if (targetSide == CombatantSide.DEFENDER) team else beasts

        for (ally in allies) {
            if (ally.id == targetId || ally.isDead) continue
            val shareBuff = ally.buffs.find {
                it.type == BuffType.DAMAGE_SHARE && it.remainingDuration > 0
            } ?: continue
            val shareDamage = (incomingDamage * shareBuff.value).toInt()
            extraDamage[ally.id] = (extraDamage[ally.id] ?: 0) + shareDamage
        }

        return extraDamage
    }

    /**
     * Calculate linked damage. Returns additional damage to apply to the linked enemy.
     */
    fun calculateLinkedDamage(
        attacker: Combatant,
        target: Combatant,
        damage: Int,
        beasts: List<Combatant>,
        team: List<Combatant>
    ): Map<String, Int> {
        val linkedDamage = mutableMapOf<String, Int>()
        val enemies = if (attacker.side == CombatantSide.DEFENDER) beasts else team

        for (enemy in enemies) {
            if (enemy.id == target.id || enemy.isDead) continue
            val linkBuff = enemy.buffs.find {
                it.type == BuffType.DAMAGE_LINK && it.remainingDuration > 0
            } ?: continue
            val linkDamage = (damage * linkBuff.value).toInt().coerceAtLeast(1)
            linkedDamage[enemy.id] = linkDamage
            break // Only one enemy can be linked at a time
        }

        return linkedDamage
    }

    data class ShieldResult(
        val absorbed: Int,
        val remainingDamage: Int,
        val shieldBuff: CombatBuff? = null,
        val remainingShield: Int = 0
    )
}
