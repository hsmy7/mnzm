package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.overflow.OverflowMailDraft
import com.xianxia.sect.core.overflow.OverflowMailHandler
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.state.testGameStateRepository
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 复现用户报告：仓库容量不足时获得物品 → 物品转溢出邮件 → 但仓库内已存在的相同物品消失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WarehouseFullExistingItemReproTest {

    private lateinit var system: InventorySystem
    private lateinit var stateStore: GameStateStore
    private lateinit var scopeProvider: ApplicationScopeProvider
    private lateinit var inventoryConfig: InventoryConfig
    private lateinit var handler: CollectHandler

    class CollectHandler : OverflowMailHandler {
        val drafts = mutableListOf<OverflowMailDraft>()
        override fun sendOverflowMails(drafts: List<OverflowMailDraft>) {
            this.drafts.addAll(drafts)
        }
    }

    @Before
    fun setUp() {
        scopeProvider = ApplicationScopeProvider()
        stateStore = GameStateStoreImpl(scopeProvider, testGameStateRepository())
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = true
        inventoryConfig = InventoryConfig()
        handler = CollectHandler()
        system = InventorySystem(
            stateStore, inventoryConfig,
            handler
        )
        system.initialize()
        runBlocking { stateStore.reset() }
    }

    @After
    fun tearDown() {
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = false
        runBlocking { stateStore.reset() }
        scopeProvider.close()
    }

    private fun pill(id: String, name: String, rarity: Int, quantity: Int) = Pill(
        id = id, name = name, rarity = rarity,
        category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = quantity
    )

    /** 填满仓库：1 个"回气丹"满堆叠 + capacity-1 个填充丹药 */
    private fun fillWarehouse(maxStack: Int, capacity: Int) {
        stateStore.update {
            system.addPill(pill("p1", "回气丹", 1, maxStack))
            for (i in 0 until capacity - 1) {
                system.addPill(pill("fill$i", "填充丹药$i", 1, 1))
            }
        }
    }

    @Test
    fun `obtain same pill when warehouse full - existing stack must survive`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        val capacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        fillWarehouse(maxStack, capacity)

        // 仓库满 + 回气丹堆叠满 → 新增回气丹走溢出邮件
        stateStore.update {
            val result = system.addPill(pill("pNew", "回气丹", 1, 10))
            assertTrue(result is DomainResult.Failure)
        }
        // 溢出邮件应为 10 个
        assertEquals(1, handler.drafts.size)
        assertEquals(10, handler.drafts[0].quantity)

        // 关键断言：仓库内已存在的回气丹必须仍然存在（数量不变）
        val existing = stateStore.pills.value.firstOrNull { it.name == "回气丹" }
        assertNotNull("仓库内已有回气丹不应消失", existing)
        assertEquals("已有回气丹数量不应变化", maxStack, existing!!.quantity)
    }

    @Test
    fun `obtain same pill when warehouse full with merge space - existing stack survives`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        val capacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        // 回气丹堆叠未满（留 5 空位），其余槽位填满
        stateStore.update {
            system.addPill(pill("p1", "回气丹", 1, maxStack - 5))
            for (i in 0 until capacity - 1) {
                system.addPill(pill("fill$i", "填充丹药$i", 1, 1))
            }
        }
        stateStore.update {
            val result = system.addPill(pill("pNew", "回气丹", 1, 10))
            assertTrue(result is DomainResult.Partial)
        }
        assertEquals("仅溢出 5 个", 5, handler.drafts[0].quantity)

        val existing = stateStore.pills.value.firstOrNull { it.name == "回气丹" }
        assertNotNull("已有回气丹不应消失", existing)
        assertEquals("合并后应为满堆叠", maxStack, existing!!.quantity)
    }

    @Test
    fun `large single-batch obtain creates duplicate-id stacks (root cause)`() = runBlocking {
        // 仓库容量 77，maxStack 999。预置 75 个堆叠（其中回气丹满栈），剩 2 空槽。
        val capacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        stateStore.update {
            system.addPill(pill("p1", "回气丹", 1, maxStack))
            for (i in 0 until capacity - 3) {
                system.addPill(pill("fill$i", "填充丹药$i", 1, 1))
            }
        }
        // 一次获得 2500 个回气丹：前 999 新建堆叠、再 999 新建堆叠（同 id！）、502 溢出转邮件
        stateStore.update {
            val result = system.addPill(pill("big", "回气丹", 1, 2500))
            assertTrue("应 Partial 溢出 502", result is com.xianxia.sect.core.util.DomainResult.Partial)
            assertEquals(502, (result as com.xianxia.sect.core.util.DomainResult.Partial).overflow)
        }
        val all = stateStore.pills.value
        val bigStacks = all.filter { it.id == "big" }
        assertEquals("不应产生同 id 的多个堆叠", 1, bigStacks.size)
        // 总入仓 1998 = 999(existing 已满) + 999 + 999；现有 77 槽：existing + fill*74 + 2 个新堆叠
        assertEquals("仓库应有 77 个堆叠", capacity, all.size)
        val totalHuiqi = all.filter { it.name == "回气丹" }.sumOf { it.quantity }
        assertEquals("回气丹总入仓应为 2997（999+999+999）", 2997, totalHuiqi)
        // 溢出邮件 502
        assertEquals(502, handler.drafts[0].quantity)
    }

    @Test
    fun `overflow mail carries exact template id for exact-item claim`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        val capacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        stateStore.update {
            // 聚气丹（下品）满栈 + 其余槽位满 → 新获得聚气丹全部转邮件
            system.addPill(Pill(
                id = "p1", name = "聚气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack
            ))
            for (i in 0 until capacity - 1) {
                system.addPill(pill("fill$i", "填充丹药$i", 1, 1))
            }
            val result = system.addPill(Pill(
                id = "pNew", name = "聚气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 10
            ))
            assertTrue(result is DomainResult.Failure)
        }
        assertEquals(10, handler.drafts[0].quantity)
        // 关键：溢出邮件必须携带精确模板 id（领取时不再随机生成其它丹药）
        assertEquals("breakthrough_9_low", handler.drafts[0].itemId)
    }
}
