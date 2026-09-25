package com.xianxia.sect

import com.xianxia.sect.ui.components.SpriteCategory
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
 * 四向守卫：
 * 1. 结构合法（version/category/drawable 全局唯一/modules/bake）
 * 2. registry 全覆盖（每个注册 res 都有映射条目——已映射或待补）
 * 3. 每个注册分类都已登记烘焙档位策略（新分类不得静默漏检），且分类名在 SpriteCategory 枚举内
 * 4. 映射条目的 bake 与其分类策略一致（小物件 maxDim / 大图 preserve / 角色按前缀分档）
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

    /** 分类烘焙策略：小物件缩到指定 maxDim / 大图保留源分辨率 / 按 res 前缀分档 */
    private sealed interface BakePolicy {
        /** 等比缩放到最长边 = [dim] */
        data class ScaledTo(val dim: Int) : BakePolicy

        /** 保留源分辨率（import 侧另有图集硬上限夹取） */
        data object Preserved : BakePolicy
    }

    companion object {
        /** 小物件（丹药/材料/装备/储物袋/功法）统一档位 */
        private const val SMALL_ITEM_MAX_DIM = 1024

        /** 角色头像档位：寻访结果页 2×5 方格（横屏约 170px），512 已含 3 倍密度余量 */
        private const val CHARACTER_AVATAR_MAX_DIM = 512

        /** 角色全身立绘档位：图鉴卡（约 120dp 宽）与后续详情页 */
        private const val CHARACTER_PORTRAIT_MAX_DIM = 1024

        private val SMALL_ITEM_CATEGORIES = setOf("PILL", "MATERIAL", "EQUIPMENT", "STORAGE_BAG", "MANUAL")

        /** 显示尺寸即源图尺寸的大图类——缩档会肉眼可见降质，故必须 preserve */
        private val PRESERVED_CATEGORIES = setOf(
            "PORTRAIT", "BUILDING", "UI", "BACKGROUND", "BEAST",
            "CAVE", "HEAVENLY_TRIAL", "SPIRIT_STONE", "SECT_ICON"
        )
    }

    /** 返回 null = 该分类尚未登记烘焙策略，[SpriteSourceMappingGuardTest] 据此判红 */
    private fun expectedBakePolicy(category: String, res: String): BakePolicy? = when {
        category in SMALL_ITEM_CATEGORIES -> BakePolicy.ScaledTo(SMALL_ITEM_MAX_DIM)
        category == "ITEM" -> if (res.startsWith("herb_") || res.startsWith("seed_")) {
            BakePolicy.ScaledTo(SMALL_ITEM_MAX_DIM)
        } else {
            BakePolicy.Preserved
        }
        // 头像与立绘的显示尺寸差一个量级，同分类内按 res 前缀分档
        category == "CHARACTER" -> BakePolicy.ScaledTo(
            if (res.startsWith("avatar_")) CHARACTER_AVATAR_MAX_DIM else CHARACTER_PORTRAIT_MAX_DIM
        )
        category in PRESERVED_CATEGORIES -> BakePolicy.Preserved
        else -> null
    }

    @Test
    fun `每个注册分类都已登记烘焙策略 - 新增分类不得静默漏检`() {
        val regCats = loadRegistryCategories()
        val unclassified = regCats.keys.filter { cat ->
            regCats.getValue(cat).any { (_, res) -> expectedBakePolicy(cat, res) == null }
        }
        assertTrue(
            "以下分类没有烘焙档位策略——旧版用白名单集合做 if/else，新分类不在集合内时整段校验" +
                "会静默放行（G16 侦察实测）。请在 expectedBakePolicy 里为该分类定档：\n$unclassified",
            unclassified.isEmpty()
        )
    }

    @Test
    fun `注册表分类必须存在于 SpriteCategory 枚举`() {
        val enumNames = SpriteCategory.entries.map { it.name }.toSet()
        val unknown = loadRegistryCategories().keys - enumNames
        assertTrue(
            "resource-registry.json 用了枚举里没有的分类 $unknown——" +
                "先在 core/ui/.../SpriteResRegistry.kt 的 SpriteCategory 定义并给出 priority",
            unknown.isEmpty()
        )
    }

    @Test
    fun `烘焙规则与分类策略一致 - 按分类档位校验映射条目`() {
        val mapping = loadMapping()
        val regCats = loadRegistryCategories()
        for ((category, entries) in regCats) {
            for ((_, res) in entries) {
                val e = mapping[res] ?: continue
                when (val policy = expectedBakePolicy(category, res)) {
                    is BakePolicy.ScaledTo -> {
                        val maxDim = e.bake["maxDim"]?.jsonPrimitive?.content?.toIntOrNull()
                        assertNotNull(
                            "$res($category) 应使用 maxDim 烘焙：$policy",
                            maxDim
                        )
                        assertEquals("$res($category) 档位应为 ${policy.dim}", policy.dim, maxDim)
                    }
                    is BakePolicy.Preserved -> assertTrue(
                        "$res($category) 应使用 preserve 烘焙而非 maxDim",
                        e.bake["preserve"]?.jsonPrimitive?.content == "true"
                    )
                    null -> Unit // 由 每个注册分类都已登记烘焙策略 判红，此处不重复报
                }
            }
        }
    }
}
