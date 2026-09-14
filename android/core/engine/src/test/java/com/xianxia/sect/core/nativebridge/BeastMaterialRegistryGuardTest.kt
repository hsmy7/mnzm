package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ItemDatabase
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
 * BeastMaterialRegistryGuardTest — 妖兽材料/材料静态数据守卫。
 *
 * 守护目标：生成器提取的妖兽材料表快照（beast_material_db_sample.json，由
 * scripts/gen-beast-material-db.mjs 生成）与 Kotlin BeastMaterialDatabase
 * **实时数据**一致（逐字段：id/name/tier/rarity/category/description/icon/
 * dropWeight + 派生 price/materialCategory），并与 ItemDatabase.allMaterials
 * （材料表 = 妖兽材料转换）交叉锚定。
 *
 * 防漂移双端锚定：
 *   - 本测试：快照 ↔ Kotlin Registry（Kotlin 侧改数据 → 快照过期 → 失败提示重跑生成器）
 *   - C++ 侧 beast_material_db_test：快照 ↔ C++ 表（C++ 侧手改表 → 失败）
 *
 * 修复指引：修改 BeastMaterialDatabase 后运行 `node scripts/gen-beast-material-db.mjs`
 * 重新生成快照与 C++ 表。
 */
class BeastMaterialRegistryGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadRoot(): JsonObject? {
        val resource = javaClass.getResourceAsStream("/templates/beast_material_db_sample.json")
        assumeTrue("快照不存在（先运行 node scripts/gen-beast-material-db.mjs）", resource != null)
        resource ?: return null
        return json.parseToJsonElement(resource.readBytes().decodeToString()).jsonObject
    }

    @Test
    fun `beast material snapshot matches kotlin registry`() {
        val root = loadRoot() ?: return
        val entries = root.getValue("entries").jsonArray

        val kotlinAll = BeastMaterialDatabase.getAllMaterials()
        assertEquals("妖兽材料数量与 Kotlin Registry 不一致", kotlinAll.size, entries.size)

        entries.forEach { e ->
            val obj = e.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val material = BeastMaterialDatabase.getMaterialById(id)
            assertNotNull("妖兽材料 $id 不存在于 Kotlin Registry", material)
            material ?: return@forEach

            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, material.name)
            assertEquals("$id.tier", obj.getValue("tier").jsonPrimitive.content.toInt(), material.tier)
            assertEquals("$id.rarity", obj.getValue("rarity").jsonPrimitive.content.toInt(), material.rarity)
            assertEquals("$id.category", obj.getValue("category").jsonPrimitive.content, material.category)
            assertEquals("$id.description", obj.getValue("description").jsonPrimitive.content, material.description)
            assertEquals("$id.icon", obj.getValue("icon").jsonPrimitive.content, material.icon)
            assertEquals(
                "$id.dropWeight",
                obj.getValue("dropWeight").jsonPrimitive.content.toDouble(),
                material.dropWeight,
                1e-12
            )
            // 派生字段
            assertEquals("$id.price", obj.getValue("price").jsonPrimitive.content.toInt(), material.price)
            assertEquals(
                "$id.materialCategory",
                obj.getValue("materialCategory").jsonPrimitive.content,
                material.materialCategory.name
            )
        }
    }

    @Test
    fun `material templates cross-anchor with item database`() {
        // 材料表（ItemDatabase.allMaterials）由妖兽材料转换——逐条锚定
        val beastMaterials = BeastMaterialDatabase.getAllMaterials()
        assertEquals("ItemDatabase 材料表数量与妖兽材料不一致", beastMaterials.size, ItemDatabase.allMaterials.size)
        beastMaterials.forEach { m ->
            val template = ItemDatabase.getMaterialById(m.id)
            assertNotNull("材料 ${m.id} 不存在于 ItemDatabase", template)
            template ?: return@forEach
            assertEquals("${m.id}.name", m.name, template.name)
            assertEquals("${m.id}.category", m.materialCategory, template.category)
            assertEquals("${m.id}.rarity", m.rarity, template.rarity)
            assertEquals("${m.id}.description", m.description, template.description)
            assertEquals("${m.id}.price", m.price, template.price)
        }
    }

    @Test
    fun `beast type query helpers match kotlin`() {
        // getMaterialsByBeastType 语义：中文妖兽名 → id 前缀匹配
        val tigerKotlin = BeastMaterialDatabase.getMaterialsByBeastType("虎妖")
        assertEquals("虎妖材料应为 24 条（4 类 × 6 品阶）", 24, tigerKotlin.size)
        val turtleKotlin = BeastMaterialDatabase.getMaterialsByBeastType("龟妖")
        assertEquals("龟妖材料应为 24 条", 24, turtleKotlin.size)
        // 未知妖兽 → 空
        assertEquals(0, BeastMaterialDatabase.getMaterialsByBeastType("麒麟").size)
    }
}
