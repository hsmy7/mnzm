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
    fun `calculateRealmGapFactors - 同境界同层双因子为零`() {
        val factors = BattleCalculator.calculateRealmGapFactors(5, 3, 5, 3)
        assertEquals(0.0, factors.damageAmplification, 0.001)
        assertEquals(0.0, factors.damageReduction, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 同境界高 4 层增伤`() {
        // 炼气五层(9,5) vs 炼气一层(9,1)：gap = (9-9)×9 + (5-1) = 4
        val factors = BattleCalculator.calculateRealmGapFactors(9, 5, 9, 1)
        assertEquals(1.2, factors.damageAmplification, 0.001) // 0.30 × 4
        assertEquals(0.0, factors.damageReduction, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 同境界低 4 层减伤封顶`() {
        // 炼气一层(9,1) 打 炼气五层(9,5)：防守方高 4 层 → 减伤 min(1.0, 1.2) 封顶
        val factors = BattleCalculator.calculateRealmGapFactors(9, 1, 9, 5)
        assertEquals(0.0, factors.damageAmplification, 0.001)
        assertEquals(1.0, factors.damageReduction, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 跨境界加层数差增伤`() {
        // 筑基三层(8,3) vs 炼气五层(9,5)：gap = (9-8)×9 + (3-5) = 7
        val factors = BattleCalculator.calculateRealmGapFactors(8, 3, 9, 5)
        assertEquals(2.1, factors.damageAmplification, 0.001) // 0.30 × 7
        assertEquals(0.0, factors.damageReduction, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 跨境界反向减伤封顶`() {
        // 炼气五层(9,5) 打 筑基三层(8,3)：防守方（筑基）高 7 层 → 减伤封顶
        val factors = BattleCalculator.calculateRealmGapFactors(9, 5, 8, 3)
        assertEquals(0.0, factors.damageAmplification, 0.001)
        assertEquals(1.0, factors.damageReduction, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 减伤未封顶用例`() {
        // 炼气一层(9,1) 打 炼气三层(9,3)：防守方高 2 层 → 减伤 0.6（未封顶）
        val factors = BattleCalculator.calculateRealmGapFactors(9, 1, 9, 3)
        assertEquals(0.0, factors.damageAmplification, 0.001)
        assertEquals(0.6, factors.damageReduction, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - layer 为 0 回退初层`() {
        // (9,0) 视为 (9,1)：vs 炼气五层(9,5) → 防守方高 4 层 → 减伤封顶
        val factors = BattleCalculator.calculateRealmGapFactors(9, 0, 9, 5)
        assertEquals(0.0, factors.damageAmplification, 0.001)
        assertEquals(1.0, factors.damageReduction, 0.001)
        // 双方 layer 均为 0（未知）→ 中性
        val neutral = BattleCalculator.calculateRealmGapFactors(9, 0, 9, 0)
        assertEquals(0.0, neutral.damageAmplification, 0.001)
        assertEquals(0.0, neutral.damageReduction, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 越界层数钳制`() {
        // (9,20) 钳制为 9 层，(9,-3) 钳制为 1 层 → gap = 9-1 = 8
        val factors = BattleCalculator.calculateRealmGapFactors(9, 20, 9, -3)
        assertEquals(2.4, factors.damageAmplification, 0.001) // 0.30 × 8
        assertEquals(0.0, factors.damageReduction, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 越界 realm 钳制到 0 至 9`() {
        // 存档篡改 realm=-1（超越仙人）：钳制到 0 后 vs 炼气(9) → majorGap=9 封顶，不再无上限爆炸
        val negative = BattleCalculator.calculateRealmGapFactors(-1, 1, 9, 1)
        assertEquals(24.3, negative.damageAmplification, 0.001) // 0.30 × 81（钳制后合法域最大值）
        assertEquals(9.0, negative.majorRealmDamageAmplification, 0.001) // 1.0 × 9
        // 篡改 realm=Int.MAX_VALUE：钳制到 9 后与防守方同为炼气 → 全部归零
        val maxRealm = BattleCalculator.calculateRealmGapFactors(Int.MAX_VALUE, 1, 9, 1)
        assertEquals(0.0, maxRealm.damageAmplification, 0.001)
        assertEquals(0.0, maxRealm.damageReduction, 0.001)
        assertEquals(0.0, maxRealm.majorRealmDamageAmplification, 0.001)
        // 篡改 realm=Int.MIN_VALUE：钳制到 0 → 与 -1 同结果（合法域内封顶）
        val minRealm = BattleCalculator.calculateRealmGapFactors(Int.MIN_VALUE, 1, 9, 1)
        assertEquals(24.3, minRealm.damageAmplification, 0.001)
        assertEquals(9.0, minRealm.majorRealmDamageAmplification, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 负配置大境界因子钳制为零`() {
        // 配置 damageBonusPerMajorRealm=-2.0（篡改/误配）：因子钳制 0 而非负数（负数 × 减伤超额会"负负得正"反转伤害语义）
        val factors = BattleCalculator.calculateRealmGapFactors(
            8, 1, 9, 1, damageBonusPerMajorRealm = -2.0
        )
        assertEquals(0.0, factors.majorRealmDamageAmplification, 0.001)
        // 小层因子不受影响
        assertEquals(2.7, factors.damageAmplification, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 跨大境界大境界加成`() {
        // 筑基一层(8,1) 打 炼气一层(9,1)：小层增伤 0.30×9=2.7 + 大境界增伤 1.0（双因子并存叠加）
        val factors = BattleCalculator.calculateRealmGapFactors(8, 1, 9, 1)
        assertEquals(2.7, factors.damageAmplification, 0.001) // 0.30 × 9
        assertEquals(0.0, factors.damageReduction, 0.001)
        assertEquals(1.0, factors.majorRealmDamageAmplification, 0.001) // 高 1 大境界 +100%
    }

    @Test
    fun `calculateRealmGapFactors - 高 2 大境界大境界加成累加`() {
        // 金丹一层(7,1) 打 炼气一层(9,1)：高 2 大境界 → +200%
        val factors = BattleCalculator.calculateRealmGapFactors(7, 1, 9, 1)
        assertEquals(2.0, factors.majorRealmDamageAmplification, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 高 3 大境界大境界加成不封顶`() {
        // 元婴一层(6,1) 打 炼气一层(9,1)：高 3 大境界 → +300%（不封顶）
        val factors = BattleCalculator.calculateRealmGapFactors(6, 1, 9, 1)
        assertEquals(3.0, factors.majorRealmDamageAmplification, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 防守方高境界无大境界减伤对称`() {
        // 炼气一层(9,1) 打 筑基一层(8,1)：防守方高境界 → 小层减伤封顶兜底，大境界因子恒 0（仅增伤方向）
        val factors = BattleCalculator.calculateRealmGapFactors(9, 1, 8, 1)
        assertEquals(0.0, factors.damageAmplification, 0.001)
        assertEquals(1.0, factors.damageReduction, 0.001)
        assertEquals(0.0, factors.majorRealmDamageAmplification, 0.001)
    }

    @Test
    fun `calculateRealmGapFactors - 同大境界大境界因子为零`() {
        // 炼气三层(9,3) 打 炼气五层(9,5)：同大境界（majorGap=0）→ 大境界因子 0，小层减伤照常
        val factors = BattleCalculator.calculateRealmGapFactors(9, 3, 9, 5)
        assertEquals(0.0, factors.damageAmplification, 0.001)
        assertEquals(0.6, factors.damageReduction, 0.001)
        assertEquals(0.0, factors.majorRealmDamageAmplification, 0.001)
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
        zones: DamageZones = baseZones(),
        isCrit: Boolean = false,
        variance: Double = 1.0
    ): Int = BattleCalculator.calculateFinalDamage(
        rawAttack = rawAttack,
        defense = defense,
        skillMultiplier = skillMultiplier,
        zones = zones,
        isCrit = isCrit,
        variance = variance
    )

    /** 复制 calculateFinalDamage 公式的浮点期望值计算，避免 Int 截断误差 */
    private fun expectedFinalDamage(
        rawAttack: Int = 1000,
        defense: Int = 200,
        skillMultiplier: Double = 1.0,
        zones: DamageZones = baseZones(),
        isCrit: Boolean = false,
        variance: Double = 1.0
    ): Double {
        val effectiveAttack = rawAttack * (1.0 + zones.attackBuffs)
        val effectiveDefense = defense *
            (1.0 - zones.physiqueDefenseBonus).coerceAtLeast(0.0) *
            (1.0 - zones.affixDefenseBonus).coerceAtLeast(0.0)
        val reduction = effectiveDefense / (effectiveDefense + GameConfig.Battle.DEFENSE_CONSTANT)
        val preCritDamage = effectiveAttack * skillMultiplier * (1.0 - reduction)
        val critMult = if (isCrit) 1.0 + GameConfig.Battle.CRIT_BASE_MULTIPLIER else 1.0
        val physiqueCritMult = if (isCrit) (1.0 + zones.physiqueCritDamageBonus) else 1.0
        val affixCritMult = if (isCrit) (1.0 + zones.affixCritDamageBonus) else 1.0
        return preCritDamage * critMult * physiqueCritMult * affixCritMult *
            (1.0 + zones.damageAmplification) *
            (1.0 + zones.physiqueDamageAmplification) *
            (1.0 + zones.affixDamageAmplification) *
            (1.0 + zones.realmGapDamageAmplification) *
            (1.0 + zones.majorRealmDamageAmplification) *
            (1.0 - zones.damageReduction) *
            (1.0 - zones.physiqueDamageReduction) *
            (1.0 - zones.affixDamageReduction) *
            (1.0 - zones.realmGapDamageReduction) *
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
            affixDefenseBonus = 0.25,
            realmGapDamageAmplification = 0.50, // 境界压制增伤，独立乘算（等价 ×1.5）
            majorRealmDamageAmplification = 1.0 // 跨大境界增伤，独立乘算（等价 ×2.0）
        )
        val expected = expectedFinalDamage(
            rawAttack = 1000,
            defense = 200,
            skillMultiplier = 2.0,
            zones = allZones,
            isCrit = true,
            variance = 1.0
        ).toInt().toClampedMin()
        val actual = baseFinal(
            rawAttack = 1000,
            defense = 200,
            skillMultiplier = 2.0,
            zones = allZones,
            isCrit = true,
            variance = 1.0
        )
        assertEquals(expected, actual)
    }

    // ==================== 境界压制独立乘算因子测试 ====================

    @Test
    fun `realmGapDamageAmplification - 境界压制增伤独立乘算`() {
        val zones = baseZones().copy(realmGapDamageAmplification = 0.50)
        val expected = expectedFinalDamage(zones = zones).toInt().toClampedMin()
        val actual = baseFinal(zones = zones)
        assertEquals(expected, actual)
    }

    @Test
    fun `realmGapDamageAmplification - 境界压制增伤与 buff 增伤独立乘算`() {
        // 独立乘算：base × (1+buff) × (1+realmGap)，而非 base × (1+buff+realmGap)
        val both = baseFinal(zones = baseZones().copy(
            damageAmplification = 0.10, realmGapDamageAmplification = 0.20
        ))
        val additive = baseFinal(zones = baseZones().copy(
            damageAmplification = 0.30 // 0.10 + 0.20，加算
        ))
        assertTrue("境界压制增伤应独立乘算不被乘区稀释: both=$both, additive=$additive", both > additive)
    }

    @Test
    fun `realmGapDamageReduction - 境界压制减伤独立乘算`() {
        val zones = baseZones().copy(realmGapDamageReduction = 0.30)
        val expected = expectedFinalDamage(zones = zones).toInt().toClampedMin()
        val actual = baseFinal(zones = zones)
        assertEquals(expected, actual)
    }

    @Test
    fun `realmGapDamageReduction - 境界压制减伤与 buff 减伤独立乘算`() {
        // 独立乘算：base × (1-buff) × (1-realmGap)，而非 base × (1-buff-realmGap)
        val both = baseFinal(zones = baseZones().copy(
            damageReduction = 0.10, realmGapDamageReduction = 0.20
        ))
        val additive = baseFinal(zones = baseZones().copy(
            damageReduction = 0.30 // 0.10 + 0.20，加算
        ))
        assertTrue("境界压制减伤应独立乘算: both=$both, additive=$additive", both > additive)
    }

    @Test
    fun `majorRealmDamageAmplification - 大境界加成与小层境界加成独立乘算`() {
        // 独立乘算：base × (1+小层) × (1+大境界)，而非 base × (1+小层+大境界)
        val both = baseFinal(zones = baseZones().copy(
            realmGapDamageAmplification = 0.30, majorRealmDamageAmplification = 1.0
        ))
        val additive = baseFinal(zones = baseZones().copy(
            realmGapDamageAmplification = 1.30 // 0.30 + 1.0，加算
        ))
        assertTrue("大境界加成应与小层境界加成独立乘算: both=$both, additive=$additive", both > additive)
    }

    @Test
    fun `majorRealmDamageAmplification - 大境界加成与 buff 增伤独立乘算`() {
        // 独立乘算：base × (1+buff) × (1+大境界)，而非 base × (1+buff+大境界)
        val both = baseFinal(zones = baseZones().copy(
            damageAmplification = 0.10, majorRealmDamageAmplification = 1.0
        ))
        val additive = baseFinal(zones = baseZones().copy(
            damageAmplification = 1.10 // 0.10 + 1.0，加算
        ))
        assertTrue("大境界加成应独立乘算不被乘区稀释: both=$both, additive=$additive", both > additive)
    }

    @Test
    fun `calculateDamage - CombatantStats 路径注入大境界加成`() {
        // 筑基一层(8,1) 打 炼气一层(9,1)：伤害 = 基线 × (1+2.7) × (1+1.0) = ×7.4
        // （CombatantStats.realmLayer 默认 0 → safeLayer 回退初层 1；critRate=0 无暴击、同速无闪避，仅波动）
        val attackerHigh = createCombatant(physicalAttack = 200, critRate = 0.0, realm = 8)
        val attackerBase = createCombatant(physicalAttack = 200, critRate = 0.0, realm = 9)
        val defender = createCombatant(physicalDefense = 50, realm = 9)
        var totalMajor = 0
        var totalBase = 0
        var count = 0
        for (i in 1..1000) {
            totalMajor += BattleCalculator.withRng(rng).calculateDamage(
                attackerHigh, defender,
                isPhysicalAttack = true,
                dodgeChanceModifier = 0.0
            ).damage
            totalBase += BattleCalculator.withRng(rng).calculateDamage(
                attackerBase, defender,
                isPhysicalAttack = true,
                dodgeChanceModifier = 0.0
            ).damage
            count++
        }
        val avgMajor = totalMajor.toDouble() / count
        val avgBase = totalBase.toDouble() / count
        assertTrue(
            "大境界加成应使 CombatantStats 路径伤害 ≈ ×7.4: avgMajor=$avgMajor, avgBase=$avgBase",
            avgMajor > avgBase * 5.0 && avgMajor < avgBase * 10.0
        )
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
