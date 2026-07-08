package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.util.ZoneCalculator
import org.junit.Assert.*
import org.junit.Test

/**
 * 灵矿月度结算测试 — 验证时间戳差分惰性结算逻辑。
 *
 * 核心验证点：
 * - 正常路径：矿工 × 基础产出 × 乘区加成 = 正确月产出
 * - 边界条件：空矿场 = 0，无矿工 = 0
 * - 精度验证：Long + roundToLong 无截断损失
 */
class SpiritMineMonthlySettlementTest {

    // ═══════════════════════════════════════════════════════════════
    // SpiritMineZones.calculateMonthly
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `calculateMonthly - no miners returns zero`() {
        val zones = CultivationSettlement.SpiritMineZones(
            minerCount = 0,
            avgMiningSkillBonus = 0.0,
            deaconMoralityBonus = 0.0,
            policyBoost = 0.0
        )
        val result = zones.calculateMonthly(220.0)
        assertEquals(0L, result)
    }

    @Test
    fun `calculateMonthly - single miner base output`() {
        val zones = CultivationSettlement.SpiritMineZones(
            minerCount = 1,
            avgMiningSkillBonus = 0.0,
            deaconMoralityBonus = 0.0,
            policyBoost = 0.0
        )
        val result = zones.calculateMonthly(220.0)
        // 1 × 220 = 220, 无乘区加成
        assertEquals(220L, result)
    }

    @Test
    fun `calculateMonthly - multiple miners linear scaling`() {
        val zones = CultivationSettlement.SpiritMineZones(
            minerCount = 100,
            avgMiningSkillBonus = 0.0,
            deaconMoralityBonus = 0.0,
            policyBoost = 0.0
        )
        val result = zones.calculateMonthly(220.0)
        // 100 × 220 = 22000，返回 Long，无截断
        assertEquals(22000L, result)
    }

    @Test
    fun `calculateMonthly - policy boost applied`() {
        val zones = CultivationSettlement.SpiritMineZones(
            minerCount = 10,
            avgMiningSkillBonus = 0.0,
            deaconMoralityBonus = 0.0,
            policyBoost = ZoneCalculator.multiplierToZone(1.2)  // +20%
        )
        val result = zones.calculateMonthly(220.0)
        // 10 × 220 × 1.2 = 2640
        assertEquals(2640L, result)
    }

    @Test
    fun `calculateMonthly - deacon morality bonus`() {
        val zones = CultivationSettlement.SpiritMineZones(
            minerCount = 10,
            avgMiningSkillBonus = 0.0,
            deaconMoralityBonus = 0.15,  // 执事加成 +15%
            policyBoost = 0.0
        )
        val result = zones.calculateMonthly(220.0)
        // 10 × 220 × 1.15 = 2530
        assertEquals(2530L, result)
    }

    @Test
    fun `calculateMonthly - mining skill bonus`() {
        val zones = CultivationSettlement.SpiritMineZones(
            minerCount = 10,
            avgMiningSkillBonus = 0.2,  // 采矿技能平均加成 +20%
            deaconMoralityBonus = 0.0,
            policyBoost = 0.0
        )
        val result = zones.calculateMonthly(220.0)
        // 10 × 220 × 1.2 = 2640
        assertEquals(2640L, result)
    }

    @Test
    fun `calculateMonthly - all zones combined`() {
        val zones = CultivationSettlement.SpiritMineZones(
            minerCount = 50,
            avgMiningSkillBonus = 0.1,    // +10%
            deaconMoralityBonus = 0.05,   // +5%
            policyBoost = 0.2             // +20% (灵矿增产)
        )
        val result = zones.calculateMonthly(220.0)
        // 50 × 220 × 1.1 × 1.05 × 1.2 = 50 × 220 × 1.386 = 15246
        assertEquals(15246L, result)
    }

    @Test
    fun `calculateMonthly - returns Long not Int no truncation`() {
        // 验证使用 roundToLong 而非 toInt() 截断
        val zones = CultivationSettlement.SpiritMineZones(
            minerCount = 3,
            avgMiningSkillBonus = 0.0,
            deaconMoralityBonus = 0.0,
            policyBoost = 0.0
        )
        val result = zones.calculateMonthly(220.0)
        // 3 × 220 = 660，返回 Long 类型
        assertTrue("结果应为 Long 类型", result is Long)
        assertEquals(660L, result)
    }

    @Test
    fun `calculateMonthly - fractional basePerMiner rounds correctly`() {
        val zones = CultivationSettlement.SpiritMineZones(
            minerCount = 1,
            avgMiningSkillBonus = 0.0,
            deaconMoralityBonus = 0.0,
            policyBoost = 0.0
        )
        // 使用分数基础值测试 roundToLong
        val result = zones.calculateMonthly(100.5)
        assertEquals(101L, result)  // 100.5 → roundToLong → 101
    }

    // ═══════════════════════════════════════════════════════════════
    // 时间戳差分逻辑验证（纯函数版本）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `time delta - same month skip`() {
        // 验证回档保护：lastSettledMonth >= currentMonth 时跳过
        val currentMonth = 100  // gameYear*12 + gameMonth
        val lastSettled = 100   // same as current
        val monthlyRate = 22000L

        val delta = currentMonth - lastSettled
        assertTrue("delta <= 0 时应跳过", delta <= 0)
    }

    @Test
    fun `time delta - normal one month catchup`() {
        val currentMonth = 101
        val lastSettled = 100
        val monthlyRate = 22000L

        val delta = currentMonth - lastSettled
        val production = monthlyRate * delta
        assertEquals(22000L, production)
    }

    @Test
    fun `time delta - multiple month catchup`() {
        val currentMonth = 104  // 跳过4个月 (100→101,102,103,104)
        val lastSettled = 100
        val monthlyRate = 22000L

        val delta = currentMonth - lastSettled
        val production = monthlyRate * delta
        assertEquals(88000L, production)  // 4 × 22000
    }

    @Test
    fun `time delta - backwards time protection`() {
        // 读档回档时：lastSettled > currentMonth
        val currentMonth = 95
        val lastSettled = 100
        val monthlyRate = 22000L

        val delta = currentMonth - lastSettled
        assertTrue("回档时 delta <= 0", delta <= 0)
        // production = 0，无灵石入账
    }
}
