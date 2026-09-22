package com.xianxia.sect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * source-mapping 管线守卫测试。
 *
 * 权威源：`scripts/source-mapping.json`（source↔drawable 映射，由 scaffold-source-mapping.mjs
 * 生成/维护）+ `scripts/resource-registry.json`（精灵注册源）。
 *
 * 三向守卫：
 * 1. 结构合法（version/category/drawable 全局唯一/modules/bake）
 * 2. registry 全覆盖（每个注册 res 都有映射条目——已映射或待补）
 * 3. 烘焙规则与分类策略一致（小物件 maxDim / 大图 preserve）
 *
 * 覆盖规则（rules/static-resources.md）：新增精灵必须在 resource-registry.json 登记，
 * 且必须在 source-mapping.json 有映射条目（否则无法重烘焙/校验），本测试据此守卫。
 */
class SpriteSourceMappingGuardTest {

    private val mappingFile = File("../scripts/source-mapping.json")
    private val registryFile = File("../scripts/resource-registry.json")

    private val json = Json { ignoreUnknownKeys = true }

    private data class MapEntry(
        val drawable: String,
        val source: String?,
        val modules: List<String>,
        val bake: JsonObject,
        val category: String
    )

    private fun loadMapping(): Map<String, MapEntry> {
        assertTrue("source-mapping.json 不存在（运行 node scripts/scaffold-source-mapping.mjs 生成）", mappingFile.exists())
        val root = json.parseToJsonElement(mappingFile.readText()).jsonObject
        assertEquals(1, root.getValue("version").jsonPrimitive.content.toInt())
        val map = LinkedHashMap<String, MapEntry>()
        for (cat in root.getValue("categories").jsonArray) {
            val c = cat.jsonObject
            val category = c.getValue("category").jsonPrimitive.content
            for (e in c.getValue("entries").jsonArray) {
                val o = e.jsonObject
                val drawable = o.getValue("drawable").jsonPrimitive.content
                assertTrue("source-mapping.json drawable 重复: $drawable", !map.containsKey(drawable))
                map[drawable] = MapEntry(
                    drawable = drawable,
                    source = o["source"]?.jsonPrimitive?.content,
                    modules = o.getValue("modules").jsonArray.map { it.jsonPrimitive.content },
                    bake = o.getValue("bake").jsonObject,
                    category = category
                )
            }
        }
        return map
    }

    private fun loadRegistryRes(): Set<String> {
        assertTrue("resource-registry.json 不存在", registryFile.exists())
        val root = json.parseToJsonElement(registryFile.readText()).jsonObject
        val res = mutableSetOf<String>()
        for (cat in root.getValue("categories").jsonArray) {
            for (e in cat.jsonObject.getValue("entries").jsonArray) {
                res.add(e.jsonObject.getValue("res").jsonPrimitive.content)
            }
        }
        return res
    }

    private fun loadRegistryCategories(): Map<String, List<Pair<String, String>>> {
        val root = json.parseToJsonElement(registryFile.readText()).jsonObject
        val out = mutableMapOf<String, MutableList<Pair<String, String>>>()
        for (cat in root.getValue("categories").jsonArray) {
            val c = cat.jsonObject
            val category = c.getValue("category").jsonPrimitive.content
            val list = out.getOrPut(category) { mutableListOf() }
            for (e in c.getValue("entries").jsonArray) {
                val o = e.jsonObject
                list.add(o.getValue("name").jsonPrimitive.content to o.getValue("res").jsonPrimitive.content)
            }
        }
        return out
    }

    @Test
    fun `source-mapping 结构合法 - drawable 全局唯一 + modules 合法`() {
        val mapping = loadMapping()
        assertTrue("mapping 不应为空", mapping.isNotEmpty())
        for ((drawable, e) in mapping) {
            assertTrue("$drawable 的 modules 非法（须 feature/game 或 app）: ${e.modules}",
                e.modules.isNotEmpty() && e.modules.all { it == "feature/game" || it == "app" })
            // source 合法：要么 null(待补)，要么非空路径
            if (e.source != null) assertTrue("$drawable source 非空", e.source!!.isNotBlank())
            // bake 合法：preserve 或 maxDim 或 canvas 或 square（square = REPEAT POT 裁方）
            val hasPreserve = e.bake["preserve"]?.jsonPrimitive?.content == "true"
            val hasMaxDim = e.bake["maxDim"] != null
            val hasCanvas = e.bake["canvas"] != null
            val hasSquare = e.bake["square"] != null
            assertTrue("$drawable bake 非法: ${e.bake}", hasPreserve || hasMaxDim || hasCanvas || hasSquare)
        }
    }

    @Test
    fun `registry 全覆盖 - 每个注册 res 都有映射条目`() {
        val mapping = loadMapping()
        val registryRes = loadRegistryRes()
        val missing = registryRes - mapping.keys
        assertTrue(
            "以下注册 res 在 source-mapping.json 无映射条目（无法重烘焙/校验）——" +
                "运行 node scripts/scaffold-source-mapping.mjs 并补齐映射:\n$missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `烘焙规则与分类策略一致 - 小物件 maxDim 大图 preserve`() {
        val mapping = loadMapping()
        val regCats = loadRegistryCategories()
        // 小物件分类：应为 maxDim 烘焙；大图分类：应为 preserve
        val smallCats = setOf("PILL", "MATERIAL", "EQUIPMENT", "STORAGE_BAG", "MANUAL")
        val preserveCats = setOf(
            "PORTRAIT", "BUILDING", "UI", "BACKGROUND", "BEAST",
            "CAVE", "HEAVENLY_TRIAL", "SPIRIT_STONE", "SECT_ICON"
        )
        for ((category, entries) in regCats) {
            for ((_, res) in entries) {
                val e = mapping[res] ?: continue
                val isMaxDimItem = category == "ITEM" &&
                    (res.startsWith("herb_") || res.startsWith("seed_"))
                if (category in smallCats || isMaxDimItem) {
                    val maxDim = e.bake["maxDim"]?.jsonPrimitive?.content?.toIntOrNull()
                    assertNotNull("$res($category) 应使用 maxDim 烘焙而非 preserve", maxDim)
                    assertTrue("$res maxDim 应为 1024", maxDim == 1024)
                } else if (category in preserveCats || (category == "ITEM" && res.startsWith("growing_"))) {
                    val preserve = e.bake["preserve"]?.jsonPrimitive?.content == "true"
                    assertTrue("$res($category) 应使用 preserve 烘焙而非 maxDim", preserve)
                }
            }
        }
    }
}
