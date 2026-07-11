package com.xianxia.sect.ui.game.dialogs.heavenlytrial

import com.xianxia.sect.core.HealType
import com.xianxia.sect.core.BuffType
import com.xianxia.sect.core.DamageType
import com.xianxia.sect.core.model.CombatSkill
import com.xianxia.sect.core.engine.domain.battle.Combatant
import com.xianxia.sect.core.engine.domain.battle.CombatBuff
import org.junit.Assert.*
import org.junit.Test

class HeavenlyTrialCombatLogicTest {

    // ═══════════════════════════════════════════
    // applyBuffToTarget — healPercent and healFixed
    // ═══════════════════════════════════════════

    @Test
    fun `applyBuffToTarget - healPercent only`() {
        val target = combatant(hp = 50, maxHp = 100)
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healPercent = 0.3, healType = HealType.HP
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals("HP should increase by 30% of max: 50+30=80", 80, result.hp)
        assertEquals(100, result.maxHp)
        assertEquals(50, result.mp)
    }

    @Test
    fun `applyBuffToTarget - healFixed only`() {
        val target = combatant(hp = 50, maxHp = 100)
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healFixed = 25, healType = HealType.HP
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals("HP should increase by fix 25: 50+25=75", 75, result.hp)
    }

    @Test
    fun `applyBuffToTarget - healPercent and healFixed combined`() {
        val target = combatant(hp = 30, maxHp = 100)
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healPercent = 0.2, healFixed = 15, healType = HealType.HP
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals("HP should increase by 20% (20) + fixed (15) = 35: 30+35=65",
            65, result.hp)
    }

    @Test
    fun `applyBuffToTarget - both zero does nothing`() {
        val target = combatant(hp = 50, maxHp = 100)
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healPercent = 0.0, healFixed = 0, healType = HealType.HP
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals(50, result.hp)
    }

    @Test
    fun `applyBuffToTarget - MP recovery`() {
        val target = combatant(hp = 100, maxHp = 100, mp = 20, maxMp = 100)
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healPercent = 0.5, healFixed = 10, healType = HealType.MP
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals("HP unchanged", 100, result.hp)
        assertEquals("MP 20+50+10=80", 80, result.mp)
    }

    @Test
    fun `applyBuffToTarget - HP clamped at maxHp`() {
        val target = combatant(hp = 95, maxHp = 100)
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healPercent = 0.5, healFixed = 20, healType = HealType.HP
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals(100, result.hp)
    }

    @Test
    fun `applyBuffToTarget - dead target returns unchanged`() {
        val target = combatant(hp = 0, maxHp = 100)
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healPercent = 0.5, healFixed = 20, healType = HealType.HP
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals("Dead target not healed", 0, result.hp)
    }

    @Test
    fun `applyBuffToTarget - full HP stays full`() {
        val target = combatant(hp = 100, maxHp = 100)
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healPercent = 0.3, healFixed = 10, healType = HealType.HP
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals(100, result.hp)
    }

    // ═══════════════════════════════════════════
    // applyBuffToTarget — buffs
    // ═══════════════════════════════════════════

    @Test
    fun `applyBuffToTarget - single legacy buffType applied`() {
        val target = combatant()
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            buffType = BuffType.PHYSICAL_DEFENSE_BOOST, buffValue = 0.2, buffDuration = 3
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals(1, result.buffs.size)
        assertEquals(BuffType.PHYSICAL_DEFENSE_BOOST, result.buffs[0].type)
        assertEquals(0.2, result.buffs[0].value, 0.001)
        assertEquals(3, result.buffs[0].remainingDuration)
    }

    @Test
    fun `applyBuffToTarget - buffs list applied`() {
        val target = combatant()
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            buffs = listOf(
                Triple(BuffType.PHYSICAL_ATTACK_BOOST, 0.3, 2),
                Triple(BuffType.SPEED_BOOST, 0.5, 1)
            )
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals(2, result.buffs.size)
        assertEquals(BuffType.PHYSICAL_ATTACK_BOOST, result.buffs[0].type)
        assertEquals(BuffType.SPEED_BOOST, result.buffs[1].type)
    }

    @Test
    fun `applyBuffToTarget - both legacy and list buffs applied`() {
        val target = combatant()
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            buffType = BuffType.PHYSICAL_DEFENSE_BOOST, buffValue = 0.2, buffDuration = 3,
            buffs = listOf(Triple(BuffType.PHYSICAL_ATTACK_BOOST, 0.3, 2))
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals(2, result.buffs.size)
        assertEquals(BuffType.PHYSICAL_DEFENSE_BOOST, result.buffs[0].type)
        assertEquals(BuffType.PHYSICAL_ATTACK_BOOST, result.buffs[1].type)
    }

    @Test
    fun `applyBuffToTarget - empty buffs list adds no extra buffs`() {
        val target = combatant()
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            buffs = emptyList()
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals(0, result.buffs.size)
    }

    @Test
    fun `applyBuffToTarget - existing buffs preserved`() {
        val existingBuff = CombatBuff(BuffType.PHYSICAL_DEFENSE_BOOST, 0.1, 5)
        val target = combatant().copy(buffs = listOf(existingBuff))
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            buffType = BuffType.PHYSICAL_ATTACK_BOOST, buffValue = 0.2, buffDuration = 3
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals(2, result.buffs.size)
        assertEquals(BuffType.PHYSICAL_DEFENSE_BOOST, result.buffs[0].type)
        assertEquals(BuffType.PHYSICAL_ATTACK_BOOST, result.buffs[1].type)
    }

    @Test
    fun `applyBuffToTarget - same buff type replaces existing`() {
        val existing = CombatBuff(BuffType.PHYSICAL_DEFENSE_BOOST, 0.1, 5)
        val target = combatant().copy(buffs = listOf(existing))
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            buffType = BuffType.PHYSICAL_DEFENSE_BOOST, buffValue = 0.3, buffDuration = 2
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals("Same type buff replaces, not stacks", 1, result.buffs.size)
        assertEquals(BuffType.PHYSICAL_DEFENSE_BOOST, result.buffs[0].type)
        assertEquals("Value updated", 0.3, result.buffs[0].value, 0.001)
        assertEquals("Duration replaced", 2, result.buffs[0].remainingDuration)
    }

    @Test
    fun `applyBuffToTarget - negative healPercent treated as 0`() {
        val target = combatant(hp = 50, maxHp = 100)
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healPercent = -0.5, healFixed = 20, healType = HealType.HP
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals("Negative percent is clamped to 0, only fixed applies: 50+20=70",
            70, result.hp)
    }

    @Test
    fun `applyBuffToTarget - negative healFixed treated as 0`() {
        val target = combatant(hp = 50, maxHp = 100)
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healPercent = 0.3, healFixed = -99, healType = HealType.HP
        )
        val result = applyBuffToTarget(target, skill)
        assertEquals("Negative fixed is clamped to 0, only percent applies: 50+30=80",
            80, result.hp)
    }

    @Test
    fun `applyBuffToTarget - effectiveMaxHp used with HP_BOOST`() {
        val hpBuff = CombatBuff(BuffType.HP_BOOST, 0.5, 10) // +50% maxHp
        val target = combatant(hp = 100, maxHp = 100).copy(buffs = listOf(hpBuff))
        // effectiveMaxHp = 100 * (1 + 0.5) = 150
        val skill = CombatSkill(
            name = "test", damageType = DamageType.PHYSICAL,
            damageMultiplier = 0.0, mpCost = 0, cooldown = 0,
            healPercent = 0.5, healType = HealType.HP
        )
        val result = applyBuffToTarget(target, skill)
        // healing = effectiveMaxHp * 0.5 = 75, clamped to 150
        assertEquals("Healed by 50% of effectiveMaxHp (150): 100+75=150", 150, result.hp)
    }

    // ═══════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════

    private fun combatant(
        hp: Int = 100, maxHp: Int = 100,
        mp: Int = 50, maxMp: Int = 100
    ): Combatant = Combatant(
        id = "test-1",
        name = "Test",
        hp = hp, maxHp = maxHp,
        mp = mp, maxMp = maxMp,
        physicalAttack = 10, magicAttack = 10,
        physicalDefense = 5, magicDefense = 5,
        speed = 100, critRate = 0.05,
        skills = emptyList(), buffs = emptyList(),
        realm = 1, realmName = "练气"
    )
}
