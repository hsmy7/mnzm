package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * RecipeRegistryGuardTest — 锻造/丹药配方静态数据守卫（批次 2 剩余子步）。
 *
 * 守护目标：生成器提取的配方表快照（recipe_db_sample.json，由
 * scripts/gen-recipe-db.mjs 生成）与 Kotlin ForgeRecipeDatabase/PillRecipeDatabase
 * **实时数据**一致（逐字段：id/name/tier/rarity/duration/successRate/materials/
 * category/grade/pillType/效果字段）。
 *
 * 防漂移双端锚定：
 *   - 本测试：快照 ↔ Kotlin Registry（Kotlin 侧改数据 → 快照过期 → 失败提示重跑生成器）
 *   - C++ 侧 recipe_db_test：快照 ↔ C++ 表（C++ 侧手改表 → 失败）
 *
 * 修复指引：修改两 Registry 后运行 `node scripts/gen-recipe-db.mjs` 重新生成快照与 C++ 表。
 */
class RecipeRegistryGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadRoot(): JsonObject? {
        val resource = javaClass.getResourceAsStream("/templates/recipe_db_sample.json")
        assumeTrue("快照不存在（先运行 node scripts/gen-recipe-db.mjs）", resource != null)
        resource ?: return null
        return json.parseToJsonElement(resource.readBytes().decodeToString()).jsonObject
    }

    // ── 锻造配方 ──────────────────────────────────────────────────

    @Test
    fun `forge recipe snapshot matches kotlin registry`() {
        val root = loadRoot() ?: return
        val entries = root.getValue("forgeRecipes").jsonArray

        val kotlinAll = ForgeRecipeDatabase.getAllRecipes()
        assertEquals("锻造配方数量与 Kotlin Registry 不一致", kotlinAll.size, entries.size)

        entries.forEach { e ->
            val obj = e.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val recipe = ForgeRecipeDatabase.getRecipeById(id)
            assertNotNull("配方 $id 不存在于 Kotlin Registry", recipe)
            recipe ?: return@forEach
            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, recipe.name)
            assertEquals("$id.type", obj.getValue("type").jsonPrimitive.content, recipe.type.name)
            assertEquals("$id.tier", obj.getValue("tier").jsonPrimitive.content.toInt(), recipe.tier)
            assertEquals("$id.rarity", obj.getValue("rarity").jsonPrimitive.content.toInt(), recipe.rarity)
            assertEquals("$id.duration", obj.getValue("duration").jsonPrimitive.content.toInt(), recipe.duration)
            assertEquals(
                "$id.successRate",
                obj.getValue("successRate").jsonPrimitive.content.toDouble(),
                recipe.successRate,
                1e-12
            )
            // 材料 map 逐 key 比对
            val snapshotMaterials = obj.getValue("materials").jsonObject
            assertEquals("$id.materials.size", recipe.materials.size, snapshotMaterials.size)
            snapshotMaterials.forEach { (key, value) ->
                assertEquals(
                    "$id.materials[$key]",
                    recipe.materials[key] ?: 0,
                    value.jsonPrimitive.content.toInt()
                )
            }
        }
    }

    // ── 丹药配方 ──────────────────────────────────────────────────

    @Test
    fun `pill recipe snapshot matches kotlin registry`() {
        val root = loadRoot() ?: return
        val entries = root.getValue("pillRecipes").jsonArray

        val kotlinAll = PillRecipeDatabase.getAllRecipes()
        assertEquals("丹药配方数量与 Kotlin Registry 不一致", kotlinAll.size, entries.size)

        entries.forEach { e ->
            val obj = e.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val recipe = PillRecipeDatabase.getRecipeById(id)
            assertNotNull("配方 $id 不存在于 Kotlin Registry", recipe)
            recipe ?: return@forEach
            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, recipe.name)
            assertEquals("$id.tier", obj.getValue("tier").jsonPrimitive.content.toInt(), recipe.tier)
            assertEquals("$id.rarity", obj.getValue("rarity").jsonPrimitive.content.toInt(), recipe.rarity)
            assertEquals("$id.category", obj.getValue("category").jsonPrimitive.content, recipe.category.name)
            // 快照 grade 为小写（与 Pill 模板 id 后缀一致），Kotlin 枚举 name 为大写——统一大写比较
            assertEquals(
                "$id.grade",
                obj.getValue("grade").jsonPrimitive.content.uppercase(),
                recipe.grade.name
            )
            assertEquals("$id.pillType", obj.getValue("pillType").jsonPrimitive.content, recipe.pillType)
            assertEquals("$id.duration", obj.getValue("duration").jsonPrimitive.content.toInt(), recipe.duration)
            assertEquals(
                "$id.successRate",
                obj.getValue("successRate").jsonPrimitive.content.toDouble(),
                recipe.successRate,
                1e-12
            )
            // 突破相关字段
            assertEquals(
                "$id.breakthroughChance",
                obj.getValue("breakthroughChance").jsonPrimitive.content.toDouble(),
                recipe.breakthroughChance,
                1e-12
            )
            assertEquals("$id.targetRealm", obj.getValue("targetRealm").jsonPrimitive.content.toInt(), recipe.targetRealm)
            // 材料 map
            val snapshotMaterials = obj.getValue("materials").jsonObject
            assertEquals("$id.materials.size", recipe.materials.size, snapshotMaterials.size)
            snapshotMaterials.forEach { (key, value) ->
                assertEquals(
                    "$id.materials[$key]",
                    recipe.materials[key] ?: 0,
                    value.jsonPrimitive.content.toInt()
                )
            }
        }
    }

    // ── 一致性：id 无重复且非空 ────────────────────────────────────

    @Test
    fun `snapshot ids are unique`() {
        val root = loadRoot() ?: return
        for (key in listOf("forgeRecipes", "pillRecipes")) {
            val ids = root.getValue(key).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals("$key 出现重复 id", ids.size, ids.toSet().size)
            org.junit.Assert.assertTrue("$key 不应为空", ids.isNotEmpty())
        }
    }
}
