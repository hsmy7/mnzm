package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.model.BattleTeam
import com.xianxia.sect.core.model.BattleTeamSlot
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.CaveExplorationTeam
import com.xianxia.sect.core.model.DirectDiscipleSlot
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.LibrarySlot
import com.xianxia.sect.core.model.PatrolSlot
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.model.WarehouseGarrisonSlot
import com.xianxia.sect.core.model.WorldSect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffSlotCleanupTest — 弟子槽位清理跨语言差分对拍（计划 v2 阶段 4 / 批 4-5）。
 *
 * 守护目标：C++ gamecore::system::slot_cleanup（11 类槽位纯数据变换）与
 * Kotlin DiscipleSlotCleanup.clearAllSlotsDataOnly 语义逐位一致。
 *
 * Kotlin 基准：真实 DiscipleSlotCleanup（注入真实 Gate/Registry，
 * clearAllSlotsDataOnly 不触碰 Gate 注册表）。
 *
 * 已知边界：activeMissions 走空列表对拍（Kotlin 完整 ActiveMission 依赖
 * MissionTemplate/MissionRewardConfig 重模型；成员过滤由 C++ GTest +
 * Kotlin DiscipleSlotCleanupTest 分别覆盖）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffSlotCleanupTest {

    private val json = Json { encodeDefaults = true }

    private fun freshCore() {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
    }

    private fun cppExec(actionId: Int, params: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreExecute(
            actionId, json.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
        ).decodeToString()
        return json.parseToJsonElement(result) as JsonObject
    }

    private fun assertSuccess(result: JsonObject) {
        assertEquals("C++ 响应: $result", "success", result["status"]!!.jsonPrimitive.content)
    }

    private fun str(el: kotlinx.serialization.json.JsonElement): String = el.jsonPrimitive.content
    private fun bool(el: kotlinx.serialization.json.JsonElement): Boolean = el.jsonPrimitive.content.toBoolean()
    private fun int(el: kotlinx.serialization.json.JsonElement): Int = el.jsonPrimitive.content.toInt()

    private fun kotlinGameData(): GameData = GameData(
        spiritMineSlots = listOf(
            SpiritMineSlot(discipleId = "1", discipleName = "张三"),
            SpiritMineSlot(discipleId = "2", discipleName = "李四"),
        ),
        librarySlots = listOf(LibrarySlot(discipleId = "1", discipleName = "张三")),
        elderSlots = ElderSlots(
            viceSectMaster = "1",
            innerElder = "2",
            preachingMasters = listOf(
                DirectDiscipleSlot(index = 0, discipleId = "1"),
                DirectDiscipleSlot(index = 1, discipleId = "2"),
            ),
        ),
        residenceSlots = listOf(ResidenceSlot(discipleId = "1", discipleName = "张三")),
        activeBloodRefinements = mapOf("br-1" to BloodRefinementProgress(discipleId = "1")),
        patrolSlots = listOf(PatrolSlot(discipleId = "1")),
        warehouseGarrisons = listOf(WarehouseGarrisonSlot(discipleId = "1", discipleName = "张三")),
        battleTeams = listOf(
            BattleTeam(slots = listOf(
                BattleTeamSlot(index = 0, discipleId = "1", discipleName = "张三"),
                BattleTeamSlot(index = 1, discipleId = "2", discipleName = "李四"),
            )),
        ),
        worldMapSects = listOf(
            WorldSect(id = "player", isPlayerSect = true, garrisonSlots = listOf(
                GarrisonSlot(index = 3, discipleId = "1", discipleName = "张三"),
            )),
            WorldSect(id = "ai-1", isPlayerSect = false, garrisonSlots = listOf(
                GarrisonSlot(index = 1, discipleId = "1"),
            )),
        ),
        productionSlots = listOf(
            ProductionSlot(id = "p1", assignedDiscipleId = "1", assignedDiscipleName = "张三"),
        ),
        caveExplorationTeams = listOf(
            CaveExplorationTeam(memberIds = listOf("1", "2"), memberNames = listOf("张三", "李四")),
        ),
        activeMissions = emptyList(),
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod")  // 对拍参数构造：12 类槽位 JSON 单函数承载
    private fun cppParams(data: GameData, includeResidence: Boolean): JsonObject = buildJsonObject {
        put("discipleId", "1")
        put("includeResidence", includeResidence)
        put("spiritMineSlots", buildJsonArray {
            for (s in data.spiritMineSlots) {
                add(buildJsonObject {
                    put("discipleId", s.discipleId); put("discipleName", s.discipleName)
                })
            }
        })
        put("librarySlots", buildJsonArray {
            for (s in data.librarySlots) {
                add(buildJsonObject {
                    put("discipleId", s.discipleId); put("discipleName", s.discipleName)
                })
            }
        })
        put("elderSlots", buildJsonObject {
            put("viceSectMaster", data.elderSlots.viceSectMaster)
            put("innerElder", data.elderSlots.innerElder)
            put("preachingMasters", buildJsonArray {
                for (s in data.elderSlots.preachingMasters) {
                    add(buildJsonObject {
                        put("index", s.index); put("discipleId", s.discipleId)
                        put("discipleName", s.discipleName)
                    })
                }
            })
        })
        put("residenceSlots", buildJsonArray {
            for (s in data.residenceSlots) {
                add(buildJsonObject {
                    put("buildingInstanceId", s.buildingInstanceId)
                    put("discipleId", s.discipleId); put("discipleName", s.discipleName)
                })
            }
        })
        put("activeBloodRefinements", buildJsonObject {
            for ((key, p) in data.activeBloodRefinements) {
                put(key, buildJsonObject { put("discipleId", p.discipleId) })
            }
        })
        put("patrolSlots", buildJsonArray {
            for (s in data.patrolSlots) {
                add(buildJsonObject { put("discipleId", s.discipleId) })
            }
        })
        put("warehouseGarrisons", buildJsonArray {
            for (s in data.warehouseGarrisons) {
                add(buildJsonObject {
                    put("discipleId", s.discipleId); put("discipleName", s.discipleName)
                })
            }
        })
        put("battleTeams", buildJsonArray {
            for (t in data.battleTeams) {
                add(buildJsonObject {
                    put("id", t.id); put("name", t.name); put("teamNumber", t.teamNumber)
                    put("slots", buildJsonArray {
                        for (s in t.slots) {
                            add(buildJsonObject {
                                put("index", s.index); put("discipleId", s.discipleId)
                                put("discipleName", s.discipleName); put("isAlive", s.isAlive)
                            })
                        }
                    })
                })
            }
        })
        put("worldMapSects", buildJsonArray {
            for (s in data.worldMapSects) {
                add(buildJsonObject {
                    put("id", s.id); put("isPlayerSect", s.isPlayerSect)
                    put("garrisonSlots", buildJsonArray {
                        for (g in s.garrisonSlots) {
                            add(buildJsonObject {
                                put("index", g.index); put("discipleId", g.discipleId)
                                put("discipleName", g.discipleName)
                            })
                        }
                    })
                })
            }
        })
        put("productionSlots", buildJsonArray {
            for (s in data.productionSlots) {
                add(buildJsonObject {
                    put("id", s.id)
                    put("assignedDiscipleId", s.assignedDiscipleId ?: "")
                    put("assignedDiscipleName", s.assignedDiscipleName)
                })
            }
        })
        put("caveExplorationTeams", buildJsonArray {
            for (t in data.caveExplorationTeams) {
                add(buildJsonObject {
                    put("id", t.id); put("memberIds", buildJsonArray { t.memberIds.forEach { add(it) } })
                    put("memberNames", buildJsonArray { t.memberNames.forEach { add(it) } })
                    put("status", t.status.name)
                })
            }
        })
        put("activeMissions", buildJsonArray { })
    }

    @Suppress("LongMethod")  // 对拍断言：12 类槽位逐字段比较单函数承载
    @Test
    fun `clear all slots matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        for (includeResidence in listOf(false, true)) {
            val data = kotlinGameData()
            // Kotlin 基准
            val cleanup = DiscipleSlotCleanup(DiscipleAssignmentGate(DiscipleAssignmentRegistry()))
            val kotlinOut = cleanup.clearAllSlotsDataOnly(data, "1", includeResidence)
            // C++
            val r = cppExec(ActionIds.SLOT_CLEAR_ALL, cppParams(data, includeResidence))
            assertSuccess(r)
            val c = r["data"]!!.jsonObject
            val tag = "includeResidence=$includeResidence"

            // 灵矿
            val kMine = kotlinOut.spiritMineSlots
            val cMine = c.getValue("spiritMineSlots").jsonArray
            assertEquals("$tag 灵矿数", kMine.size, cMine.size)
            assertEquals("$tag 灵矿[0]", kMine[0].discipleId, str(cMine[0].jsonObject.getValue("discipleId")))
            assertEquals("$tag 灵矿[1]", kMine[1].discipleId, str(cMine[1].jsonObject.getValue("discipleId")))

            // 长老
            val kElder = kotlinOut.elderSlots
            val cElder = c.getValue("elderSlots").jsonObject
            assertEquals("$tag viceSectMaster", kElder.viceSectMaster, str(cElder.getValue("viceSectMaster")))
            assertEquals("$tag innerElder", kElder.innerElder, str(cElder.getValue("innerElder")))
            val kPreaching = kElder.preachingMasters
            val cPreaching = cElder.getValue("preachingMasters").jsonArray
            assertEquals("$tag preachingMasters 数", kPreaching.size, cPreaching.size)
            assertEquals("$tag preaching[0] discipleId", kPreaching[0].discipleId,
                str(cPreaching[0].jsonObject.getValue("discipleId")))
            assertEquals("$tag preaching[0] index", kPreaching[0].index,
                int(cPreaching[0].jsonObject.getValue("index")))
            assertEquals("$tag preaching[1] discipleId", kPreaching[1].discipleId,
                str(cPreaching[1].jsonObject.getValue("discipleId")))

            // 住所
            val kResidence = kotlinOut.residenceSlots
            val cResidence = c.getValue("residenceSlots").jsonArray
            assertEquals("$tag 住所数", kResidence.size, cResidence.size)
            assertEquals("$tag 住所[0]", kResidence[0].discipleId,
                str(cResidence[0].jsonObject.getValue("discipleId")))

            // 血炼
            val kBlood = kotlinOut.activeBloodRefinements
            val cBlood = c.getValue("activeBloodRefinements").jsonObject
            assertEquals("$tag 血炼数", kBlood.size, cBlood.size)

            // 战斗队伍
            val kTeam = kotlinOut.battleTeams[0]
            val cTeam = c.getValue("battleTeams").jsonArray[0].jsonObject
            val kSlots = kTeam.slots
            val cSlots = cTeam.getValue("slots").jsonArray
            assertEquals("$tag 队伍槽位数", kSlots.size, cSlots.size)
            for (i in kSlots.indices) {
                assertEquals("$tag slot[$i] discipleId", kSlots[i].discipleId,
                    str(cSlots[i].jsonObject.getValue("discipleId")))
                assertEquals("$tag slot[$i] isAlive", kSlots[i].isAlive,
                    bool(cSlots[i].jsonObject.getValue("isAlive")))
            }

            // 世界地图驻防
            val kSects = kotlinOut.worldMapSects
            val cSects = c.getValue("worldMapSects").jsonArray
            assertEquals("$tag 宗门数", kSects.size, cSects.size)
            for (i in kSects.indices) {
                val kG = kSects[i].garrisonSlots
                val cG = cSects[i].jsonObject.getValue("garrisonSlots").jsonArray
                assertEquals("$tag sect[$i] 驻防数", kG.size, cG.size)
                for (j in kG.indices) {
                    assertEquals("$tag sect[$i] garrison[$j] discipleId", kG[j].discipleId,
                        str(cG[j].jsonObject.getValue("discipleId")))
                    assertEquals("$tag sect[$i] garrison[$j] index", kG[j].index,
                        int(cG[j].jsonObject.getValue("index")))
                }
            }

            // 生产槽位
            val kProd = kotlinOut.productionSlots[0]
            val cProd = c.getValue("productionSlots").jsonArray[0].jsonObject
            assertTrue("$tag assignedDiscipleId 应为 null（C++ nullopt → JSON null）",
                cProd.getValue("assignedDiscipleId") is kotlinx.serialization.json.JsonNull)
            assertEquals("$tag assignedDiscipleName", kProd.assignedDiscipleName,
                str(cProd.getValue("assignedDiscipleName")))

            // 洞府探索队
            val kCave = kotlinOut.caveExplorationTeams[0]
            val cCave = c.getValue("caveExplorationTeams").jsonArray[0].jsonObject
            val kCaveIds = kCave.memberIds
            val cCaveIds = cCave.getValue("memberIds").jsonArray
            assertEquals("$tag 洞府成员数", kCaveIds.size, cCaveIds.size)
            for (i in kCaveIds.indices) {
                assertEquals("$tag 洞府成员[$i]", kCaveIds[i], str(cCaveIds[i]))
            }
            assertEquals("$tag 洞府状态", kCave.status.name, str(cCave.getValue("status")))
        }
    }

    @Test
    fun `clear all slots keeps unrelated disciple`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val data = kotlinGameData()
        val cleanup = DiscipleSlotCleanup(DiscipleAssignmentGate(DiscipleAssignmentRegistry()))
        val kotlinOut = cleanup.clearAllSlotsDataOnly(data, "999", true)
        val params = cppParams(data, includeResidence = true).toMutableMap().apply {
            put("discipleId", kotlinx.serialization.json.JsonPrimitive("999"))
        }
        val r = cppExec(ActionIds.SLOT_CLEAR_ALL, JsonObject(params))
        assertSuccess(r)
        val c = r["data"]!!.jsonObject
        val cMine = c.getValue("spiritMineSlots").jsonArray
        assertEquals("灵矿[0] 保留", kotlinOut.spiritMineSlots[0].discipleId,
            str(cMine[0].jsonObject.getValue("discipleId")))
        assertEquals("viceSectMaster 保留", kotlinOut.elderSlots.viceSectMaster,
            str(c.getValue("elderSlots").jsonObject.getValue("viceSectMaster")))
        val cCave = c.getValue("caveExplorationTeams").jsonArray[0].jsonObject
        assertEquals("洞府成员保留", kotlinOut.caveExplorationTeams[0].memberIds.size,
            cCave.getValue("memberIds").jsonArray.size)
        assertTrue("洞府成员为 2", cCave.getValue("memberIds").jsonArray.size == 2)
    }
}
