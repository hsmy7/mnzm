package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 库存 add/remove 家族 native 转发回退守卫（计划 v2 批 8-2）。
 *
 * JVM 测试环境无桌面 JNI（GameCoreBridge.isLoaded=false），native 通道恒降级 null——
 * 本类断言：① OFF 模式走 Kotlin 原实现；② AUTHORITATIVE 模式在 native 不可用时
 * 静默回退 Kotlin 原实现且不抛异常（双实现并行契约）。
 * native 通道本体语义由 GTest execute_dispatch_test（含溢出草稿回传 3 用例）+
 * CI DiffInventoryTest 同源系统函数对拍覆盖。
 *
 * 注意：必须 Robolectric 运行——FakeAtomicStateStore 的 DiscipleTables 基于
 * android.util.SparseArray（对齐 GameEngineTraitAddTest）。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineInventoryForwardTest {

    private lateinit var engine: GameEngine
    private lateinit var inventoryFacade: InventoryFacade

    private val stack = EquipmentStack(
        id = "eq-1", name = "木剑", rarity = 1, slot = EquipmentSlot.WEAPON, quantity = 5
    )

    @Before
    fun setup() {
        val mockCore = mock<GameEngineCore>()
        whenever(mockCore.stateSyncServiceRef).thenReturn(mock<StateSyncService>())
        inventoryFacade = mock<InventoryFacade>().also {
            whenever(it.inventorySystem).thenReturn(mock())
        }
        val mockEconomy = mock<EconomyFacade>().also { economy ->
            whenever(economy.inventoryFacade).thenReturn(inventoryFacade)
            whenever(economy.mailService).thenReturn(mock())
        }
        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = FakeAtomicStateStore(),
            gameRngManager = mock(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade(),
            economyFacade = mockEconomy,
            battleFacade = mock()
        )
    }

    /** 构造期 Facade 访问器 stub 链（防 GameEngine 构造 NPE，对齐 GameEngineTraitAddTest）。 */
    private fun mockCultivationFacade(): CultivationFacade = mock<CultivationFacade>().also {
        whenever(it.cultivationService).thenReturn(mock())
        whenever(it.discipleService).thenReturn(mock())
        val mockProductionFacade = mock<ProductionFacade>()
        whenever(mockProductionFacade.productionSlots)
            .thenReturn(MutableStateFlow(emptyList()))
        whenever(it.productionFacade).thenReturn(mockProductionFacade)
        val mockPC = mock<ProductionCoordinator>()
        whenever(mockPC.repository).thenReturn(mock())
        whenever(it.productionCoordinator).thenReturn(mockPC)
    }

    @Test
    fun `OFF 模式 addEquipmentStack 走 Kotlin 原实现`() = runBlocking {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.addEquipmentStack(stack)
        }
        verify(inventoryFacade).addEquipmentStack(stack)
    }

    @Test
    fun `AUTHORITATIVE 且 native 不可用时 addEquipmentStack 静默回退 Kotlin`() = runBlocking {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.addEquipmentStack(stack)  // JVM 无 JNI → tryExecuteNative 返回 null
        }
        verify(inventoryFacade).addEquipmentStack(stack)
    }

    @Test
    fun `AUTHORITATIVE 且 native 不可用时 removeEquipment 回退并透传 facade 结果`() = runBlocking {
        whenever(inventoryFacade.removeEquipment("eq-1")).thenReturn(true)
        val removed = NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.removeEquipment("eq-1")
        }
        verify(inventoryFacade).removeEquipment("eq-1")
        assertEquals(true, removed)
    }

    @Test
    fun `AUTHORITATIVE 且 native 不可用时 addPillToWarehouse 静默回退 Kotlin`() = runBlocking {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.addPillToWarehouse(com.xianxia.sect.core.model.Pill(name = "回气丹", quantity = 2))
        }
        verify(inventoryFacade).addPillToWarehouse(any())
    }

    @Test
    fun `AUTHORITATIVE 且 native 不可用时 sortWarehouse_consolidateStacks_toggleItemLock 静默回退 Kotlin`() = runBlocking {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.sortWarehouse()
            engine.consolidateStacks()
            engine.toggleItemLock("eq-1", "equipment")
        }
        verify(inventoryFacade).sortWarehouse()
        verify(inventoryFacade).consolidateStacks()
        verify(inventoryFacade).toggleItemLock("eq-1", "equipment")
    }

    @Test
    fun `withMode 恢复默认 AUTHORITATIVE`() {
        assertEquals(NativeEngineFlag.Mode.AUTHORITATIVE, NativeEngineFlag.mode)
    }
}
