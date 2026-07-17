package com.xianxia.sect.core.domain.disciple

import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DiscipleStatCalculator] 血炼百分比乘区计算单元测试。
 *
 * 血炼系统从绝对值存储改为百分比乘区后：
 * - 每次血炼直接累加材料百分比到累计记录
 * - 属性 = 境界基础 × 方差 × 层数 × (1 + 天赋% + 血炼%)
 * - 不再直接修改 DiscipleTables.base* 列
 *
 * 验证：
 * - 百分比直接累加（简单相加 = 天然单利）
 * - 多属性百分比独立累加
 * - 边界情况（零百分比等）
 */
class BloodRefinementSimpleInterestTest {

    // ── getAccumulatedPct ──────────────────────────────────

    @Test
    fun `getAccumulatedPct - null记录返回0`() {
        val pct = DiscipleStatCalculator.getAccumulatedPct(null, "hp")
        assertEquals(0.0, pct, 0.0001)
    }

    @Test
    fun `getAccumulatedPct - 读取各属性累计百分比`() {
        val total = BloodRefinementPctTotal(
            discipleId = "d1",
            hpBonusPct = 0.10,
            physicalAttackBonusPct = 0.20,
            magicAttackBonusPct = 0.15,
            physicalDefenseBonusPct = 0.05,
            magicDefenseBonusPct = 0.03,
            speedBonusPct = 0.08
        )

        assertEquals(0.10, DiscipleStatCalculator.getAccumulatedPct(total, "hp"), 0.0001)
        assertEquals(0.20, DiscipleStatCalculator.getAccumulatedPct(total, "physicalAttack"), 0.0001)
        assertEquals(0.15, DiscipleStatCalculator.getAccumulatedPct(total, "magicAttack"), 0.0001)
        assertEquals(0.05, DiscipleStatCalculator.getAccumulatedPct(total, "physicalDefense"), 0.0001)
        assertEquals(0.03, DiscipleStatCalculator.getAccumulatedPct(total, "magicDefense"), 0.0001)
        assertEquals(0.08, DiscipleStatCalculator.getAccumulatedPct(total, "speed"), 0.0001)
    }

    @Test
    fun `getAccumulatedPct - 未知属性key返回0`() {
        val total = BloodRefinementPctTotal(hpBonusPct = 0.10)
        assertEquals(0.0, DiscipleStatCalculator.getAccumulatedPct(total, "unknown"), 0.0001)
    }

    // ── addPctToTotal ──────────────────────────────────

    @Test
    fun `addPctToTotal - 累加hp百分比`() {
        val total = BloodRefinementPctTotal(discipleId = "d1", hpBonusPct = 0.10)
        val updated = DiscipleStatCalculator.addPctToTotal(total, "hp", 0.05)
        assertEquals(0.15, updated.hpBonusPct, 0.0001)
        assertEquals(0.0, updated.physicalAttackBonusPct, 0.0001)
    }

    @Test
    fun `addPctToTotal - 累加各属性百分比`() {
        val total = BloodRefinementPctTotal(discipleId = "d1")
        val updated = total
            .let { DiscipleStatCalculator.addPctToTotal(it, "hp", 0.10) }
            .let { DiscipleStatCalculator.addPctToTotal(it, "physicalAttack", 0.20) }
            .let { DiscipleStatCalculator.addPctToTotal(it, "magicAttack", 0.15) }
            .let { DiscipleStatCalculator.addPctToTotal(it, "physicalDefense", 0.05) }
            .let { DiscipleStatCalculator.addPctToTotal(it, "magicDefense", 0.03) }
            .let { DiscipleStatCalculator.addPctToTotal(it, "speed", 0.08) }

        assertEquals(0.10, updated.hpBonusPct, 0.0001)
        assertEquals(0.20, updated.physicalAttackBonusPct, 0.0001)
        assertEquals(0.15, updated.magicAttackBonusPct, 0.0001)
        assertEquals(0.05, updated.physicalDefenseBonusPct, 0.0001)
        assertEquals(0.03, updated.magicDefenseBonusPct, 0.0001)
        assertEquals(0.08, updated.speedBonusPct, 0.0001)
    }

    @Test
    fun `addPctToTotal - 未知属性key返回原记录不变`() {
        val total = BloodRefinementPctTotal(discipleId = "d1", hpBonusPct = 0.10)
        val updated = DiscipleStatCalculator.addPctToTotal(total, "unknown", 0.50)
        assertEquals("未知属性不应修改记录", total, updated)
    }

    // ── 集成场景：模拟多次血炼完整流程 ──────────────────────────────────

    @Test
    fun `集成 - 模拟5次同属性血炼验证百分比累加`() {
        // 新系统：每次血炼直接累加材料百分比，天然单利
        var bonusTotal: BloodRefinementPctTotal? = null
        val bonusPercent = 0.10  // 10%

        repeat(5) {
            bonusTotal = DiscipleStatCalculator.addPctToTotal(
                bonusTotal ?: BloodRefinementPctTotal(discipleId = "d1"), "hp", bonusPercent
            )
        }

        assertEquals(
            "5次10%累加后总百分比应为5×0.10=0.50",
            0.50,
            checkNotNull(bonusTotal).hpBonusPct,
            0.0001
        )
    }

    @Test
    fun `集成 - 模拟混合属性血炼验证各属性独立累加`() {
        var bonusTotal: BloodRefinementPctTotal? = null

        // 第1次：hp +10%
        bonusTotal = DiscipleStatCalculator.addPctToTotal(
            bonusTotal ?: BloodRefinementPctTotal(discipleId = "d1"), "hp", 0.10
        )
        // 第2次：physicalAttack +20%
        bonusTotal = DiscipleStatCalculator.addPctToTotal(
            checkNotNull(bonusTotal), "physicalAttack", 0.20
        )
        // 第3次：hp +10%（再次）
        bonusTotal = DiscipleStatCalculator.addPctToTotal(
            checkNotNull(bonusTotal), "hp", 0.10
        )

        assertEquals("hp两次累加应为 0.10+0.10=0.20", 0.20, checkNotNull(bonusTotal).hpBonusPct, 0.0001)
        assertEquals("physicalAttack一次应为 0.20", 0.20, bonusTotal.physicalAttackBonusPct, 0.0001)
        assertEquals("其他属性应为 0.0", 0.0, bonusTotal.speedBonusPct, 0.0001)
    }
}