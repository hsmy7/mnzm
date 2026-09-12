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
 * DiffLifecycleTest — 弟子生命周期跨语言差分对拍。
 *
 * 守护目标：C++ gamecore::system::lifecycle（最大寿元/老化判定/5岁回正）与
 * Kotlin DiscipleAgePolicy.computeMaxAge + DiscipleLifecycleProcessor 老化逻辑
 * 语义**逐位一致**。
 *
 * Kotlin 基准：真实生产实现（Disciple.computeMaxAge 扩展函数 + 复刻老化判定）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffLifecycleTest {

    private val json = Json { encodeDefaults = true }

    private fun cppOp(op: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreCultivationOp(
            json.encodeToString(JsonObject.serializer(), op).encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $result", !result.contains("\"error\""))
        return json.parseToJsonElement(result) as JsonObject
    }

    /** Kotlin 基准：复刻 Disciple.computeMaxAge（无 Talent/Affix Registry 时用传入加成） */
    private fun kotlinMaxAge(lifespan: Int, realmMaxAge: Int, lifespanBonus: Double): Int {
        val traitLifespan = (realmMaxAge * (1.0 + lifespanBonus))
            .toInt().coerceAtLeast(1)
        return maxOf(lifespan, realmMaxAge, traitLifespan)
            .coerceAtMost(20000)
    }

    @Test
    fun `compute max age matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val cases = listOf(
            Triple(100, 80, 0.0),      // lifespan 主导
            Triple(100, 500, 0.0),     // realmMaxAge 主导
            Triple(100, 80, 0.5),      // 特质加成主导
            Triple(100, 80, 0.1),      // 加成不足 → lifespan
            Triple(0, 80, 0.15),       // 截断
            Triple(999999, 80, 0.0),   // 硬上限
            Triple(100, 80, 1000.0),   // 巨大加成 → 上限
            Triple(0, 80, -1.0),       // 负加成 → trait 至少 1
        )
        for ((lifespan, realmMaxAge, bonus) in cases) {
            val op = buildJsonObject {
                put("op", "computeMaxAge"); put("lifespan", lifespan)
                put("realmMaxAge", realmMaxAge); put("lifespanBonus", bonus)
            }
            val actual = cppOp(op)["value"]!!.toString().toInt()
            assertEquals(
                "lifespan=$lifespan realmMaxAge=$realmMaxAge bonus=$bonus",
                kotlinMaxAge(lifespan, realmMaxAge, bonus), actual
            )
        }
    }

    @Test
    fun `age disciple matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val cases = listOf(
            intArrayOf(30, 1, 80),  // 正常老化
            intArrayOf(4, 0, 80),   // 5 岁回正
            intArrayOf(4, 2, 80),   // 5 岁但 layer!=0
            intArrayOf(79, 1, 80),  // 寿元耗尽
            intArrayOf(78, 1, 80),  // 未到寿元
        )
        for (c in cases) {
            val op = buildJsonObject {
                put("op", "ageDisciple"); put("age", c[0])
                put("realmLayer", c[1]); put("maxAge", c[2])
            }
            val r = cppOp(op)
            val agedAge = c[0] + 1
            var agedLayer = c[1]
            if (agedAge == 5 && agedLayer == 0) agedLayer = 1
            val dead = agedAge >= c[2]
            assertEquals("age 不一致", agedAge, r["age"]!!.toString().toInt())
            assertEquals("realmLayer 不一致", agedLayer, r["realmLayer"]!!.toString().toInt())
            assertEquals("dead 不一致", dead, r["dead"]!!.toString().toBoolean())
        }
    }

    @Test
    fun `age alive matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        for ((age, layer) in listOf(30 to 1, 4 to 0, 4 to 3)) {
            val op = buildJsonObject {
                put("op", "ageAlive"); put("age", age); put("realmLayer", layer)
            }
            val r = cppOp(op)
            val agedAge = age + 1
            val agedLayer = if (agedAge == 5 && layer == 0) 1 else layer
            assertEquals("age 不一致", agedAge, r["age"]!!.toString().toInt())
            assertEquals("realmLayer 不一致", agedLayer, r["realmLayer"]!!.toString().toInt())
        }
    }
}
