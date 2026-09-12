package com.xianxia.sect.core.nativebridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Diff 表面断言共享族（internal 共享）：
 * C++ 导出面 vs Kotlin 期望面的全量结构对拍——镜像生成字段豁免
 * （timestamp/生成 id/库存集合 id）+ 协议漂移 fail-fast（仅 C++ 持有键）。
 */

/** 库存集合路径锚点（镜像生成 id 排除用；集合内容本身参与 diff） */
internal val INVENTORY_COLLECTION_KEYS = setOf(
    "equipmentStacks", "equipmentInstances", "manualStacks",
    "manualInstances", "pills", "materials", "herbs", "seeds", "storageBags"
)

internal fun diffAssertCppSurfaceMatches(expected: JsonElement, actual: JsonElement) {
    diffAssertNodeMatches(expected, actual, "$")
}


internal fun diffAssertNodeMatches(expected: JsonElement, actual: JsonElement, path: String) {
    when {
        actual is JsonObject && expected is JsonObject ->
            diffCompareObjects(expected, actual, path)
        actual is JsonArray && expected is JsonArray ->
            diffCompareArrays(expected, actual, path)
        else -> diffAssertPrimitiveEquals(expected, actual, path)
    }
}

internal fun diffCompareObjects(
    expected: JsonObject,
    actual: JsonObject,
    path: String
) {
    for ((k, a) in actual) {
        if (diffIsMirrorGeneratedField(path, k)) continue
        val e = expected[k]
        assertTrue("$path.$k 仅 C++ 导出持有而 Kotlin 缺失（协议漂移）", e != null)
        diffAssertNodeMatches(e!!, a, "$path.$k")
    }
}

/**
 * diff 面排除的镜像生成/边界字段：
 * - timestamp：现实墙钟（Clock 注入边界）
 * - 任务 id：Mission.id 为镜像生成（C++ 确定性自增 vs Kotlin
 *   UUID，语义等价仅保证唯一）；任务内容（template/rewards/difficulty）
 *   双端一致参与对拍——MISSION(8) 分区双端消费对齐
 * - 库存集合 + 年度 by-source：InventorySystem 嵌套 update 写入
 *   （FakeGameStateStore 嵌套事务不回写外层 buffer——committed 读
 *   口径差家族），Kotlin-Fake 臂丢失，C++ 侧 GTest 黄金守护（603/603
 *   含入库/年度追踪断言）
 * - 秘境 AI 队伍 id：镜像生成（C++ 确定性自增 vs Kotlin UUID，语义等价
 *   仅保证唯一——inventory.h generateNewId 同款契约）
 */
internal fun diffIsMirrorGeneratedField(path: String, k: String): Boolean = when {
    k == "timestamp" -> true
    k == "id" && path.contains("availableMissions") -> true
    k == "id" && path.contains("worldLevels") -> true   // Kotlin UUID vs C++ 空串
    k == "id" && path.contains("recruitList") -> true   // 新生儿 Kotlin UUID vs C++ 空串
    k == "id" && diffIsMirrorIdPath(path) -> true
    else -> false
}

/**
 * 镜像生成 id 字段路径（C++ 确定性自增 vs Kotlin UUID，语义等价仅保证
 * 唯一——inventory.h generateNewId 同款契约）：
 * - 秘境 AI 队伍
 * - 库存集合（FakeGameStateStore 嵌套事务下库存内容已纳入
 *   diff 对拍面，仅 id 为镜像生成字段排除）
 */
internal fun diffIsMirrorIdPath(path: String): Boolean {
    if (path.contains("secretRealmAITeams")) return true
    return INVENTORY_COLLECTION_KEYS.any { path.contains(it) }
}

internal fun diffCompareArrays(
    expected: JsonArray,
    actual: JsonArray,
    path: String
) {
    assertEquals("$path size", expected.size, actual.size)
    actual.forEachIndexed { i, a ->
        diffAssertNodeMatches(expected[i], a, "$path[$i]")
    }
}

/** 数字统一 IEEE754 double 位比较（C++ 导出整值 double 规范化为整数形式） */
internal fun diffAssertPrimitiveEquals(
    expected: JsonElement,
    actual: JsonElement,
    path: String
) {
    require(actual is JsonPrimitive && expected is JsonPrimitive) {
        "$path 结构不匹配：期望=$expected 实际=$actual"
    }
    assertTrue("$path 期望=$expected 实际=$actual", primitivesEqual(expected, actual))
}

internal fun primitivesEqual(expected: JsonPrimitive, actual: JsonPrimitive): Boolean =
    when {
        actual === JsonNull || expected === JsonNull -> actual == expected
        actual.booleanOrNull != null || expected.booleanOrNull != null ->
            actual.booleanOrNull == expected.booleanOrNull
        else -> numericOrStringEquals(expected, actual)
    }

internal fun numericOrStringEquals(
    expected: JsonPrimitive,
    actual: JsonPrimitive
): Boolean {
    val eD = expected.doubleOrNull
    val aD = actual.doubleOrNull
    return if (eD != null && aD != null) {
        java.lang.Double.doubleToLongBits(eD) ==
            java.lang.Double.doubleToLongBits(aD)
    } else {
        actual.content == expected.content
    }
}
