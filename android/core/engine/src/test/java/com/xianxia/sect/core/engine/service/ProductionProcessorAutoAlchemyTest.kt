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

    @Test
    fun `findBestCraftableRecipe - sorts by tier descending then rarity descending`() {
        // 提供全部 6 阶草药来验证 findBestCraftableRecipe 返回最高阶配方
        val herbs = listOf(
            // tier 1
            Herb(name = "聚灵草", rarity = 1, quantity = 999, category = "grass", description = ""),
            Herb(name = "清心草", rarity = 1, quantity = 999, category = "grass", description = ""),
            Herb(name = "凝气草", rarity = 1, quantity = 999, category = "grass", description = ""),
            Herb(name = "云雾花", rarity = 1, quantity = 999, category = "flower", description = ""),
            Herb(name = "白莲", rarity = 1, quantity = 999, category = "flower", description = ""),
            Herb(name = "晨露花", rarity = 1, quantity = 999, category = "flower", description = ""),
            Herb(name = "精气果", rarity = 1, quantity = 999, category = "fruit", description = ""),
            Herb(name = "赤心果", rarity = 1, quantity = 999, category = "fruit", description = ""),
            Herb(name = "灵韵果", rarity = 1, quantity = 999, category = "fruit", description = ""),
            // tier 2
            Herb(name = "寒霜草", rarity = 2, quantity = 999, category = "grass", description = ""),
            Herb(name = "烈焰草", rarity = 2, quantity = 999, category = "grass", description = ""),
            Herb(name = "金灵草", rarity = 2, quantity = 999, category = "grass", description = ""),
            Herb(name = "冰魄莲", rarity = 2, quantity = 999, category = "flower", description = ""),
            Herb(name = "双生花", rarity = 2, quantity = 999, category = "flower", description = ""),
            Herb(name = "紫霄花", rarity = 2, quantity = 999, category = "flower", description = ""),
            Herb(name = "通灵果", rarity = 2, quantity = 999, category = "fruit", description = ""),
            Herb(name = "玄灵果", rarity = 2, quantity = 999, category = "fruit", description = ""),
            Herb(name = "五行果", rarity = 2, quantity = 999, category = "fruit", description = ""),
            // tier 3
            Herb(name = "龙血草", rarity = 3, quantity = 999, category = "grass", description = ""),
            Herb(name = "风铃草", rarity = 3, quantity = 999, category = "grass", description = ""),
            Herb(name = "九转灵草", rarity = 3, quantity = 999, category = "grass", description = ""),
            Herb(name = "九转仙兰", rarity = 3, quantity = 999, category = "flower", description = ""),
            Herb(name = "凤凰花", rarity = 3, quantity = 999, category = "flower", description = ""),
            Herb(name = "青龙花", rarity = 3, quantity = 999, category = "flower", description = ""),
            Herb(name = "赤阳果", rarity = 3, quantity = 999, category = "fruit", description = ""),
            Herb(name = "玄灵莓", rarity = 3, quantity = 999, category = "fruit", description = ""),
            Herb(name = "天元果", rarity = 3, quantity = 999, category = "fruit", description = ""),
            // tier 4
            Herb(name = "玄冰草", rarity = 4, quantity = 999, category = "grass", description = ""),
            Herb(name = "风暴草", rarity = 4, quantity = 999, category = "grass", description = ""),
            Herb(name = "神命草", rarity = 4, quantity = 999, category = "grass", description = ""),
            Herb(name = "日月同辉花", rarity = 4, quantity = 999, category = "flower", description = ""),
            Herb(name = "紫云花", rarity = 4, quantity = 999, category = "flower", description = ""),
            Herb(name = "玄武花", rarity = 4, quantity = 999, category = "flower", description = ""),
            Herb(name = "长生果", rarity = 4, quantity = 999, category = "fruit", description = ""),
            Herb(name = "仙灵果", rarity = 4, quantity = 999, category = "fruit", description = ""),
            Herb(name = "天灵果", rarity = 4, quantity = 999, category = "fruit", description = ""),
            // tier 5
            Herb(name = "仙灵草", rarity = 5, quantity = 999, category = "grass", description = ""),
            Herb(name = "天灵草", rarity = 5, quantity = 999, category = "grass", description = ""),
            Herb(name = "混沌草", rarity = 5, quantity = 999, category = "grass", description = ""),
            Herb(name = "涅槃凤仙花", rarity = 5, quantity = 999, category = "flower", description = ""),
            Herb(name = "龙鳞仙莲", rarity = 5, quantity = 999, category = "flower", description = ""),
            Herb(name = "白虎幽兰", rarity = 5, quantity = 999, category = "flower", description = ""),
            Herb(name = "九叶还魂果", rarity = 5, quantity = 999, category = "fruit", description = ""),
            Herb(name = "玄天灵果", rarity = 5, quantity = 999, category = "fruit", description = ""),
            Herb(name = "星陨神果", rarity = 5, quantity = 999, category = "fruit", description = ""),
            // tier 6
            Herb(name = "鸿蒙草", rarity = 6, quantity = 999, category = "grass", description = ""),
            Herb(name = "太初草", rarity = 6, quantity = 999, category = "grass", description = ""),
            Herb(name = "永恒草", rarity = 6, quantity = 999, category = "grass", description = ""),
            Herb(name = "永恒花", rarity = 6, quantity = 999, category = "flower", description = ""),
            Herb(name = "混沌仙莲", rarity = 6, quantity = 999, category = "flower", description = ""),
            Herb(name = "造化神花", rarity = 6, quantity = 999, category = "flower", description = ""),
            Herb(name = "瑞麟仙果", rarity = 6, quantity = 999, category = "fruit", description = ""),
            Herb(name = "玄武帝果", rarity = 6, quantity = 999, category = "fruit", description = ""),
            Herb(name = "混沌神果", rarity = 6, quantity = 999, category = "fruit", description = "")
        )
        val recipe = PillRecipeDatabase.findBestCraftableRecipe(herbs)
        assertNotNull("全草药充足时应返回最高阶配方", recipe)
        assertTrue("应返回最高阶配方（全草药充足）", recipe!!.tier == 6)
    }

    @Test
    fun `findBestCraftableRecipe - prefers higher tier over lower tier`() {
        // 足够 1-3 阶所有草药 → 应返回 3 阶配方（材料充足时的最高阶）
        val herbs = listOf(
            // tier 1 草药
            Herb(name = "聚灵草", rarity = 1, quantity = 999, category = "grass", description = ""),
            Herb(name = "清心草", rarity = 1, quantity = 999, category = "grass", description = ""),
            Herb(name = "凝气草", rarity = 1, quantity = 999, category = "grass", description = ""),
            Herb(name = "云雾花", rarity = 1, quantity = 999, category = "flower", description = ""),
            Herb(name = "白莲", rarity = 1, quantity = 999, category = "flower", description = ""),
            Herb(name = "晨露花", rarity = 1, quantity = 999, category = "flower", description = ""),
            Herb(name = "精气果", rarity = 1, quantity = 999, category = "fruit", description = ""),
            Herb(name = "赤心果", rarity = 1, quantity = 999, category = "fruit", description = ""),
            Herb(name = "灵韵果", rarity = 1, quantity = 999, category = "fruit", description = ""),
            // tier 2 草药
            Herb(name = "寒霜草", rarity = 2, quantity = 999, category = "grass", description = ""),
            Herb(name = "烈焰草", rarity = 2, quantity = 999, category = "grass", description = ""),
            Herb(name = "金灵草", rarity = 2, quantity = 999, category = "grass", description = ""),
            Herb(name = "冰魄莲", rarity = 2, quantity = 999, category = "flower", description = ""),
            Herb(name = "双生花", rarity = 2, quantity = 999, category = "flower", description = ""),
            Herb(name = "紫霄花", rarity = 2, quantity = 999, category = "flower", description = ""),
            Herb(name = "通灵果", rarity = 2, quantity = 999, category = "fruit", description = ""),
            Herb(name = "玄灵果", rarity = 2, quantity = 999, category = "fruit", description = ""),
            Herb(name = "五行果", rarity = 2, quantity = 999, category = "fruit", description = ""),
            // tier 3 草药
            Herb(name = "龙血草", rarity = 3, quantity = 999, category = "grass", description = ""),
            Herb(name = "风铃草", rarity = 3, quantity = 999, category = "grass", description = ""),
            Herb(name = "九转灵草", rarity = 3, quantity = 999, category = "grass", description = ""),
            Herb(name = "九转仙兰", rarity = 3, quantity = 999, category = "flower", description = ""),
            Herb(name = "凤凰花", rarity = 3, quantity = 999, category = "flower", description = ""),
            Herb(name = "青龙花", rarity = 3, quantity = 999, category = "flower", description = ""),
            Herb(name = "赤阳果", rarity = 3, quantity = 999, category = "fruit", description = ""),
            Herb(name = "玄灵莓", rarity = 3, quantity = 999, category = "fruit", description = ""),
            Herb(name = "天元果", rarity = 3, quantity = 999, category = "fruit", description = "")
        )
        val recipe = PillRecipeDatabase.findBestCraftableRecipe(herbs)
        assertNotNull("3 阶草药充足时应返回 3 阶配方", recipe)
        assertTrue("应返回 3 阶或更高配方", recipe!!.tier >= 3)
    }
}
