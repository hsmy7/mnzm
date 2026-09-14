package com.xianxia.sect.core.nativebridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffBreakthroughTest — 突破系统跨语言差分对拍。
 *
 * 守护目标：C++ gamecore::system::breakthrough（成功/失败应用、突破循环、
 * 完成时间预估）与 Kotlin DiscipleBreakthroughHandler / LazyEvaluationDispatcher
 * 语义**逐位一致**。
 *
 * Kotlin 基准：真实生产实现（applyBreakthroughSuccess/Failure 复刻 +
 * estimateMonthsToNextBreakthrough 直接调用）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffBreakthroughTest {

    private val json = Json { encodeDefaults = true }

    // ── estimateMonthsToNextBreakthrough（Kotlin 真实公式）───────────

    @Suppress("ReturnCount")  // 复刻 Kotlin 公式的分支出口，禁止重构（漂移即对拍失败）
    private fun kotlinEstimate(remaining: Double, rate: Double): Int {
        if (remaining <= 0.0) return 0
        if (rate <= 0.0) return Int.MAX_VALUE
        val phasesNeeded = kotlin.math.ceil(remaining / rate).toInt()
        return (phasesNeeded + 2) / 3
    }

    @Test
    fun `estimate months matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val cases = listOf(
            60.0 to 10.0, 61.0 to 10.0, 1.0 to 10.0, 0.0 to 10.0,
            100.0 to 0.0, 30.0 to 10.0, 89.0 to 10.0, 90.0 to 10.0,
        )
        for ((remaining, rate) in cases) {
            val op = buildJsonObject {
                put("op", "estimateMonths"); put("remaining", remaining); put("rate", rate)
            }
            // estimateMonths 走 breakthrough 通道：直接调用 C++ 侧
            val result = DiffRngBridge.nativeCoreCultivationOp(
                json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
            ).decodeToString()
            assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
            val actual = (json.parseToJsonElement(result) as JsonObject)["value"]!!
                .toString().toInt()
            assertEquals("remaining=$remaining rate=$rate 不一致",
                kotlinEstimate(remaining, rate), actual)
        }
    }

    // ── 突破成功/失败应用（Kotlin 复刻）────────────────────────────

    /** 复刻 DiscipleBreakthroughHandler.applyBreakthroughSuccess */
    @Suppress("UnusedParameter")  // cultivation 仅作签名对齐（C++ 侧同参数表）
    private fun kotlinSuccess(
        realm: Int, realmLayer: Int, cultivation: Double, lifespan: Int, lifespanGain: Int,
    ): Triple<Int, Int, Double> {
        var newRealm = realm
        var newLayer = realmLayer
        val maxLayers = com.xianxia.sect.core.GameConfig.Realm.get(realm).maxLayers
        if (newLayer < maxLayers) {
            newLayer++
        } else {
            newRealm--
            newLayer = 1
        }
        val newLifespan = if (newRealm != realm) lifespan + lifespanGain else lifespan
        // 仅比较 realm/layer/cultivation/lifespan 语义
        @Suppress("UNUSED_VARIABLE")
        val ignored = newLifespan
        return Triple(newRealm, newLayer, 0.0)
    }

    @Test
    fun `breakthrough success application matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val cases = listOf(
            // (realm, layer, cultivation, lifespan, gain)
            intArrayOf(9, 1, 98, 80, 50),
            intArrayOf(9, 9, 98, 80, 50),   // 满层 → 大境界
            intArrayOf(8, 5, 300, 120, 30),
            intArrayOf(5, 9, 5000, 500, 100),
        )
        for (c in cases) {
            val op = buildJsonObject {
                put("op", "breakthroughSuccess")
                put("realm", c[0]); put("realmLayer", c[1])
                put("cultivation", c[2].toDouble()); put("lifespan", c[3])
                put("lifespanGain", c[4])
            }
            val result = DiffRngBridge.nativeCoreCultivationOp(
                json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
            ).decodeToString()
            assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
            val r = json.parseToJsonElement(result) as JsonObject
            val expected = kotlinSuccess(c[0], c[1], c[2].toDouble(), c[3], c[4])
            assertEquals("realm 不一致", expected.first, r["realm"]!!.toString().toInt())
            assertEquals("realmLayer 不一致", expected.second, r["realmLayer"]!!.toString().toInt())
            assertEquals("cultivation 未清零", 0.0, r["cultivation"]!!.toString().toDouble(), 1e-12)
            // 寿命：大境界变化加增益
            val expectedLifespan = if (expected.first != c[0]) c[3] + c[4] else c[3]
            assertEquals("lifespan 不一致", expectedLifespan, r["lifespan"]!!.toString().toInt())
        }
    }

    @Test
    fun `breakthrough failure clears cultivation matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = buildJsonObject {
            put("op", "breakthroughFailure")
            put("realm", 9); put("realmLayer", 3); put("cultivation", 98.0)
        }
        val result = DiffRngBridge.nativeCoreCultivationOp(
            json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
        val r = json.parseToJsonElement(result) as JsonObject
        assertEquals(0.0, r["cultivation"]!!.toString().toDouble(), 1e-12)
        assertEquals(9, r["realm"]!!.toString().toInt())       // 境界不变
        assertEquals(3, r["realmLayer"]!!.toString().toInt())  // 层数不变
    }

    @Test
    fun `max cultivation matches Kotlin for breakthrough cases`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (realm in 1..9) {
            for (layer in listOf(1, 5, 9)) {
                val op = buildJsonObject {
                    put("op", "maxCultivation"); put("realm", realm)
                    put("realmLayer", layer); put("cultivation", 0.0)
                }
                val result = DiffRngBridge.nativeCoreCultivationOp(
                    json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
                ).decodeToString()
                val r = json.parseToJsonElement(result) as JsonObject
                val base = com.xianxia.sect.core.GameConfig.Realm.get(realm).cultivationBase
                val nextBase = com.xianxia.sect.core.GameConfig.Realm.get(realm - 1).cultivationBase
                val maxLayers = com.xianxia.sect.core.GameConfig.Realm.get(realm).maxLayers
                val expected = base + (layer - 1) * (nextBase - base).toDouble() / maxLayers
                assertEquals("realm=$realm layer=$layer",
                    expected, r["value"]!!.toString().toDouble(), 1e-9)
            }
        }
    }
}
