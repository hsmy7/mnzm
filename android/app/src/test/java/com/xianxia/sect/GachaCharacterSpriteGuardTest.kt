package com.xianxia.sect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G16 守卫：寻访角色素材的「配置 ↔ 注册表 ↔ 映射 ↔ 双模块产物」四方一致。
 *
 * 因果链：`game-data.json` 的 `characterTemplates[*].avatarKey/portraitKey` 是运行时
 * `SpriteResRegistry.resolve(key)` 的**唯一取图入口**（G11 寻访结果页与图鉴按该键显示）。
 * 该键要能解析，四个环节缺一不可——
 *   ① `scripts/resource-registry.json` 有 `name == res == key` 的条目（codegen 据此生成注册映射）
 *   ② `scripts/source-mapping.json` 有非 null `source`（否则素材改动无法重烘焙）
 *   ③ `app` 与 `feature/game` 两个 `drawable-nodpi` 目录各有一份 WebP（双模块放置要求）
 *   ④ 条目归在 `CHARACTER` 分类（档位与预加载语义由分类决定）
 *
 * 本批之前这四个环节对 12 个键**全部缺失**（G11 的硬阻塞，见 recon §G11-4），
 * 而编译与既有守卫都不报警：配置里的字符串没人消费，注册表面向的是另一批资源。
 * 故本测试以配置为锚点遍历，把这条断链锁死——新增角色模板若忘了走素材流程即红。
 *
 * 修复路径统一：源图放 `模拟宗门美术素材/<角色名>/{头像,全身像}.png` →
 * 在 `scaffold-source-mapping.mjs` 的 `CHARACTERS` 表登记 → 跑该脚本与
 * `import-art-assets.mjs` → 在 `resource-registry.json` 的 CHARACTER 分类加行。
 */
class GachaCharacterSpriteGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val gameDataFile = File("src/main/assets/data/game-data.json")
    private val registryFile = File("../scripts/resource-registry.json")
    private val mappingFile = File("../scripts/source-mapping.json")
    private val appDrawableDir = File("src/main/res/drawable-nodpi")
    private val gameDrawableDir = File("../feature/game/src/main/res/drawable-nodpi")

    /** CHARACTER 分类下的 res → name */
    private fun characterRegistryEntries(): Map<String, String> {
        val root = json.parseToJsonElement(registryFile.readText()).jsonObject
        val out = LinkedHashMap<String, String>()
        for (cat in root.getValue("categories").jsonArray) {
            val c = cat.jsonObject
            if (c.getValue("category").jsonPrimitive.content != CHARACTER_CATEGORY) continue
            for (e in c.getValue("entries").jsonArray) {
                val o = e.jsonObject
                out[o.getValue("res").jsonPrimitive.content] = o.getValue("name").jsonPrimitive.content
            }
        }
        return out
    }

    /** 全部映射条目按 drawable 索引 */
    private fun mappingEntries(): Map<String, JsonObject> {
        val root = json.parseToJsonElement(mappingFile.readText()).jsonObject
        val out = LinkedHashMap<String, JsonObject>()
        for (cat in root.getValue("categories").jsonArray) {
            for (e in cat.jsonObject.getValue("entries").jsonArray) {
                val o = e.jsonObject
                out[o.getValue("drawable").jsonPrimitive.content] = o
            }
        }
        return out
    }

    /** 配置里的 (角色 id → 该角色应有的两个精灵键)，键名按 avatarKey / portraitKey 原样取 */
    private fun configuredSpriteKeys(): List<String> {
        val root = json.parseToJsonElement(gameDataFile.readText()).jsonObject
        val templates = root.getValue("db").jsonObject
            .getValue("characterTemplates").jsonArray
        return templates.flatMap { t ->
            val o = t.jsonObject
            listOf(
                o.getValue("avatarKey").jsonPrimitive.content,
                o.getValue("portraitKey").jsonPrimitive.content
            )
        }
    }

    @Test
    fun `配置键全部已注册 - 每个 avatarKey 与 portraitKey 都有 name 等于 res 的条目`() {
        val entries = characterRegistryEntries()
        val unresolved = configuredSpriteKeys().filter { key -> entries[key] != key }
        assertTrue(
            "以下寻访角色精灵键未在 resource-registry.json 的 $CHARACTER_CATEGORY 分类登记" +
                "（或 name 与 res 不一致，导致 SpriteResRegistry.resolve(键名) 取不到图）：\n$unresolved\n" +
                "修复：按 rules/static-resources.md §2.2 走七步素材流程，registry 行的 name 与 res 都写键名",
            unresolved.isEmpty()
        )
    }

    /** 取字符串字段；JSON `null` 与缺键一律视为无值（`JsonNull.content` 会是字面量 "null"） */
    private fun JsonObject.stringOrNull(key: String): String? {
        val element = this[key] ?: return null
        return if (element is JsonNull) null else element.jsonPrimitive.content
    }

    @Test
    fun `配置键全部可重烘焙 - source-mapping 有非 null source`() {
        val mapping = mappingEntries()
        val broken = configuredSpriteKeys().filter { key ->
            mapping[key]?.stringOrNull("source").isNullOrEmpty()
        }
        assertTrue(
            "以下键在 source-mapping.json 无可用 source（素材更新后 import-art-assets.mjs 会静默跳过）：" +
                "\n$broken\n修复：在 scaffold-source-mapping.mjs 的 CHARACTERS 表登记该角色目录后重跑脚手架",
            broken.isEmpty()
        )
    }

    @Test
    fun `配置键全部双模块放置 - app 与 feature game 各有一份 WebP`() {
        val absent = configuredSpriteKeys().filter { key ->
            !appDrawableDir.resolve("$key.webp").isFile || !gameDrawableDir.resolve("$key.webp").isFile
        }
        assertTrue(
            "以下键缺少双模块 drawable-nodpi WebP（rules/static-resources.md §1.1）：\n$absent\n" +
                "修复：cd android && node scripts/import-art-assets.mjs",
            absent.isEmpty()
        )
    }

    @Test
    fun `角色素材档位与映射声明一致 - 映射 modules 覆盖双模块`() {
        val mapping = mappingEntries()
        val wrongModules = configuredSpriteKeys().mapNotNull { key ->
            val modules = mapping[key]?.get("modules")?.jsonArray?.map { it.jsonPrimitive.content }
            if (modules == null || !modules.containsAll(DUAL_MODULES)) key to modules else null
        }
        assertTrue(
            "以下键的映射 modules 未覆盖双模块：\n$wrongModules\n" +
                "修复：在 scaffold-source-mapping.mjs 里把该条目登记为 $DUAL_MODULES 后重跑脚手架",
            wrongModules.isEmpty()
        )
    }

    @Test
    fun `注册表角色条目数与配置模板数一致 - 不漏角色也不留孤儿键`() {
        val configured = configuredSpriteKeys().toSet()
        val registered = characterRegistryEntries().keys
        assertEquals("双向差集必须为空（漏角色 / 已下线角色残留）", configured, registered)
    }

    private companion object {
        /** 角色素材分类（core/ui SpriteCategory 同名值） */
        const val CHARACTER_CATEGORY = "CHARACTER"

        /** 双模块放置要求的两个模块名 */
        val DUAL_MODULES = listOf("feature/game", "app")
    }
}
