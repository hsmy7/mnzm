package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.registry.EquipAffixPool
import com.xianxia.sect.core.registry.EquipMainStatPool
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.EquipmentSetDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * TemplateRegistryGuardTest — 静态数据守卫（B3 五段复合结构版）。
 *
 * 守护目标：生成器提取的装备快照（core/engine/src/test/resources/templates/
 * equipment_db_sample.json，由 scripts/gen-templates.mjs 生成）与 Kotlin
 * 注册表**实时数据**逐条一致。
 *
 * 防漂移双端锚定：
 *   - 本测试：快照 ↔ Kotlin 注册表四真源（EquipmentDatabase/EquipmentSetDatabase/
 *     EquipMainStatPool/EquipAffixPool）
 *   - C++ 侧 equipment_db_test：快照 ↔ C++ 四表
 *
 * 修复指引：改动任一注册表后运行 `node scripts/gen-templates.mjs` 重新生成
 * 快照与 C++ 表（G0 门禁：重跑零差异）。
 */
class TemplateRegistryGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadSample(): kotlinx.serialization.json.JsonObject? {
        val resource = javaClass.getResourceAsStream("/templates/equipment_db_sample.json")
        assumeTrue("快照不存在（先运行 node scripts/gen-templates.mjs）", resource != null)
        resource ?: return null
        return json.parseToJsonElement(resource.readBytes().decodeToString()).jsonObject
    }

    @Test
    fun `部件模板快照与Kotlin注册表逐条一致`() {
        val root = loadSample() ?: return
        val pieces = root.getValue("setPieces").jsonArray
        assertEquals("部件模板数量", EquipmentDatabase.setPieces.size, pieces.size)
        pieces.forEach { element ->
            val obj = element.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val piece = EquipmentDatabase.getPieceById(id)
            assertNotNull("部件 $id 不存在于 Kotlin 注册表（被删除或改名？）", piece)
            piece ?: return@forEach
            assertEquals("$id.setId", obj.getValue("setId").jsonPrimitive.content, piece.setId)
            assertEquals("$id.part", obj.getValue("part").jsonPrimitive.content, piece.part.name)
            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, piece.name)
            assertEquals("$id.description", obj.getValue("description").jsonPrimitive.content, piece.description)
            assertEquals(
                "$id.priceByRarity",
                obj.getValue("priceByRarity").jsonArray.map { it.jsonPrimitive.int },
                piece.priceByRarity
            )
            assertEquals(
                "$id.minRealmByRarity",
                obj.getValue("minRealmByRarity").jsonArray.map { it.jsonPrimitive.int },
                piece.minRealmByRarity
            )
        }
        assertEquals(
            "pieceCount 与 setPieces 自洽",
            root.getValue("pieceCount").jsonPrimitive.int,
            pieces.size
        )
    }

    @Test
    fun `展开条目与快照部件展开一致`() {
        val root = loadSample() ?: return
        val pieces = root.getValue("setPieces").jsonArray
        // 12 部件 × 品阶 1..6 = 72 条；展开口径逐部件核对 id/价格/门槛
        var expectedCount = 0
        pieces.forEach { element ->
            val obj = element.jsonObject
            val pieceId = obj.getValue("id").jsonPrimitive.content
            val prices = obj.getValue("priceByRarity").jsonArray.map { it.jsonPrimitive.int }
            val minRealms = obj.getValue("minRealmByRarity").jsonArray.map { it.jsonPrimitive.int }
            prices.forEachIndexed { index, price ->
                expectedCount++
                val entryId = "${pieceId}_r${index + 1}"
                val entry = EquipmentDatabase.getById(entryId)
                assertNotNull("展开条目 $entryId 缺失", entry)
                entry ?: return@forEachIndexed
                assertEquals("$entryId.pieceId", pieceId, entry.pieceId)
                assertEquals("$entryId.rarity", index + 1, entry.rarity)
                assertEquals("$entryId.price", price, entry.price)
                assertEquals("$entryId.minRealm", minRealms[index], entry.minRealm)
            }
        }
        assertEquals("展开条目总数应为 72", 72, EquipmentDatabase.entries.size)
        assertEquals(72, expectedCount)
    }

    @Test
    fun `套装表快照与Kotlin逐条一致`() {
        val root = loadSample() ?: return
        val sets = root.getValue("sets").jsonArray
        assertEquals("套装数量", EquipmentSetDatabase.sets.size, sets.size)
        sets.forEach { element ->
            val obj = element.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val def = EquipmentSetDatabase.getById(id)
            assertNotNull("套装 $id 不存在于 Kotlin 注册表", def)
            def ?: return@forEach
            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, def.name)
            assertEquals("$id.school", obj.getValue("school").jsonPrimitive.content, def.school.name)
            listOf("bonus2", "bonus4", "bonus6").forEach { tier ->
                val expected = obj.getValue(tier).jsonArray.map {
                    val entry = it.jsonObject
                    EquipStat.valueOf(entry.getValue("stat").jsonPrimitive.content) to
                        entry.getValue("value").jsonPrimitive.double
                }
                val actual = when (tier) {
                    "bonus2" -> def.bonus2.entries
                    "bonus4" -> def.bonus4.entries
                    else -> def.bonus6.entries
                }.map { it.stat to it.value }
                assertEquals("$id.$tier", expected, actual)
            }
        }
    }

    @Test
    fun `主词条池与基数表快照一致`() {
        val root = loadSample() ?: return
        val pools = root.getValue("mainStatPools").jsonObject
        EquipmentSlot.entries.forEach { part ->
            val entry = pools.getValue(part.name).jsonObject
            val expectedPool = entry.getValue("stats").jsonArray
                .map { EquipStat.valueOf(it.jsonPrimitive.content) }
            assertEquals("部位 ${part.name} 主词条池", expectedPool, EquipMainStatPool.poolFor(part))
            assertEquals(
                "部位 ${part.name} 数值系数",
                entry.getValue("coefficient").jsonPrimitive.double,
                EquipMainStatPool.partCoefficient(part), 1e-12
            )
        }
        val bases = root.getValue("mainStatBase").jsonObject
        bases.forEach { (statName, arr) ->
            val stat = EquipStat.valueOf(statName)
            val expected = arr.jsonArray.map { it.jsonPrimitive.double }
            (1..6).forEach { rarity ->
                assertEquals(
                    "$stat 品阶 $rarity 基数",
                    expected[rarity - 1], EquipMainStatPool.baseValue(stat, rarity), 1e-12
                )
            }
        }
    }

    @Test
    fun `副词条池快照一致`() {
        val root = loadSample() ?: return
        val affixes = root.getValue("subAffixes").jsonArray
        assertEquals("副词条池 7 项", 7, affixes.size)
        assertEquals(
            "池声明序",
            affixes.map { EquipStat.valueOf(it.jsonObject.getValue("stat").jsonPrimitive.content) },
            EquipAffixPool.all()
        )
        affixes.forEach { element ->
            val obj = element.jsonObject
            val stat = EquipStat.valueOf(obj.getValue("stat").jsonPrimitive.content)
            assertEquals(
                "$stat 权重",
                obj.getValue("weight").jsonPrimitive.int, EquipAffixPool.weightOf(stat)
            )
            val tiers = obj.getValue("tierValues").jsonArray.map { it.jsonPrimitive.double }
            (1..6).forEach { rarity ->
                assertEquals(
                    "$stat 品阶 $rarity 档位值",
                    tiers[rarity - 1], EquipAffixPool.tierValue(stat, rarity), 1e-12
                )
            }
        }
    }
}
