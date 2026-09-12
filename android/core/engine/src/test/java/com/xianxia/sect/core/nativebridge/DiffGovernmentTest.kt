package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.util.ZoneCalculator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffGovernmentTest — 内政系统跨语言差分对拍。
 *
 * 守护目标：C++ gamecore::system::government（乘区法/概率乘区/时间缩减加速/
 * 政策月度忠诚道德/灵矿产出）与 Kotlin ZoneCalculator 公式**逐位一致**。
 *
 * Kotlin 基准：真实 ZoneCalculator（生产代码同一实现）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffGovernmentTest {

    private val json = Json { encodeDefaults = true }

    private fun cppOp(op: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreGovernmentOp(
            json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
        return json.parseToJsonElement(result) as JsonObject
    }

    // ── 乘区法 ─────────────────────────────────────────────────────

    @Test
    fun `zone calculate matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val cases = listOf(
            Triple(100.0, listOf(0.2, 0.5, -0.1), 100.0 * 1.2 * 1.5 * 0.9),
            Triple(50.0, emptyList(), 50.0),
            Triple(10.0, listOf(-0.5, 0.3), 10.0 * 0.5 * 1.3),
        )
        for ((base, zones, expected) in cases) {
            val op = buildJsonObject {
                put("op", "zoneCalculate"); put("base", base)
                put("zones", buildJsonArray { zones.forEach { add(JsonPrimitive(it)) } })
            }
            val cpp = cppOp(op)
            assertEquals("base=$base zones=$zones",
                expected, cpp["value"]!!.toString().toDouble(), 1e-12)
            // Kotlin 真实实现
            assertEquals("base=$base zones=$zones",
                ZoneCalculator.calculate(base, *zones.toDoubleArray()),
                cpp["value"]!!.toString().toDouble(), 1e-12)
        }
    }

    @Test
    fun `probability matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (case in listOf(
            Triple(0.5, 0.1, 0.2), Triple(0.9, 0.5, 0.0),
            Triple(0.1, 0.0, 2.0), Triple(0.3, -0.1, 0.05),
        )) {
            val op = buildJsonObject {
                put("op", "calculateProbability")
                put("baseProb", case.first); put("positiveSum", case.second)
                put("penaltySum", case.third)
            }
            val cpp = cppOp(op)
            assertEquals(
                ZoneCalculator.calculateProbability(case.first, case.second, case.third),
                cpp["value"]!!.toString().toDouble(), 1e-12
            )
        }
    }

    @Test
    fun `reduced duration matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (case in listOf(
            Triple(100, listOf(0.2, 0.5), 40),
            Triple(10, listOf(0.99), 1),
            Triple(10, listOf(-0.5), 10),
            Triple(36, listOf(0.2), 29),
        )) {
            val op = buildJsonObject {
                put("op", "calculateReducedDuration"); put("baseDuration", case.first)
                put("reductions", buildJsonArray { case.second.forEach { add(JsonPrimitive(it)) } })
            }
            val cpp = cppOp(op)
            assertEquals(
                ZoneCalculator.calculateReducedDuration(case.first, *case.second.toDoubleArray()),
                cpp["value"]!!.toString().toInt()
            )
        }
    }

    @Test
    fun `accelerated time matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (case in listOf(
            Pair(100, listOf(0.2, 0.5)),
            Pair(10, emptyList()),
            Pair(36, listOf(0.2)),
            Pair(100, listOf(1.0)),
        )) {
            val op = buildJsonObject {
                put("op", "calculateAcceleratedTime"); put("baseTime", case.first)
                put("bonuses", buildJsonArray { case.second.forEach { add(JsonPrimitive(it)) } })
            }
            val cpp = cppOp(op)
            assertEquals(
                ZoneCalculator.calculateAcceleratedTime(case.first, *case.second.toDoubleArray()),
                cpp["value"]!!.toString().toInt()
            )
        }
    }

    // ── 政策月度忠诚/道德 ───────────────────────────────────────────

    @Test
    fun `policy monthly deltas match Kotlin config`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        // Kotlin：BENEVOLENT +1, RELAXED +2, STRICT -1, ENHANCED -1, CURFEW -1
        val op = buildJsonObject {
            put("op", "policyMonthlyDeltas")
            put("benevolentGovernance", true)
            put("relaxedMgmt", true)
            put("strictTraining", true)
            put("enhancedSecurity", true)
            put("curfew", true)
            put("moralEducation", true)
        }
        val cpp = cppOp(op)
        val expectedLoyalty = 1 + 2 - 1 - 1 - 1
        assertEquals(expectedLoyalty, cpp["loyaltyDelta"]!!.toString().toInt())
        assertEquals(1, cpp["moralityDelta"]!!.toString().toInt())
    }

    // ── 灵矿产出 ───────────────────────────────────────────────────

    @Test
    fun `spirit mine monthly matches Kotlin formula`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (case in listOf(
            Triple(3, 0.0, 0.0),  // 无加成
            Triple(3, 0.1, 0.0),  // 采矿加成
            Triple(2, 0.0, 0.2),  // 政策加成
        )) {
            val op = buildJsonObject {
                put("op", "spiritMineMonthly"); put("minerCount", case.first)
                put("avgMiningSkillBonus", case.second)
                put("deaconMoralityBonus", 0.0); put("policyBoost", case.third)
                put("basePerMiner", 170.0)
            }
            val cpp = cppOp(op)
            val base = case.first * 170.0
            val expected = (base * (1.0 + case.second) * (1.0 + case.third))
            assertEquals("minerCount=${case.first}",
                kotlin.math.round(expected), cpp["value"]!!.toString().toDouble(), 1e-9)
        }
    }
}
