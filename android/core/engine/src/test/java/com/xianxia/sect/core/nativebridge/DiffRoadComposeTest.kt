package com.xianxia.sect.core.nativebridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffRoadComposeTest — 道路渲染合成器跨语言差分对拍（计划 v2 阶段 6）。
 *
 * 守护目标：C++ gamecore::map::road_compositor（单一权威）经桌面对拍桥
 * `compose` op 产出的操作序列与**手算规格**一致——主体→描边条→转角件→
 * 十字中心的顺序契约 + 格内局部整型几何（tileSize=32 运行时不变量）。
 * 逐格合成逻辑已从 Kotlin 侧移除（SoftwareCanvasBackend 仅消费本通道），
 * 本测试是对拍桥通道 + 合成器语义的规格级守护（全掩码系统覆盖在
 * GTest road_compositor_test）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffRoadComposeTest {

    private val json = Json { encodeDefaults = true }

    /** C++ RoadSprite 枚举序（= ROAD_RECTS 声明序 = SPRITE_KEYS 下标） */
    private val spriteKeys = arrayOf(
        "road_base", "road_base_v", "road_junction",
        "road_edge_h", "road_edge_v",
        "road_corner_tr", "road_corner_tl", "road_corner_br", "road_corner_bl",
        "road_cross_center"
    )

    private fun cppCompose(mask: Int, tileSize: Int = 32): List<List<Int>> {
        val op = buildJsonObject { put("op", "compose"); put("mask", mask); put("tileSize", tileSize) }
        val result = DiffRngBridge.nativeRoadOp(
            json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
        val obj = json.parseToJsonElement(result) as JsonObject
        return obj["ops"]!!.jsonArray.map { e ->
            e.jsonArray.map { it.jsonPrimitive.content.toInt() }
        }
    }

    private fun List<List<Int>>.assertOp(
        index: Int,
        sprite: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        label: String
    ) {
        assertEquals("$label #${index} sprite", listOf(sprite, x, y, w, h), this[index])
    }

    @Test
    fun `孤格 - 主体加四边描边加四角共9操作`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val ops = cppCompose(0)
        assertEquals(9, ops.size)
        ops.assertOp(0, sprite("road_junction"), 0, 0, 32, 32, "主体")
        ops.assertOp(1, sprite("road_edge_h"), 0, 0, 32, 8, "上描边")
        ops.assertOp(2, sprite("road_edge_h"), 0, 24, 32, 8, "下描边")
        ops.assertOp(3, sprite("road_edge_v"), 0, 0, 8, 32, "左描边")
        ops.assertOp(4, sprite("road_edge_v"), 24, 0, 8, 32, "右描边")
        ops.assertOp(5, sprite("road_corner_tl"), 0, 0, 8, 8, "左上角")
        ops.assertOp(6, sprite("road_corner_tr"), 24, 0, 8, 8, "右上角")
        ops.assertOp(7, sprite("road_corner_bl"), 0, 24, 8, 8, "左下角")
        ops.assertOp(8, sprite("road_corner_br"), 24, 24, 8, 8, "右下角")
    }

    @Test
    fun `垂直直路 - 主体加左右描边共3操作`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val ops = cppCompose(0b0101)  // 上+下
        assertEquals(3, ops.size)
        ops.assertOp(0, sprite("road_base_v"), 0, 0, 32, 32, "主体")
        ops.assertOp(1, sprite("road_edge_v"), 0, 0, 8, 32, "左描边")
        ops.assertOp(2, sprite("road_edge_v"), 24, 0, 8, 32, "右描边")
    }

    @Test
    fun `死路端点 - 按方向归直路主体`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val ops = cppCompose(0b0001)  // 仅上邻居
        assertEquals(6, ops.size)
        ops.assertOp(0, sprite("road_base_v"), 0, 0, 32, 32, "主体(纵向)")
        ops.assertOp(1, sprite("road_edge_h"), 0, 24, 32, 8, "下描边")
        ops.assertOp(2, sprite("road_edge_v"), 0, 0, 8, 32, "左描边")
        ops.assertOp(3, sprite("road_edge_v"), 24, 0, 8, 32, "右描边")
        ops.assertOp(4, sprite("road_corner_bl"), 0, 24, 8, 8, "左下角")
        ops.assertOp(5, sprite("road_corner_br"), 24, 24, 8, 8, "右下角")
    }

    @Test
    fun `十字 - 主体加十字中心装饰外溢半格`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val ops = cppCompose(0b1111)
        assertEquals(2, ops.size)
        ops.assertOp(0, sprite("road_junction"), 0, 0, 32, 32, "主体")
        ops.assertOp(1, sprite("road_cross_center"), -16, -16, 64, 64, "十字中心")
    }

    @Test
    fun `全16掩码 - 结构不变量（首操作为主体+步长5+几何有界）`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (mask in 0..15) {
            val ops = cppCompose(mask)
            assertTrue("mask=$mask 至少主体 1 op", ops.size >= 1)
            assertTrue("mask=$mask 上限 10 op", ops.size <= 10)
            // 首操作恒为主体（铺满整格）
            assertEquals("mask=$mask 主体铺满", listOf(ops[0][0], 0, 0, 32, 32), ops[0])
            assertTrue("mask=$mask 主体是 base/base_v/junction 之一", ops[0][0] in 0..2)
            for (op in ops) {
                assertEquals("mask=$mask 每条记录 5 元素", 5, op.size)
                assertEquals("mask=$mask sprite 枚举界内", true, op[0] in spriteKeys.indices)
                assertTrue("mask=$mask w>0", op[3] > 0)
                assertTrue("mask=$mask h>0", op[4] > 0)
                assertTrue("mask=$mask x 有界", op[1] in -16..24)
                assertTrue("mask=$mask y 有界", op[2] in -16..24)
            }
        }
    }

    private fun sprite(key: String): Int = spriteKeys.indexOf(key)
}
