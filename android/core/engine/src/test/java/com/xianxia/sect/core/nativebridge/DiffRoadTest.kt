package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.util.RoadTiling
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffRoadTest — 道路系统跨语言差分对拍（批次 R 验收核心）。
 *
 * 守护目标：C++ gamecore::map::road_system（tileTypeForBitmask/roadBorderMask/
 * bitmaskAt）与 Kotlin RoadTiling 语义**逐位一致**——双端渲染深耦合收敛的
 * 求解器权威性守护。
 *
 * Kotlin 基准：真实 RoadTiling（生产代码同一实现）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffRoadTest {

    private val json = Json { encodeDefaults = true }

    private fun cppOp(op: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeRoadOp(
            json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
        return json.parseToJsonElement(result) as JsonObject
    }

    @Test
    fun `tile type matches Kotlin for all masks`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        // 全 16 种 4-bit 掩码全覆盖
        for (mask in 0..15) {
            val op = buildJsonObject { put("op", "tileType"); put("mask", mask) }
            val cpp = cppOp(op)
            val expected = RoadTiling.tileTypeForBitmask(mask)
            assertEquals(
                "mask=$mask",
                expected.ordinal,
                cpp["value"]!!.jsonPrimitive.content.toInt()
            )
        }
    }

    @Test
    fun `border mask matches Kotlin for all masks`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (mask in 0..15) {
            val op = buildJsonObject { put("op", "borderMask"); put("mask", mask) }
            val cpp = cppOp(op)
            assertEquals(
                "mask=$mask",
                RoadTiling.roadBorderMask(mask),
                cpp["value"]!!.jsonPrimitive.content.toInt()
            )
        }
    }

    @Test
    fun `bitmask at matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        // 道路集合：十字形（中心 3,3 + 四邻）
        val roads = listOf(
            listOf(3, 3), listOf(3, 2), listOf(4, 3),
            listOf(3, 4), listOf(2, 3), listOf(5, 5),
        )
        val roadsJson = buildJsonArray {
            roads.forEach {
                add(
                    buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(it[0]))
                        add(kotlinx.serialization.json.JsonPrimitive(it[1]))
                    }
                )
            }
        }
        // Kotlin 基准：构造 RoadData 集合（RoadTiling.bitmaskAt 公开入口）
        val roadData = roads.map { (x, y) ->
            com.xianxia.sect.core.model.RoadData(gridX = x, gridY = y)
        }
        for ((x, y) in listOf(3 to 3, 3 to 2, 5 to 5, 0 to 0, 3 to 1)) {
            val op = buildJsonObject {
                put("op", "bitmaskAt")
                put("roads", roadsJson)
                put("x", x); put("y", y)
                put("width", 10); put("height", 10)
            }
            val cpp = cppOp(op)
            val expected = RoadTiling.bitmaskAt(roadData, x, y, 10, 10)
            assertEquals(
                "($x,$y)",
                expected,
                cpp["value"]!!.jsonPrimitive.content.toInt()
            )
        }
    }
}
