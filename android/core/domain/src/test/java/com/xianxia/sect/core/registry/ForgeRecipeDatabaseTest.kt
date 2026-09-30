package com.xianxia.sect.core.registry

import com.xianxia.sect.core.model.EquipmentSlot
import org.junit.Assert.*
import org.junit.Test

/**
 * 锻造配方库测试（五行属性伤害系统后：36 条套装部件配方，按品阶产出）。
 *
 * 覆盖：静态数据合法性、id/部件唯一性、材料表六档完整、时长/成功率取档、
 * 各查询入口（byId/byPiece/byMaterial/byType）与 getCraftableRecipes 恒全量
 * （配方不分 tier，产出品阶由锻造槽位 tier 决定——交接决策 3）。
 */
class ForgeRecipeDatabaseTest {

    // 1. 全量 36 条（6 套 × 6 部位，五行属性伤害系统）
    @Test
    fun allRecipes_is36_andCoversSetsAndParts() {
        val recipes = ForgeRecipeDatabase.getAllRecipes()
        assertEquals("应为 6 套 × 6 部位 = 36 条配方", 36, recipes.size)

        val setIds = recipes.map { it.setId }.toSet()
        assertEquals(
            "套装应恰为物理 + 五行六套",
            setOf("lietian", "gengjin", "qingmu", "xuanshui", "lihuo", "houtu"),
            setIds
        )

        for (setId in setIds) {
            val parts = recipes.filter { it.setId == setId }.map { it.part }.toSet()
            assertEquals(
                "套装 $setId 应覆盖全部六部位",
                EquipmentSlot.entries.toSet(),
                parts
            )
        }
    }

    // 2. 静态数据合法（id/名称/描述/部件存在/材料表六档）
    @Test
    fun allRecipes_haveValidData() {
        for (recipe in ForgeRecipeDatabase.getAllRecipes()) {
            assertTrue("Recipe ${recipe.id} id 空白", recipe.id.isNotBlank())
            assertTrue("Recipe ${recipe.id} name 空白", recipe.name.isNotBlank())
            assertTrue("Recipe ${recipe.id} description 空白", recipe.description.isNotBlank())

            assertNotNull(
                "Recipe ${recipe.id} 引用的部件在 EquipmentDatabase 不存在: ${recipe.pieceId}",
                EquipmentDatabase.getPieceById(recipe.pieceId)
            )
            assertEquals(
                "Recipe ${recipe.id} 的 setId 应与部件模板一致",
                EquipmentDatabase.getPieceById(recipe.pieceId)!!.setId,
                recipe.setId
            )

            assertEquals(
                "Recipe ${recipe.id} 材料表应为品阶 1..6 六档",
                6,
                recipe.tierMaterials.size
            )
            for (tier in 1..6) {
                val materials = recipe.materialsFor(tier)
                assertTrue(
                    "Recipe ${recipe.id} tier $tier 材料表为空",
                    materials.isNotEmpty()
                )
                for ((materialId, quantity) in materials) {
                    assertTrue(
                        "Recipe ${recipe.id} tier $tier 材料 id 空白",
                        materialId.isNotBlank()
                    )
                    assertTrue(
                        "Recipe ${recipe.id} tier $tier 材料 $materialId 数量非正: $quantity",
                        quantity > 0
                    )
                }
            }
        }
    }

    // 3. id 唯一
    @Test
    fun allRecipes_haveUniqueIds() {
        val ids = ForgeRecipeDatabase.getAllRecipes().map { it.id }
        assertEquals("存在重复配方 id", ids.size, ids.toSet().size)
    }

    // 4. 时长/成功率按 tier 取档且与全局表一致
    @Test
    fun durationAndSuccessRate_followTierTables() {
        assertEquals(6, ForgeRecipeDatabase.TIER_DURATION.size)
        assertEquals(6, ForgeRecipeDatabase.TIER_SUCCESS_RATE.size)

        val recipe = ForgeRecipeDatabase.getAllRecipes().first()
        for (tier in 1..6) {
            assertEquals(
                "durationFor($tier) 应与 TIER_DURATION 一致",
                ForgeRecipeDatabase.TIER_DURATION[tier],
                recipe.durationFor(tier)
            )
            assertEquals(
                "successRateFor($tier) 应与 TIER_SUCCESS_RATE 一致",
                ForgeRecipeDatabase.TIER_SUCCESS_RATE[tier - 1],
                recipe.successRateFor(tier),
                1e-9
            )
            assertTrue(
                "tier $tier 成功率应在 (0,1]",
                recipe.successRateFor(tier) > 0.0 && recipe.successRateFor(tier) <= 1.0
            )
            assertTrue("tier $tier 时长应为正", recipe.durationFor(tier) > 0)
        }
    }

    // 5. getRecipeById 命中/未命中
    @Test
    fun getRecipeById_knownAndUnknown() {
        val knownId = ForgeRecipeDatabase.getAllRecipes().first().id
        val result = ForgeRecipeDatabase.getRecipeById(knownId)
        assertNotNull("已知 id 应命中", result)
        assertEquals(knownId, result!!.id)

        assertNull("未知 id 应返回 null", ForgeRecipeDatabase.getRecipeById("nonExistentRecipeId12345"))
    }

    // 6. getRecipeByPiece 与部件一一对应
    @Test
    fun getRecipeByPiece_roundTripsAllPieces() {
        for (recipe in ForgeRecipeDatabase.getAllRecipes()) {
            val byPiece = ForgeRecipeDatabase.getRecipeByPiece(recipe.pieceId)
            assertNotNull("部件 ${recipe.pieceId} 应反查到配方", byPiece)
            assertEquals(recipe.id, byPiece!!.id)
        }
        assertNull("未知部件应返回 null", ForgeRecipeDatabase.getRecipeByPiece("no_such_piece"))
    }

    // 7. getRecipesByType 每部位恰 6 条（六套各一）
    @Test
    fun getRecipesByType_returnsSixRecipesPerPart() {
        for (part in EquipmentSlot.entries) {
            val recipes = ForgeRecipeDatabase.getRecipesByType(part)
            assertEquals("部位 $part 应有 6 条配方（六套各一）", 6, recipes.size)
            assertTrue(
                "部位 $part 的配方 part 字段应一致",
                recipes.all { it.part == part }
            )
        }
    }

    // 8. getRecipesByMaterial 命中与未命中
    @Test
    fun getRecipesByMaterial_hitsAndMisses() {
        val byBearHide = ForgeRecipeDatabase.getRecipesByMaterial("bearHide0")
        assertTrue("bearHide0 应被头部配方引用", byBearHide.isNotEmpty())
        assertTrue(
            "引用 bearHide0 的配方材料表应确含该材料",
            byBearHide.all { recipe -> recipe.tierMaterials.any { it.containsKey("bearHide0") } }
        )
        assertTrue("未知材料应返回空", ForgeRecipeDatabase.getRecipesByMaterial("no_such_material").isEmpty())
    }

    // 9. getCraftableRecipes 恒全量（配方不分 tier，产出品阶由槽位 tier 决定）
    @Test
    fun getCraftableRecipes_alwaysReturnsAll() {
        for (maxTier in 0..7) {
            assertEquals(
                "getCraftableRecipes($maxTier) 应恒返回全部 36 条",
                ForgeRecipeDatabase.getAllRecipes().size,
                ForgeRecipeDatabase.getCraftableRecipes(maxTier).size
            )
        }
    }

    // 10. getDurationByTier 越界回退
    @Test
    fun getDurationByTier_knownAndFallback() {
        assertEquals(3, ForgeRecipeDatabase.getDurationByTier(1))
        assertEquals(120, ForgeRecipeDatabase.getDurationByTier(6))
        assertEquals("越界 tier 回退 2", 2, ForgeRecipeDatabase.getDurationByTier(99))
    }
}
