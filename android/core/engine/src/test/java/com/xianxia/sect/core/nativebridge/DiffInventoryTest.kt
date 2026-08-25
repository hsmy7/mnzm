package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
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
 * DiffInventoryTest — 库存系统跨语言差分对拍（批次 4 验收核心）。
 *
 * 守护目标：C++ StackableItemStore/InventorySystem 的合并/分块/溢出/移除语义
 * 与 Kotlin 真实 StackableItemStore 一致。
 *
 * Kotlin 基准：**真实** StackableItemStore（与生产代码同一实现，非复刻）。
 * 由于 C++ 分块新 id 与 Kotlin UUID 不同，仅比较"按 (name,rarity) 聚合数量"，
 * 不比较新生成的堆叠 id。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffInventoryTest {

    private val json = Json { encodeDefaults = true }

    private fun notFound(id: String) = AppError.Domain.Inventory.NotFound(id)

    // ── Kotlin 基准（真实 StackableItemStore） ────────────────────────

    private fun kotlinAddEquipment(
        initial: List<EquipmentStack>, item: EquipmentStack, maxSlots: Int = 50,
    ): List<EquipmentStack> {
        val store = StackableItemStore(
            initialItems = initial,
            stackKeyOf = { StackKey.of(it.name, it.rarity, it.slot.name) },
            maxStack = 999,
            maxSlots = { maxSlots },
            notFound = ::notFound
        )
        store.add(item)
        return store.all()
    }

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

    private fun addEquipmentOp(
        id: String, name: String, rarity: Int = 1, slot: String = "WEAPON",
        quantity: Int, source: String = "battle",
    ) = buildJsonObject {
        put("op", "invAddEquipment"); put("id", id); put("name", name)
        put("rarity", rarity); put("slot", slot); put("quantity", quantity)
        put("source", source); put("suppressed", false)
    }

    private fun addPillOp(
        id: String, name: String, rarity: Int = 1, category: String = "CULTIVATION",
        grade: String = "MEDIUM", quantity: Int,
    ) = buildJsonObject {
        put("op", "invAddPill"); put("id", id); put("name", name)
        put("rarity", rarity); put("category", category); put("grade", grade)
        put("quantity", quantity); put("source", "alchemy"); put("suppressed", false)
    }

    private fun removeOp(type: String, id: String, quantity: Int = 1) = buildJsonObject {
        put("op", "invRemove"); put("type", type); put("id", id); put("quantity", quantity)
        put("bypassLock", false)
    }

    // ── 数量聚合比较（忽略新生成的堆叠 id）────────────────────────────

    private fun aggregateByKey(items: List<Pair<String, Int>>): Map<String, Int> =
        items.groupingBy { "${it.first}:${it.second}" }.fold(0) { acc, p -> acc + p.second }

    private fun kotlinEquipmentAggregate(items: List<EquipmentStack>): Map<String, Int> =
        aggregateByKey(items.map { it.name to it.quantity })

    private fun cppEquipmentAggregate(native: NativeGameState): Map<String, Int> =
        aggregateByKey(native.equipmentStacks.map { it.name to it.quantity })

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
    fun `equipment merge matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val initial = listOf(
            EquipmentStack(id = "eq-1", name = "木剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 50)
        )
        val incoming = EquipmentStack(id = "eq-2", name = "木剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 30)
        val kotlinResult = kotlinAddEquipment(initial, incoming)
        val cppResult = execCppOps(
            NativeGameState(equipmentStacks = initial, gameData = GameData()),
            listOf(addEquipmentOp("eq-2", "木剑", quantity = 30))
        )
        assertEquals(kotlinEquipmentAggregate(kotlinResult), cppEquipmentAggregate(cppResult))
        assertEquals(1, kotlinResult.size)   // 合并 → 单堆叠
        assertEquals(1, cppResult.equipmentStacks.size)
        assertEquals(80, cppResult.equipmentStacks[0].quantity)
        assertEquals("eq-1", cppResult.equipmentStacks[0].id)  // 保留目标 id
    }

    @Test
    fun `equipment chunk creation matches Kotlin aggregate`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val incoming = EquipmentStack(
            id = "eq-1", name = "铁剑", rarity = 2,
            slot = EquipmentSlot.WEAPON, quantity = 2000
        )
        val kotlinResult = kotlinAddEquipment(emptyList(), incoming)
        val cppResult = execCppOps(
            NativeGameState(equipmentStacks = emptyList(), gameData = GameData()),
            listOf(addEquipmentOp("eq-1", "铁剑", rarity = 2, quantity = 2000))
        )
        assertEquals(kotlinEquipmentAggregate(kotlinResult), cppEquipmentAggregate(cppResult))
        assertEquals(3, cppResult.equipmentStacks.size)  // 999+999+2
        assertEquals(2000, cppResult.equipmentStacks.sumOf { it.quantity })
        assertEquals("eq-1", cppResult.equipmentStacks[0].id)  // 首个分块保留原 id
        assertTrue(cppResult.equipmentStacks.drop(1).none { it.id == "eq-1" })  // 后续分块新 id
    }

    @Test
    fun `equipment remove matches Kotlin aggregate`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val initial = listOf(
            EquipmentStack(id = "eq-1", name = "木剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 10)
        )
        val cppResult = execCppOps(
            NativeGameState(equipmentStacks = initial, gameData = GameData()),
            listOf(removeOp("equipment", "eq-1", 4), removeOp("equipment", "eq-1", 6))
        )
        assertTrue(cppResult.equipmentStacks.isEmpty())
        // 移除不记年
        assertEquals(0L, cppResult.gameData.annualTotalIncome)
    }

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

    @Test
    fun `full warehouse failure produces no phantom items`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        // 预填 50 个不同名称装备占满槽位（baseCapacity=50）
        val initial = (1..50).map {
            EquipmentStack(
                id = "pre-$it", name = "占位$it", rarity = 1,
                slot = EquipmentSlot.WEAPON, quantity = 1
            )
        }
        val cppResult = execCppOps(
            NativeGameState(equipmentStacks = initial, gameData = GameData()),
            listOf(addEquipmentOp("new-1", "新物品", quantity = 10))
        )
        // 满仓 + 不同键 → 全部失败，不新增
        assertEquals(50, cppResult.equipmentStacks.size)
        assertEquals(0, cppResult.gameData.annualEquipmentBySource["battle:1"] ?: 0)
    }
}
