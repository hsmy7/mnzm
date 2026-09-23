package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * G01 守卫：`gachaPools` / `characterTemplates` / Q31 色表单源。
 *
 * 配置缺失时 `assumeTrue` 跳过（G09 启用前允许）；色表测试恒跑。
 * 修复：改 `scripts/data/gacha_config_sample.json` 后重跑
 * `node scripts/gen-game-data.mjs`。
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
        return candidates.firstOrNull { it.exists() }
            ?: run {
                assumeTrue(
                    "game-data.json 不存在（先运行 node scripts/gen-game-data.mjs）",
                    false,
                )
                error("unreachable")
            }
    }

    private fun loadRoot(): JsonObject =
        json.parseToJsonElement(gameDataPath().readText()).jsonObject

    @Test
    fun `standard pool category weights sum to 100 and rarity max 4`() {
        val root = loadRoot()
        val db = root.getValue("db").jsonObject
        assumeTrue(
            "gachaPools 尚未写入（先运行 node scripts/gen-game-data.mjs）",
            db.containsKey("gachaPools"),
        )
        val pools = db.getValue("gachaPools").jsonArray
        assumeTrue("gachaPools 为空（G09 启用前允许跳过）", pools.isNotEmpty())

        val pool = pools.first {
            it.jsonObject.getValue("poolId").jsonPrimitive.content == "standard"
        }.jsonObject
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
            "无转化比例（Q44）",
            false,
            pool.containsKey("fragmentToSpiritStoneRatio"),
        )
    }

    @Test
    fun `character templates are the six named cast with stable ids`() {
        val root = loadRoot()
        val db = root.getValue("db").jsonObject
        assumeTrue(
            "characterTemplates 尚未写入（先运行 node scripts/gen-game-data.mjs）",
            db.containsKey("characterTemplates"),
        )
        val templates = db.getValue("characterTemplates").jsonArray
        assumeTrue("characterTemplates 为空", templates.isNotEmpty())

        val ids = templates.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        val expected = setOf(
            "zhouming", "suqing", "linxuetang", "xuhe", "xieche", "zhaoyan",
        )
        assertEquals("六角色模板 id 集合固定（Q36）", expected, ids)

        templates.forEach { e ->
            val t = e.jsonObject
            assertNotNull("模板须有姓名", t["name"])
            val roots = t.getValue("spiritRoots").jsonArray
            assertTrue(
                "模板 ${t.getValue("id").jsonPrimitive.content} 灵根数须为 1 或 2",
                roots.size in 1..2,
            )
        }
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
}
