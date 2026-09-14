package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * 库存出售/上架域 native 事务门控单测（W2-a 下沉——BuildingNativeTxGateTest 同族
 * 三级降级契约守护）。
 *
 * JVM 单测环境 GameCoreBridge 恒未加载：断言 AUTHORITATIVE 稳态与 flag OFF 两
 * 模式下五操作均走 Kotlin 回退臂且状态变更语义不变（native 臂零激活、零异常
 * 泄漏）；native 事务本身的校验链/取价语义由桌面 C++ inventory_tx_test.cpp 黄金
 * 用例守护，真机转发臂由真机验证批覆盖。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class InventoryNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var facade: InventoryFacadeImpl
    private lateinit var nativeTx: InventoryNativeTx

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        store.update { gameData = GameData(slotId = 1) }
        store.persistentDiscipleTables.writeAllowed = true
        val wallet = com.xianxia.sect.core.wallet.SpiritStoneWallet(
            stateStore = store,
            ledger = mock(com.xianxia.sect.core.wallet.SpiritStoneLedger::class.java),
            eventBus = mock(com.xianxia.sect.core.event.EventBus::class.java)
        )
        val inventorySystem = InventorySystem(
            stateStore = store,
            inventoryConfig = InventoryConfig(),
            overflowMailHandler = com.xianxia.sect.core.overflow.NoOpOverflowMailHandler
        )
        val gameEngineCore = mock(GameEngineCore::class.java)
        nativeTx = InventoryNativeTx(gameEngineCore)
        facade = InventoryFacadeImpl(
            inventorySystem = inventorySystem,
            stateStore = store,
            inventoryConfig = InventoryConfig(),
            gameEngineCore = gameEngineCore,
            spiritStoneWallet = wallet,
            gameRngManager = mock(GameRngManager::class.java)
        )
    }

    @After
    fun restoreFlag() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
    }

    /** 种子：玩家 1000 灵石 + 仓库 3 把精铁剑（可上架 2） */
    private fun seedWarehouse() {
        store.equipmentStacks.value = listOf(
            EquipmentStack(
                id = "s1", name = "精铁剑", rarity = 1,
                slot = EquipmentSlot.WEAPON, quantity = 3
            )
        )
        store.update { gameData = gameData.copy(spiritStones = 1000L) }
    }

    private fun seedAcquisition(price: Long = 500L) {
        store.update {
            gameData = gameData.copy(
                merchantAcquisitionItems = listOf(
                    MerchantItem("a1", "精铁剑", "equipment", "s1", 1, price, 5)
                )
            )
        }
    }

    // ── 转发臂门控（桥未加载恒降级 null） ───────────────────────

    @Test
    fun `native 转发 - AUTHORITATIVE 且桥未加载五入口均返回 null`() {
        assertNull(nativeTx.sellItem("equipment", "s1", 1))
        assertNull(nativeTx.bulkSell(listOf(
            InventoryFacade.BulkSellOperation("s1", "精铁剑", 1, "equipment")
        )))
        assertNull(nativeTx.sellToMerchant("a1", 1))
        assertNull(nativeTx.listItemsToMerchant(listOf("s1" to 1)))
        assertNull(nativeTx.removePlayerListedItem("a1"))
        assertNull(nativeTx.consumeMaterialByName("兽皮", 1, 1))
    }

    @Test
    fun `native 转发 - flag OFF 五入口均返回 null`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertNull(nativeTx.sellItem("equipment", "s1", 1))
            assertNull(nativeTx.bulkSell(emptyList()))
            assertNull(nativeTx.sellToMerchant("a1", 1))
            assertNull(nativeTx.listItemsToMerchant(emptyList()))
            assertNull(nativeTx.removePlayerListedItem("a1"))
            assertNull(nativeTx.consumeMaterialByName("兽皮", 1, 1))
        }
    }

    // ── 回退臂语义（native 降级下行为不变） ──────────────────────

    @Test
    fun `sellEquipment - AUTHORITATIVE 桥未加载走回退臂成功`() = runTest {
        seedWarehouse()
        assertTrue(facade.sellEquipment("s1", 2))
        // 模板价 4000 × 2 × 0.8 = 6400；1000 + 6400
        assertEquals(7400L, store.gameData.value.spiritStones)
        assertEquals(1, store.equipmentStacks.value.size)
        assertEquals(1, store.equipmentStacks.value[0].quantity)
    }

    @Test
    fun `sellEquipment - flag OFF 走回退臂成功`() = runTest {
        seedWarehouse()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertTrue(facade.sellEquipment("s1", 1))
        }
        assertEquals(4200L, store.gameData.value.spiritStones)
    }

    @Test
    fun `sellEquipment - 守卫失败保持零写入`() = runTest {
        seedWarehouse()
        assertFalse("数量超过持有", facade.sellEquipment("s1", 5))
        assertFalse("不存在", facade.sellEquipment("missing", 1))
        assertEquals(1000L, store.gameData.value.spiritStones)
        assertEquals(3, store.equipmentStacks.value[0].quantity)
    }

    @Test
    fun `bulkSellItems - 降级臂聚合入账一次`() = runTest {
        seedWarehouse()
        val result = facade.bulkSellItems(
            listOf(InventoryFacade.BulkSellOperation("s1", "精铁剑", 2, "equipment"))
        )
        assertEquals(1, result.soldCount)
        assertEquals(6400L, result.totalEarned)
        assertEquals(listOf("精铁剑 2"), result.soldItemNames)
        assertTrue(result.failedItemNames.isEmpty())
        assertEquals(7400L, store.gameData.value.spiritStones)
    }

    @Test
    fun `sellToMerchant - 降级臂收购成功并回写收购项`() = runTest {
        seedWarehouse()
        seedAcquisition(price = 500L)
        facade.sellToMerchant("a1", 2)
        assertEquals(2000L, store.gameData.value.spiritStones)
        assertEquals(1, store.equipmentStacks.value.size)
        assertEquals(1, store.equipmentStacks.value[0].quantity)
        assertEquals(3, store.gameData.value.merchantAcquisitionItems[0].quantity)
    }

    @Test
    fun `sellToMerchant - 非法价格零写入`() = runTest {
        seedWarehouse()
        seedAcquisition(price = 0L)
        facade.sellToMerchant("a1", 1)
        assertEquals("灵石不变", 1000L, store.gameData.value.spiritStones)
        assertEquals("仓库不变", 3, store.equipmentStacks.value[0].quantity)
    }

    @Test
    fun `listItemsToMerchant - 降级臂登记上架且不扣仓库`() = runTest {
        seedWarehouse()
        facade.listItemsToMerchant(listOf("s1" to 2))
        assertEquals(1, store.gameData.value.playerListedItems.size)
        assertEquals(2, store.gameData.value.playerListedItems[0].quantity)
        assertEquals("上架不扣仓库", 3, store.equipmentStacks.value[0].quantity)
    }

    @Test
    fun `removePlayerListedItem - 降级臂按 id 移除`() = runTest {
        seedWarehouse()
        facade.listItemsToMerchant(listOf("s1" to 2))
        val listedId = store.gameData.value.playerListedItems[0].id
        facade.removePlayerListedItem(listedId)
        assertTrue(store.gameData.value.playerListedItems.isEmpty())
    }

    @Test
    fun `consumeMaterialByName - 降级臂按名称品阶扣减`() = runTest {
        // materials 在 Fake 中为持久化 EntityStore（不经 flow 回灌）——必须经 update 播种
        store.update {
            materials.replaceAll(
                listOf(
                    com.xianxia.sect.core.model.Material(
                        id = "x1", name = "兽皮", rarity = 1, quantity = 2
                    ),
                    com.xianxia.sect.core.model.Material(
                        id = "x2", name = "兽皮", rarity = 1, quantity = 3
                    )
                )
            )
        }
        assertTrue(facade.consumeMaterialByName("兽皮", 1, 4))
        assertEquals(1, store.materials.value.size)
        assertEquals("x2", store.materials.value[0].id)
        assertEquals(1, store.materials.value[0].quantity)
    }
}
