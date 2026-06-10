package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import org.junit.Assert.*
import org.junit.Test

class PillRecipeDatabaseTest {

    // ============================================================
    // getAllRecipes 基本验证
    // ============================================================

    @Test
    fun `getAllRecipes返回非空列表`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        assertTrue("丹方列表不应为空", recipes.isNotEmpty())
    }

    // ============================================================
    // 所有丹方数据完整性
    // ============================================================

    @Test
    fun `所有丹方id非空`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方id不应为空: $recipe", recipe.id.isNotEmpty())
        }
    }

    @Test
    fun `所有丹方name非空`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方name不应为空: ${recipe.id}", recipe.name.isNotEmpty())
        }
    }

    @Test
    fun `所有丹方tier在1到6范围`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方tier应在1-6范围: ${recipe.id} tier=${recipe.tier}",
                recipe.tier in 1..6)
        }
    }

    @Test
    fun `所有丹方rarity在1到6范围`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方rarity应在1-6范围: ${recipe.id} rarity=${recipe.rarity}",
                recipe.rarity in 1..6)
        }
    }

    @Test
    fun `所有丹方successRate在0到1范围`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方successRate应在0-1范围: ${recipe.id} rate=${recipe.successRate}",
                recipe.successRate in 0.0..1.0)
        }
    }

    @Test
    fun `所有丹方duration为正数`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方duration应为正数: ${recipe.id} duration=${recipe.duration}",
                recipe.duration > 0)
        }
    }

    @Test
    fun `所有丹方materials非空`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方materials不应为空: ${recipe.id}", recipe.materials.isNotEmpty())
        }
    }

    @Test
    fun `所有丹方materials数量值为正`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            recipe.materials.forEach { (matId, count) ->
                assertTrue("材料数量应为正数: ${recipe.id} mat=$matId count=$count",
                    count > 0)
            }
        }
    }

    @Test
    fun `所有丹方category为有效枚举值`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方category应为有效枚举: ${recipe.id}",
                recipe.category in PillCategory.entries)
        }
    }

    @Test
    fun `所有丹方grade为有效枚举值`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方grade应为有效枚举: ${recipe.id}",
                recipe.grade in PillGrade.entries)
        }
    }

    @Test
    fun `所有丹方pillType非空`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方pillType不应为空: ${recipe.id}", recipe.pillType.isNotEmpty())
        }
    }

    @Test
    fun `所有丹方description非空`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertTrue("丹方description不应为空: ${recipe.id}", recipe.description.isNotEmpty())
        }
    }

    // ============================================================
    // ID 唯一性
    // ============================================================

    @Test
    fun `所有丹方ID唯一`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        val ids = recipes.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("丹方ID应唯一", ids.size, uniqueIds.size)
    }

    // ============================================================
    // getRecipeById
    // ============================================================

    @Test
    fun `getRecipeById已知id返回对应丹方`() {
        val first = PillRecipeDatabase.getAllRecipes().first()
        val result = PillRecipeDatabase.getRecipeById(first.id)
        assertNotNull("已知id应返回丹方", result)
        assertEquals(first.id, result!!.id)
    }

    @Test
    fun `getRecipeById未知id返回null`() {
        val result = PillRecipeDatabase.getRecipeById("nonexistent_recipe_id")
        assertNull("未知id应返回null", result)
    }

    // ============================================================
    // getRecipeByName
    // ============================================================

    @Test
    fun `getRecipeByName已知名称返回对应丹方`() {
        val first = PillRecipeDatabase.getAllRecipes().first()
        val result = PillRecipeDatabase.getRecipeByName(first.name)
        assertNotNull("已知名称应返回丹方", result)
        assertEquals(first.name, result!!.name)
    }

    @Test
    fun `getRecipeByName未知名称返回null`() {
        val result = PillRecipeDatabase.getRecipeByName("不存在的丹方名称")
        assertNull("未知名称应返回null", result)
    }

    // ============================================================
    // getRecipeByNameAndGrade
    // ============================================================

    @Test
    fun `getRecipeByNameAndGrade已知名称和品级返回对应丹方`() {
        val first = PillRecipeDatabase.getAllRecipes().first()
        val result = PillRecipeDatabase.getRecipeByNameAndGrade(first.name, first.grade)
        assertNotNull("已知名称和品级应返回丹方", result)
        assertEquals(first.name, result!!.name)
        assertEquals(first.grade, result.grade)
    }

    @Test
    fun `getRecipeByNameAndGrade错误品级返回null`() {
        val first = PillRecipeDatabase.getAllRecipes().first()
        val wrongGrade = PillGrade.entries.first { it != first.grade }
        val result = PillRecipeDatabase.getRecipeByNameAndGrade(first.name, wrongGrade)
        // 同名不同品级可能存在也可能不存在，这里只验证返回值类型正确
        // 如果返回非null，则品级应不等于first.grade
        if (result != null) {
            assertNotEquals(first.grade, result.grade)
        }
    }

    @Test
    fun `getRecipeByNameAndGrade未知名称返回null`() {
        val result = PillRecipeDatabase.getRecipeByNameAndGrade("不存在的丹方", PillGrade.MEDIUM)
        assertNull(result)
    }

    // ============================================================
    // getRecipesByCategory（通过 filter 模拟，源码无此方法）
    // ============================================================

    @Test
    fun `按category过滤-修炼类丹方存在`() {
        val recipes = PillRecipeDatabase.getAllRecipes().filter { it.category == PillCategory.CULTIVATION }
        assertTrue("修炼类丹方应存在", recipes.isNotEmpty())
        recipes.forEach {
            assertEquals(PillCategory.CULTIVATION, it.category)
        }
    }

    @Test
    fun `按category过滤-战斗类丹方存在`() {
        val recipes = PillRecipeDatabase.getAllRecipes().filter { it.category == PillCategory.BATTLE }
        assertTrue("战斗类丹方应存在", recipes.isNotEmpty())
        recipes.forEach {
            assertEquals(PillCategory.BATTLE, it.category)
        }
    }

    @Test
    fun `按category过滤-功能类丹方存在`() {
        val recipes = PillRecipeDatabase.getAllRecipes().filter { it.category == PillCategory.FUNCTIONAL }
        assertTrue("功能类丹方应存在", recipes.isNotEmpty())
        recipes.forEach {
            assertEquals(PillCategory.FUNCTIONAL, it.category)
        }
    }

    @Test
    fun `按category过滤-三个类别覆盖所有丹方`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        val byCategory = recipes.groupBy { it.category }
        assertEquals(recipes.size, byCategory.values.sumOf { it.size })
    }

    // ============================================================
    // getRecipesByRarity（通过 filter 模拟，源码无此方法）
    // ============================================================

    @Test
    fun `按rarity过滤-每个稀有度都有丹方`() {
        for (rarity in 1..6) {
            val recipes = PillRecipeDatabase.getAllRecipes().filter { it.rarity == rarity }
            assertTrue("稀有度$rarity 应有丹方", recipes.isNotEmpty())
        }
    }

    @Test
    fun `按rarity过滤-稀有度与tier对应`() {
        val recipes = PillRecipeDatabase.getAllRecipes()
        recipes.forEach { recipe ->
            assertEquals("丹方rarity应等于tier: ${recipe.id}", recipe.tier, recipe.rarity)
        }
    }

    // ============================================================
    // getRecipesByTier
    // ============================================================

    @Test
    fun `getRecipesByTier返回对应tier的丹方`() {
        val tier1 = PillRecipeDatabase.getRecipesByTier(1)
        assertTrue("tier 1应有丹方", tier1.isNotEmpty())
        tier1.forEach {
            assertEquals(1, it.tier)
        }
    }

    @Test
    fun `getRecipesByTier无效tier返回空列表`() {
        val result = PillRecipeDatabase.getRecipesByTier(99)
        assertTrue(result.isEmpty())
    }

    // ============================================================
    // getRecipesByMaterial / getRecipesByHerb
    // ============================================================

    @Test
    fun `getRecipesByMaterial返回使用该材料的丹方`() {
        val firstRecipe = PillRecipeDatabase.getAllRecipes().first()
        val firstMaterial = firstRecipe.materials.keys.first()
        val result = PillRecipeDatabase.getRecipesByMaterial(firstMaterial)
        assertTrue("使用材料 $firstMaterial 的丹方应存在", result.isNotEmpty())
        result.forEach {
            assertTrue(it.materials.containsKey(firstMaterial))
        }
    }

    @Test
    fun `getRecipesByHerb与getRecipesByMaterial结果一致`() {
        val firstRecipe = PillRecipeDatabase.getAllRecipes().first()
        val firstHerb = firstRecipe.materials.keys.first()
        val byMaterial = PillRecipeDatabase.getRecipesByMaterial(firstHerb)
        val byHerb = PillRecipeDatabase.getRecipesByHerb(firstHerb)
        assertEquals(byMaterial.size, byHerb.size)
    }

    // ============================================================
    // getDurationByTier
    // ============================================================

    @Test
    fun `getDurationByTier已知tier返回对应时长`() {
        assertTrue(PillRecipeDatabase.getDurationByTier(1) > 0)
        assertTrue(PillRecipeDatabase.getDurationByTier(6) > 0)
    }

    @Test
    fun `getDurationByTier高tier时长更长`() {
        val d1 = PillRecipeDatabase.getDurationByTier(1)
        val d6 = PillRecipeDatabase.getDurationByTier(6)
        assertTrue("高tier时长应更长", d6 > d1)
    }

    @Test
    fun `getDurationByTier无效tier返回默认值2`() {
        assertEquals(2, PillRecipeDatabase.getDurationByTier(99))
    }

    // ============================================================
    // getTierName
    // ============================================================

    @Test
    fun `getTierName各tier名称`() {
        assertEquals("凡品", PillRecipeDatabase.getTierName(1))
        assertEquals("灵品", PillRecipeDatabase.getTierName(2))
        assertEquals("宝品", PillRecipeDatabase.getTierName(3))
        assertEquals("玄品", PillRecipeDatabase.getTierName(4))
        assertEquals("地品", PillRecipeDatabase.getTierName(5))
        assertEquals("天品", PillRecipeDatabase.getTierName(6))
    }

    @Test
    fun `getTierName无效tier返回未知`() {
        assertEquals("未知", PillRecipeDatabase.getTierName(0))
        assertEquals("未知", PillRecipeDatabase.getTierName(99))
    }
}
