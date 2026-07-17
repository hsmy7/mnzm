package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SectCombatPowerCalculatorTest {

    @Test
    fun `calculateDiscipleCombatPower - both attacks counted`() {
        val stats = DiscipleStats(
            maxHp = 1000,
            physicalAttack = 200,
            magicAttack = 100,
            physicalDefense = 50,
            magicDefense = 30,
            speed = 80
        )
        val result = SectCombatPowerCalculator.calculateDiscipleCombatPower(stats)
        // (200+100)*5 + 1000*4 + (50+30)*3 + 80*2
        assertEquals((200 + 100) * 5L + 1000 * 4L + (50 + 30) * 3L + 80 * 2L, result)
    }

    @Test
    fun `calculateDiscipleCombatPower - zero stats`() {
        val stats = DiscipleStats()
        val result = SectCombatPowerCalculator.calculateDiscipleCombatPower(stats)
        assertEquals(0L, result)
    }

    @Test
    fun `calculateDisciplePower - player and AI get same formula`() {
        val disciple = Disciple(name = "Test", realm = 5, realmLayer = 3)
        val aggregate = disciple.toAggregate()
        val power = SectCombatPowerCalculator.calculateDisciplePower(aggregate)
        // 玩家和 AI 使用同一公式，不需要 *3
        val baseStats = DiscipleStatCalculator.getPermanentBaseStats(aggregate)
        val expected = SectCombatPowerCalculator.calculateDiscipleCombatPower(baseStats)
        assertEquals(expected, power)
    }

    @Test
    fun `calculateDisciplePower - includes blood refinement zone`() {
        val brPct = BloodRefinementPctTotal(
            discipleId = "d1",
            hpBonusPct = 0.10,         // +10% HP
            physicalAttackBonusPct = 0.20,  // +20% 物攻
            magicAttackBonusPct = 0.15,     // +15% 法攻
            physicalDefenseBonusPct = 0.05, // +5% 物防
            magicDefenseBonusPct = 0.05,    // +5% 法防
            speedBonusPct = 0.10            // +10% 速度
        )
        val disciple = Disciple(name = "A", realm = 5, realmLayer = 3)
        val aggregate = disciple.toAggregate()

        val powerWithBr = SectCombatPowerCalculator.calculateDisciplePower(aggregate, brPct)
        val powerWithoutBr = SectCombatPowerCalculator.calculateDisciplePower(aggregate)

        // 血炼乘区应增加战力
        assertNotEquals("血炼乘区应影响战力", powerWithoutBr, powerWithBr)
        assertEquals("有血炼时战力应更高", true, powerWithBr > powerWithoutBr)
    }

    @Test
    fun `calculateDisciplePower - excludes equipment and manuals`() {
        // 装备/功法不影响战力——它们不应参与计算
        val disciple = Disciple(name = "Test", realm = 5, realmLayer = 3)
        val aggregate = disciple.toAggregate()
        val power = SectCombatPowerCalculator.calculateDisciplePower(aggregate)

        // 战力值应为 正数（基于境界基础属性）
        assertEquals("境界5层3的弟子应有战力", true, power > 0)
    }

    @Test
    fun `computeFingerprint - same disciple same fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val a1 = d1.toAggregate()
        val a2 = d1.toAggregate()
        val fp1 = SectCombatPowerCalculator.computeFingerprint(a1)
        val fp2 = SectCombatPowerCalculator.computeFingerprint(a2)
        assertEquals(fp1, fp2)
    }

    @Test
    fun `computeFingerprint - different realm different fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val d2 = Disciple(name = "B", realm = 6, realmLayer = 3)
        val fp1 = SectCombatPowerCalculator.computeFingerprint(d1.toAggregate())
        val fp2 = SectCombatPowerCalculator.computeFingerprint(d2.toAggregate())
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `computeFingerprint - different blood refinement different fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val br1 = BloodRefinementPctTotal(discipleId = "d1", hpBonusPct = 0.10)
        val br2 = BloodRefinementPctTotal(discipleId = "d1", hpBonusPct = 0.20)

        val fp1 = SectCombatPowerCalculator.computeFingerprint(d1.toAggregate(), br1)
        val fp2 = SectCombatPowerCalculator.computeFingerprint(d1.toAggregate(), br2)
        assertNotEquals("不同血炼百分比应产生不同指纹", fp1, fp2)
    }

    @Test
    fun `computeFingerprint - weapon no longer affects fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val d2 = Disciple(name = "B", realm = 5, realmLayer = 3)
        // 两个弟子境界、层数、方差、天赋相同 → 指纹相同
        // 即使他们可能有不同装备（装备不影响战力指纹）
        val fp1 = SectCombatPowerCalculator.computeFingerprint(d1.toAggregate())
        val fp2 = SectCombatPowerCalculator.computeFingerprint(d2.toAggregate())
        assertEquals("相同境界/层数的弟子指纹应相同（装备不影响）", fp1, fp2)
    }
}