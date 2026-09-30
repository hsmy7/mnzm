package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.state.StackKey
import com.xianxia.sect.core.state.StackableItemStore
import com.xianxia.sect.core.util.AppError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffInventoryTest — 库存系统跨语言差分对拍。
 *
 * 守护目标：C++ StackableItemStore/InventorySystem 的合并/分块/溢出/移除语义
 * 与 Kotlin 真实 StackableItemStore 一致。
 *
 * Kotlin 基准：**真实** StackableItemStore（与生产代码同一实现，非复刻）。
 * B3 装备重构：装备堆叠差分用例随堆叠轨退役删除（装备走实例轨，
 * 无合并/分块语义）；堆叠类对拍保留丹药族。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffInventoryTest {

    private val json = Json { encodeDefaults = true }

    private fun notFound(id: String) = AppError.Domain.Inventory.NotFound(id)

    // ── Kotlin 基准（真实 StackableItemStore） ────────────────────────

    private fun kotlinAddPill(initial: List<Pill>, item: Pill): List<Pill> {
        val store = StackableItemStore(
            initialItems = initial,
            stackKeyOf = { StackKey.of(it.name, it.rarity, it.category.name, it.grade?.name ?: "NONE") },
            maxStack = 999,
            maxSlots = { 50 },
            notFound = ::notFound
        )
        store.add(item)
        return store.all()
    }

    // ── 操作 JSON 构造 ────────────────────────────────────────────────

    private fun addPillOp(
        id: String, name: String, rarity: Int = 1, category: String = "CULTIVATION",
        grade: String = "MEDIUM", quantity: Int,
    ) = buildJsonObject {
        put("op", "invAddPill"); put("id", id); put("name", name)
        put("rarity", rarity); put("category", category); put("grade", grade)
        put("quantity", quantity); put("source", "alchemy"); put("suppressed", false)
    }

    // ── 对拍执行 ─────────────────────────────────────────────────────

    private fun execCppOps(initial: NativeGameState, ops: List<JsonObject>): NativeGameState {
        val encoded = json.encodeToString(NativeGameState.serializer(), initial)
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        val resultJson = DiffRngBridge.nativeCoreExecOps(
            json.encodeToString(JsonArray.serializer(), buildJsonArray { ops.forEach { add(it) } })
                .encodeToByteArray()
        ).decodeToString()
        assertTrue("C++ 执行出错: $resultJson", !resultJson.contains("\"error\""))
        return json.decodeFromString(
            NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
        )
    }

    // ── 测试用例 ─────────────────────────────────────────────────────

    @Test
    fun `pill grade distinct keys match Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val low = Pill(
            id = "pill-1", name = "聚气丹", rarity = 1,
            category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 10
        )
        val high = Pill(
            id = "pill-2", name = "聚气丹", rarity = 1,
            category = PillCategory.CULTIVATION, grade = PillGrade.HIGH, quantity = 5
        )
        val kotlinResult = kotlinAddPill(kotlinAddPill(emptyList(), low), high)
        val cppResult = execCppOps(
            NativeGameState(pills = emptyList(), gameData = GameData()),
            listOf(
                addPillOp("pill-1", "聚气丹", grade = "LOW", quantity = 10),
                addPillOp("pill-2", "聚气丹", grade = "HIGH", quantity = 5)
            )
        )
        // 品阶不同 → 不合并 → 2 个堆叠
        assertEquals(2, kotlinResult.size)
        assertEquals(2, cppResult.pills.size)
        // 年度丹药来源追踪（按 grade 名；annualPillBySource 值为 Int）
        assertEquals(10, cppResult.gameData.annualPillBySource["alchemy:LOW"])
        assertEquals(5, cppResult.gameData.annualPillBySource["alchemy:HIGH"])
    }

}
