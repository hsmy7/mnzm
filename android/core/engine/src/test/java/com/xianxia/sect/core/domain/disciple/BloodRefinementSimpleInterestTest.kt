package com.xianxia.sect.core.domain.disciple

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementBonusTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiscipleStatCalculator] 血炼单利计算单元测试。
 *
 * 覆盖历史 bug #8：
 * - 旧实现每次血炼 bonus = 当前 base × bonusPercent，导致 baseₙ = base₀ × (1+p)ⁿ 复利叠加
 * - 修复后 bonus = (当前 base - 已累计 bonus) × bonusPercent，基于原始 base 计算单利
 *
 * 验证：
 * - 单次血炼加成与旧实现一致（无累计 bonus 时）
 * - 多次血炼加成基于原始 base（单利），不随累计值增长（非复利）
 * - 边界情况：累计 bonus 超过当前 base 时回退到最小值 1
 */
class BloodRefinementSimpleInterestTest {

    // ── calculateSimpleInterestBonus ──────────────────────────────────

    @Test
    fun `calculateSimpleInterestBonus - 无累计bonus时等于旧实现的复利首项`() {
        // 首次血炼：无累计 bonus，originalBase = currentBase
        val bonus = DiscipleStatCalculator.calculateSimpleInterestBonus(
            currentBase = 1000,
            accumulatedBonus = 0,
            bonusPercent = 0.10
        )

        assertEquals("首次血炼 bonus = 1000 × 10% = 100", 100, bonus)
    }

    @Test
    fun `calculateSimpleInterestBonus - 有累计bonus时基于原始base计算单利`() {
        // 第二次血炼：当前 base = 1100（含首次 bonus 100），累计 bonus = 100
        // 单利：originalBase = 1100 - 100 = 1000，bonus = 1000 × 10% = 100
        val bonus = DiscipleStatCalculator.calculateSimpleInterestBonus(
            currentBase = 1100,
            accumulatedBonus = 100,
            bonusPercent = 0.10
        )

        assertEquals("单利 bonus 应基于原始 base 1000，结果 = 100", 100, bonus)
    }

    @Test
    fun `calculateSimpleInterestBonus - 多次血炼后单利保持不变（对比复利递增）`() {
        // 模拟 5 次血炼（10% 加成）
        // 单利：每次 bonus = 1000 × 10% = 100，5 次后 base = 1500，累计 bonus = 500
        // 复利（旧实现）：每次 bonus = 当前 base × 10%，5 次后 base = 1000 × 1.1^5 ≈ 1610
        var currentBase = 1000
        var accumulatedBonus = 0
        val bonusPercent = 0.10

        repeat(5) {
            val bonus = DiscipleStatCalculator.calculateSimpleInterestBonus(
                currentBase = currentBase,
                accumulatedBonus = accumulatedBonus,
                bonusPercent = bonusPercent
            )
            currentBase += bonus
            accumulatedBonus += bonus
        }

        assertEquals("5 次单利后 base 应为 1500", 1500, currentBase)
        assertEquals("5 次单利后累计 bonus 应为 500", 500, accumulatedBonus)
        // 对比复利：1000 × 1.1^5 ≈ 1610.51 → 1610，单利明显低于复利
        assertTrue("单利 base 1500 应小于复利 base ~1610", currentBase < 1610)
    }

    @Test
    fun `calculateSimpleInterestBonus - bonusPercent为0时返回最小值1`() {
        val bonus = DiscipleStatCalculator.calculateSimpleInterestBonus(
            currentBase = 1000,
            accumulatedBonus = 0,
            bonusPercent = 0.0
        )

        assertEquals("bonusPercent 为 0 时应返回最小值 1", 1, bonus)
    }

    @Test
    fun `calculateSimpleInterestBonus - 累计bonus超过当前base时回退到最小值1`() {
        // 边界情况：数据不一致时（累计 bonus > 当前 base），originalBase 会被 coerceAtLeast(1)
        val bonus = DiscipleStatCalculator.calculateSimpleInterestBonus(
            currentBase = 100,
            accumulatedBonus = 200,  // 超过 currentBase
            bonusPercent = 0.10
        )

        assertEquals("累计 bonus 超过 currentBase 时应回退到最小值 1", 1, bonus)
    }

    @Test
    fun `calculateSimpleInterestBonus - 不同bonusPercent对应不同加成`() {
        val testCases = listOf(
            Triple(1000, 0.01, 10),   // 1% → 10
            Triple(1000, 0.03, 30),   // 3% → 30
            Triple(1000, 0.06, 60),   // 6% → 60
            Triple(1000, 0.12, 120),  // 12% → 120
            Triple(1000, 0.20, 200),  // 20% → 200
            Triple(1000, 0.30, 300)   // 30% → 300
        )

        for ((base, percent, expected) in testCases) {
            val bonus = DiscipleStatCalculator.calculateSimpleInterestBonus(
                currentBase = base,
                accumulatedBonus = 0,
                bonusPercent = percent
            )
            assertEquals("base=$base, percent=$percent 应得 bonus=$expected", expected, bonus)
        }
    }

    // ── getAccumulatedBonus ──────────────────────────────────

    @Test
    fun `getAccumulatedBonus - null记录返回0`() {
        val bonus = DiscipleStatCalculator.getAccumulatedBonus(
            total = null,
            statKey = "hp"
        )
        assertEquals(0, bonus)
    }

    @Test
    fun `getAccumulatedBonus - 读取各属性累计值`() {
        val total = BloodRefinementBonusTotal(
            discipleId = "d1",
            hpBonus = 100,
            physicalAttackBonus = 20,
            magicAttackBonus = 30,
            physicalDefenseBonus = 15,
            magicDefenseBonus = 10,
            speedBonus = 5
        )

        assertEquals(100, DiscipleStatCalculator.getAccumulatedBonus(total, "hp"))
        assertEquals(20, DiscipleStatCalculator.getAccumulatedBonus(total, "physicalAttack"))
        assertEquals(30, DiscipleStatCalculator.getAccumulatedBonus(total, "magicAttack"))
        assertEquals(15, DiscipleStatCalculator.getAccumulatedBonus(total, "physicalDefense"))
        assertEquals(10, DiscipleStatCalculator.getAccumulatedBonus(total, "magicDefense"))
        assertEquals(5, DiscipleStatCalculator.getAccumulatedBonus(total, "speed"))
    }

    @Test
    fun `getAccumulatedBonus - 未知属性key返回0`() {
        val total = BloodRefinementBonusTotal(hpBonus = 100)
        assertEquals(0, DiscipleStatCalculator.getAccumulatedBonus(total, "unknown"))
    }

    // ── addBonusToTotal ──────────────────────────────────

    @Test
    fun `addBonusToTotal - 累加hp属性加成`() {
        val total = BloodRefinementBonusTotal(discipleId = "d1", hpBonus = 100)

        val updated = DiscipleStatCalculator.addBonusToTotal(total, "hp", 50)

        assertEquals(150, updated.hpBonus)
        // 其他属性不变
        assertEquals(0, updated.physicalAttackBonus)
    }

    @Test
    fun `addBonusToTotal - 累加各属性加成`() {
        val total = BloodRefinementBonusTotal(discipleId = "d1")

        val updated = total
            .let { DiscipleStatCalculator.addBonusToTotal(it, "hp", 100) }
            .let { DiscipleStatCalculator.addBonusToTotal(it, "physicalAttack", 20) }
            .let { DiscipleStatCalculator.addBonusToTotal(it, "magicAttack", 30) }
            .let { DiscipleStatCalculator.addBonusToTotal(it, "physicalDefense", 15) }
            .let { DiscipleStatCalculator.addBonusToTotal(it, "magicDefense", 10) }
            .let { DiscipleStatCalculator.addBonusToTotal(it, "speed", 5) }

        assertEquals(100, updated.hpBonus)
        assertEquals(20, updated.physicalAttackBonus)
        assertEquals(30, updated.magicAttackBonus)
        assertEquals(15, updated.physicalDefenseBonus)
        assertEquals(10, updated.magicDefenseBonus)
        assertEquals(5, updated.speedBonus)
    }

    @Test
    fun `addBonusToTotal - 未知属性key返回原记录不变`() {
        val total = BloodRefinementBonusTotal(discipleId = "d1", hpBonus = 100)

        val updated = DiscipleStatCalculator.addBonusToTotal(total, "unknown", 50)

        assertEquals("未知属性不应修改记录", total, updated)
    }

    // ── 集成场景：模拟多次血炼完整流程 ──────────────────────────────────

    @Test
    fun `集成 - 模拟5次同属性血炼验证单利不递增`() {
        // 模拟 SettlementCoordinator.processBloodRefinementProgress 的核心逻辑
        var currentBase = 1000  // 原始 base
        var bonusTotal: BloodRefinementBonusTotal? = null
        val bonusPercent = 0.10

        val bonuses = mutableListOf<Int>()

        repeat(5) {
            val accumulatedBonus = DiscipleStatCalculator.getAccumulatedBonus(bonusTotal, "hp")
            val bonus = DiscipleStatCalculator.calculateSimpleInterestBonus(
                currentBase = currentBase,
                accumulatedBonus = accumulatedBonus,
                bonusPercent = bonusPercent
            )
            bonuses.add(bonus)

            // 更新状态
            currentBase += bonus
            val currentTotal = bonusTotal ?: BloodRefinementBonusTotal(discipleId = "d1")
            bonusTotal = DiscipleStatCalculator.addBonusToTotal(currentTotal, "hp", bonus)
        }

        // 单利：每次 bonus 都应相同（100），因为基于原始 base 1000
        assertEquals("每次单利 bonus 应相同", listOf(100, 100, 100, 100, 100), bonuses)
        assertEquals("5 次后 base 应为 1500", 1500, currentBase)
        assertEquals("5 次后累计 bonus 应为 500", 500, bonusTotal!!.hpBonus)
    }

    @Test
    fun `集成 - 模拟混合属性血炼验证各属性独立单利`() {
        // 交替血炼 hp 和 physicalAttack，验证各属性独立计算单利
        var currentHp = 1000
        var currentPhysicalAttack = 100
        var bonusTotal: BloodRefinementBonusTotal? = null
        val bonusPercent = 0.10

        // 第 1 次：hp
        var accHp = DiscipleStatCalculator.getAccumulatedBonus(bonusTotal, "hp")
        var bonus1 = DiscipleStatCalculator.calculateSimpleInterestBonus(currentHp, accHp, bonusPercent)
        currentHp += bonus1
        bonusTotal = DiscipleStatCalculator.addBonusToTotal(
            bonusTotal ?: BloodRefinementBonusTotal(discipleId = "d1"), "hp", bonus1
        )

        // 第 2 次：physicalAttack
        var accPa = DiscipleStatCalculator.getAccumulatedBonus(bonusTotal, "physicalAttack")
        var bonus2 = DiscipleStatCalculator.calculateSimpleInterestBonus(currentPhysicalAttack, accPa, bonusPercent)
        currentPhysicalAttack += bonus2
        bonusTotal = DiscipleStatCalculator.addBonusToTotal(bonusTotal!!, "physicalAttack", bonus2)

        // 第 3 次：hp（验证 hp 单利不变）
        accHp = DiscipleStatCalculator.getAccumulatedBonus(bonusTotal, "hp")
        var bonus3 = DiscipleStatCalculator.calculateSimpleInterestBonus(currentHp, accHp, bonusPercent)
        currentHp += bonus3
        bonusTotal = DiscipleStatCalculator.addBonusToTotal(bonusTotal!!, "hp", bonus3)

        // 验证：hp 两次 bonus 相同（单利），physicalAttack 一次 bonus 独立计算
        assertEquals("hp 首次 bonus = 100", 100, bonus1)
        assertEquals("hp 第二次 bonus = 100（单利）", 100, bonus3)
        assertEquals("physicalAttack bonus = 10", 10, bonus2)
        assertEquals("hp 最终 base = 1200", 1200, currentHp)
        assertEquals("physicalAttack 最终 base = 110", 110, currentPhysicalAttack)
    }
}
