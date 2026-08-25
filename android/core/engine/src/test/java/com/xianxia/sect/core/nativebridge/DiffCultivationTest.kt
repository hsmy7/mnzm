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
 * DiffCultivationTest — 修炼推进跨语言差分对拍（批次 5b 验收核心）。
 *
 * 守护目标：C++ gamecore::system::cultivation（maxCultivation/每旬累积/检查点投影/
 * 绝对月份）与 Kotlin CultivationService.computeMaxCultivation +
 * DiscipleTables.getEffectiveCultivation 公式**逐位一致**。
 *
 * Kotlin 基准：真实生产实现（computeMaxCultivation 复刻 DiscipleCore.maxCultivation
 * 公式；投影公式 = checkpoint + rate × Δmonth × 3）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffCultivationTest {

    private val json = Json { encodeDefaults = true }

    private fun cppOp(op: JsonObject): Double {
        val result = DiffRngBridge.nativeCoreCultivationOp(
            json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
        return (json.parseToJsonElement(result) as JsonObject)["value"]!!.toString().toDouble()
    }

    // ── Kotlin 基准 ─────────────────────────────────────────────────

    /** 复刻 CultivationService.computeMaxCultivation（与 DiscipleCore.maxCultivation 一致） */
    private fun kotlinMaxCultivation(realm: Int, realmLayer: Int, cultivation: Double): Double {
        if (realm == 0) return cultivation
        val base = com.xianxia.sect.core.GameConfig.Realm.get(realm).cultivationBase
        val nextBase = com.xianxia.sect.core.GameConfig.Realm.get(realm - 1).cultivationBase
        val maxLayers = com.xianxia.sect.core.GameConfig.Realm.get(realm).maxLayers
        return base + (realmLayer - 1) * (nextBase - base).toDouble() / maxLayers
    }

    // ── 测试用例 ─────────────────────────────────────────────────────

    @Test
    fun `max cultivation matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (realm in 1..9) {
            for (layer in listOf(1, 3, 5, 9)) {
                val op = buildJsonObject {
                    put("op", "maxCultivation"); put("realm", realm)
                    put("realmLayer", layer); put("cultivation", 0.0)
                }
                assertEquals(
                    kotlinMaxCultivation(realm, layer, 0.0),
                    cppOp(op), 1e-12
                )
            }
        }
    }

    @Test
    fun `max cultivation immortal returns current`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = buildJsonObject {
            put("op", "maxCultivation"); put("realm", 0); put("realmLayer", 1)
            put("cultivation", 12345.0)
        }
        assertEquals(12345.0, cppOp(op), 1e-12)
    }

    @Test
    fun `accumulate clamps at cap matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        // 90 + 19 = 109 > 炼气 1 层上限 98 → 98
        val op = buildJsonObject {
            put("op", "accumulate"); put("realm", 9); put("realmLayer", 1)
            put("cultivation", 90.0); put("rate", 19.0); put("alive", true)
        }
        assertEquals(98.0, cppOp(op), 1e-12)
    }

    @Test
    fun `accumulate below cap adds rate matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = buildJsonObject {
            put("op", "accumulate"); put("realm", 9); put("realmLayer", 1)
            put("cultivation", 50.0); put("rate", 19.0); put("alive", true)
        }
        assertEquals(69.0, cppOp(op), 1e-12)
    }

    @Test
    fun `accumulate dead disciple no gain matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = buildJsonObject {
            put("op", "accumulate"); put("realm", 9); put("realmLayer", 1)
            put("cultivation", 50.0); put("rate", 19.0); put("alive", false)
        }
        assertEquals(50.0, cppOp(op), 1e-12)
    }

    @Test
    fun `projection matches Kotlin formula`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for (deltaMonth in listOf(0, 1, 2, 10)) {
            val op = buildJsonObject {
                put("op", "projection"); put("cultivation", 500.0)
                put("checkpoint", 500.0); put("checkpointMonth", 100)
                put("currentMonth", 100 + deltaMonth); put("rate", 10.0)
                put("hasCheckpoint", true)
            }
            val expected = if (deltaMonth <= 0) 500.0 else 500.0 + 10.0 * deltaMonth * 3.0
            assertEquals(expected, cppOp(op), 1e-12)
        }
    }

    @Test
    fun `projection no checkpoint falls back matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = buildJsonObject {
            put("op", "projection"); put("cultivation", 800.0)
            put("checkpoint", 0.0); put("checkpointMonth", 0)
            put("currentMonth", 150); put("rate", 10.0)
            put("hasCheckpoint", false)
        }
        assertEquals(800.0, cppOp(op), 1e-12)
    }

    @Test
    fun `projection zero rate returns checkpoint matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val op = buildJsonObject {
            put("op", "projection"); put("cultivation", 500.0)
            put("checkpoint", 500.0); put("checkpointMonth", 100)
            put("currentMonth", 150); put("rate", 0.0)
            put("hasCheckpoint", true)
        }
        assertEquals(500.0, cppOp(op), 1e-12)
    }

    @Test
    fun `absolute month matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for ((y, m) in listOf(1 to 1, 1 to 12, 2 to 1, 5 to 7)) {
            val op = buildJsonObject {
                put("op", "absoluteMonth"); put("year", y); put("month", m)
            }
            assertEquals((y * 12 + m).toDouble(), cppOp(op), 1e-12)
        }
    }
}
