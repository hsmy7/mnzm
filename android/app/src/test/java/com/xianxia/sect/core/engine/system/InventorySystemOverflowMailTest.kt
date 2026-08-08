package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.overflow.OverflowMailDraft
import com.xianxia.sect.core.overflow.OverflowMailHandler
import com.xianxia.sect.core.engine.service.OverflowMailSender
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
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
        system = makeSystem(handler)
        system.initialize()
        runBlocking { stateStore.reset() }
    }

    /** 以指定溢出处理器构造 InventorySystem（真实 sender 用例复用同一 stateStore） */
    private fun makeSystem(handler: OverflowMailHandler): InventorySystem = InventorySystem(
        stateStore, inventoryConfig,
        SpiritStoneWallet(stateStore, SpiritStoneLedger(), mock(EventBus::class.java)),
        mock(com.xianxia.sect.core.engine.config.GameConfigProvider::class.java),
        handler
    )

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

    // ═══════════════════════════════════════════════════════════════
    // D-01 集成：真实 OverflowMailSender + 真实 GameStateStoreImpl 世代号钩子
    // 核心不变量：DB 中的草稿行 ⇒ 其来源事务已提交
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `overflow then transaction failure - drafts discarded not persisted`() = runBlocking {
        val mailRepo = mock(com.xianxia.sect.core.repository.MailRepository::class.java)
        org.mockito.kotlin.whenever(mailRepo.getPersistedOverflowDraftsBlocking()).thenReturn(emptyList())
        org.mockito.kotlin.whenever(mailRepo.getPersistedDirectMailDraftsBlocking()).thenReturn(emptyList())
        org.mockito.kotlin.whenever(mailRepo.insertOverflowDraftsBlocking(any())).thenReturn(1)
        org.mockito.kotlin.whenever(mailRepo.insertDirectMailDraftBlocking(any())).thenReturn(true)
        val sender = OverflowMailSender(mailRepo, stateStore, scopeProvider)
        val overflowSystem = makeSystem(sender)

        val maxStack = inventoryConfig.getMaxStackSize("pill")
        val capacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        try {
            stateStore.update {
                assertEquals("事务内世代号必须 >0", 1L, stateStore.currentTransactionGeneration)
                overflowSystem.addPill(Pill(id = "p1", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack))
                for (i in 0 until capacity - 1) {
                    overflowSystem.addPill(Pill(id = "fill$i", name = "填充丹药$i", rarity = 1,
                        category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 1))
                }
                // Failure(Full)：溢出草稿已入 staging（世代号>0）
                overflowSystem.addPill(Pill(id = "pNew", name = "回气丹", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 10))
                assertEquals("溢出后仍处事务内", 1L, stateStore.currentTransactionGeneration)
                // 随后事务块抛异常 → 回滚 → 草稿必须丢弃（复制不可能发生）
                error("模拟事务中断")
            }
            fail("事务块应抛出模拟异常")
        } catch (e: IllegalStateException) {
            assertEquals("异常应为模拟事务中断", "模拟事务中断", e.message)
        }
        // 回滚钩子丢弃 staging——草稿永不落盘
        org.mockito.kotlin.verify(mailRepo, org.mockito.kotlin.never())
            .insertOverflowDraftsBlocking(any())
        org.mockito.kotlin.verify(mailRepo, org.mockito.kotlin.never())
            .insertDirectMailDraftBlocking(any())
        Unit
    }

    @Test
    fun `overflow in committed transaction - drafts persisted exactly once`() = runBlocking {
        val mailRepo = mock(com.xianxia.sect.core.repository.MailRepository::class.java)
        org.mockito.kotlin.whenever(mailRepo.getPersistedOverflowDraftsBlocking()).thenReturn(emptyList())
        org.mockito.kotlin.whenever(mailRepo.getPersistedDirectMailDraftsBlocking()).thenReturn(emptyList())
        org.mockito.kotlin.whenever(mailRepo.insertOverflowDraftsBlocking(any())).thenReturn(1)
        org.mockito.kotlin.whenever(mailRepo.insertDirectMailDraftBlocking(any())).thenReturn(true)
        val sender = OverflowMailSender(mailRepo, stateStore, scopeProvider)
        val overflowSystem = makeSystem(sender)

        val maxStack = inventoryConfig.getMaxStackSize("pill")
        val capacity = com.xianxia.sect.core.GameConfig.Warehouse.BASE_CAPACITY
        stateStore.update {
            overflowSystem.addPill(Pill(id = "p1", name = "回气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = maxStack))
            for (i in 0 until capacity - 1) {
                overflowSystem.addPill(Pill(id = "fill$i", name = "填充丹药$i", rarity = 1,
                    category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 1))
            }
            overflowSystem.addPill(Pill(id = "pNew", name = "回气丹", rarity = 1,
                category = PillCategory.CULTIVATION, grade = PillGrade.LOW, quantity = 10))
        }
        // 提交钩子恰一次落盘（10 个全量溢出草稿）
        org.mockito.kotlin.verify(mailRepo, org.mockito.kotlin.times(1))
            .insertOverflowDraftsBlocking(any())
        org.mockito.kotlin.verify(mailRepo, org.mockito.kotlin.never())
            .insertDirectMailDraftBlocking(any())
        Unit
    }
}
