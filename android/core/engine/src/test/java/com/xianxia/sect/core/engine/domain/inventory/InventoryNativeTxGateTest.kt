package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.system.InventorySystem
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
        store.update { gameData = GameData() }
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

    /** 种子：玩家 1000 灵石 + 仓库 2 把精铁剑实例（r1 基价 4000，卖价 3200/件） */
    private fun seedWarehouse() {
        store.equipmentInstances.value = listOf(
            swordInstance("s1"), swordInstance("s2")
        )
        store.update { gameData = gameData.copy(spiritStones = 1000L) }
    }

    /** B3 实例轨种子装备（散件：basePrice 走 GameConfig.Rarity 基价） */
    private fun swordInstance(id: String, locked: Boolean = false) =
        com.xianxia.sect.core.model.EquipmentInstance(
            id = id, name = "精铁剑",
            growth = com.xianxia.sect.core.model.EquipGrowth(
                affix = com.xianxia.sect.core.model.EquipAffixSet(
                    mainStat = com.xianxia.sect.core.model.EquipStatValue(
                        com.xianxia.sect.core.model.EquipStat.ATTACK, 5.0
                    )
                )
            ),
            meta = com.xianxia.sect.core.model.EquipInstanceMeta(rarity = 1, isLocked = locked)
        )

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
        // 模板价 4000 × 0.8 = 3200（B3 实例轨整件出售，quantity 仅协议占位）；1000 + 3200
        assertEquals(4200L, store.gameData.value.spiritStones)
        assertEquals("被售实例应移除", 1, store.equipmentInstances.value.size)
        assertEquals("s2", store.equipmentInstances.value[0].id)
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
        store.equipmentInstances.value = listOf(swordInstance("s1"), swordInstance("lock", locked = true))
        assertFalse("不存在", facade.sellEquipment("missing", 1))
        assertFalse("锁定实例拒售", facade.sellEquipment("lock", 1))
        assertEquals(1000L, store.gameData.value.spiritStones)
        assertEquals("仓库不变", 2, store.equipmentInstances.value.size)
    }

    @Test
    fun `bulkSellItems - 降级臂聚合入账一次`() = runTest {
        seedWarehouse()
        val result = facade.bulkSellItems(
            listOf(InventoryFacade.BulkSellOperation("s1", "精铁剑", 1, "equipment"))
        )
        assertEquals(1, result.soldCount)
        assertEquals(3200L, result.totalEarned)
        assertEquals(listOf("精铁剑 1"), result.soldItemNames)
        assertTrue(result.failedItemNames.isEmpty())
        assertEquals(4200L, store.gameData.value.spiritStones)
    }

    @Test
    fun `sellToMerchant - 降级臂收购成功并回写收购项`() = runTest {
        seedWarehouse()
        seedAcquisition(price = 500L)
        facade.sellToMerchant("a1", 2)
        assertEquals(2000L, store.gameData.value.spiritStones)
        assertEquals("两件实例均被收购移除", 0, store.equipmentInstances.value.size)
        assertEquals(3, store.gameData.value.merchantAcquisitionItems[0].quantity)
    }

    @Test
    fun `sellToMerchant - 非法价格零写入`() = runTest {
        seedWarehouse()
        seedAcquisition(price = 0L)
        facade.sellToMerchant("a1", 1)
        assertEquals("灵石不变", 1000L, store.gameData.value.spiritStones)
        assertEquals("仓库不变", 2, store.equipmentInstances.value.size)
    }

    @Test
    fun `listItemsToMerchant - 降级臂登记上架且不扣仓库`() = runTest {
        seedWarehouse()
        facade.listItemsToMerchant(listOf("s1" to 2))
        assertEquals(1, store.gameData.value.playerListedItems.size)
        // B3 实例轨：1 件 = 1 条目，quantity 仅协议占位（整件上架恒 1）
        assertEquals(1, store.gameData.value.playerListedItems[0].quantity)
        assertEquals("上架不扣仓库", 2, store.equipmentInstances.value.size)
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
