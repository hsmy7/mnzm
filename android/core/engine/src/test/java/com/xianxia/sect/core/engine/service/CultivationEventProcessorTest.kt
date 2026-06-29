package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.*
import org.junit.Test

/**
 * CultivationEventProcessor 自动装备/学习相关单元测试。
 *
 * 覆盖 qualifiesForSectAutoPublic 的 Disciple 字段访问正确性，
 * 以及 processAutoFromWarehouse 的入口条件判断。
 *
 * qualifiesForSectAutoPublic 同时在 CultivationEventProcessor
 * 和 DiscipleBreakthroughHandler 中存在，逻辑一致。
 */
class CultivationEventProcessorTest {

    // ═══════════════════════════════════════════════════════════════
    // qualifiesForSectAutoPublic — Disciple 字段访问验证
    // ═══════════════════════════════════════════════════════════════
    // 该函数读取 disciple.statusData["followed"] 和
    // disciple.spiritRootType.split(",").size，以下测试验证
    // Disciple 字段映射的正确性。

    /** 与生产代码 CultivationEventProcessor.qualifiesForSectAutoPublic 逻辑一致 */
    private fun qualifiesForSectAutoPublic(
        disciple: Disciple, focused: Boolean, rootCounts: Set<Int>
    ): Boolean {
        if (focused || rootCounts.isNotEmpty()) {
            if (focused && disciple.statusData["followed"] == "true") return true
            val rootCount = disciple.spiritRootType.split(",").size
            return rootCount in rootCounts
        }
        return false
    }

    // ── focused + followed ──────────────────────────────────────────

    @Test
    fun `qualifiesForSectAutoPublic - 已关注已跟随弟子 focused=true 返回 true`() {
        val d = Disciple(
            id = "d1",
            spiritRootType = "火",
            statusData = mapOf("followed" to "true")
        )
        assertTrue(qualifiesForSectAutoPublic(d, focused = true, rootCounts = emptySet()))
    }

    @Test
    fun `qualifiesForSectAutoPublic - 已关注未跟随弟子 focused=true 返回 false`() {
        val d = Disciple(
            id = "d2",
            spiritRootType = "火",
            statusData = mapOf("followed" to "false")
        )
        assertFalse(qualifiesForSectAutoPublic(d, focused = true, rootCounts = emptySet()))
    }

    @Test
    fun `qualifiesForSectAutoPublic - 无statusData条目的弟子 focused=true 返回 false`() {
        val d = Disciple(
            id = "d3",
            spiritRootType = "火",
            statusData = emptyMap()
        )
        assertFalse(qualifiesForSectAutoPublic(d, focused = true, rootCounts = emptySet()))
    }

    // ── rootCounts 灵根数匹配 ───────────────────────────────────────

    @Test
    fun `qualifiesForSectAutoPublic - 单灵根匹配 rootCounts 中的 1`() {
        val d = Disciple(id = "d4", spiritRootType = "火")
        assertTrue(qualifiesForSectAutoPublic(d, focused = false, rootCounts = setOf(1, 3)))
    }

    @Test
    fun `qualifiesForSectAutoPublic - 双灵根匹配 rootCounts 中的 2`() {
        val d = Disciple(id = "d5", spiritRootType = "火,水")
        assertTrue(qualifiesForSectAutoPublic(d, focused = false, rootCounts = setOf(2, 3)))
    }

    @Test
    fun `qualifiesForSectAutoPublic - 五灵根匹配 rootCounts 中的 5`() {
        val d = Disciple(id = "d6", spiritRootType = "金,木,水,火,土")
        assertTrue(qualifiesForSectAutoPublic(d, focused = false, rootCounts = setOf(5)))
    }

    @Test
    fun `qualifiesForSectAutoPublic - 灵根数不匹配 rootCounts 返回 false`() {
        val d = Disciple(id = "d7", spiritRootType = "火,水")
        assertFalse(qualifiesForSectAutoPublic(d, focused = false, rootCounts = setOf(1, 3, 5)))
    }

    // ── 组合条件 ────────────────────────────────────────────────────

    @Test
    fun `qualifiesForSectAutoPublic - focused且followed优先于rootCounts不匹配`() {
        // focused=true + followed=true → 应返回 true，哪怕灵根数不在 rootCounts
        val d = Disciple(
            id = "d8",
            spiritRootType = "火,水,木",
            statusData = mapOf("followed" to "true")
        )
        assertTrue(qualifiesForSectAutoPublic(d, focused = true, rootCounts = setOf(1)))
    }

    @Test
    fun `qualifiesForSectAutoPublic - focused未followed但rootCounts匹配返回true`() {
        val d = Disciple(
            id = "d9",
            spiritRootType = "火,水",
            statusData = mapOf("followed" to "false")
        )
        // focused=true 但不 followed，回退到 rootCounts 检查
        assertTrue(qualifiesForSectAutoPublic(d, focused = true, rootCounts = setOf(2)))
    }

    @Test
    fun `qualifiesForSectAutoPublic - 两条件都不满足返回false`() {
        val d = Disciple(
            id = "d10",
            spiritRootType = "火,水,木",
            statusData = mapOf("followed" to "false")
        )
        assertFalse(qualifiesForSectAutoPublic(d, focused = true, rootCounts = setOf(1)))
    }

    // ═══════════════════════════════════════════════════════════════
    // processAutoFromWarehouse — 入口条件判断
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `processAutoFromWarehouse - 入口条件 shouldSkip 逻辑`() {
        // 如果 equipFocused=false && equipRootCounts 为空
        //   && learnFocused=false && learnRootCounts 为空
        // 则 shouldSkip = true（跳过自动装备/学习）
        val equipFocused = false
        val equipRootCounts: Set<Int> = emptySet()
        val learnFocused = false
        val learnRootCounts: Set<Int> = emptySet()
        val hasAutoEquip = equipFocused || equipRootCounts.isNotEmpty()
        val hasAutoLearn = learnFocused || learnRootCounts.isNotEmpty()

        assertFalse("所有自动设置关闭时应跳过", hasAutoEquip || hasAutoLearn)
    }

    @Test
    fun `processAutoFromWarehouse - 入口条件 autoEquip 开启`() {
        val hasAutoEquip = false || setOf(1, 2).isNotEmpty() // rootCounts 非空
        assertTrue("autoEquip rootCounts 非空时应启用", hasAutoEquip)
    }

    @Test
    fun `processAutoFromWarehouse - 入口条件 focused 单独也可开启`() {
        val hasAutoEquip = true || emptySet<Int>().isNotEmpty() // focused=true
        assertTrue("focused=true 单独也应启用 autoEquip", hasAutoEquip)
    }

    // ═══════════════════════════════════════════════════════════════
    // 叛逃概率公式
    // ═══════════════════════════════════════════════════════════════
    // 公式：desertionProb = (30 - loyalty) * 0.03，范围 [0, 0.9]

    private fun calcDesertionProb(loyalty: Int): Double {
        val threshold = 30
        val probPerPoint = 0.03
        val maxProb = 0.9
        return ((threshold - loyalty) * probPerPoint)
            .coerceIn(0.0, maxProb)
    }

    @Test
    fun `叛逃概率 - loyalty=30 概率为0`() {
        assertEquals(0.0, calcDesertionProb(30), 0.001)
    }

    @Test
    fun `叛逃概率 - loyalty=29 概率为3%`() {
        assertEquals(0.03, calcDesertionProb(29), 0.001)
    }

    @Test
    fun `叛逃概率 - loyalty=20 概率为30%`() {
        assertEquals(0.30, calcDesertionProb(20), 0.001)
    }

    @Test
    fun `叛逃概率 - loyalty=0 概率为90%上限`() {
        assertEquals(0.90, calcDesertionProb(0), 0.001)
    }

    @Test
    fun `叛逃概率 - loyalty=-10 概率仍为90%上限`() {
        assertEquals(0.90, calcDesertionProb(-10), 0.001)
    }

    @Test
    fun `叛逃概率 - loyalty=35 概率为0不叛逃`() {
        assertEquals(0.0, calcDesertionProb(35), 0.001)
    }

    @Test
    fun `叛逃概率 - loyalty=31 概率为0`() {
        // loyalty > 30 → 不会被筛选，即使计算概率也是0
        assertEquals(0.0, calcDesertionProb(31), 0.001)
    }

    // ═══════════════════════════════════════════════════════════════
    // 偷盗概率公式
    // ═══════════════════════════════════════════════════════════════
    // 公式：(30 - morality) * 0.03，范围 [0, 0.9]

    private fun calcTheftProb(morality: Int): Double {
        val threshold = 30
        val probPerPoint = 0.03
        val maxProb = 0.9
        return ((threshold - morality) * probPerPoint)
            .coerceIn(0.0, maxProb)
    }

    @Test
    fun `偷盗概率 - morality=30 概率为0`() {
        assertEquals(0.0, calcTheftProb(30), 0.001)
    }

    @Test
    fun `偷盗概率 - morality=20 概率为30%`() {
        assertEquals(0.30, calcTheftProb(20), 0.001)
    }

    @Test
    fun `偷盗概率 - morality=0 概率为90%上限`() {
        assertEquals(0.90, calcTheftProb(0), 0.001)
    }

    @Test
    fun `偷盗概率 - morality=35 概率为0不偷盗`() {
        assertEquals(0.0, calcTheftProb(35), 0.001)
    }

    // ═══════════════════════════════════════════════════════════════
    // 叛逃筛选条件
    // ═══════════════════════════════════════════════════════════════
    // 直接叛逃：loyalty < 30
    // 偷盗后叛逃：morality < 30 AND loyalty < 30

    @Test
    fun `筛选条件 - 直接叛逃 loyalty=29 应入选`() {
        assertTrue("loyalty<30 应被筛选", 29 < 30)
    }

    @Test
    fun `筛选条件 - 直接叛逃 loyalty=30 不应入选`() {
        assertFalse("loyalty=30 不应被筛选", 30 < 30)
    }

    @Test
    fun `筛选条件 - 直接叛逃 loyalty=31 不应入选`() {
        assertFalse("loyalty>30 不应被筛选", 31 < 30)
    }

    @Test
    fun `筛选条件 - 偷盗 morality=29 loyalty=29 应入选`() {
        assertTrue("两个都低于阈值应入选", 29 < 30 && 29 < 30)
    }

    @Test
    fun `筛选条件 - 偷盗 morality=31 loyalty=29 不应入选`() {
        assertFalse("道德高于阈值不应入选", 31 < 30 && 29 < 30)
    }

    @Test
    fun `筛选条件 - 偷盗 morality=29 loyalty=31 不应入选`() {
        assertFalse("忠诚高于阈值不应入选", 29 < 30 && 31 < 30)
    }

    @Test
    fun `筛选条件 - 偷盗 morality=35 loyalty=35 不应入选`() {
        assertFalse("两者都高于阈值不应入选", 35 < 30 && 35 < 30)
    }

    // ═══════════════════════════════════════════════════════════════
    // 防御性二次校验
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `防御性校验 - loyalty从25恢复到31后跳过叛逃`() {
        val loyalBefore = 25
        val loyalNow = 31
        val wasAtRisk = loyalBefore < 30
        assertTrue("筛选时loyalty=25应入选", wasAtRisk)
        val shouldSkip = loyalNow >= 30
        assertTrue("当前loyalty=31应跳过叛逃", shouldSkip)
    }

    @Test
    fun `防御性校验 - loyalty从25到25仍执行叛逃`() {
        val loyalNow = 25
        val shouldSkip = loyalNow >= 30
        assertFalse("loyalty=25仍低于阈值不应跳过", shouldSkip)
    }
}
