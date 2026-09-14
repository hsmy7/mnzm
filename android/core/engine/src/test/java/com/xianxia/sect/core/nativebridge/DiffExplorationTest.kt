package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.LevelType
import com.xianxia.sect.core.model.WorldLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffExplorationTest — 世界关卡跨语言差分对拍。
 *
 * 守护目标：C++ gamecore::system::exploration（过期判定/清理/刷新判定/妖兽移动）
 * 与 Kotlin WorldLevelManager 语义**逐位一致**。
 *
 * Kotlin 基准：真实 WorldLevel.checkExpired + 复刻刷新判定。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffExplorationTest {

    private val json = Json { encodeDefaults = true }

    private fun cppOp(op: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreExplorationOp(
            json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
        return json.parseToJsonElement(result) as JsonObject
    }

    private fun levelJson(
        id: String, type: LevelType = LevelType.BEAST, defeated: Boolean = false,
        expiryYear: Int, expiryMonth: Int,
    ) = buildJsonObject {
        put("id", id); put("type", type.name)
        put("defeated", defeated)
        put("expiryYear", expiryYear); put("expiryMonth", expiryMonth)
    }

    @Test
    fun `check expired matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val cases = listOf(
            // (level, year, month)
            Triple(levelJson("b1", defeated = false, expiryYear = 3, expiryMonth = 6), 2, 12),
            Triple(levelJson("b1", defeated = false, expiryYear = 3, expiryMonth = 6), 3, 5),
            Triple(levelJson("b1", defeated = false, expiryYear = 3, expiryMonth = 6), 3, 6),  // 含等号
            Triple(levelJson("b1", defeated = false, expiryYear = 3, expiryMonth = 6), 4, 1),
            Triple(levelJson("b1", defeated = true, expiryYear = 99, expiryMonth = 1), 1, 1),
            Triple(levelJson("c1", type = LevelType.CAVE, defeated = false, expiryYear = 3, expiryMonth = 6), 3, 6),
        )
        for ((level, year, month) in cases) {
            val op = buildJsonObject {
                put("op", "checkExpired"); put("year", year); put("month", month)
                put("level", level)
            }
            val cpp = cppOp(op)
            val kotlinLevel = WorldLevel(
                id = (level["id"] as kotlinx.serialization.json.JsonPrimitive).content,
                type = if ((level["type"] as kotlinx.serialization.json.JsonPrimitive).content == "BEAST")
                    LevelType.BEAST else LevelType.CAVE,
                defeated = (level["defeated"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean(),
                expiryYear = (level["expiryYear"] as kotlinx.serialization.json.JsonPrimitive).content.toInt(),
                expiryMonth = (level["expiryMonth"] as kotlinx.serialization.json.JsonPrimitive).content.toInt(),
            )
            assertEquals(
                "year=$year month=$month",
                kotlinLevel.checkExpired(year, month),
                cpp["value"]!!.toString().toBoolean()
            )
        }
    }

    @Test
    fun `should refresh matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (case in listOf(
            Triple(0, 1, 1),      // 首次
            Triple(13, 1, 3),     // 差 2 个月
            Triple(13, 1, 4),     // 差 3 个月（含等号）
            Triple(13, 2, 1),     // 差 12 个月
        )) {
            val op = buildJsonObject {
                put("op", "shouldRefresh")
                put("lastRefreshMonth", case.first)
                put("year", case.second); put("month", case.third)
            }
            val cpp = cppOp(op)
            val last = case.first
            val expected = last == 0 ||
                (case.second * 12 + case.third - last) >= 3
            assertEquals("last=$last y=${case.second} m=${case.third}",
                expected, cpp["value"]!!.toString().toBoolean())
        }
    }
}
