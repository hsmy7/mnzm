package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.EquipLevelCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装备等级系统守卫（装备重构 B3，方案 §3.6/§6.1——替换孕养体系 R4）。
 *
 * 等级/经验只存 EquipmentInstance.growth 单点；本系统为纯函数：
 * Lv1 初始、30 封顶、满级后经验不再累计（溢出清零）、经验曲线
 * `100 × level × rarityMul`（倍率表声明序禁重排）、强化节点每 3 级一次。
 */
class EquipmentLevelSystemTest {

    // ── 等级边界 ──────────────────────────────────────────────

    @Test
    fun `等级边界常量与方案拍板一致`() {
        assertEquals("初始等级", 1, EquipLevelCurve.MIN_LEVEL)
        assertEquals("封顶等级", 30, EquipLevelCurve.MAX_LEVEL)
        assertEquals("强化间隔（每 3 级一次）", 3, EquipLevelCurve.REINFORCE_INTERVAL)
        assertEquals("单条副词条最大强化次数（初始 1 + 强化 10）", 11, EquipLevelCurve.MAX_SUB_ROLLS)
    }

    @Test
    fun `满级判定`() {
        assertFalse("Lv1 非满级", EquipmentLevelSystem.isMaxLevel(1))
        assertFalse("Lv29 非满级", EquipmentLevelSystem.isMaxLevel(29))
        assertTrue("Lv30 满级", EquipmentLevelSystem.isMaxLevel(30))
        assertTrue("越界高等级按满级", EquipmentLevelSystem.isMaxLevel(31))
    }

    @Test
    fun `满级后推进不获经验且溢出清零`() {
        // 满级后任何经验增益都被丢弃，exp 恒 0（S4）
        assertEquals(30 to 0, EquipmentLevelSystem.advance(30, 0, 999_999, 1))
        assertEquals(30 to 0, EquipmentLevelSystem.advance(30, 5, 100, 3))
    }

    @Test
    fun `等级输入越界先钳制再推进`() {
        // level 越界钳制到 [1, 30]
        val (lv, exp) = EquipmentLevelSystem.advance(0, 0, 0, 1)
        assertEquals(1, lv)
        assertEquals(0, exp)
    }

    // ── 经验曲线 ──────────────────────────────────────────────

    @Test
    fun `经验曲线为100x等级x品阶倍率`() {
        val multipliers = EquipLevelCurve.RARITY_EXP_MULTIPLIERS
        assertEquals("品阶倍率表六档", listOf(1.0, 1.5, 2.0, 3.0, 4.5, 6.0), multipliers)
        (1..6).forEach { rarity ->
            (1 until EquipLevelCurve.MAX_LEVEL).forEach { level ->
                val expected = (100 * level * multipliers[rarity - 1]).toInt()
                assertEquals("rarity=$rarity level=$level", expected, EquipLevelCurve.expRequired(level, rarity))
            }
        }
    }

    @Test
    fun `经验曲线随等级与品阶单调不减`() {
        (1..6).forEach { rarity ->
            val curve = (1 until EquipLevelCurve.MAX_LEVEL).map { EquipLevelCurve.expRequired(it, rarity) }
            curve.zipWithNext().forEach { (low, high) ->
                assertTrue("rarity=$rarity 经验曲线须单调不减：$low -> $high", high >= low)
            }
        }
        // 同等级下高品阶消耗更高（倍率表非降序）
        (1 until EquipLevelCurve.MAX_LEVEL).forEach { level ->
            val byRarity = (1..6).map { EquipLevelCurve.expRequired(it, level) }
            byRarity.zipWithNext().forEach { (low, high) ->
                assertTrue("level=$level 高品阶经验消耗不应更低：$low -> $high", high >= low)
            }
        }
    }

    @Test
    fun `推进逐级累积并在节点处升级`() {
        val rarity = 1
        val need1 = EquipLevelCurve.expRequired(1, rarity)
        // 半程不升级
        assertEquals(1 to need1 / 2, EquipmentLevelSystem.advance(1, 0, need1 / 2, rarity))
        // 恰好一级
        assertEquals(2 to 0, EquipmentLevelSystem.advance(1, 0, need1, rarity))
        // 一次性连升两级（节点逐级判定）
        val need2 = EquipLevelCurve.expRequired(2, rarity)
        assertEquals(3 to 0, EquipmentLevelSystem.advance(1, 0, need1 + need2, rarity))
        // 升级后残余经验结转
        val carry = need1 / 3
        assertEquals(2 to carry, EquipmentLevelSystem.advance(1, 0, need1 + carry, rarity))
        // 既有存量参与判定
        assertEquals(2 to 0, EquipmentLevelSystem.advance(1, need1 - 1, 1, rarity))
    }

    @Test
    fun `推进到满级即清零不保留溢出`() {
        val rarity = 1
        var level = 1
        var totalGain = 0
        for (lv in 1 until EquipLevelCurve.MAX_LEVEL) totalGain += EquipLevelCurve.expRequired(lv, rarity)
        val (finalLv, finalExp) = EquipmentLevelSystem.advance(level, 0, totalGain + 123_456, rarity)
        assertEquals("应恰好停在满级", EquipLevelCurve.MAX_LEVEL, finalLv)
        assertEquals("满级溢出清零", 0, finalExp)
    }

    // ── 强化节点 ──────────────────────────────────────────────

    @Test
    fun `强化节点为3的倍数且限界内`() {
        assertFalse(EquipmentLevelSystem.triggersReinforcement(1))
        assertFalse(EquipmentLevelSystem.triggersReinforcement(2))
        assertTrue(EquipmentLevelSystem.triggersReinforcement(3))
        assertTrue(EquipmentLevelSystem.triggersReinforcement(30))
        assertFalse("Lv33 越界（不可能到达）不触发", EquipmentLevelSystem.triggersReinforcement(33))
        assertEquals("Lv1..30 内强化节点共 10 次", 10, (1..30).count { EquipmentLevelSystem.triggersReinforcement(it) })
    }

    // ── 升级消耗与分解返还 ────────────────────────────────────

    @Test
    fun `灵石消耗为100x品阶平方x等级`() {
        assertEquals(100L, EquipmentLevelSystem.spiritStonesCost(1, 1))
        assertEquals(100L * 2 * 2 * 3, EquipmentLevelSystem.spiritStonesCost(3, 2))
        assertEquals(100L * 6 * 6 * 29, EquipmentLevelSystem.spiritStonesCost(29, 6))
    }

    @Test
    fun `兽材消耗为max1_等级除10`() {
        assertEquals(1, EquipmentLevelSystem.beastMaterialCost(1))
        assertEquals(1, EquipmentLevelSystem.beastMaterialCost(9))
        assertEquals(1, EquipmentLevelSystem.beastMaterialCost(10))
        assertEquals(2, EquipmentLevelSystem.beastMaterialCost(20))
        assertEquals("floor(2.9)=2", 2, EquipmentLevelSystem.beastMaterialCost(29))
        assertEquals(3, EquipmentLevelSystem.beastMaterialCost(30))
    }

    @Test
    fun `分解返还为累计消耗的50向下取整`() {
        assertEquals(EquipLevelCurve.DISMANTLE_REFUND_RATIO, 0.5, 1e-12)
        // Lv1 从未升级：返还 0
        val (stones0, beasts0) = EquipmentLevelSystem.dismantleRefund(1, 1)
        assertEquals(0L, stones0)
        assertEquals(0, beasts0)
        // 与逐级累加口径逐位一致（灵石 floor、兽材 floor）
        (1..6).forEach { rarity ->
            (2..EquipLevelCurve.MAX_LEVEL).forEach { level ->
                var stones = 0L
                var beasts = 0
                for (lv in EquipLevelCurve.MIN_LEVEL until level) {
                    stones += EquipmentLevelSystem.spiritStonesCost(lv, rarity)
                    beasts += EquipmentLevelSystem.beastMaterialCost(lv)
                }
                val (refundStones, refundBeasts) = EquipmentLevelSystem.dismantleRefund(rarity, level)
                assertEquals("rarity=$rarity level=$level 灵石返还", (stones * 0.5).toLong(), refundStones)
                assertEquals("rarity=$rarity level=$level 兽材返还", (beasts * 0.5).toInt(), refundBeasts)
            }
        }
    }

    // ── 主词条等级成长 ────────────────────────────────────────

    @Test
    fun `主词条等级成长乘数为每级10`() {
        assertEquals(1.0, EquipLevelCurve.mainLevelMultiplier(1), 1e-12)
        assertEquals(1.1, EquipLevelCurve.mainLevelMultiplier(2), 1e-12)
        assertEquals(1.0 + 0.10 * 29, EquipLevelCurve.mainLevelMultiplier(30), 1e-12)
    }
}
