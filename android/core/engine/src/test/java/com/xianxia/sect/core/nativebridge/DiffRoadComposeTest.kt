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
 * DiffRoadComposeTest — 道路渲染合成器跨语言差分对拍。
 *
 * 守护目标：C++ gamecore::map::road_compositor（单一权威）经桌面对拍桥
 * `compose` op 产出的操作序列与**手算规格**一致——主体恒为 road_body、
 * 直路按方向出侧边缘（每条 2 条拼接，1/6×1/2 格）、T 中心仅缺邻居侧 1 面、
 * 转角两开放侧且完全在格内、孤格左右轴、十字中心无边缘。
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
        "road_body", "road_edge_v", "road_edge_h"
    )

    private fun cppCompose(mask: Int, tileSize: Int = 36): List<List<Int>> {
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
        label: String,
        flip: Int = 0
    ) {
        // 2.4：操作记录扩为 6 元素（尾部 flip = flipU 水平镜像标志）——
        // 左/上缘条 flip=1（深色描边边朝外），右/下缘与交汇块/主体 flip=0
        assertEquals("$label #${index} sprite", listOf(sprite, x, y, w, h, flip), this[index])
    }

    @Test
    fun `孤格 - 主体加左右轴共5操作`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val ops = cppCompose(0)
        assertEquals(5, ops.size)
        ops.assertOp(0, sprite("road_body"), 0, 0, 36, 36, "主体")
        ops.assertOp(1, sprite("road_edge_v"), 0, 0, 6, 18, "左缘上", flip = 1)
        ops.assertOp(2, sprite("road_edge_v"), 0, 18, 6, 18, "左缘下", flip = 1)
        ops.assertOp(3, sprite("road_edge_v"), 30, 0, 6, 18, "右缘上")
        ops.assertOp(4, sprite("road_edge_v"), 30, 18, 6, 18, "右缘下")
    }

    @Test
    fun `垂直直路 - 主体加左右边缘共5操作`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val ops = cppCompose(0b0101)  // 上+下
        assertEquals(5, ops.size)
        ops.assertOp(0, sprite("road_body"), 0, 0, 36, 36, "主体")
        ops.assertOp(1, sprite("road_edge_v"), 0, 0, 6, 18, "左缘上", flip = 1)
        ops.assertOp(2, sprite("road_edge_v"), 0, 18, 6, 18, "左缘下", flip = 1)
        ops.assertOp(3, sprite("road_edge_v"), 30, 0, 6, 18, "右缘上")
        ops.assertOp(4, sprite("road_edge_v"), 30, 18, 6, 18, "右缘下")
    }

    @Test
    fun `T 中心 - 缺邻居侧单面边缘加分支基座两内凹角交汇块`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val ops = cppCompose(0b1011)  // 上+左+右（缺下）
        assertEquals(5, ops.size)
        ops.assertOp(0, sprite("road_body"), 0, 0, 36, 36, "主体")
        ops.assertOp(1, sprite("road_edge_h"), 0, 30, 18, 6, "下缘左条")
        ops.assertOp(2, sprite("road_edge_h"), 18, 30, 18, 6, "下缘右条")
        ops.assertOp(3, sprite("road_edge_v"), 0, 0, 6, 6, "左上内凹角")
        ops.assertOp(4, sprite("road_edge_v"), 30, 0, 6, 6, "右上内凹角")
    }

    @Test
    fun `十字中心 - 主体加四内凹角交汇块`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val ops = cppCompose(0b1111)
        assertEquals(5, ops.size)
        ops.assertOp(0, sprite("road_body"), 0, 0, 36, 36, "主体")
        ops.assertOp(1, sprite("road_edge_v"), 0, 0, 6, 6, "左上内凹角")
        ops.assertOp(2, sprite("road_edge_v"), 30, 0, 6, 6, "右上内凹角")
        ops.assertOp(3, sprite("road_edge_v"), 0, 30, 6, 6, "左下内凹角")
        ops.assertOp(4, sprite("road_edge_v"), 30, 30, 6, 6, "右下内凹角")
    }

    @Test
    fun `转角 - 外缘两开放侧各2条加内凹角交汇块且完全在格内`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val ops = cppCompose(0b1001)  // 上+左（外缘=下+右，内凹角=左上）
        assertEquals(6, ops.size)
        ops.assertOp(0, sprite("road_body"), 0, 0, 36, 36, "主体")
        ops.assertOp(1, sprite("road_edge_h"), 0, 30, 18, 6, "下缘左条")
        ops.assertOp(2, sprite("road_edge_h"), 18, 30, 18, 6, "下缘右条")
        ops.assertOp(3, sprite("road_edge_v"), 30, 0, 6, 18, "右缘上")
        ops.assertOp(4, sprite("road_edge_v"), 30, 18, 6, 18, "右缘下(在格内)")
        ops.assertOp(5, sprite("road_edge_v"), 0, 0, 6, 6, "左上内凹角")
    }

    @Test
    fun `水平直路 - 上缘翻转下缘不翻转`() {
        // 2.4 flipU 规则差分对拍：上缘（无邻居侧）flip=1（深色边镜像朝外）、下缘 flip=0
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val ops = cppCompose(0b1010)  // 左+右
        assertEquals(5, ops.size)
        ops.assertOp(0, sprite("road_body"), 0, 0, 36, 36, "主体")
        ops.assertOp(1, sprite("road_edge_h"), 0, 0, 18, 6, "上缘左条", flip = 1)
        ops.assertOp(2, sprite("road_edge_h"), 18, 0, 18, 6, "上缘右条", flip = 1)
        ops.assertOp(3, sprite("road_edge_h"), 0, 30, 18, 6, "下缘左条")
        ops.assertOp(4, sprite("road_edge_h"), 18, 30, 18, 6, "下缘右条")
    }

    @Test
    fun `全16掩码 - 结构不变量（首操作为主体+步长6+几何有界+flip布尔域）`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (mask in 0..15) {
            val ops = cppCompose(mask)
            assertTrue("mask=$mask 至少主体 1 op", ops.size >= 1)
            assertTrue("mask=$mask 上限 6 op", ops.size <= 6)
            // 首操作恒为主体（铺满整格）
            assertEquals("mask=$mask 主体铺满", listOf(ops[0][0], 0, 0, 36, 36, 0), ops[0])
            assertTrue("mask=$mask 主体是 BODY", ops[0][0] == 0)
            for (op in ops) {
                assertEquals("mask=$mask 每条记录 6 元素", 6, op.size)
                assertEquals("mask=$mask sprite 枚举界内", true, op[0] in spriteKeys.indices)
                assertTrue("mask=$mask w>0", op[3] > 0)
                assertTrue("mask=$mask h>0", op[4] > 0)
                assertTrue("mask=$mask x 有界", op[1] in -18..54)
                assertTrue("mask=$mask y 有界", op[2] in -18..54)
                assertTrue("mask=$mask flip 布尔域", op[5] == 0 || op[5] == 1)
            }
        }
    }

    private fun sprite(key: String): Int = spriteKeys.indexOf(key)
}
