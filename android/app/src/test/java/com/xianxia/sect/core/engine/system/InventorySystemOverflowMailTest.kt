package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.overflow.OverflowMailDraft
import com.xianxia.sect.core.overflow.OverflowMailHandler
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * InventorySystem 溢出转邮件三态测试（对抗性审查 HIGH-3 补充）：
 * - Partial（部分入仓）→ 溢出量转邮件草稿
 * - Failure(Full)（零合并且无空槽）→ 全部数量转邮件草稿
 * - withOverflowMailSuppressed 内 → 不转邮件（凭据类路径）
 * - Success → 不转
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InventorySystemOverflowMailTest {

    private lateinit var system: InventorySystem
    private lateinit var stateStore: GameStateStore
    private lateinit var scopeProvider: ApplicationScopeProvider
    private lateinit var inventoryConfig: InventoryConfig
    private lateinit var handler: CollectingOverflowMailHandler

    /** 收集草稿的测试 handler */
    class CollectingOverflowMailHandler : OverflowMailHandler {
        val drafts = mutableListOf<OverflowMailDraft>()
        override fun sendOverflowMails(drafts: List<OverflowMailDraft>) {
            this.drafts.addAll(drafts)
        }
    }

    @Before
    fun setUp() {
        scopeProvider = ApplicationScopeProvider()
        stateStore = GameStateStoreImpl(scopeProvider, mock(GameStateRepository::class.java))
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = true
        inventoryConfig = InventoryConfig()
        handler = CollectingOverflowMailHandler()
        system = InventorySystem(
            stateStore, inventoryConfig,
            SpiritStoneWallet(stateStore, SpiritStoneLedger(), mock(EventBus::class.java)),
            mock(com.xianxia.sect.core.engine.config.GameConfigProvider::class.java),
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

    @Test
    fun `success - no overflow mail`() = runBlocking {
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "回气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 5))
        }
        assertTrue(handler.drafts.isEmpty())
    }

    @Test
    fun `partial - overflow quantity sent as mail draft`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        val capacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        stateStore.update {
            // 填满仓库（回气丹堆叠满 + 其余槽位满），再添加同种丹药 → 只能部分合并
            system.addPill(Pill(id = "p1", name = "回气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack))
            for (i in 0 until capacity - 1) {
                system.addPill(Pill(id = "fill$i", name = "填充丹药$i", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 1))
            }
            // 仓库满 + 回气丹堆叠已满 → 添加 10 个回气丹零合并 → Failure(Full)
            val result = system.addPill(Pill(id = "pNew", name = "回气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 10))
            assertTrue("零合并且仓库满必须 Failure", result is com.xianxia.sect.core.util.DomainResult.Failure)
        }
        // Failure(Full) → 全部 10 个转邮件草稿
        assertEquals(1, handler.drafts.size)
        assertEquals(10, handler.drafts[0].quantity)
        assertEquals("回气丹", handler.drafts[0].itemName)
        assertEquals("pill", handler.drafts[0].itemType)
    }

    @Test
    fun `suppressed - no overflow mail`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        val capacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        stateStore.update {
            system.addPill(Pill(id = "p1", name = "回气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack))
            for (i in 0 until capacity - 1) {
                system.addPill(Pill(id = "fill$i", name = "填充丹药$i", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 1))
            }
            // 凭据类路径：抑制溢出转邮件
            val result = system.withOverflowMailSuppressed {
                system.addPill(Pill(id = "pNew", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 10))
            }
            assertTrue(result is com.xianxia.sect.core.util.DomainResult.Failure)
        }
        assertTrue("suppressed 内不转邮件", handler.drafts.isEmpty())
    }

    @Test
    fun `partial with merge space - overflow only sent`() = runBlocking {
        val maxStack = inventoryConfig.getMaxStackSize("pill")
        stateStore.update {
            // 回气丹堆叠有 5 个空间；仓库其余槽位满 → 添加 10 个 → 合并 5、溢出 5
            system.addPill(Pill(id = "p1", name = "回气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack - 5))
            val capacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
            for (i in 0 until capacity - 1) {
                system.addPill(Pill(id = "fill$i", name = "填充丹药$i", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 1))
            }
            val result = system.addPill(Pill(id = "pNew", name = "回气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 10))
            assertTrue("有合并空间必须 Partial", result is com.xianxia.sect.core.util.DomainResult.Partial)
        }
        // 仅溢出 5 个转邮件
        assertEquals(1, handler.drafts.size)
        assertEquals(5, handler.drafts[0].quantity)
    }
}
