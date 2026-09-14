package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/**
 * 弟子生命周期域 native 事务门控单测（batch-14 下沉——InventoryNativeTxGateTest
 * 同族三级降级契约守护）。
 *
 * JVM 单测环境 GameCoreBridge 恒未加载：断言 AUTHORITATIVE 稳态与 flag OFF 两
 * 模式下五入口均走 Kotlin 回退臂且状态变更语义不变（native 臂零激活、零异常
 * 泄漏）；native 事务本身的校验链/槽位清理语义由桌面 C++
 * disciple_lifecycle_tx_test.cpp 黄金用例守护，真机转发臂由真机验证批覆盖。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class DiscipleLifecycleNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var store: GameStateStore
    private lateinit var facade: DiscipleFacadeImpl

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        tables = store.discipleTables
        facade = buildFacade()
    }

    @After
    fun restoreFlag() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
    }

    private fun buildFacade(): DiscipleFacadeImpl {
        val mockStore = store
        // ProductionSlotRepository 是 final 类：mock 拦截依赖类加载时机（顺序敏感 flaky），
        // 用真实实例 + mockSmart 端口（DiscipleReflectionReleaseTest 同款脚手架）
        val productionRepo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        val slotManager = DiscipleSlotManager(
            stateStore = mockStore,
            productionSlotRepository = productionRepo,
            discipleSlotCleanup = DiscipleSlotCleanup(
                DiscipleAssignmentGate(DiscipleAssignmentRegistry())
            ),
            discipleStatusServiceProvider = javax.inject.Provider { mockSmart() },
            ioDispatcher = IoDispatcher()
        )
        val lifecycleManager = DiscipleLifecycleManager(
            stateStore = mockStore,
            slotManager = slotManager,
            productionSlotRepository = mockSmart(),
        )
        val statusService = DiscipleStatusService(
            stateStore = mockStore,
            discipleLifecycleManager = lifecycleManager,
            secretRealmService = mockSmart()
        )
        val service = DiscipleService(
            stateStore = mockStore,
            discipleFactory = mockSmart(),
            rngManager = mockSmart(),
            discipleEquipmentService = DiscipleEquipmentService(stateStore = mockStore),
            discipleLifecycleManager = lifecycleManager,
            discipleMasterApprenticeService = DiscipleMasterApprenticeService(stateStore = mockStore),
            discipleSlotManager = slotManager,
            discipleStatusService = statusService,
            inventorySystem = mockSmart(com.xianxia.sect.core.engine.system.InventorySystem::class.java),
        )
        val cultivationService = mockSmart(com.xianxia.sect.core.engine.service.CultivationService::class.java)
        Mockito.`when`(cultivationService.getHighFrequencyData())
            .thenReturn(MutableStateFlow(HighFrequencyData()))
        return DiscipleFacadeImpl(
            discipleService = service,
            stateStore = mockStore,
            cultivationService = cultivationService,
            gameEngineCore = mockSmart(),
            pillManager = mockSmart(),
            assignmentGate = mockSmart(),
            discipleSlotCleanup = mockSmart(),
            lawEnforcementProcessor = mockSmart(),
            productionCoordinator = mockSmart<com.xianxia.sect.core.engine.domain.production.ProductionCoordinator>(),
        )
    }

    /** 种子：存活弟子 1/2 + 弟子 1 在灵矿槽位 */
    private fun seedDisciples() {
        tables.insert(Disciple(id = "1", name = "弟子1", age = 20))
        tables.isAlive[1] = 1
        tables.insert(Disciple(id = "2", name = "弟子2", age = 30))
        tables.isAlive[2] = 1
        store.update {
            gameData = gameData.copy(
                spiritMineSlots = listOf(SpiritMineSlot(index = 0, discipleId = "1", discipleName = "弟子1"))
            )
        }
    }

    // ── 转发臂门控（桥未加载恒降级 null） ───────────────────────

    @Test
    fun `native 转发 - AUTHORITATIVE 且桥未加载五入口均返回 null`() {
        seedDisciples()
        assertNull(facade.tryNativeExpelDisciple("1"))
        assertNull(facade.tryNativeApprenticeToMaster("1", "2"))
        assertNull(facade.tryNativeReleaseReflection("1"))
        assertNull(facade.tryNativeSalaryToggle(3, true))
    }

    @Test
    fun `native 转发 - flag OFF 四入口均返回 null`() {
        seedDisciples()
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertNull(facade.tryNativeExpelDisciple("1"))
            assertNull(facade.tryNativeApprenticeToMaster("1", "2"))
            assertNull(facade.tryNativeReleaseReflection("1"))
            assertNull(facade.tryNativeSalaryToggle(3, true))
        }
    }

    // ── 回退臂语义不变（桥未加载 = Kotlin 原路径全量生效） ──────

    @Test
    fun `回退臂 - 逐出走 Kotlin 原路径删行清槽计数`() {
        seedDisciples()
        tables.statuses[1] = DiscipleStatus.MINING

        val result = facade.expelDisciple("1")

        assertTrue(result is com.xianxia.sect.core.util.DomainResult.Success)
        assertTrue("row must be removed", 1 !in tables.ids)
        assertEquals("annual deserted counter incremented", 1, store.gameData.value.annualDesertedDisciples)
        assertEquals("slot cleared", "", store.gameData.value.spiritMineSlots[0].discipleId)
        assertTrue("control disciple intact", 2 in tables.ids)
    }

    @Test
    fun `回退臂 - 拜师 masterIds 落表与双侧 lifeEvents`() {
        seedDisciples()

        val result = facade.apprenticeToMaster("1", "2")

        assertTrue(result is com.xianxia.sect.core.util.DomainResult.Success)
        assertEquals("2", tables.masterIds[1])
        assertTrue(tables.lifeEvents[1]!!.last().endsWith("拜弟子2为师"))
        assertTrue(tables.lifeEvents[2]!!.last().endsWith("收弟子1为徒"))
    }

    @Test
    fun `回退臂 - 年俸开关走 Kotlin 原路径写映射`() {
        facade.updateYearlySalaryEnabled(3, true)

        assertEquals(true, store.gameData.value.yearlySalaryEnabled[3])
    }
}
