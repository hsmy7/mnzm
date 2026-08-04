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
        assertTrue(BattleCalculator.checkInstantKill(attackerRealm = 2, defenderRealm = 0, attackerLayer = 1, defenderLayer = 1))
    }

    @Test
    fun `checkInstantKill - defender 2 realms higher returns false`() {
        // 低境界攻击高境界不得触发斩杀（原公式方向反转守卫）
        assertFalse(BattleCalculator.checkInstantKill(attackerRealm = 0, defenderRealm = 2, attackerLayer = 1, defenderLayer = 1))
    }

    @Test
    fun `checkInstantKill - same realm any layer gap does not trigger`() {
        // 同境界最大层差 8（层 1..9）：总小层差 ≤ 8，永不触发
        assertFalse(BattleCalculator.checkInstantKill(attackerRealm = 3, defenderRealm = 3, attackerLayer = 9, defenderLayer = 1))
        assertFalse(BattleCalculator.checkInstantKill(attackerRealm = 3, defenderRealm = 3, attackerLayer = 9, defenderLayer = 0))
    }

    @Test
    fun `checkInstantKill - attacker higher with lower layer still triggers across 2 realms`() {
        // 攻击方高 2 大境界但层数低：总小层差 = 18 - 8 = 10 > 9
        assertTrue(BattleCalculator.checkInstantKill(attackerRealm = 4, defenderRealm = 2, attackerLayer = 1, defenderLayer = 9))
    }

    @Test
    fun `calculateCombatantDamage - target realm higher than attacker does not instant kill`() {
        val attacker = combatant(id = "weak_attacker", realm = 0, realmLayer = 1, physAtk = 500)
        val defender = combatant(id = "strong_defender", realm = 2, realmLayer = 1, hp = 1000, maxHp = 1000, physDef = 10)
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
            id = "defender", hp = 800, maxHp = 1000,
            buffs = listOf(CombatBuff(type = BuffType.SHIELD, value = -0.5, remainingDuration = 3))
        )
        val result = BattleCalculator.calculateShieldAbsorption(defender, 100)
        assertEquals("负护盾不吸收", 0, result.absorbed)
        assertEquals("伤害保持原值不被放大", 100, result.remainingDamage)
        val infinity = combatant(
            id = "defender2", hp = 800, maxHp = 1000,
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
        val attacker = combatant(
            id = "attacker",
            buffs = listOf(CombatBuff(type = BuffType.PHYSICAL_ATTACK_BOOST, value = 0.5, remainingDuration = 3))
        )
        val zones = BattleCalculator.buildDamageZones(attacker)
        assertEquals(0.5, zones.physicalAttackBuffs, 1e-9)
        assertEquals(0.0, zones.magicAttackBuffs, 1e-9)
    }

    @Test
    fun `calculateCombatantDamage - physical buff does not boost magic skill damage`() {
        val attacker = combatant(
            id = "attacker", physAtk = 300, magAtk = 300,
            buffs = listOf(CombatBuff(type = BuffType.PHYSICAL_ATTACK_BOOST, value = 1.0, remainingDuration = 3))
        )
        val defender = combatant(id = "defender", hp = 5000, maxHp = 5000, magDef = 100)

        val withBuff = BattleCalculator.calculateCombatantDamage(
            attacker, defender, skill(dmgType = DamageType.MAGIC), rng = freshRng()
        )
        val withoutBuff = BattleCalculator.calculateCombatantDamage(
            combatant(id = "attacker", physAtk = 300, magAtk = 300),
            defender, skill(dmgType = DamageType.MAGIC), rng = freshRng()
        )
        // 物理攻击 +100% 不应影响魔法技能伤害（同一 RNG 序列）
        assertEquals("物理攻击buff不得加成魔法技能", withoutBuff.damage, withBuff.damage)
    }

    // ---- fixture ----

    private fun combatant(
        id: String = "u1",
        hp: Int = 800, maxHp: Int = 1000,
        physAtk: Int = 100, magAtk: Int = 80,
        physDef: Int = 60, magDef: Int = 50,
        realm: Int = 3, realmLayer: Int = 1,
        buffs: List<CombatBuff> = emptyList()
    ) = Combatant(
        id = id, name = id, side = CombatantSide.DEFENDER,
        hp = hp, maxHp = maxHp, mp = 500, maxMp = 500,
        physicalAttack = physAtk, magicAttack = magAtk,
        physicalDefense = physDef, magicDefense = magDef,
        speed = 100, critRate = 0.0,
        skills = emptyList(), buffs = buffs,
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
}
