package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.Herb
import org.junit.Assert.*
import org.junit.Test

/**
 * ProductionProcessor 炼丹完成逻辑纯函数测试。
 *
 * 覆盖 processAlchemyCompletion 中的 PillGrade.random()
 * 概率分布和 findBestCraftableRecipe 的边界条件。
 */
class ProductionProcessorAlchemyTest {

    // ═══════════════════════════════════════════════════════════════
    // PillGrade.random() — 概率分布
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `PillGrade random - probability distribution within tolerance`() {
        val trials = 10000
        val counts = IntArray(3)
        repeat(trials) { counts[com.xianxia.sect.core.model.PillGrade.random().ordinal]++ }
        val lowPct = counts[0].toDouble() / trials
        val medPct = counts[1].toDouble() / trials
        val highPct = counts[2].toDouble() / trials

        // LOW: 60%, MEDIUM: 34%, HIGH: 6% — 允许 ±4% 误差
        assertEquals(0.60, lowPct, 0.04)
        assertEquals(0.34, medPct, 0.04)
        assertEquals(0.06, highPct, 0.03)
    }

    // ═══════════════════════════════════════════════════════════════
    // findBestCraftableRecipe — 边界条件
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `findBestCraftableRecipe - empty herbs returns null`() {
        val result = PillRecipeDatabase.findBestCraftableRecipe(emptyList())
        assertNull(result)
    }

    @Test
    fun `findBestCraftableRecipe - insufficient herbs returns null`() {
        // 使用极少数量的草药，确保任何配方都不满足
        val herbs = listOf(
            Herb(name = "清心草", rarity = 1, quantity = 0, category = "grass", description = "")
        )
        val result = PillRecipeDatabase.findBestCraftableRecipe(herbs)
        assertNull(result)
    }
}
