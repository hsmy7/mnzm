package com.xianxia.sect.core.engine.domain.inventory

import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipInstanceMeta
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

/**
 * 商人交易价格校验守卫测试（含同类缺口覆盖）。
 *
 * 核心守卫：
 * - buyMerchantItem 负价/0 价商品拒绝购买（灵石不变 / 不入库 / 商家库存不变）
 * - sellToMerchant 负价/0 价收购拒绝（仓库物品保留——防"事务内先移除物品、后 wallet.add 静默拒绝"致物品丢失）
 * - 正价购买/收购回归正常
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class MerchantPriceValidationTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var store: FakeAtomicStateStore
    private lateinit var facade: InventoryFacadeImpl

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
        facade = InventoryFacadeImpl(
            inventorySystem = inventorySystem,
            stateStore = store,
            inventoryConfig = InventoryConfig(),
            gameEngineCore = mock(),
            spiritStoneWallet = wallet,
            // B3 购买链按件 roll 词条（gameRngManager.getRng(EQUIPMENT).asKotlinRandom()），
            // mock 未 stub getRng 返回 null 会 NPE——用固定种子真实例
            gameRngManager = GameRngManager().apply { initSystemSeed(20260930L) }
        )
    }

    /** 种子：玩家 1000 灵石 + 商人 1 件在售商品（"精铁剑" rarity=1，库存 5，可指定价格） */
    private fun seedMerchantItem(price: Long) {
        store.update {
            gameData = gameData.copy(
                spiritStones = 1000L,
                travelingMerchantItems = listOf(
                    MerchantItem("m1", "精铁剑", "equipment", "sword_iron", 1, price, 5)
                )
            )
        }
    }

    /** B3 实例轨种子：精铁剑仓库实例（name/rarity 参与收购计数与扣减） */
    private fun swordInstance(id: String) = EquipmentInstance(
        id = id, name = "精铁剑",
        part = EquipmentSlot.WEAPON,
        growth = EquipGrowth(
            affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 5.0))
        ),
        meta = EquipInstanceMeta(rarity = 1)
    )

    /** 种子：玩家仓库 3 把精铁剑 + 商人收购需求（可指定价格） */
    private fun seedAcquisitionItem(price: Long) {
        store.equipmentInstances.value = listOf(
            swordInstance("s1"), swordInstance("s2"), swordInstance("s3")
        )
        store.update {
            gameData = gameData.copy(
                spiritStones = 1000L,
                merchantAcquisitionItems = listOf(
                    MerchantItem("a1", "精铁剑", "equipment", "sword_iron", 1, price, 5)
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // buyMerchantItem 价格校验
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `buyMerchantItem - negative price rejects purchase`() = runTest {
        seedMerchantItem(price = -100L)

        facade.buyMerchantItem("m1", 1)

        assertEquals("灵石不变", 1000L, store.gameData.value.spiritStones)
        assertTrue("不入库", store.equipmentInstances.value.isEmpty())
        assertEquals("商家库存不变", 5, store.gameData.value.travelingMerchantItems.first().quantity)
    }

    @Test
    fun `buyMerchantItem - zero price rejects purchase`() = runTest {
        seedMerchantItem(price = 0L)

        facade.buyMerchantItem("m1", 1)

        assertEquals("灵石不变", 1000L, store.gameData.value.spiritStones)
        assertTrue("不入库", store.equipmentInstances.value.isEmpty())
        assertEquals("商家库存不变", 5, store.gameData.value.travelingMerchantItems.first().quantity)
    }

    @Test
    fun `buyMerchantItem - positive price succeeds`() = runTest {
        seedMerchantItem(price = 100L)

        facade.buyMerchantItem("m1", 2)

        assertEquals("灵石扣减 1000-200", 800L, store.gameData.value.spiritStones)
        // B3 实例轨：quantity 件 = quantity 条实例（不再合并堆叠）
        assertEquals("入库 2 实例", 2, store.equipmentInstances.value.size)
        assertEquals("商家库存 5-2", 3, store.gameData.value.travelingMerchantItems.first().quantity)
    }

    // ═══════════════════════════════════════════════════════════════
    // sellToMerchant 收购价校验（同类缺口）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `sellToMerchant - negative acquisition price keeps warehouse items`() = runTest {
        seedAcquisitionItem(price = -50L)

        facade.sellToMerchant("a1", 1)

        assertEquals("仓库物品保留(防丢失)", 3, store.equipmentInstances.value.size)
        assertEquals("灵石不变", 1000L, store.gameData.value.spiritStones)
        assertEquals("收购需求不变", 5, store.gameData.value.merchantAcquisitionItems.first().quantity)
    }

    @Test
    fun `sellToMerchant - zero acquisition price keeps warehouse items`() = runTest {
        seedAcquisitionItem(price = 0L)

        facade.sellToMerchant("a1", 1)

        assertEquals("仓库物品保留(防丢失)", 3, store.equipmentInstances.value.size)
        assertEquals("灵石不变", 1000L, store.gameData.value.spiritStones)
        assertEquals("收购需求不变", 5, store.gameData.value.merchantAcquisitionItems.first().quantity)
    }

    @Test
    fun `sellToMerchant - positive price succeeds`() = runTest {
        seedAcquisitionItem(price = 50L)

        facade.sellToMerchant("a1", 2)

        assertEquals("仓库物品移除 3-2", 1, store.equipmentInstances.value.size)
        assertEquals("灵石增加 1000+100", 1100L, store.gameData.value.spiritStones)
        assertEquals("收购需求 5-2", 3, store.gameData.value.merchantAcquisitionItems.first().quantity)
    }
}
