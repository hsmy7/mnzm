package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.domain.exploration.SecretRealmBattleHelper
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmEventGenerator
import com.xianxia.sect.core.engine.domain.exploration.SecretRealmAIProcessor
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SecretRealmAIMember
import com.xianxia.sect.core.model.SecretRealmAITeam
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.model.SecretRealmEventRecord
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * DiffSecretRealmTest — 远古秘境状态机核心跨语言差分对拍。
 *
 * 守护目标：C++ gamecore::system::secret_realm（平均境界/妖兽事件/下一事件分派/
 * 妖兽属性预生成/妖兽掉落/遗迹秘宝/丢失物品/AI 队伍派遣）与 Kotlin
 * SecretRealmEventGenerator / SecretRealmBattleHelper / SecretRealmAIProcessor
 * 语义逐位一致（同种子 SECRET_REALM 分区 RNG 下结果完全相同）。
 *
 * Kotlin 基准：真实 Kotlin 类（注入真实 GameRngManager，SECRET_REALM 分区）。
 *
 * 已知边界：遗迹秘宝模板实例化（数据库注册表）与战斗执行保留 Kotlin；
 * 本测试对拍 C++ 生成/判定逻辑（候选模板列表由测试从同源数据构造）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffSecretRealmTest {

    private data class StatsCase(
        val seed: Long, val realm: Int, val type: String, val ambush: Boolean, val layer: Int
    )

    private data class LootCase(val seed: Long, val type: String, val realm: Int, val count: Int)

    private val json = Json { encodeDefaults = true }

    private fun freshCore(seed: Long) {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
        DiffRngBridge.nativeCoreRngInitSeed(seed)
    }

    private fun kotlinRng(seed: Long): GameRngManager =
        GameRngManager().also { it.initSystemSeed(seed) }

    private fun cppExec(actionId: Int, params: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreExecute(
            actionId, json.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
        ).decodeToString()
        return json.parseToJsonElement(result) as JsonObject
    }

    private fun assertSuccess(result: JsonObject) {
        assertEquals("success", result["status"]!!.jsonPrimitive.content)
    }

    private fun str(el: JsonElement): String = el.jsonPrimitive.content
    private fun int(el: JsonElement): Int = el.jsonPrimitive.content.toInt()

    private fun members(): List<SecretRealmMemberState> = listOf(
        SecretRealmMemberState(discipleId = "1", name = "张三", realm = 3, realmName = "合体"),
        SecretRealmMemberState(discipleId = "2", name = "李四", realm = 4, realmName = "炼虚"),
        SecretRealmMemberState(discipleId = "3", name = "王五", realm = 5, realmName = "化神"),
        SecretRealmMemberState(discipleId = "4", name = "赵六", realm = 6, realmName = "元婴"),
    )

    private fun membersJson(): JsonArray = buildJsonArray {
        for (m in members()) {
            add(buildJsonObject {
                put("discipleId", m.discipleId); put("name", m.name)
                put("portraitRes", m.portraitRes); put("realm", m.realm)
                put("realmName", m.realmName); put("currentHp", m.currentHp)
                put("isDying", m.isDying); put("isDead", m.isDead); put("maxHp", m.maxHp)
            })
        }
    }

    private fun assertEventParity(k: SecretRealmEventRecord, c: JsonObject, tag: String) {
        assertEquals("$tag eventType", k.eventType, str(c.getValue("eventType")))
        assertEquals("$tag title", k.title, str(c.getValue("title")))
        assertEquals("$tag description", k.description, str(c.getValue("description")))
        val cOptions = c.getValue("options").jsonArray
        assertEquals("$tag options.size", k.options.size, cOptions.size)
        for (i in k.options.indices) {
            val ko = k.options[i]
            val co = cOptions[i].jsonObject
            assertEquals("$tag option[$i].label", ko.label, str(co.getValue("label")))
            assertEquals("$tag option[$i].description", ko.description, str(co.getValue("description")))
            assertEquals("$tag option[$i].staminaCost", ko.staminaCost, int(co.getValue("staminaCost")))
        }
        val kp = k.params
        val cp = c.getValue("params").jsonObject
        assertEquals("$tag params.beastTypeName", kp.beastTypeName, str(cp.getValue("beastTypeName")))
        assertEquals("$tag params.beastRealm", kp.beastRealm, int(cp.getValue("beastRealm")))
        assertEquals("$tag params.beastLayer", kp.beastLayer, int(cp.getValue("beastLayer")))
        assertEquals("$tag params.beastCount", kp.beastCount, int(cp.getValue("beastCount")))
    }

    @Test
    fun `player avg realm matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        val kotlinAvg = SecretRealmEventGenerator.playerAvgRealm(members())
        val r = cppExec(ActionIds.SECRET_REALM_PLAYER_AVG_REALM, buildJsonObject {
            put("members", membersJson())
        })
        assertSuccess(r)
        assertEquals(kotlinAvg, int(r["data"]!!.jsonObject.getValue("avgRealm")))
        // 全灭取上限
        val deadMembers = members().map { it.copy(isDead = true) }
        assertEquals(
            SecretRealmEventGenerator.playerAvgRealm(deadMembers),
            int(cppExec(ActionIds.SECRET_REALM_PLAYER_AVG_REALM, buildJsonObject {
                put("members", buildJsonArray {
                    for (m in deadMembers) {
                        add(buildJsonObject {
                            put("discipleId", m.discipleId); put("name", m.name)
                            put("realm", m.realm); put("isDead", true)
                        })
                    }
                })
            })["data"]!!.jsonObject.getValue("avgRealm"))
        )
    }

    @Test
    fun `beast event matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for ((seed, avg) in listOf(42L to 5, 42L to 7, 7L to 3, 99L to 9)) {
            freshCore(seed)
            val kotlinEvent = SecretRealmEventGenerator.generateBeastEvent(
                kotlinRng(seed).getRng(RngPartition.SECRET_REALM), avg)
            val r = cppExec(ActionIds.SECRET_REALM_GENERATE_BEAST_EVENT, buildJsonObject {
                put("playerAvgRealm", avg)
            })
            assertSuccess(r)
            assertEventParity(kotlinEvent, r["data"]!!.jsonObject.getValue("event").jsonObject,
                "seed=$seed avg=$avg")
        }
    }

    @Test
    fun `roll next event matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val aiTeams = listOf(
            SecretRealmAITeam(
                id = "t1", sectId = "ai-1", sectName = "万剑宗", sectLevel = 2,
                members = listOf(SecretRealmAIMember(discipleId = "1", name = "甲", realm = 5)),
            ),
            SecretRealmAITeam(
                id = "t2", sectId = "ai-2", sectName = "天机阁", sectLevel = 1,
                members = listOf(SecretRealmAIMember(discipleId = "2", name = "乙", realm = 7)),
            ),
        )
        for ((seed, avg) in listOf(42L to 5, 7L to 6, 99L to 3)) {
            freshCore(seed)
            val kotlinEvent = SecretRealmEventGenerator.rollNextEvent(
                kotlinRng(seed).getRng(RngPartition.SECRET_REALM), avg, aiTeams)
            val r = cppExec(ActionIds.SECRET_REALM_ROLL_NEXT_EVENT, buildJsonObject {
                put("playerAvgRealm", avg)
                put("aiTeams", buildJsonArray {
                    for (t in aiTeams) {
                        add(buildJsonObject {
                            put("id", t.id); put("sectId", t.sectId); put("sectName", t.sectName)
                            put("sectLevel", t.sectLevel)
                            put("members", buildJsonArray {
                                for (m in t.members) {
                                    add(buildJsonObject {
                                        put("discipleId", m.discipleId); put("name", m.name)
                                        put("portraitRes", m.portraitRes); put("realm", m.realm)
                                    })
                                }
                            })
                        })
                    }
                })
            })
            assertSuccess(r)
            assertEventParity(kotlinEvent, r["data"]!!.jsonObject.getValue("event").jsonObject,
                "seed=$seed avg=$avg")
        }
    }

    @Test
    fun `beast pregen stats matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val cases = listOf(
            StatsCase(42L, 9, "虎妖", false, 1),
            StatsCase(42L, 5, "狼妖", true, 3),
            StatsCase(7L, 2, "狐妖", false, 9),
        )
        for (c in cases) {
            freshCore(c.seed)
            val k = SecretRealmEventGenerator.buildBeastPreGenStats(
                kotlinRng(c.seed).getRng(RngPartition.SECRET_REALM), c.realm, c.type, c.ambush, c.layer)
            val r = cppExec(ActionIds.SECRET_REALM_BUILD_BEAST_STATS, buildJsonObject {
                put("realm", c.realm); put("beastTypeName", c.type)
                put("ambushSucceeded", c.ambush); put("beastLayer", c.layer)
            })
            assertSuccess(r)
            val d = r["data"]!!.jsonObject
            assertEquals("seed=${c.seed} maxHp", k.maxHp, int(d.getValue("maxHp")))
            assertEquals("seed=${c.seed} maxMp", k.maxMp, int(d.getValue("maxMp")))
            assertEquals("seed=${c.seed} atk", k.physicalAttack, int(d.getValue("physicalAttack")))
            assertEquals("seed=${c.seed} magicAtk", k.magicAttack, int(d.getValue("magicAttack")))
            assertEquals("seed=${c.seed} def", k.physicalDefense, int(d.getValue("physicalDefense")))
            assertEquals("seed=${c.seed} magicDef", k.magicDefense, int(d.getValue("magicDefense")))
            assertEquals("seed=${c.seed} speed", k.speed, int(d.getValue("speed")))
            assertEquals("seed=${c.seed} layer", k.realmLayer, int(d.getValue("realmLayer")))
        }
    }

    @Test
    fun `beast loot matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val cases = listOf(
            LootCase(42L, "虎妖", 9, 2),
            LootCase(7L, "狼妖", 7, 3),
            LootCase(99L, "蛇妖", 5, 1),
        )
        for (c in cases) {
            freshCore(c.seed)
            val k = SecretRealmEventGenerator.rollBeastLoot(
                kotlinRng(c.seed).getRng(RngPartition.SECRET_REALM), c.type, c.realm, c.count)
            val r = cppExec(ActionIds.SECRET_REALM_ROLL_BEAST_LOOT, buildJsonObject {
                put("beastTypeName", c.type); put("beastRealm", c.realm); put("beastCount", c.count)
            })
            assertSuccess(r)
            val cj = r["data"]!!.jsonObject.getValue("rewards").jsonArray
            assertEquals("seed=${c.seed} 数量", k.size, cj.size)
            for (i in k.indices) {
                val co = cj[i].jsonObject
                assertEquals("seed=${c.seed}[$i] type", k[i].type, str(co.getValue("type")))
                assertEquals("seed=${c.seed}[$i] itemId", k[i].itemId, str(co.getValue("itemId")))
                assertEquals("seed=${c.seed}[$i] name", k[i].name, str(co.getValue("name")))
                assertEquals("seed=${c.seed}[$i] rarity", k[i].rarity, int(co.getValue("rarity")))
                assertEquals("seed=${c.seed}[$i] quantity", k[i].quantity, int(co.getValue("quantity")))
            }
        }
    }

    @Test
    fun `loot loss matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        val backpack = SecretRealmBackpack(
            spiritStones = 1234,
            equipment = listOf(
                com.xianxia.sect.core.model.EquipmentStack(id = "eq1", name = "木剑", rarity = 1),
                com.xianxia.sect.core.model.EquipmentStack(id = "eq2", name = "铁剑", rarity = 1),
            ),
            pills = listOf(
                com.xianxia.sect.core.model.Pill(id = "p1", name = "回春丹"),
                com.xianxia.sect.core.model.Pill(id = "p2", name = "凝气丹"),
            ),
            herbs = listOf(
                com.xianxia.sect.core.model.Herb(id = "h1", name = "灵芝"),
            ),
        )
        val kotlinLoss = SecretRealmBattleHelper.applyLootLoss(
            backpack, kotlinRng(42).getRng(RngPartition.SECRET_REALM))
        val r = cppExec(ActionIds.SECRET_REALM_LOOT_LOSS, buildJsonObject {
            put("backpack", buildJsonObject {
                put("spiritStones", backpack.spiritStones)
                put("equipment", buildJsonArray {
                    for (e in backpack.equipment) {
                        add(buildJsonObject { put("id", e.id); put("name", e.name); put("rarity", e.rarity) })
                    }
                })
                put("pills", buildJsonArray {
                    for (p in backpack.pills) {
                        add(buildJsonObject { put("id", p.id); put("name", p.name) })
                    }
                })
                put("herbs", buildJsonArray {
                    for (h in backpack.herbs) {
                        add(buildJsonObject { put("id", h.id); put("name", h.name) })
                    }
                })
            })
        })
        assertSuccess(r)
        val d = r["data"]!!.jsonObject
        assertEquals("lostItemCount", kotlinLoss.lostItemCount, int(d.getValue("lostItemCount")))
        assertEquals("lostSpiritStones", kotlinLoss.lostSpiritStones,
            d.getValue("lostSpiritStones").jsonPrimitive.content.toLong())
        val cBackpack = d.getValue("backpack").jsonObject
        assertEquals("kept equipment", kotlinLoss.backpack.equipment.size,
            cBackpack.getValue("equipment").jsonArray.size)
        assertEquals("kept pills", kotlinLoss.backpack.pills.size,
            cBackpack.getValue("pills").jsonArray.size)
        assertEquals("kept herbs", kotlinLoss.backpack.herbs.size,
            cBackpack.getValue("herbs").jsonArray.size)
    }

    @Test
    fun `ruins treasure matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 候选模板由测试从同源数据库 API 构造（与 Kotlin generateRuinsTreasure
        // 内部查库内容/顺序一致），C++ 传同一候选 → RNG 消费与选取逐位一致。
        val rarity = 2
        val candidates = buildJsonObject {
            for ((type, list) in listOf(
                "equipment" to com.xianxia.sect.core.registry.EquipmentDatabase.getByRarity(rarity)
                    .map { it.id to it.name },
                "manual" to (if (com.xianxia.sect.core.registry.ManualDatabase.isInitialized)
                    com.xianxia.sect.core.registry.ManualDatabase.getByRarity(rarity)
                        .map { it.id to it.name } else emptyList()),
                "pill" to com.xianxia.sect.core.registry.ItemDatabase.getPillsByRarity(rarity)
                    .map { it.id to it.name },
                "material" to com.xianxia.sect.core.registry.ItemDatabase.allMaterials.values
                    .filter { it.rarity == rarity }.map { it.id to it.name },
                "herb" to com.xianxia.sect.core.registry.HerbDatabase.getByRarity(rarity)
                    .map { it.id to it.name },
                "seed" to com.xianxia.sect.core.registry.HerbDatabase.getSeedsByRarity(rarity)
                    .map { it.id to it.name },
            )) {
                put(type, buildJsonObject {
                    put(rarity.toString(), buildJsonArray {
                        for ((id, name) in list) {
                            add(buildJsonArray { add(id); add(name) })
                        }
                    })
                })
            }
        }
        for (seed in listOf(42L, 7L, 99L)) {
            freshCore(seed)
            val kotlinRewards = SecretRealmEventGenerator.generateRuinsTreasure(
                kotlinRng(seed).getRng(RngPartition.SECRET_REALM),
                minCount = 2, maxCount = 5, minRarity = rarity, maxRarity = rarity)
            val r = cppExec(ActionIds.SECRET_REALM_GENERATE_RUINS_TREASURE, buildJsonObject {
                put("minCount", 2); put("maxCount", 5)
                put("minRarity", rarity); put("maxRarity", rarity)
                put("candidates", candidates)
            })
            assertSuccess(r)
            val c = r["data"]!!.jsonObject.getValue("rewards").jsonArray
            assertEquals("seed=$seed 数量", kotlinRewards.size, c.size)
            for (i in kotlinRewards.indices) {
                val co = c[i].jsonObject
                assertEquals("seed=$seed[$i] type", kotlinRewards[i].type, str(co.getValue("type")))
                assertEquals("seed=$seed[$i] itemId", kotlinRewards[i].itemId, str(co.getValue("itemId")))
                assertEquals("seed=$seed[$i] rarity", kotlinRewards[i].rarity, int(co.getValue("rarity")))
            }
        }
    }

    @Suppress("LongMethod")  // 对拍装配：Kotlin 侧状态构造 + C++ 参数构造 + 全字段断言单函数承载
    @Test
    fun `ai dispatch matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        val disciples = mapOf(
            "ai-1" to listOf(
                Disciple(id = "1", name = "甲", portraitRes = "p1", realm = 3, isAlive = true),
                Disciple(id = "2", name = "乙", portraitRes = "p2", realm = 5, isAlive = true),
                Disciple(id = "3", name = "丙", portraitRes = "p3", realm = 7, isAlive = true),
                Disciple(id = "4", name = "丁", portraitRes = "p4", realm = 9, isAlive = true),
                Disciple(id = "5", name = "戊", portraitRes = "p5", realm = 1, isAlive = true),
                Disciple(id = "6", name = "亡者", portraitRes = "p6", realm = 0, isAlive = false),
            ),
            "ai-2" to listOf(
                Disciple(id = "7", name = "庚", portraitRes = "p7", realm = 4, isAlive = true),
            ),
        )
        val sects = listOf(
            WorldSect(id = "ai-1", name = "万剑宗", level = 2, x = 100f, y = 100f),
            WorldSect(id = "ai-2", name = "", level = 0, x = 200f, y = 200f),  // 空名 → 回退 sectId
        )
        // Kotlin 基准：真实 SecretRealmAIProcessor（需秘境存在）
        val tables = com.xianxia.sect.core.state.DiscipleTables().also {
            it.writeAllowed = true
            it.replaceAll(disciples.values.flatten())
        }
        val state = MutableGameState(
            gameData = GameData().apply {
                secretRealmState = com.xianxia.sect.core.model.SecretRealmState(id = "sr1", x = 1f, y = 1f)
                aiSectDisciples = disciples
                worldMapSects = sects
            },
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
        SecretRealmAIProcessor().processMonthlyAiTeams(state)
        val kotlinTeams = state.gameData.secretRealmAITeams

        val r = cppExec(ActionIds.SECRET_REALM_AI_DISPATCH, buildJsonObject {
            put("pools", buildJsonArray {
                for ((sectId, list) in disciples) {
                    add(buildJsonObject {
                        put("sectId", sectId)
                        val sect = sects.find { it.id == sectId }
                        put("sectFound", sect != null)
                        put("sectName", sect?.name ?: "")
                        put("sectLevel", sect?.level ?: 0)
                        put("disciples", buildJsonArray {
                            for (d in list) {
                                add(buildJsonObject {
                                    put("id", d.id); put("name", d.name)
                                    put("portraitRes", d.portraitRes); put("realm", d.realm)
                                    put("isAlive", d.isAlive)
                                })
                            }
                        })
                    })
                }
            })
        })
        assertSuccess(r)
        val cTeams = r["data"]!!.jsonObject.getValue("teams").jsonArray
        assertEquals("队伍数", kotlinTeams.size, cTeams.size)
        val kotlinById = kotlinTeams.associateBy { it.sectId }
        for (i in cTeams.indices) {
            val co = cTeams[i].jsonObject
            val sectId = str(co.getValue("sectId"))
            val kt = kotlinById.getValue(sectId)
            assertEquals("$sectId sectName", kt.sectName, str(co.getValue("sectName")))
            assertEquals("$sectId sectLevel", kt.sectLevel, int(co.getValue("sectLevel")))
            val cMembers = co.getValue("members").jsonArray
            assertEquals("$sectId members.size", kt.members.size, cMembers.size)
            for (j in kt.members.indices) {
                val cm = cMembers[j].jsonObject
                assertEquals("$sectId[$j] discipleId", kt.members[j].discipleId,
                    str(cm.getValue("discipleId")))
                assertEquals("$sectId[$j] name", kt.members[j].name, str(cm.getValue("name")))
                assertEquals("$sectId[$j] realm", kt.members[j].realm, int(cm.getValue("realm")))
            }
        }
        assertTrue("ai-1 应派 4 人（境界 1,3,5,7）", kotlinTeams.first { it.sectId == "ai-1" }.members.size == 4)
        assertTrue("ai-1 不应含亡者", kotlinTeams.first { it.sectId == "ai-1" }.members.none { it.discipleId == "6" })
        assertTrue("ai-2 应派 1 人", kotlinTeams.first { it.sectId == "ai-2" }.members.size == 1)
    }
}
