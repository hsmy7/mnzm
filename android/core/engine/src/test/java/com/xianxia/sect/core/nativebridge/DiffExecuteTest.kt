package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.GameData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DiffExecuteTest {
    private val json = Json { encodeDefaults = true }
    private fun str(elem: JsonElement): String = elem.jsonPrimitive.content
    private fun exec(actionId: Int, params: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreExecute(
            actionId, json.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
        ).decodeToString()
        return json.parseToJsonElement(result) as JsonObject
    }
    private fun freshCore() { DiffRngBridge.nativeDestroy(); DiffRngBridge.nativeCoreInit() }

    @Test
    fun `action ids match generated catalog`() {
        assumeTrue(DiffRngBridge.isAvailable())
        assertEquals(1000, ActionIds.WALLET_ADD)
        assertEquals(1001, ActionIds.WALLET_DEDUCT)
        assertEquals(1002, ActionIds.WALLET_BATCH)
        assertEquals(1010, ActionIds.INV_ADD_EQUIPMENT_STACK)
        assertEquals(1014, ActionIds.INV_ADD_PILL)
        assertEquals(1030, ActionIds.SPIRIT_FIELD_HARVEST)
        assertEquals(1100, ActionIds.DISCIPLE_BASE_STATS)
        assertEquals(1200, ActionIds.BATTLE_FINAL_DAMAGE)
        assertEquals(1300, ActionIds.GOV_POLICY_COSTS)
        assertEquals(1400, ActionIds.WORLD_LEVEL_MONTHLY)
    }

    @Test
    fun `wallet add via execute`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val initial = NativeGameState(gameData = GameData().apply { spiritStones = 1000 })
        assertTrue(DiffRngBridge.nativeCoreImportState(
            json.encodeToString(NativeGameState.serializer(), initial).encodeToByteArray()))
        val r = exec(ActionIds.WALLET_ADD, buildJsonObject {
            put("amount", 500); put("grade", "LOW"); put("source", "Battle")
        })
        assertEquals("success", str(r.getValue("status")))
        assertEquals(1500L, str((r.getValue("data") as JsonObject).getValue("balance")).toLong())
        val decoded = json.decodeFromString(
            NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
        )
        assertEquals(1500L, decoded.gameData.spiritStones)
        assertEquals(500L, decoded.gameData.annualTotalIncome)
    }

    @Test
    fun `wallet deduct insufficient via execute`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val initial = NativeGameState(gameData = GameData().apply { spiritStones = 100 })
        assertTrue(DiffRngBridge.nativeCoreImportState(
            json.encodeToString(NativeGameState.serializer(), initial).encodeToByteArray()))
        val r = exec(ActionIds.WALLET_DEDUCT, buildJsonObject {
            put("amount", 500); put("grade", "LOW"); put("autoConvert", false)
        })
        assertEquals("failure", str(r.getValue("status")))
        assertEquals("INSUFFICIENT", str(r.getValue("code")))
    }

    @Test
    fun `inventory add equipment instance via execute`() {
        // B3：1010 号语义=「添加装备实例」（号保留、JSON 形状=实例，与 C++
        // execute_dispatch INV_ADD_EQUIPMENT_STACK 分支对齐）；重复 id 拒绝
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val instanceJson = """
            {"id":"eq-1","name":"木剑","setId":"lietian","part":"WEAPON",
             "growth":{"level":1,"exp":0,
                       "affix":{"mainStat":{"stat":"ATTACK","value":10.0},
                                "subStats":[{"stat":"DEFENSE","value":3.0}],
                                "subRolls":[1]}},
             "meta":{"rarity":1,"minRealm":9,"description":"","isLocked":false},
             "ownerId":null,"isEquipped":false}
        """.trimIndent()
        val r = exec(ActionIds.INV_ADD_EQUIPMENT_STACK, json.parseToJsonElement(instanceJson) as JsonObject)
        assertEquals("success", str(r.getValue("status")))
        assertEquals("success", str((r.getValue("data") as JsonObject).getValue("status")))
        val decoded = json.decodeFromString(
            NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
        )
        assertEquals("实例入表", 1, decoded.equipmentInstances.size)
        val inst = decoded.equipmentInstances.single()
        assertEquals("eq-1", inst.id)
        assertEquals("lietian", inst.setId)
        assertEquals(com.xianxia.sect.core.model.EquipmentSlot.WEAPON, inst.part)
        assertEquals("ATTACK", inst.growth.affix.mainStat.stat.name)
        // 重复 id 拒绝（实例轨防双持有）；信封外层恒 success，业务结果在 data.status
        val dup = exec(ActionIds.INV_ADD_EQUIPMENT_STACK, json.parseToJsonElement(instanceJson) as JsonObject)
        assertEquals(
            "duplicate-id 应失败",
            "failure",
            str((dup.getValue("data") as JsonObject).getValue("status")),
        )
    }

    @Test
    fun `inventory remove equipment instance via execute`() {
        // B3：removeEquipment 实例轨语义——按 id 整件移除
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val instanceJson = """
            {"id":"eq-1","name":"木剑","setId":"lietian","part":"WEAPON",
             "growth":{"level":1,"exp":0,
                       "affix":{"mainStat":{"stat":"ATTACK","value":10.0},"subStats":[],"subRolls":[]}},
             "meta":{"rarity":1,"minRealm":9,"description":"","isLocked":false},
             "ownerId":null,"isEquipped":false}
        """.trimIndent()
        val initial = NativeGameState(
            equipmentInstances = listOf(
                json.decodeFromString(
                    com.xianxia.sect.core.model.EquipmentInstance.serializer(), instanceJson
                )
            ),
            gameData = GameData()
        )
        assertTrue(DiffRngBridge.nativeCoreImportState(
            json.encodeToString(NativeGameState.serializer(), initial).encodeToByteArray()))
        val r = exec(ActionIds.INV_REMOVE_EQUIPMENT, buildJsonObject {
            put("id", "eq-1"); put("quantity", 1)
        })
        assertEquals("success", str(r.getValue("status")))
        assertTrue(str((r.getValue("data") as JsonObject).getValue("removed")).toBoolean())
        val decoded = json.decodeFromString(
            NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
        )
        assertTrue("实例应整件移除", decoded.equipmentInstances.isEmpty())
    }

    @Test
    fun `unknown action returns NOT_IMPLEMENTED`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val r = exec(999999, buildJsonObject {})
        assertEquals("failure", str(r.getValue("status")))
        assertEquals("NOT_IMPLEMENTED", str(r.getValue("code")))
    }
}
