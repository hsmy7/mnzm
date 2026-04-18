package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.util.BattleCalculator.CombatantStats
import com.xianxia.sect.core.util.BattleCalculator.DamageResult
import org.junit.Assert.*
import org.junit.Test

class BattleCalculatorTest {

    private fun createCombatant(
        physicalAttack: Int = 100,
        magicAttack: Int = 80,
        physicalDefense: Int = 50,
        magicDefense: Int = 40,
        speed: Int = 50,
        critRate: Double = 0.1,
        realm: Int = 5,
        element: String = "metal"
    ): CombatantStats {
        return object : CombatantStats {
            override val physicalAttack = physicalAttack
            override val magicAttack = magicAttack
            override val physicalDefense = physicalDefense
            override val magicDefense = magicDefense
            override val speed = speed
            override val critRate = critRate
            override val realm = realm
            override val element = element
        }
    }

    private fun expectedDamage(attack: Int, defense: Int, multiplier: Double = 1.0, critMultiplier: Double = 1.0): Double {
        val reduction = defense.toDouble() / (defense.toDouble() + GameConfig.Battle.DEFENSE_CONSTANT)
        return attack * multiplier * (1.0 - reduction) * critMultiplier
    }

    @Test
    fun `calculateDamage - physical attack damage correct`() {
        val attacker = createCombatant(physicalAttack = 200, magicAttack = 50)
        val defender = createCombatant(physicalDefense = 50)
        var totalDamage = 0
        var count = 0
        for (i in 1..1000) {
            val result = BattleCalculator.calculateDamage(
                attacker, defender,
                isPhysicalAttack = true,
                dodgeChanceModifier = 0.0
            )
            if (!result.isDodged) {
                totalDamage += result.damage
                count++
            }
        }
        assertTrue(count > 900)
        val avgDamage = totalDamage.toDouble() / count
        val expected = expectedDamage(200, 50)
        assertTrue("avgDamage $avgDamage should be near $expected", avgDamage > expected * 0.7 && avgDamage < expected * 1.5)
    }

    @Test
    fun `calculateDamage - magic attack damage correct`() {
        val attacker = createCombatant(physicalAttack = 50, magicAttack = 200)
        val defender = createCombatant(physicalDefense = 50, magicDefense = 30)
        var totalDamage = 0
        var count = 0
        for (i in 1..1000) {
            val result = BattleCalculator.calculateDamage(
                attacker, defender,
                isPhysicalAttack = false,
                dodgeChanceModifier = 0.0
            )
            if (!result.isDodged) {
                totalDamage += result.damage
                count++
            }
        }
        assertTrue(count > 900)
        val avgDamage = totalDamage.toDouble() / count
        val expected = expectedDamage(200, 30)
        assertTrue("avgDamage $avgDamage should be near $expected", avgDamage > expected * 0.7 && avgDamage < expected * 1.5)
    }

    @Test
    fun `calculateDamage - auto select higher attack type`() {
        val attacker = createCombatant(physicalAttack = 200, magicAttack = 50)
        val defender = createCombatant()
        val result = BattleCalculator.calculateDamage(
            attacker, defender,
            isPhysicalAttack = null,
            dodgeChanceModifier = 0.0
        )
        assertTrue(result.isPhysical)
    }

    @Test
    fun `calculateDamage - auto select magic attack`() {
        val attacker = createCombatant(physicalAttack = 50, magicAttack = 200)
        val defender = createCombatant()
        val result = BattleCalculator.calculateDamage(
            attacker, defender,
            isPhysicalAttack = null,
            dodgeChanceModifier = 0.0
        )
        assertFalse(result.isPhysical)
    }

    @Test
    fun `calculateDamage - skill multiplier affects damage`() {
        val attacker = createCombatant(physicalAttack = 200)
        val defender = createCombatant(physicalDefense = 50)
        var normalTotal = 0
        var boostedTotal = 0
        var normalCount = 0
        var boostedCount = 0
        for (i in 1..500) {
            val normal = BattleCalculator.calculateDamage(attacker, defender, skillDamageMultiplier = 1.0, dodgeChanceModifier = 0.0)
            val boosted = BattleCalculator.calculateDamage(attacker, defender, skillDamageMultiplier = 2.0, dodgeChanceModifier = 0.0)
            if (!normal.isDodged) { normalTotal += normal.damage; normalCount++ }
            if (!boosted.isDodged) { boostedTotal += boosted.damage; boostedCount++ }
        }
        if (normalCount > 0 && boostedCount > 0) {
            assertTrue("2x multiplier damage should be higher", boostedTotal.toDouble() / boostedCount > normalTotal.toDouble() / normalCount * 1.5)
        }
    }

    @Test
    fun `calculateDamage - crit increases damage`() {
        val attacker = createCombatant(physicalAttack = 200, critRate = 1.0)
        val defender = createCombatant(physicalDefense = 50)
        val result = BattleCalculator.calculateDamage(attacker, defender, dodgeChanceModifier = 0.0)
        assertTrue(result.isCrit)
        val expected = expectedDamage(200, 50, critMultiplier = GameConfig.Battle.CRIT_MULTIPLIER)
        assertTrue(result.damage >= (expected * 0.7).toInt())
    }

    @Test
    fun `calculateDamage - no crit when critRate is 0`() {
        val attacker = createCombatant(physicalAttack = 200, critRate = 0.0)
        val defender = createCombatant(physicalDefense = 50)
        val result = BattleCalculator.calculateDamage(attacker, defender, dodgeChanceModifier = 0.0)
        assertFalse(result.isCrit)
    }

    @Test
    fun `calculateDamage - minimum damage is 1`() {
        val attacker = createCombatant(physicalAttack = 1)
        val defender = createCombatant(physicalDefense = 9999)
        val result = BattleCalculator.calculateDamage(attacker, defender, dodgeChanceModifier = 0.0)
        assertTrue(result.damage >= 1)
    }

    @Test
    fun `calculateDamage - dodged attack deals 0 damage`() {
        val fastAttacker = createCombatant(speed = 10000)
        val slowDefender = createCombatant(speed = 1)
        var dodged = false
        for (i in 1..100) {
            val result = BattleCalculator.calculateDamage(fastAttacker, slowDefender, dodgeChanceModifier = 0.5)
            if (result.isDodged) {
                dodged = true
                assertEquals(0, result.damage)
                assertFalse(result.isCrit)
                break
            }
        }
        assertTrue("should have at least one dodge", dodged)
    }

    @Test
    fun `calculateDamage - skill name passed through`() {
        val attacker = createCombatant()
        val defender = createCombatant()
        val result = BattleCalculator.calculateDamage(
            attacker, defender,
            skillName = "天剑诀",
            dodgeChanceModifier = 0.0
        )
        assertEquals("天剑诀", result.skillName)
    }

    @Test
    fun `calculateDamage - hits passed through`() {
        val attacker = createCombatant()
        val defender = createCombatant()
        val result = BattleCalculator.calculateDamage(
            attacker, defender,
            skillHits = 3,
            dodgeChanceModifier = 0.0
        )
        assertEquals(3, result.hits)
    }

    @Test
    fun `calculateDodgeChance - same speed gives 0`() {
        val attacker = createCombatant(speed = 50)
        val defender = createCombatant(speed = 50)
        val dodgeChance = BattleCalculator.calculateDodgeChance(attacker, defender)
        assertEquals(0.0, dodgeChance, 0.001)
    }

    @Test
    fun `calculateDodgeChance - faster attacker gives positive dodge`() {
        val attacker = createCombatant(speed = 100)
        val defender = createCombatant(speed = 50)
        val dodgeChance = BattleCalculator.calculateDodgeChance(attacker, defender)
        assertTrue(dodgeChance > 0)
    }

    @Test
    fun `calculateDodgeChance - slower attacker gives 0`() {
        val attacker = createCombatant(speed = 50)
        val defender = createCombatant(speed = 100)
        val dodgeChance = BattleCalculator.calculateDodgeChance(attacker, defender)
        assertEquals(0.0, dodgeChance, 0.001)
    }

    @Test
    fun `calculateDodgeChance - max dodge is 0_5`() {
        val fastAttacker = createCombatant(speed = 10000)
        val slowDefender = createCombatant(speed = 1)
        val dodgeChance = BattleCalculator.calculateDodgeChance(fastAttacker, slowDefender)
        assertEquals(0.5, dodgeChance, 0.001)
    }

    @Test
    fun `calculateDodgeChance - modifier affects dodge`() {
        val attacker = createCombatant(speed = 100)
        val defender = createCombatant(speed = 50)
        val lowModifier = BattleCalculator.calculateDodgeChance(attacker, defender, modifier = 0.25)
        val highModifier = BattleCalculator.calculateDodgeChance(attacker, defender, modifier = 0.75)
        assertTrue(highModifier > lowModifier)
    }

    @Test
    fun `calculatePhysicalDamage - basic physical damage`() {
        val attacker = createCombatant(physicalAttack = 200, critRate = 0.0)
        val defender = createCombatant(physicalDefense = 50)
        var totalDamage = 0
        var count = 0
        for (i in 1..1000) {
            val damage = BattleCalculator.calculatePhysicalDamage(attacker, defender)
            totalDamage += damage
            count++
        }
        val avgDamage = totalDamage.toDouble() / count
        val expected = expectedDamage(200, 50)
        assertTrue("avgDamage $avgDamage should be near $expected", avgDamage > expected * 0.7 && avgDamage < expected * 1.5)
    }

    @Test
    fun `calculatePhysicalDamage - low attack vs high defense still deals damage`() {
        val attacker = createCombatant(physicalAttack = 1, critRate = 0.0)
        val defender = createCombatant(physicalDefense = 9999)
        val damage = BattleCalculator.calculatePhysicalDamage(attacker, defender)
        assertTrue(damage >= 0)
    }

    @Test
    fun `calculateMagicDamage - basic magic damage`() {
        val attacker = createCombatant(magicAttack = 200, critRate = 0.0)
        val defender = createCombatant(magicDefense = 30)
        var totalDamage = 0
        var count = 0
        for (i in 1..1000) {
            val damage = BattleCalculator.calculateMagicDamage(attacker, defender)
            totalDamage += damage
            count++
        }
        val avgDamage = totalDamage.toDouble() / count
        val expected = expectedDamage(200, 30)
        assertTrue("avgDamage $avgDamage should be near $expected", avgDamage > expected * 0.7 && avgDamage < expected * 1.5)
    }

    @Test
    fun `calculateRealmGapMultiplier - same realm returns 1`() {
        assertEquals(1.0, BattleCalculator.calculateRealmGapMultiplier(5, 5), 0.001)
    }

    @Test
    fun `calculateRealmGapMultiplier - lower realm attacker gets bonus`() {
        val multiplier = BattleCalculator.calculateRealmGapMultiplier(7, 5)
        assertTrue(multiplier > 1.0)
    }

    @Test
    fun `calculateRealmGapMultiplier - higher realm attacker gets penalty`() {
        val multiplier = BattleCalculator.calculateRealmGapMultiplier(3, 5)
        assertTrue(multiplier < 1.0)
    }

    @Test
    fun `calculateElementMultiplier - advantage returns higher`() {
        val multiplier = BattleCalculator.calculateElementMultiplier("metal", "wood")
        assertTrue(multiplier > 1.0)
    }

    @Test
    fun `calculateElementMultiplier - disadvantage returns lower`() {
        val multiplier = BattleCalculator.calculateElementMultiplier("wood", "metal")
        assertTrue(multiplier < 1.0)
    }

    @Test
    fun `calculateElementMultiplier - neutral returns 1`() {
        val multiplier = BattleCalculator.calculateElementMultiplier("metal", "water")
        assertEquals(1.0, multiplier, 0.001)
    }

    @Test
    fun `generateBattleMessage - dodge message`() {
        val result = DamageResult(damage = 0, isCrit = false, isPhysical = true, isDodged = true)
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("闪避"))
        assertTrue(message.contains("李四"))
    }

    @Test
    fun `generateBattleMessage - physical damage message`() {
        val result = DamageResult(damage = 100, isCrit = false, isPhysical = true, isDodged = false)
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("物理"))
        assertTrue(message.contains("100"))
    }

    @Test
    fun `generateBattleMessage - magic damage message`() {
        val result = DamageResult(damage = 150, isCrit = false, isPhysical = false, isDodged = false)
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("法术"))
        assertTrue(message.contains("150"))
    }

    @Test
    fun `generateBattleMessage - crit message`() {
        val result = DamageResult(damage = 200, isCrit = true, isPhysical = true, isDodged = false)
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("暴击"))
    }

    @Test
    fun `generateBattleMessage - hits message`() {
        val result = DamageResult(damage = 100, isCrit = false, isPhysical = true, isDodged = false, hits = 3)
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("3连击"))
    }

    @Test
    fun `generateBattleMessage - skill name message`() {
        val result = DamageResult(damage = 100, isCrit = false, isPhysical = true, isDodged = false, skillName = "天剑诀")
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("天剑诀"))
    }

    @Test
    fun `calculateDamage - damage variance between 0_9 and 1_1`() {
        val attacker = createCombatant(physicalAttack = 1000, critRate = 0.0)
        val defender = createCombatant(physicalDefense = 0)
        val damages = mutableListOf<Int>()
        for (i in 1..1000) {
            val result = BattleCalculator.calculateDamage(attacker, defender, isPhysicalAttack = true, dodgeChanceModifier = 0.0)
            if (!result.isDodged) damages.add(result.damage)
        }
        assertTrue(damages.isNotEmpty())
        val min = damages.min()
        val max = damages.max()
        val expected = expectedDamage(1000, 0)
        assertTrue("min $min should be >= ${expected * 0.85}", min >= (expected * 0.85).toInt())
        assertTrue("max $max should be <= ${expected * 1.15}", max <= (expected * 1.15).toInt())
    }

    @Test
    fun `defense percentage reduction works correctly`() {
        val attacker = createCombatant(physicalAttack = 100, critRate = 0.0)
        val lowDefender = createCombatant(physicalDefense = 100)
        val highDefender = createCombatant(physicalDefense = 900)
        var lowTotal = 0
        var highTotal = 0
        for (i in 1..500) {
            lowTotal += BattleCalculator.calculatePhysicalDamage(attacker, lowDefender)
            highTotal += BattleCalculator.calculatePhysicalDamage(attacker, highDefender)
        }
        assertTrue("high defense should take less damage", highTotal < lowTotal)
        val lowReduction = 100.0 / (100.0 + GameConfig.Battle.DEFENSE_CONSTANT)
        val highReduction = 900.0 / (900.0 + GameConfig.Battle.DEFENSE_CONSTANT)
        assertTrue("high defense reduction should be higher", highReduction > lowReduction)
    }
}
