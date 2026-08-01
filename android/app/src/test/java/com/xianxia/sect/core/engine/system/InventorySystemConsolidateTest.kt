package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock

/**
 * consolidateStacks 合并与 addXxx 边界回归测试。
 * 覆盖对抗性审查发现的：死循环（≥3 同键堆叠总数>maxStack）、
 * 超上限分块、零合并 Failure 语义、锁定堆叠策略、储物袋合并。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InventorySystemConsolidateTest {

    private lateinit var system: InventorySystem
    private lateinit var stateStore: GameStateStore
    private lateinit var scopeProvider: ApplicationScopeProvider
    private lateinit var inventoryConfig: InventoryConfig
    private lateinit var spiritStoneWallet: SpiritStoneWallet

    @Before
    fun setUp() {
        scopeProvider = ApplicationScopeProvider()
        stateStore = GameStateStoreImpl(scopeProvider, mock(GameStateRepository::class.java))
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = true
        inventoryConfig = InventoryConfig()
        spiritStoneWallet = SpiritStoneWallet(stateStore, SpiritStoneLedger(), mock(EventBus::class.java))
        system = InventorySystem(stateStore, inventoryConfig, spiritStoneWallet, mock(
            com.xianxia.sect.core.engine.config.GameConfigProvider::class.java))
        system.initialize()
        runBlocking { stateStore.reset() }
    }

    @After
    fun tearDown() {
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = false
        runBlocking {
            delay(100)
            stateStore.reset()
        }
        scopeProvider.close()
    }
    fun `consolidateStacks - merges split storage bags`() = runBlocking {
        stateStore.update {
            storageBags = EntityStore(listOf(
                StorageBag(id = "b1", name = "凡品储物袋", rarity = 1, quantity = 3),
                StorageBag(id = "b2", name = "凡品储物袋", rarity = 1, quantity = 3),
                StorageBag(id = "b3", name = "凡品储物袋", rarity = 1, quantity = 2)
            ))
        }
        system.consolidateStacks()
        val bags = stateStore.storageBags.value
        assertEquals(1, bags.size)
        assertEquals(8, bags[0].quantity)
    }

    @Test
    fun `consolidateStacks - locked stack absorbs quantity but is never removed`() = runBlocking {
        stateStore.update {
            pills = EntityStore(listOf(
                Pill(
                    id = "pLocked", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW,
                    quantity = 5, isLocked = true
                ),
                Pill(
                    id = "pFree", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW,
                    quantity = 3
                )
            ))
        }
        system.consolidateStacks()
        val pills = stateStore.pills.value
        assertEquals(1, pills.size)
        // 锁定堆叠作为合并目标，吸收数量且自身保持不变（ID 与 isLocked）
        assertTrue(pills[0].isLocked)
        assertEquals("pLocked", pills[0].id)
        assertEquals(8, pills[0].quantity)
    }

    @Test
    fun `consolidateStacks - locked stack as target absorbs free source, lock preserved`() = runBlocking {
        stateStore.update {
            pills = EntityStore(listOf(
                Pill(
                    id = "pLocked", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW,
                    quantity = 3, isLocked = true
                ),
                Pill(
                    id = "pFree", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW,
                    quantity = 3
                )
            ))
        }
        // 锁定堆叠作为合并目标吸收未锁定来源；锁定堆叠自身 ID 与 isLocked 不变
        system.consolidateStacks()
        val pills = stateStore.pills.value
        assertEquals(1, pills.size)
        assertTrue(pills[0].isLocked)
        assertEquals("pLocked", pills[0].id)
        assertEquals(6, pills[0].quantity)
    }

    @Test
    fun `consolidateStacks - 3 stacks exceeding maxStack terminates without oscillation`() = runBlocking {
        // 对抗性审查 CRITICAL 回归：≥3 同键堆叠且总数 > maxStack 时，
        // 旧 while 算法在 [999,543,999] ↔ [999,999,543] 之间无限振荡（启动卡死）
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        stateStore.update {
            pills = EntityStore(listOf(
                Pill(id = "p1", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack),
                Pill(id = "p2", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack),
                Pill(id = "p3", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 543),
                Pill(id = "p4", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 321)
            ))
        }
        // 若死循环，此调用永不返回（测试超时即失败）
        system.consolidateStacks()
        val pills = stateStore.pills.value
        // 数量守恒：999+999+543+321 = 2862 = 999+999+864
        val total = pills.sumOf { it.quantity }
        assertEquals(2862, total)
        assertTrue("所有堆叠不得超过 maxStack", pills.all { it.quantity <= maxStack })
    }

    @Test
    fun `consolidateStacks - oscillating pattern 999 543 999 terminates`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        stateStore.update {
            pills = EntityStore(listOf(
                Pill(id = "p1", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack),
                Pill(id = "p2", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 543),
                Pill(id = "p3", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack)
            ))
        }
        system.consolidateStacks()
        val pills = stateStore.pills.value
        assertEquals(2541, pills.sumOf { it.quantity })
        assertTrue(pills.all { it.quantity <= maxStack })
    }

    @Test
    fun `addPill - quantity over maxStack creates multiple stacks`() = runBlocking {
        // 对抗性审查 HIGH 回归：单次添加数量 > maxStack 必须分块，不得生成超限堆叠
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "回气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack * 2 + 5))
        }
        val pills = stateStore.pills.value
        val total = pills.sumOf { it.quantity }
        assertEquals(maxStack * 2 + 5, total)
        assertTrue("所有堆叠不得超过 maxStack", pills.all { it.quantity <= maxStack })
        assertEquals(3, pills.size)
    }

    @Test
    fun `addPill - zero-merge when warehouse full returns FAILURE not Partial`() = runBlocking {
        // 对抗性审查 HIGH 回归：仓库满且同键堆叠全满、本次零合并时
        // 必须返回 Failure（调用方拒绝领取），不得返回 Partial（物品静默丢失）
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        val capacity = GameConfig.Warehouse.BASE_CAPACITY
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "筑基丹", rarity = 2,
                category = PillCategory.FUNCTIONAL, grade = PillGrade.LOW, quantity = maxStack))
            // 填满其余槽位
            for (i in 0 until capacity - 1) {
                system.addPill(Pill(id = "fill$i", name = "填充丹药$i", rarity = 1,
                    category = PillCategory.FUNCTIONAL, grade = PillGrade.LOW, quantity = 1))
            }
        }
        var result: DomainResult<Pill>? = null
        stateStore.update {
            result = system.addPill(Pill(id = "pNew", name = "筑基丹", rarity = 2,
                category = PillCategory.FUNCTIONAL, grade = PillGrade.LOW, quantity = 1))
        }
        assertTrue("零合并且仓库满必须 Failure", result is DomainResult.Failure)
    }
}
