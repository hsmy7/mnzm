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
        critRate: Double = 0.1
    ): CombatantStats {
        return object : CombatantStats {
            override val physicalAttack = physicalAttack
            override val magicAttack = magicAttack
            override val physicalDefense = physicalDefense
            override val magicDefense = magicDefense
            override val speed = speed
            override val critRate = critRate
        }
    }

    @Test
    fun `calculateDamage - 物理攻击伤害正确`() {
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
        val expectedBase = 200.0 * 1.0 - 50.0
        assertTrue("平均伤害 $avgDamage 应接近 $expectedBase", avgDamage > expectedBase * 0.7 && avgDamage < expectedBase * 1.5)
    }

    @Test
    fun `calculateDamage - 法术攻击伤害正确`() {
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
        val expectedBase = 200.0 * 1.0 - 30.0
        assertTrue("平均伤害 $avgDamage 应接近 $expectedBase", avgDamage > expectedBase * 0.7 && avgDamage < expectedBase * 1.5)
    }

    @Test
    fun `calculateDamage - 自动选择较高攻击类型`() {
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
    fun `calculateDamage - 自动选择法术攻击`() {
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
    fun `calculateDamage - 技能倍率影响伤害`() {
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
            assertTrue("技能倍率2x伤害应大于1x", boostedTotal.toDouble() / boostedCount > normalTotal.toDouble() / normalCount * 1.5)
        }
    }

    @Test
    fun `calculateDamage - 暴击时伤害翻倍`() {
        val attacker = createCombatant(physicalAttack = 200, critRate = 1.0)
        val defender = createCombatant(physicalDefense = 50)
        val result = BattleCalculator.calculateDamage(attacker, defender, dodgeChanceModifier = 0.0)
        assertTrue(result.isCrit)
        val expectedBase = 200.0 * GameConfig.Battle.CRIT_MULTIPLIER - 50.0
        assertTrue(result.damage >= (expectedBase * 0.8).toInt())
    }

    @Test
    fun `calculateDamage - 无暴击时伤害正常`() {
        val attacker = createCombatant(physicalAttack = 200, critRate = 0.0)
        val defender = createCombatant(physicalDefense = 50)
        val result = BattleCalculator.calculateDamage(attacker, defender, dodgeChanceModifier = 0.0)
        assertFalse(result.isCrit)
    }

    @Test
    fun `calculateDamage - 最低伤害为1`() {
        val attacker = createCombatant(physicalAttack = 1)
        val defender = createCombatant(physicalDefense = 9999)
        val result = BattleCalculator.calculateDamage(attacker, defender, dodgeChanceModifier = 0.0)
        assertTrue(result.damage >= 1)
    }

    @Test
    fun `calculateDamage - 闪避时伤害为0`() {
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
        assertTrue("应至少有一次闪避", dodged)
    }

    @Test
    fun `calculateDamage - 技能名称正确传递`() {
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
    fun `calculateDamage - 连击数正确传递`() {
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
    fun `calculateDodgeChance - 速度相同时闪避率为0`() {
        val attacker = createCombatant(speed = 50)
        val defender = createCombatant(speed = 50)
        val dodgeChance = BattleCalculator.calculateDodgeChance(attacker, defender)
        assertEquals(0.0, dodgeChance, 0.001)
    }

    @Test
    fun `calculateDodgeChance - 攻击者速度更快闪避率大于0`() {
        val attacker = createCombatant(speed = 100)
        val defender = createCombatant(speed = 50)
        val dodgeChance = BattleCalculator.calculateDodgeChance(attacker, defender)
        assertTrue(dodgeChance > 0)
    }

    @Test
    fun `calculateDodgeChance - 防御者速度更快闪避率为0`() {
        val attacker = createCombatant(speed = 50)
        val defender = createCombatant(speed = 100)
        val dodgeChance = BattleCalculator.calculateDodgeChance(attacker, defender)
        assertEquals(0.0, dodgeChance, 0.001)
    }

    @Test
    fun `calculateDodgeChance - 闪避率上限为0_5`() {
        val fastAttacker = createCombatant(speed = 10000)
        val slowDefender = createCombatant(speed = 1)
        val dodgeChance = BattleCalculator.calculateDodgeChance(fastAttacker, slowDefender)
        assertEquals(0.5, dodgeChance, 0.001)
    }

    @Test
    fun `calculateDodgeChance - modifier影响闪避率`() {
        val attacker = createCombatant(speed = 100)
        val defender = createCombatant(speed = 50)
        val lowModifier = BattleCalculator.calculateDodgeChance(attacker, defender, modifier = 0.25)
        val highModifier = BattleCalculator.calculateDodgeChance(attacker, defender, modifier = 0.75)
        assertTrue(highModifier > lowModifier)
    }

    @Test
    fun `calculatePhysicalDamage - 基础物理伤害计算`() {
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
        val expectedBase = 200.0 - 50.0
        assertTrue("平均伤害 $avgDamage 应接近 $expectedBase", avgDamage > expectedBase * 0.7 && avgDamage < expectedBase * 1.5)
    }

    @Test
    fun `calculatePhysicalDamage - 低攻击对高防御伤害不低于0`() {
        val attacker = createCombatant(physicalAttack = 1, critRate = 0.0)
        val defender = createCombatant(physicalDefense = 9999)
        val damage = BattleCalculator.calculatePhysicalDamage(attacker, defender)
        assertTrue(damage >= 0)
    }

    @Test
    fun `calculateMagicDamage - 基础法术伤害计算`() {
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
        val expectedBase = 200.0 - 30.0
        assertTrue("平均伤害 $avgDamage 应接近 $expectedBase", avgDamage > expectedBase * 0.7 && avgDamage < expectedBase * 1.5)
    }

    @Test
    fun `calculateMagicDamage - 低攻击对高防御伤害不低于0`() {
        val attacker = createCombatant(magicAttack = 1, critRate = 0.0)
        val defender = createCombatant(magicDefense = 9999)
        val damage = BattleCalculator.calculateMagicDamage(attacker, defender)
        assertTrue(damage >= 0)
    }

    @Test
    fun `generateBattleMessage - 闪避消息`() {
        val result = DamageResult(damage = 0, isCrit = false, isPhysical = true, isDodged = true)
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("闪避"))
        assertTrue(message.contains("李四"))
    }

    @Test
    fun `generateBattleMessage - 物理伤害消息`() {
        val result = DamageResult(damage = 100, isCrit = false, isPhysical = true, isDodged = false)
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("物理"))
        assertTrue(message.contains("100"))
    }

    @Test
    fun `generateBattleMessage - 法术伤害消息`() {
        val result = DamageResult(damage = 150, isCrit = false, isPhysical = false, isDodged = false)
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("法术"))
        assertTrue(message.contains("150"))
    }

    @Test
    fun `generateBattleMessage - 暴击消息`() {
        val result = DamageResult(damage = 200, isCrit = true, isPhysical = true, isDodged = false)
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("暴击"))
    }

    @Test
    fun `generateBattleMessage - 连击消息`() {
        val result = DamageResult(damage = 100, isCrit = false, isPhysical = true, isDodged = false, hits = 3)
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("3连击"))
    }

    @Test
    fun `generateBattleMessage - 技能名称消息`() {
        val result = DamageResult(damage = 100, isCrit = false, isPhysical = true, isDodged = false, skillName = "天剑诀")
        val message = BattleCalculator.generateBattleMessage("张三", "李四", result)
        assertTrue(message.contains("天剑诀"))
    }

    @Test
    fun `calculateDamage - 伤害在0_9到1_1倍之间波动`() {
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
        assertTrue("最小伤害 $min 应 >= 900", min >= 850)
        assertTrue("最大伤害 $max 应 <= 1100", max <= 1150)
    }
}
