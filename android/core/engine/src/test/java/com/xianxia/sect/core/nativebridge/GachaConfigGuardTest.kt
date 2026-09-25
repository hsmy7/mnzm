package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.CharacterTemplateDb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * G01 守卫：`gachaPools` / `characterTemplates` / Q31 色表单源。
 *
 * G08 c340-2 起**取消全部 `assumeTrue` 跳过**（TASKBOOK §4 c340-2 + X-2 #6 口径）：
 * 缺文件、缺 `gachaPools`/`characterTemplates` 键、空数组一律**判红**。
 * 原写法「缺键即跳过」会让「配置被删干净」这种最严重的回归显示成全绿（假绿源头）。
 *
 * 修复：改 `scripts/data/gacha_config_sample.json` 后重跑
 * `node scripts/gen-game-data.mjs`（产物 `android/app/src/main/assets/data/game-data.json`）。
 *
 * 与同包 `CharacterTemplateGuardTest` 的分工：本类只锁**配置自身**的口径（权重和、
 * 品阶、保底、门槛、id 集合、引用封闭性）；配置 ↔ Kotlin 镜像 ↔ C++ 常量的三向比对
 * 归 `CharacterTemplateGuardTest`。
 */
class GachaConfigGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 相对 engine 模块工作目录的 game-data 路径（ManualRegistryGuardTest 同构）。 */
    private fun gameDataPath(): File {
        val candidates = listOf(
            File("../../app/src/main/assets/data/game-data.json"),
            File("../app/src/main/assets/data/game-data.json"),
            File("app/src/main/assets/data/game-data.json"),
        )
        val found = candidates.firstOrNull { it.exists() }
        // 🔴 判红而非跳过：缺产物说明生成器没跑（X-2 #6：assumeTrue 静默跳过 = 假绿源头）
        assertTrue(
            "game-data.json 不存在——已尝试 ${candidates.map { it.absolutePath }}；" +
                "在仓库根跑 node scripts/gen-game-data.mjs 后重试",
            found != null,
        )
        return found ?: candidates.first()
    }

    private fun loadRoot(): JsonObject =
        json.parseToJsonElement(gameDataPath().readText()).jsonObject

    /** 取 `db` 下的必需数组段：缺键或空数组一律判红（原 `assumeTrue` 的两层假绿口子）。 */
    private fun requiredArray(db: JsonObject, key: String): List<JsonObject> {
        val element = db[key]
        assertTrue(
            "game-data.json 的 db.$key 缺失（$REPAIR_HINT）",
            element != null,
        )
        val items = db.getValue(key).jsonArray.map { it.jsonObject }
        assertTrue(
            "game-data.json 的 db.$key 为空数组（$REPAIR_HINT）——配置被清空属于严重回归，不允许跳过",
            items.isNotEmpty(),
        )
        return items
    }

    @Test
    fun `standard pool category weights sum to 100 and rarity max 4`() {
        val db = loadRoot().getValue("db").jsonObject
        val pools = requiredArray(db, "gachaPools")

        val pool = pools.first {
            it.getValue("poolId").jsonPrimitive.content == "standard"
        }
        val categories = pool.getValue("categories").jsonArray
        val catSum = categories.sumOf { it.jsonObject.getValue("weightPct").jsonPrimitive.long }
        assertEquals("类别权重和必须 = 100%", 100L, catSum)

        val rarities = pool.getValue("itemRarityWeights").jsonArray
        val raritySum = rarities.sumOf { it.jsonObject.getValue("weightPct").jsonPrimitive.long }
        assertEquals("品阶权重和必须 = 100%", 100L, raritySum)
        val maxRarity = rarities.maxOf { it.jsonObject.getValue("rarity").jsonPrimitive.long }
        assertTrue("寻访池最高品阶必须 ≤ 4（Q37）", maxRarity <= 4)

        val pity = pool.getValue("pity").jsonObject
        assertEquals("保底阈值 = 10（Q33）", 10L, pity.getValue("pullThreshold").jsonPrimitive.long)
        assertEquals("保底碎片 = 5（Q33）", 5L, pity.getValue("fragmentCount").jsonPrimitive.long)
        assertEquals(
            "每星碎片 = 100（Q35）",
            100L,
            pool.getValue("fragmentsPerStar").jsonPrimitive.long,
        )
        assertEquals(
            "星级上限 = 5（Q35）",
            5L,
            pool.getValue("maxStar").jsonPrimitive.long,
        )
        assertEquals(
            "配置每星碎片与 Kotlin 常量 GameConfig.Gacha.FRAGMENTS_PER_STAR 不同值" +
                "（三向比对含 C++ kFragmentsPerStar，见 CharacterTemplateGuardTest）",
            GameConfig.Gacha.FRAGMENTS_PER_STAR.toLong(),
            pool.getValue("fragmentsPerStar").jsonPrimitive.long,
        )
        assertEquals(
            "配置星级上限与 Kotlin 常量 GameConfig.Gacha.MAX_STAR 不同值（同上，三向由模板守卫看护）",
            GameConfig.Gacha.MAX_STAR.toLong(),
            pool.getValue("maxStar").jsonPrimitive.long,
        )
        assertEquals(
            "无转化比例（Q44）",
            false,
            pool.containsKey("fragmentToSpiritStoneRatio"),
        )
    }

    @Test
    fun `character templates are the six named cast with stable ids`() {
        val db = loadRoot().getValue("db").jsonObject
        val templates = requiredArray(db, "characterTemplates")

        val ids = templates.map { it.getValue("id").jsonPrimitive.content }.toSet()
        val expected = setOf(
            "zhouming", "suqing", "linxuetang", "xuhe", "xieche", "zhaoyan",
        )
        assertEquals("六角色模板 id 集合固定（Q36）", expected, ids)
        assertEquals(
            "配置模板 id 集合与 Kotlin 镜像 CharacterTemplateDb.ids 不同（三向比对见 " +
                "CharacterTemplateGuardTest，此处只锁本类单独跑时的锚点）",
            CharacterTemplateDb.ids, ids,
        )

        templates.forEach { t ->
            assertNotNull("模板须有姓名", t["name"])
            val roots = t.getValue("spiritRoots").jsonArray
            assertTrue(
                "模板 ${t.getValue("id").jsonPrimitive.content} 灵根数须为 1 或 2",
                roots.size in 1..2,
            )
        }
    }

    /**
     * G08 c340-2 补断言①：碎片/星级两本账的 **key 域封闭**。
     *
     * `GameData.gachaFragmentCounts` / `gachaStarMap` 以模板 id 为唯一键域（入账写者
     * `gacha_fragment.h::addFragment` / `GachaFragmentLedger.grant` 都按 templateId 写键），
     * 所以配置里任何被引用的 `templateIds` 都必须在 `CharacterTemplateDb.ids` 内——
     * 引用表外的 id 会让玩家拿到一个永远查不到模板、立绘与姓名都为空的账本键。
     */
    @Test
    fun `pool referenced template ids are closed within the template table`() {
        val db = loadRoot().getValue("db").jsonObject
        val pools = requiredArray(db, "gachaPools")
        val referenced = pools.flatMap { pool ->
            pool["categories"]?.jsonArray.orEmpty().flatMap { category ->
                category.jsonObject["templateIds"]?.jsonArray.orEmpty()
                    .map { it.jsonPrimitive.content }
            }
        }.toSet()
        assertTrue(
            "卡池未引用任何模板 id（寻访池必须能出角色，否则验收①的模板层形同虚设）",
            referenced.isNotEmpty(),
        )
        val dangling = referenced.filterNot { CharacterTemplateDb.ids.contains(it) }
        assertEquals(
            "卡池 categories[*].templateIds 出现模板表外的 id：$dangling——" +
                "gachaFragmentCounts/gachaStarMap 的 key 域就是 CharacterTemplateDb.ids，" +
                "表外 id 会落进玩家账本却查不到模板。$REPAIR_HINT",
            emptyList<String>(), dangling,
        )
    }

    /**
     * G08 c340-2 补断言②：开局 id 必在模板表内（配置自洽面）。
     *
     * Kotlin 镜像常量与 `gachaDefaults` 的等值比对由 `CharacterTemplateGuardTest` 负责，
     * 这里只锁「开局 id 落在产物模板表里」，让本类单独跑也能守住验收①的前提。
     */
    @Test
    fun `startup template id exists in the generated template table`() {
        val root = loadRoot()
        val defaults = root["gachaDefaults"]?.jsonObject
        assertTrue("game-data.json 缺顶层 gachaDefaults 段（$REPAIR_HINT）", defaults != null)
        val startup = defaults?.getValue("startupTemplateId")?.jsonPrimitive?.content
        val ids = requiredArray(root.getValue("db").jsonObject, "characterTemplates")
            .map { it.getValue("id").jsonPrimitive.content }
        assertTrue(
            "gachaDefaults.startupTemplateId=\"$startup\" 不在产物模板表 $ids 内（$REPAIR_HINT）",
            startup != null && ids.contains(startup),
        )
    }

    @Test
    fun `gacha color single source matches Q31`() {
        assertEquals("#ffd700", GameConfig.Gacha.rarityColor(6))
        assertEquals("#f44336", GameConfig.Gacha.rarityColor(5))
        assertEquals("#9c27b0", GameConfig.Gacha.rarityColor(4))
        assertEquals("#2196f3", GameConfig.Gacha.rarityColor(3))
        assertEquals("#4caf50", GameConfig.Gacha.rarityColor(2))
        assertEquals("#b8b8b8", GameConfig.Gacha.rarityColor(1))

        assertEquals("#ffd700", GameConfig.Gacha.spiritRootCountColor(1))
        assertEquals("#f44336", GameConfig.Gacha.spiritRootCountColor(2))
        assertEquals("#9c27b0", GameConfig.Gacha.spiritRootCountColor(3))
        assertEquals("#2196f3", GameConfig.Gacha.spiritRootCountColor(4))
        assertEquals("#b8b8b8", GameConfig.Gacha.spiritRootCountColor(5))
    }

    private companion object {
        /** 统一的修复指引（判红消息里反复用） */
        const val REPAIR_HINT =
            "修复：改 scripts/data/gacha_config_sample.json 后在仓库根跑 node scripts/gen-game-data.mjs"
    }
}
