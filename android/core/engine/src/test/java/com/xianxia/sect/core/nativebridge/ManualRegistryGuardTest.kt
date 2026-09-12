package com.xianxia.sect.core.nativebridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * ManualRegistryGuardTest — 功法静态数据守卫。
 *
 * 守护目标：生成器提取的功法表快照（manual_db_sample.json，由
 * scripts/gen-manual-db.mjs 从 assets/data/manuals.json 生成）与 **数据源**
 * manuals.json 一致（逐字段：id/name/type/rarity/description/skill 全字段、
 * price/minRealm）。
 *
 * 防漂移双端锚定：
 *   - 本测试：快照 ↔ 数据源 manuals.json（数据源改 → 快照过期 → 失败提示重跑生成器）
 *   - C++ 侧 manual_db_test：快照 ↔ C++ 表（C++ 侧手改表 → 失败）
 *
 * 修复指引：修改 assets/data/manuals.json 后运行 `node scripts/gen-manual-db.mjs`
 * 重新生成快照与 C++ 表。
 */
class ManualRegistryGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 数据源路径：android/app/src/main/assets/data/manuals.json（相对 engine 模块工作目录）。 */
    private fun sourcePath(): File {
        val candidates = listOf(
            File("../../app/src/main/assets/data/manuals.json"),
            File("../app/src/main/assets/data/manuals.json"),
            File("app/src/main/assets/data/manuals.json"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("manuals.json 不存在（工作目录 ${File(".").absolutePath}）")
    }

    private fun loadSnapshot(): JsonObject? {
        val resource = javaClass.getResourceAsStream("/templates/manual_db_sample.json")
        assumeTrue("快照不存在（先运行 node scripts/gen-manual-db.mjs）", resource != null)
        resource ?: return null
        return json.parseToJsonElement(resource.readBytes().decodeToString()).jsonObject
    }

    /** 数据源四类功法合并为 List<JsonObject>。 */
    private fun loadSourceEntries(): List<JsonObject> {
        val root = json.parseToJsonElement(sourcePath().readText()).jsonObject
        val out = mutableListOf<JsonObject>()
        for (key in listOf("attackManuals", "defenseManuals", "supportManuals", "mindManuals")) {
            (root[key]?.jsonArray ?: continue).forEach { out.add(it.jsonObject) }
        }
        return out
    }

    @Test
    fun `manual snapshot matches data source`() {
        val snapshot = loadSnapshot() ?: return
        val entries = snapshot.getValue("entries").jsonArray
        val source = loadSourceEntries()

        assertEquals("快照数量与数据源不一致", source.size, entries.size)
        assertEquals("数据源应含 540 条功法", 540, source.size)

        val sourceById = source.associateBy { it.getValue("id").jsonPrimitive.content }
        entries.forEach { e ->
            val obj = e.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val src = sourceById[id]
            assertTrue("快照条目 $id 不存在于数据源", src != null)
            src ?: return@forEach

            for (field in listOf(
                "id", "name", "type", "rarity", "description", "skillName", "skillDescription",
                "skillType", "skillDamageType", "skillHits", "skillCooldown", "skillMpCost",
                "price", "minRealm", "skillTargetScope",
            )) {
                // 数据源缺省字段（nullable）→ Kotlin 解析用默认值；快照已含解析后默认值
                val srcValue = src[field]?.jsonPrimitive?.content
                if (srcValue == null) continue
                assertEquals("$id.$field", obj[field]?.jsonPrimitive?.content, srcValue)
            }
            // 浮点字段（数据源缺省 → 跳过；快照含 Kotlin 解析默认值）
            for (field in listOf("skillDamageMultiplier", "skillHealPercent", "skillHealFixed",
                "skillBuffValue", "skillBuffDuration", "skillShieldPercent",
                "skillTurnAdvancePercent", "skillDamageSharePercent", "skillDamageLinkPercent")) {
                val srcValue = src[field]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (srcValue == null) continue
                assertEquals(
                    "$id.$field",
                    obj[field]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    srcValue,
                    1e-12
                )
            }
            val srcAoe = src["skillIsAoe"]?.jsonPrimitive?.content
            if (srcAoe != null) {
                assertEquals("$id.skillIsAoe", obj["skillIsAoe"]?.jsonPrimitive?.content, srcAoe)
            }
        }
    }

    @Test
    fun `snapshot type distribution matches source`() {
        val snapshot = loadSnapshot() ?: return
        val source = loadSourceEntries()
        val counts = source.groupingBy { it.getValue("type").jsonPrimitive.content }.eachCount()
        assertEquals(108, counts["ATTACK"])
        assertEquals(162, counts["DEFENSE"])
        assertEquals(234, counts["SUPPORT"])
        assertEquals(36, counts["MIND"])
        assertEquals(540, snapshot.getValue("count").jsonPrimitive.content.toInt())
    }
}
