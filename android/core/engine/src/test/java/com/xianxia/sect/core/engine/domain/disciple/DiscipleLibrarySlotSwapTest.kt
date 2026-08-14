package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.SpiritMineSlot
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.WriteGuardRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner


/**
 * 藏经阁换人路径测试：`assignDiscipleToLibrarySlot` 顶替旧 occupant 后，
 * 旧弟子必须 release gate + 同步状态（回归：此前从不 release/sync，
 * 旧弟子 gate 注册残留 + 状态残留 STUDYING 从选择弹窗消失）。
 *
 * 使用 delegate mock store（同 DiscipleReflectionReleaseTest）+ 真实
 * DiscipleStatusService/DiscipleAssignmentGate + stubLaunchInScope，
 * 端到端验证换人后旧弟子状态正确回归。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class DiscipleLibrarySlotSwapTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mockStore: GameStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var facade: DiscipleFacadeImpl
    private lateinit var gameEngineCore: GameEngineCore

    @Before
    fun setUp() {
        val store = FakeAtomicStateStore()
        mockStore = store
        tables = store.discipleTables
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        val service = buildDiscipleService()
        facade = buildFacade(service)
    }

    @Test
    fun `assignDiscipleToLibrarySlot 换人后旧 occupant 状态回 IDLE`() = runTest {
        stubLaunchInScope(this)
        insertDisciple(1, "弟子1")
        insertDisciple(2, "弟子2")

        // A 在藏经阁槽 0（推导为 STUDYING）
        facade.assignDiscipleToLibrarySlot(0, "1", "弟子1")
        advanceUntilIdle()
        assertEquals("A 应处于学习状态", DiscipleStatus.STUDYING, tables.statuses[1])

        // B 顶替 A 的槽位 0
        facade.assignDiscipleToLibrarySlot(0, "2", "弟子2")
        advanceUntilIdle()

        assertEquals("槽位应为弟子 B", "2", mockStore.gameData.value.librarySlots[0].discipleId)
        assertEquals("旧 occupant 状态应回 IDLE", DiscipleStatus.IDLE, tables.statuses[1])
        assertEquals("新弟子状态应 STUDYING", DiscipleStatus.STUDYING, tables.statuses[2])
        assertFalse("旧 occupant gate 应释放", gate.isAssigned("1"))
        assertTrue("新弟子 gate 应注册", gate.isAssigned("2"))
    }

    @Test
    fun `assignDiscipleToLibrarySlot 旧 occupant 有其他槽位时推导回对应状态`() = runTest {
        stubLaunchInScope(this)
        insertDisciple(1, "弟子1")
        insertDisciple(2, "弟子2")
        facade.assignDiscipleToLibrarySlot(0, "1", "弟子1")
        advanceUntilIdle()
        assertEquals(DiscipleStatus.STUDYING, tables.statuses[1])

        // A 同时是灵矿工（换人后应推导回 MINING 而非 IDLE）
        mockStore.update {
            gameData = gameData.copy(
                spiritMineSlots = listOf(
                    SpiritMineSlot(index = 0, discipleId = "1", discipleName = "弟子1")
                )
            )
        }

        facade.assignDiscipleToLibrarySlot(0, "2", "弟子2")
        advanceUntilIdle()

        assertEquals("旧 occupant 应推导回 MINING", DiscipleStatus.MINING, tables.statuses[1])
        assertFalse("旧 occupant gate 应释放", gate.isAssigned("1"))
        assertTrue("新弟子 gate 应注册", gate.isAssigned("2"))
    }

    // ── 辅助 ─────────────────────────────────────────────────────────────

    private fun buildDiscipleService(): DiscipleService {
        // ProductionSlotRepository 是 final 类：mock 拦截依赖类加载时机（顺序敏感 flaky），
        // 用真实实例 + mockSmart 端口（getSlots() 真实返回空列表，语义与 mock 时代一致）
        val productionRepo = com.xianxia.sect.core.engine.testProductionSlotRepository()
        val slotManager = DiscipleSlotManager(
            stateStore = mockStore,
            productionSlotRepository = productionRepo,
            discipleSlotCleanup = DiscipleSlotCleanup(gate),
            discipleStatusServiceProvider = javax.inject.Provider { mockSmart() },
            ioDispatcher = IoDispatcher()
        )
        val equipmentService = DiscipleEquipmentService(
            stateStore = mockStore
        )
        val masterService = DiscipleMasterApprenticeService(
            stateStore = mockStore
        )
        val lifecycleManager = DiscipleLifecycleManager(
            stateStore = mockStore,
            discipleFactory = mockSmart(),
            rngManager = mockSmart(),
            slotManager = slotManager,
            productionSlotRepository = mockSmart(),
        )
        // 真实状态推导服务：syncSingleDiscipleStatus 必须真实执行才能验证状态回归
        val statusService = DiscipleStatusService(
            stateStore = mockStore,
            discipleLifecycleManager = lifecycleManager,
            secretRealmService = mockSmart()
        )
        return DiscipleService(
            stateStore = mockStore,
            discipleFactory = mockSmart(),
            rngManager = mockSmart(),
            discipleEquipmentService = equipmentService,
            discipleLifecycleManager = lifecycleManager,
            discipleMasterApprenticeService = masterService,
            discipleSlotManager = slotManager,
            discipleStatusService = statusService,
            inventorySystem = mockSmart(InventorySystem::class.java)
        )
    }

    private fun buildFacade(service: DiscipleService): DiscipleFacadeImpl {
        gameEngineCore = mockSmart(GameEngineCore::class.java)
        val cultivationService = mockSmart(CultivationService::class.java)
        Mockito.`when`(cultivationService.getHighFrequencyData())
            .thenReturn(MutableStateFlow(HighFrequencyData()))
        return DiscipleFacadeImpl(
            discipleService = service,
            stateStore = mockStore,
            cultivationService = cultivationService,
            gameEngineCore = gameEngineCore,
            inventorySystem = mockSmart(),
            pillManager = mockSmart(),
            assignmentGate = gate,
            discipleSlotCleanup = DiscipleSlotCleanup(gate),
            lawEnforcementProcessor = mockSmart(),
            productionCoordinator = mockSmart<com.xianxia.sect.core.engine.domain.production.ProductionCoordinator>(),
        )
    }

    private fun stubLaunchInScope(scope: TestScope) {
        whenever(gameEngineCore.launchInScope(any())).thenAnswer { inv ->
            val block = inv.getArgument<suspend CoroutineScope.() -> Unit>(0)
            scope.launch { block(scope) }
            null
        }
    }

    private fun insertDisciple(id: Int, name: String) {
        tables.insert(Disciple(id = id.toString(), name = name, age = 25))
        tables.isAlive[id] = 1
        tables.statuses[id] = DiscipleStatus.IDLE
    }
}
