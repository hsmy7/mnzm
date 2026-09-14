package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.registry.EquipmentDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * TemplateRegistryGuardTest — 静态数据守卫。
 *
 * 守护目标：生成器提取的装备表快照（gamecore/test/data/equipment_db_sample.json，
 * 由 scripts/gen-templates.mjs 生成）与 Kotlin EquipmentDatabase **实时数据**一致。
 *
 * 防漂移双端锚定：
 *   - 本测试：快照 ↔ Kotlin Registry（Kotlin 侧改数据 → 快照过期 → 测试失败提示重跑生成器）
 *   - C++ 侧 equipment_db_test：快照 ↔ C++ 表（C++ 侧手改表 → 失败）
 *
 * 修复指引：修改 EquipmentDatabase 后运行 `node scripts/gen-templates.mjs` 重新生成快照与 C++ 表。
 */
class TemplateRegistryGuardTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `equipment db sample matches kotlin registry`() {
        // 快照经 classpath 加载（生成器输出到 src/test/resources/templates/）
        val resource = javaClass.getResourceAsStream("/templates/equipment_db_sample.json")
        assumeTrue("快照不存在（先运行 node scripts/gen-templates.mjs）", resource != null)
        resource ?: return

        val root = json.parseToJsonElement(resource.readBytes().decodeToString()).jsonObject
        val entries = root.getValue("entries").jsonArray

        assertEquals("装备模板数量与 Kotlin Registry 不一致", EquipmentDatabase.allTemplates.size, entries.size)

        entries.forEach { e ->
            val obj = e.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val tpl = EquipmentDatabase.getById(id)
            assertNotNull("模板 $id 不存在于 Kotlin Registry（是否被删除或改名？）", tpl)
            tpl ?: return@forEach
            assertEquals("$id.name", obj.getValue("name").jsonPrimitive.content, tpl.name)
            assertEquals("$id.slot", obj.getValue("slot").jsonPrimitive.content, tpl.slot.name)
            assertEquals("$id.rarity", obj.getValue("rarity").jsonPrimitive.int, tpl.rarity)
            assertEquals("$id.physicalAttack", obj.getValue("physicalAttack").jsonPrimitive.int, tpl.physicalAttack)
            assertEquals("$id.magicAttack", obj.getValue("magicAttack").jsonPrimitive.int, tpl.magicAttack)
            assertEquals("$id.physicalDefense", obj.getValue("physicalDefense").jsonPrimitive.int, tpl.physicalDefense)
            assertEquals("$id.magicDefense", obj.getValue("magicDefense").jsonPrimitive.int, tpl.magicDefense)
            assertEquals("$id.speed", obj.getValue("speed").jsonPrimitive.int, tpl.speed)
            assertEquals("$id.hp", obj.getValue("hp").jsonPrimitive.int, tpl.hp)
            assertEquals("$id.mp", obj.getValue("mp").jsonPrimitive.int, tpl.mp)
            assertEquals(
                "$id.critChance",
                obj.getValue("critChance").jsonPrimitive.content.toDouble(),
                tpl.critChance,
                1e-12
            )
            assertEquals("$id.price", obj.getValue("price").jsonPrimitive.int, tpl.price)
        }
    }
}
