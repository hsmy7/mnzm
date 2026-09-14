package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffDeathHandlerTest — 死亡物化跨语言差分对拍。
 *
 * 守护目标：C++ gamecore::system::death_handler（markDead 三字段写入 +
 * 年死亡计数 + 装备断言 / backfillDeathYears）与 Kotlin DiscipleDeathHandler
 * 语义逐位一致。
 *
 * Kotlin 基准：真实 DiscipleDeathHandler + DiscipleTables（组件表）+ GameData。
 *
 * 已知边界（death_handler.h 注释明示）：Kotlin markDead(Int) 对不存在的 id
 * 会插入幽灵列条目（组件表内部机制，稠密 SoA 无法模拟）——C++ 静默跳过
 * （marked=false），等价 Kotlin String 版 toIntOrNull 失败跳过；实际调用方
 * （战斗/洞府/驻防阵亡）均保证弟子存在。本测试只对拍存在场景。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffDeathHandlerTest {

    private val json = Json { encodeDefaults = true }

    private fun freshCore() {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
    }

    private fun importState(disciples: List<Disciple>, annualDeceased: Int) {
        val state = NativeGameState(
            gameData = GameData().apply { annualDeceasedDisciples = annualDeceased },
            disciples = disciples
        )
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            json.encodeToString(NativeGameState.serializer(), state).encodeToByteArray()))
    }

    private fun cppExec(actionId: Int, params: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreExecute(
            actionId, json.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
        ).decodeToString()
        return json.parseToJsonElement(result) as JsonObject
    }

    private fun assertSuccess(result: JsonObject) {
        assertEquals("success", result["status"]!!.jsonPrimitive.content)
    }

    private fun kotlinTables(disciples: List<Disciple>): DiscipleTables =
        DiscipleTables().also {
            it.writeAllowed = true
            it.replaceAll(disciples)
        }

    /** 构造事务内 MutableGameState（markDead 需在 stateStore.update 事务内调用） */
    private fun kotlinState(tables: DiscipleTables, annualDeceased: Int): MutableGameState =
        MutableGameState(
            gameData = GameData().apply { annualDeceasedDisciples = annualDeceased },
            discipleTables = tables,
            equipmentStacks = EntityStore(emptyList()),
            equipmentInstances = EntityStore(emptyList()),
            manualStacks = EntityStore(emptyList()),
            manualInstances = EntityStore(emptyList()),
            pills = EntityStore(emptyList()),
            materials = EntityStore(emptyList()),
            herbs = EntityStore(emptyList()),
            seeds = EntityStore(emptyList()),
            storageBags = EntityStore(emptyList()),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )

    @Test
    fun `mark dead matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val disciples = listOf(
            Disciple(id = "1", name = "张三"),
            Disciple(id = "2", name = "李四"),
        )
        // Kotlin 基准：markDead(1, 10) → 三字段 + 计数 +1
        val tables = kotlinTables(disciples)
        val state = kotlinState(tables, annualDeceased = 5)
        DiscipleDeathHandler().markDead(state, 1, 10)
        assertEquals(0, tables.isAlive[1])
        assertEquals(DiscipleStatus.DEAD, tables.statuses[1])
        assertEquals(10, tables.deathYears[1])
        assertEquals(6, state.gameData.annualDeceasedDisciples)
        assertEquals(1, tables.isAlive[2])  // 其他弟子不受影响

        // C++：同种子无关（无 RNG），直接导入同一状态执行
        importState(disciples, annualDeceased = 5)
        val r = cppExec(ActionIds.DISCIPLE_MARK_DEAD, buildJsonObject {
            put("discipleId", "1"); put("deathYear", 10)
        })
        assertSuccess(r)
        val data = r["data"]!!.jsonObject
        assertEquals("marked", true, data["marked"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("isAlive", 0, data["isAlive"]!!.jsonPrimitive.content.toInt())
        assertEquals("status", "DEAD", data["status"]!!.jsonPrimitive.content)
        assertEquals("deathYears", 10, data["deathYears"]!!.jsonPrimitive.content.toInt())
        assertEquals("annualDeceasedDisciples", 6,
            data["annualDeceasedDisciples"]!!.jsonPrimitive.content.toInt())
        assertEquals("hadEquipment", false, data["hadEquipment"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `mark dead with equipped item flags equipment`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val disciples = listOf(
            Disciple(id = "1", name = "张三").apply { weaponId = "w-1" },
        )
        // Kotlin 基准：装备断言仅记日志（DomainLog.w），三字段与计数仍写入
        val tables = kotlinTables(disciples)
        val state = kotlinState(tables, annualDeceased = 0)
        DiscipleDeathHandler().markDead(state, 1, 3)
        assertEquals(0, tables.isAlive[1])
        assertEquals(DiscipleStatus.DEAD, tables.statuses[1])
        assertEquals(3, tables.deathYears[1])
        assertEquals(1, state.gameData.annualDeceasedDisciples)

        // C++：hadEquipment=true（Kotlin 仅记日志，断言标志由 C++ 显式返回）
        importState(disciples, annualDeceased = 0)
        val r = cppExec(ActionIds.DISCIPLE_MARK_DEAD, buildJsonObject {
            put("discipleId", "1"); put("deathYear", 3)
        })
        assertSuccess(r)
        val data = r["data"]!!.jsonObject
        assertEquals("marked", true, data["marked"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("hadEquipment", true, data["hadEquipment"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("isAlive", 0, data["isAlive"]!!.jsonPrimitive.content.toInt())
        assertEquals("status", "DEAD", data["status"]!!.jsonPrimitive.content)
        assertEquals("deathYears", 3, data["deathYears"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `mark dead missing disciple skipped in cpp`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        // 已知边界：Kotlin markDead(Int) 对不存在 id 会插入幽灵列条目（组件表
        // 内部机制，稠密 SoA 无法模拟）；C++ 静默跳过（marked=false，计数不变）。
        // 本测试只验证 C++ 侧行为（等价 Kotlin String 版 toIntOrNull 失败跳过）。
        importState(listOf(Disciple(id = "1", name = "张三")), annualDeceased = 2)
        val r = cppExec(ActionIds.DISCIPLE_MARK_DEAD, buildJsonObject {
            put("discipleId", "999"); put("deathYear", 10)
        })
        assertSuccess(r)
        val data = r["data"]!!.jsonObject
        assertEquals("marked", false, data["marked"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("计数不变", 2, data["annualDeceasedDisciples"]!!.jsonPrimitive.content.toInt())
        assertFalse("未标记不输出三字段", data.containsKey("isAlive"))
    }

    @Test
    fun `backfill death years matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val disciples = listOf(
            Disciple(id = "1", name = "亡者甲", isAlive = false),
            Disciple(id = "2", name = "亡者乙", isAlive = false),
            Disciple(id = "3", name = "存活丙", isAlive = true),
        )
        // Kotlin 基准："2" 已有 deathYears=5（先标记），"1" 缺失 → 补写 10；
        // "3" 存活跳过；已有记录不覆盖
        val tables = kotlinTables(disciples)
        val state = kotlinState(tables, annualDeceased = 1)
        DiscipleDeathHandler().markDead(state, 2, 5)
        DiscipleDeathHandler().backfillDeathYears(tables, disciples, 10)
        assertEquals(10, tables.deathYears[1])
        assertEquals(5, tables.deathYears[2])
        assertFalse(tables.deathYears.contains(3))

        // C++：先标记 "2"（year=5），再 backfill 同一列表
        importState(disciples, annualDeceased = 1)
        assertSuccess(cppExec(ActionIds.DISCIPLE_MARK_DEAD, buildJsonObject {
            put("discipleId", "2"); put("deathYear", 5)
        }))
        val r = cppExec(ActionIds.DISCIPLE_BACKFILL_DEATH_YEARS, buildJsonObject {
            put("deathYear", 10)
            put("disciples", buildJsonArray {
                for (d in disciples) {
                    add(buildJsonObject {
                        put("id", d.id); put("name", d.name); put("isAlive", d.isAlive)
                    })
                }
            })
        })
        assertSuccess(r)
        val data = r["data"]!!.jsonObject
        assertEquals("backfilled", 1, data["backfilled"]!!.jsonPrimitive.content.toInt())
        val byId = data["entries"]!!.jsonArray.associate { e ->
            val o = e.jsonObject
            o["id"]!!.jsonPrimitive.content to o["deathYears"]!!.jsonPrimitive.content.toInt()
        }
        assertEquals("补写", 10, byId["1"])
        assertEquals("已有记录不覆盖", 5, byId["2"])
        assertFalse("存活弟子无条目", byId.containsKey("3"))
    }
}
