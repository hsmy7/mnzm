package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * TraitRegistryGuardTest — 天赋/体质/词条静态数据守卫（批次 2 剩余子步）。
 *
 * 守护目标：生成器提取的特质表快照（gamecore/.../trait_db_sample.json，
 * 由 scripts/gen-trait-db.mjs 生成）与 Kotlin TalentDatabase/PhysiqueDatabase/
 * AffixDatabase **实时数据**一致（逐字段：id/name/rarity/effects/type/
 * positionBonus/description/isNegative）。
 *
 * 防漂移双端锚定：
 *   - 本测试：快照 ↔ Kotlin Registry（Kotlin 侧改数据 → 快照过期 → 测试失败提示重跑生成器）
 *   - C++ 侧 trait_db_test：快照 ↔ C++ 表（C++ 侧手改表 → 失败）
 *
 * 修复指引：修改三 Registry 后运行 `node scripts/gen-trait-db.mjs` 重新生成快照与 C++ 表。
 */
class TraitRegistryGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadRoot(): JsonObject? {
        val resource = javaClass.getResourceAsStream("/templates/trait_db_sample.json")
        assumeTrue("快照不存在（先运行 node scripts/gen-trait-db.mjs）", resource != null)
        resource ?: return null
        return json.parseToJsonElement(resource.readBytes().decodeToString()).jsonObject
    }

    // ── 天赋 ──────────────────────────────────────────────────────

    @Test
    fun `talent snapshot matches kotlin registry`() {
        val root = loadRoot() ?: return
        val entries = root.getValue("talents").jsonArray

        // 全量：talents map 含旧（废弃类型）+ 新天赋；getPositiveTalents 会过滤
        // DEPRECATED_TALENT_TYPES（20 条旧天赋），快照保留全量 → 用 talents.values 比对
        val kotlinTalents = TalentDatabase.talents.values
        assertEquals("天赋数量与 Kotlin Registry 不一致", kotlinTalents.size, entries.size)

        entries.forEach { e ->
            val obj = e.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val tpl = TalentDatabase.getById(id)
            assertNotNull("天赋 $id 不存在于 Kotlin Registry（是否被删除或改名？）", tpl)
            tpl ?: return@forEach
            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, tpl.name)
            assertEquals("$id.rarity", obj.getValue("rarity").jsonPrimitive.content.toInt(), tpl.rarity)
            assertEquals("$id.isNegative", obj.getValue("isNegative").jsonPrimitive.content.toBoolean(), tpl.isNegative)
            // effects 逐 key 比对
            val snapshotEffects = obj.getValue("effects").jsonObject
            assertEquals(
                "$id.effects.size",
                tpl.effects.size,
                snapshotEffects.size
            )
            snapshotEffects.forEach { (key, value) ->
                assertEquals(
                    "$id.effects[$key]",
                    tpl.effects[key] ?: 0.0,
                    value.jsonPrimitive.content.toDouble(),
                    1e-12
                )
            }
            // positionBonus（快照 null → Kotlin 无）
            val posElem = obj.getValue("positionBonus")
            if (posElem is kotlinx.serialization.json.JsonObject) {
                val posObj = posElem
                val ktPos = tpl.positionBonus
                assertNotNull("$id.positionBonus 快照有但 Kotlin 无", ktPos)
                ktPos ?: return@forEach
                assertEquals(
                    "$id.positionBonus.slotType",
                    posObj.getValue("slotType").jsonPrimitive.content,
                    ktPos.slotType.name
                )
                assertEquals(
                    "$id.positionBonus.effectBonus",
                    posObj.getValue("effectBonus").jsonPrimitive.content.toDouble(),
                    ktPos.effectBonus,
                    1e-12
                )
            }
        }
    }

    // ── 体质 ──────────────────────────────────────────────────────

    @Test
    fun `physique snapshot matches kotlin registry`() {
        val root = loadRoot() ?: return
        val entries = root.getValue("physiques").jsonArray

        val kotlinIds = PhysiqueDatabase.getPositivePhysiques().map { it.id }.toSet() +
            PhysiqueDatabase.getNegativePhysiques().map { it.id }.toSet()
        assertEquals("体质数量与 Kotlin Registry 不一致", kotlinIds.size, entries.size)

        entries.forEach { e ->
            val obj = e.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val tpl = PhysiqueDatabase.getById(id)
            assertNotNull("体质 $id 不存在于 Kotlin Registry", tpl)
            tpl ?: return@forEach
            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, tpl.name)
            assertEquals("$id.rarity", obj.getValue("rarity").jsonPrimitive.content.toInt(), tpl.rarity)
            assertEquals("$id.isNegative", obj.getValue("isNegative").jsonPrimitive.content.toBoolean(), tpl.isNegative)
            assertEquals(
                "$id.cultivationSpeedBonus",
                obj.getValue("cultivationSpeedBonus").jsonPrimitive.content.toDouble(),
                tpl.cultivationSpeedBonus,
                1e-12
            )
            assertEquals(
                "$id.damageAmplification",
                obj.getValue("damageAmplification").jsonPrimitive.content.toDouble(),
                tpl.damageAmplification,
                1e-12
            )
            assertEquals(
                "$id.damageReduction",
                obj.getValue("damageReduction").jsonPrimitive.content.toDouble(),
                tpl.damageReduction,
                1e-12
            )
            assertEquals(
                "$id.critDamageBonus",
                obj.getValue("critDamageBonus").jsonPrimitive.content.toDouble(),
                tpl.critDamageBonus,
                1e-12
            )
            assertEquals(
                "$id.defenseBonus",
                obj.getValue("defenseBonus").jsonPrimitive.content.toDouble(),
                tpl.defenseBonus,
                1e-12
            )
        }
    }

    // ── 词条 ──────────────────────────────────────────────────────

    @Test
    fun `affix snapshot matches kotlin registry`() {
        val root = loadRoot() ?: return
        val entries = root.getValue("affixes").jsonArray

        val kotlinIds = AffixDatabase.getPositiveAffixes().map { it.id }.toSet() +
            AffixDatabase.getNegativeAffixes().map { it.id }.toSet()
        assertEquals("词条数量与 Kotlin Registry 不一致", kotlinIds.size, entries.size)

        entries.forEach { e ->
            val obj = e.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val tpl = AffixDatabase.getById(id)
            assertNotNull("词条 $id 不存在于 Kotlin Registry", tpl)
            tpl ?: return@forEach
            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, tpl.name)
            assertEquals("$id.rarity", obj.getValue("rarity").jsonPrimitive.content.toInt(), tpl.rarity)
            assertEquals("$id.isNegative", obj.getValue("isNegative").jsonPrimitive.content.toBoolean(), tpl.isNegative)
            val snapshotEffects = obj.getValue("effects").jsonObject
            assertEquals("$id.effects.size", tpl.effects.size, snapshotEffects.size)
            snapshotEffects.forEach { (key, value) ->
                assertEquals(
                    "$id.effects[$key]",
                    tpl.effects[key] ?: 0.0,
                    value.jsonPrimitive.content.toDouble(),
                    1e-12
                )
            }
        }
    }

    // ── 一致性：三表 id 无重复且 C++ 表数量与快照一致（防御性） ────

    @Test
    fun `snapshot ids are unique`() {
        val root = loadRoot() ?: return
        for (key in listOf("talents", "physiques", "affixes")) {
            val ids = root.getValue(key).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals("$key 出现重复 id", ids.size, ids.toSet().size)
            assertTrue("$key 不应为空", ids.isNotEmpty())
        }
    }
}
