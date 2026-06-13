package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.DiscipleStats
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import org.junit.Assert.assertEquals
import org.junit.Test

class SectCombatPowerCalculatorTest {

    @Test
    fun `calculateDiscipleCombatPower - physical attack higher`() {
        val stats = DiscipleStats(
            maxHp = 1000,
            physicalAttack = 200,
            magicAttack = 100,
            physicalDefense = 50,
            magicDefense = 30,
            speed = 80
        )
        val result = SectCombatPowerCalculator.calculateDiscipleCombatPower(stats)
        assertEquals(200 * 5L + 1000 * 2L + (50 + 30) * 3L + 80 * 2L, result)
    }

    @Test
    fun `calculateDiscipleCombatPower - magic attack higher`() {
        val stats = DiscipleStats(
            maxHp = 500,
            physicalAttack = 30,
            magicAttack = 150,
            physicalDefense = 40,
            magicDefense = 60,
            speed = 50
        )
        val result = SectCombatPowerCalculator.calculateDiscipleCombatPower(stats)
        assertEquals(150 * 5L + 500 * 2L + (40 + 60) * 3L + 50 * 2L, result)
    }

    @Test
    fun `calculateDiscipleCombatPower - zero stats`() {
        val stats = DiscipleStats()
        val result = SectCombatPowerCalculator.calculateDiscipleCombatPower(stats)
        assertEquals(0L, result)
    }

    @Test
    fun `calculateAIDisciplePower - base stats times 3`() {
        val disciple = Disciple(
            name = "AI",
            realm = 5,
            realmLayer = 3
        )
        val aggregate = disciple.toAggregate()
        val baseStats = DiscipleStatCalculator.getBaseStats(aggregate)
        val basePower = SectCombatPowerCalculator.calculateDiscipleCombatPower(baseStats)
        val aiPower = SectCombatPowerCalculator.calculateAIDisciplePower(aggregate)
        assertEquals(basePower * 3, aiPower)
    }

    @Test
    fun `computePlayerFingerprint - same disciple produces same fingerprint`() {
        val disciple = Disciple(
            name = "Test",
            realm = 5,
            realmLayer = 3
        )
        val fp1 = SectCombatPowerCalculator.computePlayerFingerprint(disciple.toAggregate())
        val fp2 = SectCombatPowerCalculator.computePlayerFingerprint(disciple.toAggregate())
        assertEquals(fp1, fp2)
    }

    @Test
    fun `computePlayerFingerprint - different realm produces different fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val d2 = Disciple(name = "B", realm = 6, realmLayer = 3)
        val fp1 = SectCombatPowerCalculator.computePlayerFingerprint(d1.toAggregate())
        val fp2 = SectCombatPowerCalculator.computePlayerFingerprint(d2.toAggregate())
        assert(fp1 != fp2)
    }

    @Test
    fun `computePlayerFingerprint - different weapon produces different fingerprint`() {
        val d1 = Disciple(name = "A").copy(equipment = EquipmentSet(weaponId = "sword1"))
        val d2 = Disciple(name = "B").copy(equipment = EquipmentSet(weaponId = "sword2"))
        val fp1 = SectCombatPowerCalculator.computePlayerFingerprint(d1.toAggregate())
        val fp2 = SectCombatPowerCalculator.computePlayerFingerprint(d2.toAggregate())
        assert(fp1 != fp2)
    }

    @Test
    fun `computeAIFingerprint - same realm layer and talents produces same fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val d2 = Disciple(name = "B", realm = 5, realmLayer = 3)
        val fp1 = SectCombatPowerCalculator.computeAIFingerprint(d1.toAggregate())
        val fp2 = SectCombatPowerCalculator.computeAIFingerprint(d2.toAggregate())
        assertEquals(fp1, fp2)
    }

    @Test
    fun `computeAIFingerprint - different realm produces different fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val d2 = Disciple(name = "B", realm = 6, realmLayer = 3)
        val fp1 = SectCombatPowerCalculator.computeAIFingerprint(d1.toAggregate())
        val fp2 = SectCombatPowerCalculator.computeAIFingerprint(d2.toAggregate())
        assert(fp1 != fp2)
    }

    @Test
    fun `computeAIFingerprint - different layer produces different fingerprint`() {
        val d1 = Disciple(name = "A", realm = 5, realmLayer = 3)
        val d2 = Disciple(name = "B", realm = 5, realmLayer = 4)
        val fp1 = SectCombatPowerCalculator.computeAIFingerprint(d1.toAggregate())
        val fp2 = SectCombatPowerCalculator.computeAIFingerprint(d2.toAggregate())
        assert(fp1 != fp2)
    }

    @Test
    fun `computeAIFingerprint - different talents produce different fingerprint`() {
        val d1 = Disciple(name = "Alpha", realm = 5, realmLayer = 3)
            .copy(talentIds = listOf("talent_sword"))
        val d2 = Disciple(name = "Beta", realm = 5, realmLayer = 3)
            .copy(talentIds = listOf("talent_spear"))
        val fp1 = SectCombatPowerCalculator.computeAIFingerprint(d1.toAggregate())
        val fp2 = SectCombatPowerCalculator.computeAIFingerprint(d2.toAggregate())
        assert(fp1 != fp2)
    }
}
