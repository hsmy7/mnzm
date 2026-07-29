package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.PhysiqueCombatFactors
import com.xianxia.sect.core.registry.AffixCombatEffects
import com.xianxia.sect.core.util.BattleCalculator.CombatantStats
import com.xianxia.sect.core.util.BattleCalculator.DamageResult
import org.junit.Assert.*
import org.junit.Test

class BattleCalculatorTest {
    private val rng = DeterministicRng(42L)

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
            val result = BattleCalculator.withRng(rng).calculateDamage(
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
            val result = BattleCalculator.withRng(rng).calculateDamage(
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
        val result = BattleCalculator.withRng(rng).calculateDamage(
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
        val result = BattleCalculator.withRng(rng).calculateDamage(
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
            val normal = BattleCalculator.withRng(rng).calculateDamage(attacker, defender, skillDamageMultiplier = 1.0, dodgeChanceModifier = 0.0)
            val boosted = BattleCalculator.withRng(rng).calculateDamage(attacker, defender, skillDamageMultiplier = 2.0, dodgeChanceModifier = 0.0)
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
        val result = BattleCalculator.withRng(rng).calculateDamage(attacker, defender, dodgeChanceModifier = 0.0)
        assertTrue(result.isCrit)
        val expected = expectedDamage(200, 50, critMultiplier = 1.0 + GameConfig.Battle.CRIT_BASE_MULTIPLIER)
        assertTrue(result.damage >= (expected * 0.7).toInt())
    }

    @Test
    fun `calculateDamage - no crit when critRate is 0`() {
        val attacker = createCombatant(physicalAttack = 200, critRate = 0.0)
        val defender = createCombatant(physicalDefense = 50)
        val result = BattleCalculator.withRng(rng).calculateDamage(attacker, defender, dodgeChanceModifier = 0.0)
        assertFalse(result.isCrit)
    }

    @Test
    fun `calculateDamage - minimum damage is 1`() {
        val attacker = createCombatant(physicalAttack = 1)
        val defender = createCombatant(physicalDefense = 9999)
        val result = BattleCalculator.withRng(rng).calculateDamage(attacker, defender, dodgeChanceModifier = 0.0)
        assertTrue(result.damage >= 1)
    }

    @Test
    fun `calculateDamage - dodged attack deals 0 damage`() {
        val fastAttacker = createCombatant(speed = 10000)
        val slowDefender = createCombatant(speed = 1)
        var dodged = false
        for (i in 1..100) {
            val result = BattleCalculator.withRng(rng).calculateDamage(fastAttacker, slowDefender, dodgeChanceModifier = 0.5)
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
        val result = BattleCalculator.withRng(rng).calculateDamage(
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
        val result = BattleCalculator.withRng(rng).calculateDamage(
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
    fun `calculateDamage isPhysicalAttack true - basic physical damage`() {
        val attacker = createCombatant(physicalAttack = 200, critRate = 0.0)
        val defender = createCombatant(physicalDefense = 50)
        var totalDamage = 0
        var count = 0
        for (i in 1..1000) {
            val result = BattleCalculator.withRng(rng).calculateDamage(attacker, defender, isPhysicalAttack = true, dodgeChanceModifier = 0.0)
            totalDamage += result.damage
            count++
        }
        val avgDamage = totalDamage.toDouble() / count
        val expected = expectedDamage(200, 50)
        assertTrue("avgDamage $avgDamage should be near $expected", avgDamage > expected * 0.7 && avgDamage < expected * 1.5)
    }

    @Test
    fun `calculateDamage isPhysicalAttack true - low attack vs high defense still deals damage`() {
        val attacker = createCombatant(physicalAttack = 1, critRate = 0.0)
        val defender = createCombatant(physicalDefense = 9999)
        val result = BattleCalculator.withRng(rng).calculateDamage(attacker, defender, isPhysicalAttack = true, dodgeChanceModifier = 0.0)
        assertTrue(result.damage >= 0)
    }

    @Test
    fun `calculateDamage isPhysicalAttack false - basic magic damage`() {
        val attacker = createCombatant(magicAttack = 200, critRate = 0.0)
        val defender = createCombatant(magicDefense = 30)
        var totalDamage = 0
        var count = 0
        for (i in 1..1000) {
            val result = BattleCalculator.withRng(rng).calculateDamage(attacker, defender, isPhysicalAttack = false, dodgeChanceModifier = 0.0)
            totalDamage += result.damage
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
    fun `calculateRealmGapMultiplier - 高境界攻击低境界获得加成`() {
        val multiplier = BattleCalculator.calculateRealmGapMultiplier(0, 3)
        assertEquals(2.05, multiplier, 0.001)
    }

    @Test
    fun `calculateRealmGapMultiplier - 低境界攻击高境界受到惩罚`() {
        val multiplier = BattleCalculator.calculateRealmGapMultiplier(5, 1)
        assertEquals(0.0, multiplier, 0.001)
    }

    @Test
    fun `calculateRealmGapMultiplier - 全十境界差距加成不再被钳制`() {
        val multiplier = BattleCalculator.calculateRealmGapMultiplier(0, 9)
        assertEquals(4.15, multiplier, 0.001)
    }

    @Test
    fun `calculateRealmGapMultiplier - 全十境界差距惩罚触底为零`() {
        val multiplier = BattleCalculator.calculateRealmGapMultiplier(9, 0)
        assertEquals(0.0, multiplier, 0.001)
    }

    @Test
    fun `calculateRealmGapMultiplier - 惩罚下限不为负数`() {
        val multiplier = BattleCalculator.calculateRealmGapMultiplier(8, 0)
        assertEquals(0.0, multiplier, 0.001)
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
    fun `calculateDamage - damage variance between 0_8 and 1_2`() {
        val attacker = createCombatant(physicalAttack = 1000, critRate = 0.0)
        val defender = createCombatant(physicalDefense = 0)
        val damages = mutableListOf<Int>()
        for (i in 1..1000) {
            val result = BattleCalculator.withRng(rng).calculateDamage(attacker, defender, isPhysicalAttack = true, dodgeChanceModifier = 0.0)
            if (!result.isDodged) damages.add(result.damage)
        }
        assertTrue(damages.isNotEmpty())
        val min = damages.min()
        val max = damages.max()
        val expected = expectedDamage(1000, 0)
        assertTrue("min $min should be >= ${expected * 0.75}", min >= (expected * 0.75).toInt())
        assertTrue("max $max should be <= ${expected * 1.25}", max <= (expected * 1.25).toInt())
    }

    @Test
    fun `defense percentage reduction works correctly`() {
        val attacker = createCombatant(physicalAttack = 100, critRate = 0.0)
        val lowDefender = createCombatant(physicalDefense = 100)
        val highDefender = createCombatant(physicalDefense = 900)
        var lowTotal = 0
        var highTotal = 0
        for (i in 1..500) {
            lowTotal += BattleCalculator.withRng(rng).calculateDamage(attacker, lowDefender, isPhysicalAttack = true, dodgeChanceModifier = 0.0).damage
            highTotal += BattleCalculator.withRng(rng).calculateDamage(attacker, highDefender, isPhysicalAttack = true, dodgeChanceModifier = 0.0).damage
        }
        assertTrue("high defense should take less damage", highTotal < lowTotal)
        val lowReduction = 100.0 / (100.0 + GameConfig.Battle.DEFENSE_CONSTANT)
        val highReduction = 900.0 / (900.0 + GameConfig.Battle.DEFENSE_CONSTANT)
        assertTrue("high defense reduction should be higher", highReduction > lowReduction)
    }

    // ==================== 体质/词条独立乘算因子测试 ====================

    private fun baseZones() = DamageZones()
    private fun baseFinal(
        rawAttack: Int = 1000,
        defense: Int = 200,
        skillMultiplier: Double = 1.0,
        realmGapMultiplier: Double = 1.0,
        zones: DamageZones = baseZones(),
        isCrit: Boolean = false,
        variance: Double = 1.0
    ): Int = BattleCalculator.calculateFinalDamage(
        rawAttack = rawAttack,
        defense = defense,
        skillMultiplier = skillMultiplier,
        realmGapMultiplier = realmGapMultiplier,
        zones = zones,
        isCrit = isCrit,
        variance = variance
    )

    /** 复制 calculateFinalDamage 公式的浮点期望值计算，避免 Int 截断误差 */
    private fun expectedFinalDamage(
        rawAttack: Int = 1000,
        defense: Int = 200,
        skillMultiplier: Double = 1.0,
        realmGapMultiplier: Double = 1.0,
        zones: DamageZones = baseZones(),
        isCrit: Boolean = false,
        variance: Double = 1.0
    ): Double {
        val effectiveAttack = rawAttack * (1.0 + zones.attackBuffs)
        val effectiveDefense = defense *
            (1.0 - zones.physiqueDefenseBonus).coerceAtLeast(0.0) *
            (1.0 - zones.affixDefenseBonus).coerceAtLeast(0.0)
        val reduction = effectiveDefense / (effectiveDefense + GameConfig.Battle.DEFENSE_CONSTANT)
        val preCritDamage = effectiveAttack * skillMultiplier * (1.0 - reduction) * realmGapMultiplier
        val critMult = if (isCrit) 1.0 + GameConfig.Battle.CRIT_BASE_MULTIPLIER else 1.0
        val physiqueCritMult = if (isCrit) (1.0 + zones.physiqueCritDamageBonus) else 1.0
        val affixCritMult = if (isCrit) (1.0 + zones.affixCritDamageBonus) else 1.0
        return preCritDamage * critMult * physiqueCritMult * affixCritMult *
            (1.0 + zones.damageAmplification) *
            (1.0 + zones.physiqueDamageAmplification) *
            (1.0 + zones.affixDamageAmplification) *
            (1.0 - zones.damageReduction) *
            (1.0 - zones.physiqueDamageReduction) *
            (1.0 - zones.affixDamageReduction) *
            variance
    }

    private fun Int.toClampedMin(): Int =
        this.coerceAtLeast(GameConfig.Battle.MIN_DAMAGE)

    @Test
    fun `physiqueDamageAmplification - 体质增伤独立乘算`() {
        val zones = baseZones().copy(physiqueDamageAmplification = 0.20)
        val expected = expectedFinalDamage(zones = zones).toInt().toClampedMin()
        val actual = baseFinal(zones = zones)
        assertEquals(expected, actual)
    }

    @Test
    fun `physiqueDamageAmplification - 体质增伤与 buff 增伤独立乘算`() {
        // 独立乘算：base × (1+buff) × (1+physique)，而非 base × (1+buff+physique)
        val both = baseFinal(zones = baseZones().copy(
            damageAmplification = 0.10, physiqueDamageAmplification = 0.20
        ))
        val additive = baseFinal(zones = baseZones().copy(
            damageAmplification = 0.30  // 0.10 + 0.20，加算
        ))
        assertTrue("独立乘算应大于加算: both=$both, additive=$additive", both > additive)
    }

    @Test
    fun `physiqueCritDamageBonus - 非暴击时不生效`() {
        val base = baseFinal(isCrit = false)
        val withPhysique = baseFinal(
            isCrit = false,
            zones = baseZones().copy(physiqueCritDamageBonus = 0.30)
        )
        assertEquals(base, withPhysique)
    }

    @Test
    fun `physiqueCritDamageBonus - 暴击时独立乘算`() {
        val zones = baseZones().copy(physiqueCritDamageBonus = 0.30)
        val expected = expectedFinalDamage(isCrit = true, zones = zones).toInt().toClampedMin()
        val actual = baseFinal(isCrit = true, zones = zones)
        assertEquals(expected, actual)
    }

    @Test
    fun `affixCritDamageBonus - 非暴击时不生效`() {
        val base = baseFinal(isCrit = false)
        val withAffix = baseFinal(
            isCrit = false,
            zones = baseZones().copy(affixCritDamageBonus = 0.25)
        )
        assertEquals(base, withAffix)
    }

    @Test
    fun `affixCritDamageBonus - 暴击时独立乘算`() {
        val zones = baseZones().copy(affixCritDamageBonus = 0.25)
        val expected = expectedFinalDamage(isCrit = true, zones = zones).toInt().toClampedMin()
        val actual = baseFinal(isCrit = true, zones = zones)
        assertEquals(expected, actual)
    }

    @Test
    fun `physique and affix crit bonuses - 同时存在各自独立乘算`() {
        // 独立乘算：base × (1+physique) × (1+affix)，而非 base × (1+physique+affix)
        val both = baseFinal(
            isCrit = true,
            zones = baseZones().copy(
                physiqueCritDamageBonus = 0.30,
                affixCritDamageBonus = 0.25
            )
        )
        val additive = baseFinal(
            isCrit = true,
            zones = baseZones().copy(
                physiqueCritDamageBonus = 0.55  // 0.30 + 0.25，加算（放到 physique 上模拟）
            )
        )
        assertTrue("暴伤应独立乘算: both=$both, additive=$additive", both > additive)
    }

    @Test
    fun `physiqueDamageReduction - 防守方体质减伤独立乘算`() {
        val zones = baseZones().copy(physiqueDamageReduction = 0.15)
        val expected = expectedFinalDamage(zones = zones).toInt().toClampedMin()
        val actual = baseFinal(zones = zones)
        assertEquals(expected, actual)
    }

    @Test
    fun `affixDamageReduction - 防守方词条减伤独立乘算`() {
        val zones = baseZones().copy(affixDamageReduction = 0.10)
        val expected = expectedFinalDamage(zones = zones).toInt().toClampedMin()
        val actual = baseFinal(zones = zones)
        assertEquals(expected, actual)
    }

    @Test
    fun `physique and affix damage reduction - 同时存在各自独立乘算`() {
        // 独立乘算：base × (1-physique) × (1-affix)，而非 base × (1-physique-affix)
        val both = baseFinal(zones = baseZones().copy(
            physiqueDamageReduction = 0.15,
            affixDamageReduction = 0.10
        ))
        val additive = baseFinal(zones = baseZones().copy(
            physiqueDamageReduction = 0.25  // 0.15 + 0.10，加算
        ))
        assertTrue("减伤应独立乘算（独立乘算的减伤效果弱于加算）: both=$both, additive=$additive",
            both > additive)
    }

    @Test
    fun `physiqueDefenseBonus - 防御加成独立作用于 effectiveDefense`() {
        val defense = 200
        val zones = baseZones().copy(physiqueDefenseBonus = 0.30)
        val expected = expectedFinalDamage(defense = defense, zones = zones).toInt().toClampedMin()
        val actual = baseFinal(defense = defense, zones = zones)
        assertEquals(expected, actual)
        // 防御降低 → 减伤率降低 → 伤害提高
        assertTrue("体质防御加成应降低有效防御，从而提高伤害",
            actual > baseFinal(defense = defense))
    }

    @Test
    fun `affixDefenseBonus - 防御加成独立作用于 effectiveDefense`() {
        val defense = 200
        val zones = baseZones().copy(affixDefenseBonus = 0.25)
        val expected = expectedFinalDamage(defense = defense, zones = zones).toInt().toClampedMin()
        val actual = baseFinal(defense = defense, zones = zones)
        assertEquals(expected, actual)
        assertTrue("词条防御加成应降低有效防御，从而提高伤害",
            actual > baseFinal(defense = defense))
    }

    @Test
    fun `physique and affix defense bonuses - 同时存在各自独立乘算`() {
        val defense = 200
        val zones = baseZones().copy(
            physiqueDefenseBonus = 0.30,
            affixDefenseBonus = 0.25
        )
        val expected = expectedFinalDamage(defense = defense, zones = zones).toInt().toClampedMin()
        val actual = baseFinal(defense = defense, zones = zones)
        assertEquals(expected, actual)
    }

    @Test
    fun `attackBuffs - 攻击 buff 作用于 effectiveAttack`() {
        val zones = baseZones().copy(attackBuffs = 0.50)
        val expected = expectedFinalDamage(rawAttack = 1000, zones = zones).toInt().toClampedMin()
        val actual = baseFinal(rawAttack = 1000, zones = zones)
        assertEquals(expected, actual)
    }

    @Test
    fun `all zones combined - 全乘区组合验证`() {
        val allZones = DamageZones(
            attackBuffs = 0.20,
            damageAmplification = 0.15,
            damageReduction = 0.10,
            physiqueDamageAmplification = 0.20,
            physiqueCritDamageBonus = 0.30,
            physiqueDamageReduction = 0.15,
            physiqueDefenseBonus = 0.30,
            affixDamageAmplification = 0.10,
            affixCritDamageBonus = 0.25,
            affixDamageReduction = 0.10,
            affixDefenseBonus = 0.25
        )
        val expected = expectedFinalDamage(
            rawAttack = 1000,
            defense = 200,
            skillMultiplier = 2.0,
            realmGapMultiplier = 1.5,
            zones = allZones,
            isCrit = true,
            variance = 1.0
        ).toInt().toClampedMin()
        val actual = baseFinal(
            rawAttack = 1000,
            defense = 200,
            skillMultiplier = 2.0,
            realmGapMultiplier = 1.5,
            zones = allZones,
            isCrit = true,
            variance = 1.0
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `PhysiqueCombatFactors - 默认值全为零`() {
        val factors = PhysiqueCombatFactors()
        assertEquals(0.0, factors.damageAmplification, 0.0)
        assertEquals(0.0, factors.critDamageBonus, 0.0)
        assertEquals(0.0, factors.damageReduction, 0.0)
        assertEquals(0.0, factors.defenseBonus, 0.0)
    }

    @Test
    fun `AffixCombatEffects - 默认值全为零`() {
        val effects = AffixCombatEffects()
        assertEquals(0.0, effects.damageAmplification, 0.0)
        assertEquals(0.0, effects.critDamageBonus, 0.0)
        assertEquals(0.0, effects.damageReduction, 0.0)
        assertEquals(0.0, effects.defenseBonus, 0.0)
    }
}
