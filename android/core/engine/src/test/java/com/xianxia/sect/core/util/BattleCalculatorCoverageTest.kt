package com.xianxia.sect.core.util

import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.CombatantSide
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.SkillType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import com.xianxia.sect.core.engine.domain.battle.Combatant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 战斗共享计算层守卫测试（批次1：斩杀方向/连击段数/物魔 Buff 分乘区）。
 *
 * 覆盖 2026-08-04 战斗系统核查修复的三处共享计算层变更，
 * 防止方向反转与乘区合并类回归。
 */
class BattleCalculatorCoverageTest {

    /** 每次调用独立种子，保证同种子下 RNG 序列一致（DeterministicRng 状态随调用推进） */
    private fun freshRng() = DeterministicRng.fromSeed(42L)

    // ---- G1: 境界压制斩杀方向 ----

    @Test
    fun `checkInstantKill - attacker 2 realms higher returns true`() {
        // 高境界压制低境界（大境界差 2 > 1 触发）
        assertTrue(
            BattleCalculator.checkInstantKill(
                attackerRealm = 2, defenderRealm = 0, attackerLayer = 1, defenderLayer = 1
            )
        )
    }

    @Test
    fun `checkInstantKill - defender 2 realms higher returns false`() {
        // 低境界攻击高境界不得触发斩杀（原公式方向反转守卫）
        assertFalse(
            BattleCalculator.checkInstantKill(
                attackerRealm = 0, defenderRealm = 2, attackerLayer = 1, defenderLayer = 1
            )
        )
    }

    @Test
    fun `checkInstantKill - same realm any layer gap does not trigger`() {
        // 同境界最大层差 8（层 1..9）：总小层差 ≤ 8，永不触发
        assertFalse(
            BattleCalculator.checkInstantKill(
                attackerRealm = 3, defenderRealm = 3, attackerLayer = 9, defenderLayer = 1
            )
        )
        assertFalse(
            BattleCalculator.checkInstantKill(
                attackerRealm = 3, defenderRealm = 3, attackerLayer = 9, defenderLayer = 0
            )
        )
    }

    @Test
    fun `checkInstantKill - attacker higher with lower layer still triggers across 2 realms`() {
        // 攻击方高 2 大境界但层数低：总小层差 = 18 - 8 = 10 > 9
        assertTrue(
            BattleCalculator.checkInstantKill(
                attackerRealm = 4, defenderRealm = 2, attackerLayer = 1, defenderLayer = 9
            )
        )
    }

    @Test
    fun `calculateCombatantDamage - target realm higher than attacker does not instant kill`() {
        val attacker = combatant(id = "weak_attacker", realm = 0, realmLayer = 1, physAtk = 500)
        val defender = combatant(
            id = "strong_defender", realm = 2, realmLayer = 1, hp = 1000, maxHp = 1000, physDef = 10
        )
        val result = BattleCalculator.calculateCombatantDamage(
            attacker, defender, null, rng = freshRng(), enableInstantKill = true
        )
        // 低境界打高境界：普通伤害，绝不出现满血斩杀
        assertFalse("低境界攻击高境界不得触发斩杀", result.isInstantKill)
        assertNotEquals(defender.maxHp, result.damage)
    }

    @Test
    fun `calculateCombatantDamage - attacker realm higher triggers instant kill with full hp damage`() {
        val attacker = combatant(id = "strong_attacker", realm = 3, realmLayer = 1, physAtk = 500)
        val defender = combatant(id = "weak_defender", realm = 1, realmLayer = 1, hp = 800, maxHp = 1000, physDef = 500)
        val result = BattleCalculator.calculateCombatantDamage(
            attacker, defender, null, rng = freshRng(), enableInstantKill = true
        )
        assertTrue("高境界攻击低境界应触发斩杀", result.isInstantKill)
        assertEquals(defender.maxHp, result.damage)
    }

    // ---- G2: 多段技能伤害 = 单段 × 段数 ----

    @Test
    fun `calculateCombatantDamage - multi hit skill damage equals single hit times hits`() {
        val attacker = combatant(id = "attacker", physAtk = 300)
        val defender = combatant(id = "defender", hp = 5000, maxHp = 5000, physDef = 100)

        val singleHit = BattleCalculator.calculateCombatantDamage(
            attacker, defender, skill(hits = 1), rng = freshRng()
        )
        val tripleHit = BattleCalculator.calculateCombatantDamage(
            attacker, defender, skill(hits = 3), rng = freshRng()
        )
        // 同种子下 RNG 序列一致（hits 不消耗 RNG），三连击总伤应为单发 3 倍
        assertEquals("三连击总伤应为单发3倍", singleHit.damage * 3, tripleHit.damage)
    }

    @Test
    fun `calculateCombatantDamage - zero or negative hits clamped to single hit`() {
        // 对抗性审查：hits 篡改为 0/负值时不得产生 0 伤害/负伤害（回血）
        val attacker = combatant(id = "attacker", physAtk = 300)
        val defender = combatant(id = "defender", hp = 5000, maxHp = 5000, physDef = 100)

        val zeroHits = BattleCalculator.calculateCombatantDamage(
            attacker, defender, skill(hits = 0), rng = freshRng()
        )
        val negativeHits = BattleCalculator.calculateCombatantDamage(
            attacker, defender, skill(hits = -3), rng = freshRng()
        )
        val singleHit = BattleCalculator.calculateCombatantDamage(
            attacker, defender, skill(hits = 1), rng = freshRng()
        )
        assertEquals("hits=0 应钳制为单段伤害", singleHit.damage, zeroHits.damage)
        assertEquals("hits 为负应钳制为单段伤害", singleHit.damage, negativeHits.damage)
        assertTrue("伤害必须为正", zeroHits.damage > 0)
    }

    @Test
    fun `calculateShieldAbsorption - negative shield value does not amplify damage`() {
        // 对抗性审查：护盾 value 篡改为负值时不得放大伤害
        val defender = combatant(
            id = "defender", hp = 800, maxHp = 1000
        ).copy(buffs = listOf(CombatBuff(type = BuffType.SHIELD, value = -0.5, remainingDuration = 3)))
        val result = BattleCalculator.calculateShieldAbsorption(defender, 100)
        assertEquals("负护盾不吸收", 0, result.absorbed)
        assertEquals("伤害保持原值不被放大", 100, result.remainingDamage)
        val infinity = combatant(
            id = "defender2", hp = 800, maxHp = 1000
        ).copy(
            buffs = listOf(CombatBuff(type = BuffType.SHIELD, value = Double.POSITIVE_INFINITY, remainingDuration = 3))
        )
        val infResult = BattleCalculator.calculateShieldAbsorption(infinity, 100)
        assertEquals("Infinity 护盾按 100% 比例钳制吸收（1000×1.0=1000 ≥ 100）", 100, infResult.absorbed)
        assertEquals(0, infResult.remainingDamage)
    }

    @Test
    fun `estimateDamage - multi hit matches actual damage scaling`() {
        val attacker = combatant(id = "attacker", physAtk = 300)
        val defender = combatant(id = "defender", hp = 5000, maxHp = 5000, physDef = 100)

        val singleEst = BattleCalculator.estimateDamage(attacker, defender, skill(hits = 1))
        val tripleEst = BattleCalculator.estimateDamage(attacker, defender, skill(hits = 3))
        assertEquals("AI 估算三连击应为单发3倍", singleEst * 3, tripleEst)
    }

    // ---- G8: 物理/魔法攻击 Buff 分乘区 ----

    @Test
    fun `buildDamageZones - physical attack buff only in physical bucket`() {
        val attacker = combatant(id = "attacker").copy(
            buffs = listOf(CombatBuff(type = BuffType.PHYSICAL_ATTACK_BOOST, value = 0.5, remainingDuration = 3))
        )
        val zones = BattleCalculator.buildDamageZones(attacker)
        assertEquals(0.5, zones.physicalAttackBuffs, 1e-9)
        assertEquals(0.0, zones.magicAttackBuffs, 1e-9)
    }

    @Test
    fun `calculateCombatantDamage - physical buff does not boost magic skill damage`() {
        val attacker = combatant(
            id = "attacker", physAtk = 300
        ).copy(
            magicAttack = 300,
            buffs = listOf(CombatBuff(type = BuffType.PHYSICAL_ATTACK_BOOST, value = 1.0, remainingDuration = 3))
        )
        val defender = combatant(id = "defender", hp = 5000, maxHp = 5000).copy(magicDefense = 100)

        val withBuff = BattleCalculator.calculateCombatantDamage(
            attacker, defender, skill(dmgType = DamageType.MAGIC), rng = freshRng()
        )
        val withoutBuff = BattleCalculator.calculateCombatantDamage(
            combatant(id = "attacker", physAtk = 300).copy(magicAttack = 300),
            defender, skill(dmgType = DamageType.MAGIC), rng = freshRng()
        )
        // 物理攻击 +100% 不应影响魔法技能伤害（同一 RNG 序列）
        assertEquals("物理攻击buff不得加成魔法技能", withoutBuff.damage, withBuff.damage)
    }

    // ---- fixture ----

    private fun combatant(
        id: String = "u1",
        hp: Int = 800, maxHp: Int = 1000,
        physAtk: Int = 100, physDef: Int = 60,
        realm: Int = 3, realmLayer: Int = 1
    ) = Combatant(
        id = id, name = id, side = CombatantSide.DEFENDER,
        hp = hp, maxHp = maxHp, mp = 500, maxMp = 500,
        physicalAttack = physAtk, magicAttack = 80,
        physicalDefense = physDef, magicDefense = 50,
        speed = 100, critRate = 0.0,
        skills = emptyList(), buffs = emptyList(),
        realm = realm, realmLayer = realmLayer
    )

    private fun skill(
        name: String = "连击",
        dmgMult: Double = 0.6,
        hits: Int = 1,
        dmgType: DamageType = DamageType.PHYSICAL
    ) = CombatSkill(
        name = name,
        skillType = SkillType.ATTACK,
        damageType = dmgType,
        damageMultiplier = dmgMult,
        mpCost = 15,
        cooldown = 2,
        hits = hits,
        targetScope = "enemy"
    )

    // ---- T-C1（2026-08-05）：estimateDamage 注入 damageModifier ----

    @Test
    fun `estimateDamage - damageModifier injected into amplification zone`() {
        val attacker = combatant(id = "attacker", physAtk = 300)
        val defender = combatant(id = "defender", hp = 5000, maxHp = 5000, physDef = 100)

        val base = BattleCalculator.estimateDamage(attacker, defender, skill())
        val explicit = BattleCalculator.estimateDamage(attacker, defender, skill(), damageModifier = 1.0)
        val modified = BattleCalculator.estimateDamage(attacker, defender, skill(), damageModifier = 1.05)
        // 默认 1.0 向后兼容：显式 1.0 与不传时逐位相同
        assertEquals("默认 damageModifier=1.0 与不传一致", base, explicit)
        assertTrue("damageModifier=1.05 估算应高于基线", modified > base)
    }

    // ---- T-C2（2026-08-05）：斩杀分支 maxHp 篡改守卫 ----

    @Test
    fun `calculateCombatantDamage - instant kill with tampered negative maxHp returns zero damage`() {
        // 存档篡改：defender.maxHp 为 0/负——斩杀伤害钳制为 0，不得出现负伤害（回血）
        val attacker = combatant(id = "strong_attacker", realm = 3, realmLayer = 1, physAtk = 500)
        val defender = combatant(id = "tampered_defender", realm = 1, realmLayer = 1, hp = 0, maxHp = 0, physDef = 500)
        val negative = combatant(id = "neg_defender", realm = 1, realmLayer = 1, hp = 0, maxHp = -100, physDef = 500)

        val zeroResult = BattleCalculator.calculateCombatantDamage(
            attacker, defender, null, rng = freshRng(), enableInstantKill = true
        )
        val negResult = BattleCalculator.calculateCombatantDamage(
            attacker, negative, null, rng = freshRng(), enableInstantKill = true
        )
        assertTrue("触发斩杀", zeroResult.isInstantKill)
        assertEquals("maxHp=0 斩杀伤害钳制为 0", 0, zeroResult.damage)
        assertEquals("maxHp 为负斩杀伤害钳制为 0（不得负伤害回血）", 0, negResult.damage)
    }

    // ---- T-C4（2026-08-05）：buildDamageZones 单次遍历与过滤式参考实现等价 ----

    @Test
    fun `buildDamageZones - single pass bucketing equals filtered reference implementation`() {
        // 混合 Buff 列表（含无关类型）：单次 when 分桶与旧 filter+sumOf 逐位一致
        val attacker = combatant(id = "attacker").copy(
            buffs = listOf(
                CombatBuff(type = BuffType.PHYSICAL_ATTACK_BOOST, value = 0.5, remainingDuration = 3),
                CombatBuff(type = BuffType.MAGIC_ATTACK_BOOST, value = 0.2, remainingDuration = 3),
                CombatBuff(type = BuffType.DAMAGE_BOOST, value = 0.1, remainingDuration = 3),
                CombatBuff(type = BuffType.SHIELD, value = 0.9, remainingDuration = 3),
                CombatBuff(type = BuffType.PHYSICAL_ATTACK_REDUCE, value = 0.15, remainingDuration = 3),
                CombatBuff(type = BuffType.MAGIC_ATTACK_REDUCE, value = 0.05, remainingDuration = 3),
                CombatBuff(type = BuffType.PHYSICAL_ATTACK_BOOST, value = 0.25, remainingDuration = 3)
            )
        )
        val defender = combatant(id = "defender").copy(
            buffs = listOf(
                CombatBuff(type = BuffType.DAMAGE_REDUCTION, value = 0.3, remainingDuration = 3),
                CombatBuff(type = BuffType.SPEED_BOOST, value = 1.0, remainingDuration = 3)
            )
        )

        val zones = BattleCalculator.buildDamageZones(attacker, defender)
        // 与"过滤式参考实现"对拍（保留 filter+sumOf 语义作为参考）
        val refPhysBoost = attacker.buffs.filter { it.type == BuffType.PHYSICAL_ATTACK_BOOST }.sumOf { it.value }
        val refPhysReduce = attacker.buffs.filter { it.type == BuffType.PHYSICAL_ATTACK_REDUCE }.sumOf { it.value }
        val refMagBoost = attacker.buffs.filter { it.type == BuffType.MAGIC_ATTACK_BOOST }.sumOf { it.value }
        val refMagReduce = attacker.buffs.filter { it.type == BuffType.MAGIC_ATTACK_REDUCE }.sumOf { it.value }
        val refDmgBoost = attacker.buffs.filter { it.type == BuffType.DAMAGE_BOOST }.sumOf { it.value }
        val refDmgReduce = defender.buffs.filter { it.type == BuffType.DAMAGE_REDUCTION }.sumOf { it.value }

        assertEquals(refPhysBoost - refPhysReduce, zones.physicalAttackBuffs, 1e-9)
        assertEquals(refMagBoost - refMagReduce, zones.magicAttackBuffs, 1e-9)
        assertEquals(refDmgBoost, zones.damageAmplification, 1e-9)
        assertEquals(refDmgReduce, zones.damageReduction, 1e-9)
    }

    // ---- B4: calculateCombatantDamage 拆分后 RNG 抽数序守卫（对拍）----

    @Test
    fun `calculateCombatantDamage - instant kill path consumes zero rng draws`() {
        val attacker = combatant(id = "kill_attacker", realm = 3, realmLayer = 1, physAtk = 500)
        val defender = combatant(id = "kill_defender", realm = 1, realmLayer = 1, hp = 800, maxHp = 1000, physDef = 500)

        val rngBeforeKill = DeterministicRng.fromSeed(42L)
        val rngAfterKill = DeterministicRng.fromSeed(42L)
        val killResult = BattleCalculator.calculateCombatantDamage(
            attacker, defender, null, rng = rngBeforeKill, enableInstantKill = true
        )
        val drawAfterKill = rngBeforeKill.nextDouble()
        val drawRef = rngAfterKill.nextDouble()

        assertTrue("高境界攻击低境界应触发斩杀", killResult.isInstantKill)
        assertEquals("斩杀路径不得消耗任何 RNG 抽数（抽数序列不得前移）", drawRef, drawAfterKill, 0.0)
    }

    @Test
    fun `calculateCombatantDamage - dodge path consumes exactly one rng draw`() {
        // 攻速差极大 → dodgeChance 封顶（0.49），必存在闪避种子
        val attacker = combatant(id = "fast_attacker", physAtk = 500).copy(speed = 10000)
        val defender = combatant(id = "slow_defender", hp = 500, maxHp = 500, physDef = 0)
        val dodgingSeed = (1..200).first { seed ->
            BattleCalculator.calculateCombatantDamage(
                attacker, defender, null, rng = DeterministicRng.fromSeed(seed.toLong())
            ).isDodged
        }

        val rngAfterDodge = DeterministicRng.fromSeed(dodgingSeed.toLong())
        val dodged = BattleCalculator.calculateCombatantDamage(attacker, defender, null, rng = rngAfterDodge)
        val drawAfterDodge = rngAfterDodge.nextDouble()
        val rngRef = DeterministicRng.fromSeed(dodgingSeed.toLong())
        rngRef.nextDouble() // 模拟正式调用的闪避判定抽数
        val drawRef2 = rngRef.nextDouble()

        assertTrue("种子 $dodgingSeed 应触发闪避", dodged.isDodged)
        assertEquals(0, dodged.damage)
        assertFalse(dodged.isCrit)
        assertEquals("闪避路径应恰好消耗 1 个抽数（dodge 判定）", drawRef2, drawAfterDodge, 0.0)
    }

    @Test
    fun `calculateCombatantDamage - normal path consumes three draws in dodge then crit then variance order`() {
        // 攻速相等 → dodgeChance = 0，恒走正常管线
        // 注意：dodge 判定本身恒消耗 1 抽（rng.nextDouble() < dodgeChance 无条件调用）
        val attacker = combatant(id = "normal_attacker", physAtk = 500)
        val defender = combatant(id = "normal_defender", hp = 500, maxHp = 500, physDef = 100)

        val rngAfterDamage = DeterministicRng.fromSeed(42L)
        val result = BattleCalculator.calculateCombatantDamage(attacker, defender, null, rng = rngAfterDamage)
        val drawAfterDamage = rngAfterDamage.nextDouble()
        val rngRef = DeterministicRng.fromSeed(42L)
        rngRef.nextDouble() // dodge 判定
        rngRef.nextDouble() // crit
        rngRef.nextDouble() // variance
        val drawRef4 = rngRef.nextDouble()

        assertFalse(result.isDodged)
        assertTrue(result.damage > 0)
        assertEquals("正常路径应恰好消耗 3 个抽数（闪避判定 → 暴击 → 波动）", drawRef4, drawAfterDamage, 0.0)
    }
}
