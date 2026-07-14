package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.model.Herb
import org.junit.Assert.*
import org.junit.Test

/**
 * ProductionProcessor 自动炼丹逻辑纯函数测试。
 *
 * 覆盖 findBestCraftableRecipe 在自动炼丹场景中的表现。
 */
class ProductionProcessorAutoAlchemyTest {

    @Test
    fun `findBestCraftableRecipe - returns highest tier craftable`() {
        // 用大量的草药确保至少一个配方可合成
        val herbs = listOf(
            Herb(name = "清心草", rarity = 1, quantity = 999, category = "grass", description = ""),
            Herb(name = "赤心果", rarity = 1, quantity = 999, category = "fruit", description = ""),
            Herb(name = "凝露花", rarity = 1, quantity = 999, category = "flower", description = "")
        )
        val recipe = PillRecipeDatabase.findBestCraftableRecipe(herbs)
        assertNotNull("至少有一个 1 阶配方可合成", recipe)
        assertTrue("返回的配方 tier 应 ≥ 1", recipe!!.tier >= 1)
    }

    @Test
    fun `findBestCraftableRecipe - insufficient for higher tier falls back`() {
        // 只有 1 阶草药 → 应返回 1 阶配方（不可返回更高阶）
        val herbs = listOf(
            Herb(name = "清心草", rarity = 1, quantity = 2, category = "grass", description = ""),
            Herb(name = "赤心果", rarity = 1, quantity = 2, category = "fruit", description = "")
        )
        val recipe = PillRecipeDatabase.findBestCraftableRecipe(herbs)
        assertNotNull(recipe)
        assertEquals(1, recipe!!.tier)
    }

    @Test
    fun `findBestCraftableRecipe - exact materials match`() {
        val herbs = listOf(
            Herb(name = "清心草", rarity = 1, quantity = 2, category = "grass", description = ""),
            Herb(name = "赤心果", rarity = 1, quantity = 2, category = "fruit", description = "")
        )
        val recipe = PillRecipeDatabase.findBestCraftableRecipe(herbs)
        assertNotNull(recipe)
    }
}
