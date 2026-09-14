package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.domain.exploration.LevelGenerator
import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.WorldLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * DiffLevelGeneratorTest — 世界关卡生成器跨语言差分对拍。
 *
 * 守护目标：C++ gamecore::system::level_generator（妖兽境界选取/妖兽关卡/洞府关卡/
 * 批量生成）与 Kotlin LevelGenerator 语义逐位一致（同种子 RNG 序列下结果完全相同）。
 *
 * Kotlin 基准：真实 LevelGenerator（注入真实 GameRngManager，EXPLORATION 分区）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffLevelGeneratorTest {

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

    private fun levelOf(el: JsonElement): WorldLevel = with(el.jsonObject) {
        WorldLevel(
            id = this["id"]!!.jsonPrimitive.content,
            type = if (this["type"]!!.jsonPrimitive.content == "BEAST") LevelType.BEAST else LevelType.CAVE,
            beastType = this["beastType"]?.jsonPrimitive?.content?.toIntOrNull(),
            realm = this["realm"]!!.jsonPrimitive.content.toInt(),
            realmLayer = this["realmLayer"]!!.jsonPrimitive.content.toInt(),
            beastName = this["beastName"]!!.jsonPrimitive.content,
            guardianName = this["guardianName"]!!.jsonPrimitive.content,
            caveName = this["caveName"]!!.jsonPrimitive.content,
            x = this["x"]!!.jsonPrimitive.content.toFloat(),
            y = this["y"]!!.jsonPrimitive.content.toFloat(),
            spawnYear = this["spawnYear"]!!.jsonPrimitive.content.toInt(),
            spawnMonth = this["spawnMonth"]!!.jsonPrimitive.content.toInt(),
            expiryYear = this["expiryYear"]!!.jsonPrimitive.content.toInt(),
            expiryMonth = this["expiryMonth"]!!.jsonPrimitive.content.toInt(),
            count = this["count"]!!.jsonPrimitive.content.toInt(),
            caveImageIndex = this["caveImageIndex"]!!.jsonPrimitive.content.toInt(),
            defeated = this["defeated"]!!.jsonPrimitive.content.toBoolean(),
            beastMaxHp = this["beastMaxHp"]!!.jsonPrimitive.content.toInt(),
            beastMaxMp = this["beastMaxMp"]!!.jsonPrimitive.content.toInt(),
            beastPhysicalAttack = this["beastPhysicalAttack"]!!.jsonPrimitive.content.toInt(),
            beastMagicAttack = this["beastMagicAttack"]!!.jsonPrimitive.content.toInt(),
            beastPhysicalDefense = this["beastPhysicalDefense"]!!.jsonPrimitive.content.toInt(),
            beastMagicDefense = this["beastMagicDefense"]!!.jsonPrimitive.content.toInt(),
            beastSpeed = this["beastSpeed"]!!.jsonPrimitive.content.toInt(),
        )
    }

    private fun assertLevelParity(k: WorldLevel, c: WorldLevel, tag: String) {
        assertEquals("$tag type", k.type.name, c.type.name)
        assertEquals("$tag realm", k.realm, c.realm)
        assertEquals("$tag realmLayer", k.realmLayer, c.realmLayer)
        assertEquals("$tag beastName", k.beastName, c.beastName)
        assertEquals("$tag guardianName", k.guardianName, c.guardianName)
        assertEquals("$tag caveName", k.caveName, c.caveName)
        assertEquals("$tag x", k.x, c.x)
        assertEquals("$tag y", k.y, c.y)
        assertEquals("$tag spawnYear", k.spawnYear, c.spawnYear)
        assertEquals("$tag spawnMonth", k.spawnMonth, c.spawnMonth)
        assertEquals("$tag expiryYear", k.expiryYear, c.expiryYear)
        assertEquals("$tag expiryMonth", k.expiryMonth, c.expiryMonth)
        assertEquals("$tag count", k.count, c.count)
        assertEquals("$tag caveImageIndex", k.caveImageIndex, c.caveImageIndex)
        assertEquals("$tag beastMaxHp", k.beastMaxHp, c.beastMaxHp)
        assertEquals("$tag beastMaxMp", k.beastMaxMp, c.beastMaxMp)
        assertEquals("$tag beastPhysicalAttack", k.beastPhysicalAttack, c.beastPhysicalAttack)
        assertEquals("$tag beastMagicAttack", k.beastMagicAttack, c.beastMagicAttack)
        assertEquals("$tag beastPhysicalDefense", k.beastPhysicalDefense, c.beastPhysicalDefense)
        assertEquals("$tag beastMagicDefense", k.beastMagicDefense, c.beastMagicDefense)
        assertEquals("$tag beastSpeed", k.beastSpeed, c.beastSpeed)
    }

    @Test
    fun `select beast realm matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for ((seed, year, avg) in listOf(
            Triple(42L, 1, 5),
            Triple(42L, 100, 7),
            Triple(42L, 500, 4),
            Triple(7L, 1, null),
            Triple(7L, 2000, 9),
            Triple(99L, 250, 2),
        )) {
            freshCore(seed)
            val generator = LevelGenerator(kotlinRng(seed))
            val kotlinRealm = generator.selectBeastRealm(year, avg)
            val params = buildJsonObject {
                put("year", year)
                if (avg != null) put("playerAvgRealm", avg)
            }
            val result = cppExec(ActionIds.LEVEL_SELECT_BEAST_REALM, params)
            assertSuccess(result)
            val cppRealm = result["data"]!!.jsonObject["realm"]!!.jsonPrimitive.content.toInt()
            assertEquals("seed=$seed year=$year avg=$avg", kotlinRealm, cppRealm)
        }
    }

    @Test
    fun `generate levels matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        for ((seed, year, month) in listOf(
            Triple(42L, 1, 1),
            Triple(42L, 3, 10),
            Triple(7L, 1, 1),
            Triple(99L, 500, 5),
        )) {
            freshCore(seed)
            val generator = LevelGenerator(kotlinRng(seed))
            val sects = listOf(
                WorldSect(id = "player", name = "青云宗", x = 800f, y = 400f, isPlayerSect = true),
                WorldSect(id = "ai-1", name = "万剑宗", x = 1200f, y = 600f),
            )
            val kotlinLevels = generator.generateWorldLevels(
                existingSects = sects,
                currentYear = year,
                currentMonth = month,
                existingLevels = emptyList(),
                maxNewLevels = 6,
                playerAvgRealm = null
            )

            val params = buildJsonObject {
                put("year", year)
                put("month", month)
                put("maxNewLevels", 6)
                put("sects", buildJsonArray {
                    for (s in sects) {
                        add(buildJsonObject {
                            put("id", s.id); put("name", s.name)
                            put("x", s.x); put("y", s.y)
                            put("isPlayerSect", s.isPlayerSect)
                        })
                    }
                })
                put("existingLevels", buildJsonArray { })
            }
            val result = cppExec(ActionIds.LEVEL_GENERATE_LEVELS, params)
            assertSuccess(result)
            val cppLevels = result["data"]!!.jsonObject["levels"]!!.jsonArray.map { levelOf(it) }

            assertEquals("seed=$seed 关卡数量", kotlinLevels.size, cppLevels.size)
            for (i in kotlinLevels.indices) {
                assertLevelParity(kotlinLevels[i], cppLevels[i], "seed=$seed level[$i]")
            }
        }
    }

    @Test
    fun `generate levels with avg realm matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore(42)
        val generator = LevelGenerator(kotlinRng(42))
        // 玩家平均境界 5 → 妖兽境界 clamp [4,7]（Kotlin 真实路径）
        val sects = listOf(
            WorldSect(id = "player", name = "青云宗", x = 800f, y = 400f, isPlayerSect = true),
        )
        val kotlinLevels = generator.generateWorldLevels(
            existingSects = sects,
            currentYear = 1,
            currentMonth = 3,
            existingLevels = emptyList(),
            maxNewLevels = 2,
            playerAvgRealm = 5
        )
        val params = buildJsonObject {
            put("year", 1); put("month", 3)
            put("maxNewLevels", 2)
            put("playerAvgRealm", 5)
            put("sects", buildJsonArray {
                for (s in sects) {
                    add(buildJsonObject {
                        put("id", s.id); put("name", s.name)
                        put("x", s.x); put("y", s.y)
                        put("isPlayerSect", s.isPlayerSect)
                    })
                }
            })
            put("existingLevels", buildJsonArray { })
        }
        val result = cppExec(ActionIds.LEVEL_GENERATE_LEVELS, params)
        assertSuccess(result)
        val cppLevels = result["data"]!!.jsonObject["levels"]!!.jsonArray.map { levelOf(it) }
        assertEquals("关卡数量", kotlinLevels.size, cppLevels.size)
        for (i in kotlinLevels.indices) {
            assertLevelParity(kotlinLevels[i], cppLevels[i], "avg=5 level[$i]")
            // 妖兽境界 clamp [4,7] 断言（Kotlin 已 clamp，C++ 必须一致）
            assertTrue("realm=${cppLevels[i].realm} 应在 [4,7]",
                cppLevels[i].realm in 4..7 || cppLevels[i].type == LevelType.CAVE)
        }
    }
}
