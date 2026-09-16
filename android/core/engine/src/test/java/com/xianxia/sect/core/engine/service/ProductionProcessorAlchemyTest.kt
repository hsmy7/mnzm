package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.Herb
import org.junit.Assert.*
import org.junit.Test

/**
 * ProductionProcessor 炼丹完成逻辑纯函数测试（findBestCraftableRecipe 边界条件）。
 *
 * 旧 PillGrade.random() 概率分布用例随该死函数删除（W4-D/D5——生产炼丹品阶
 * 已走分区 RNG，无参裸抽取重载零生产调用方）。
 */
class ProductionProcessorAlchemyTest {

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
