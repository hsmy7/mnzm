package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.service.HighFrequencyData
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner


/**
 * 验证监牢（思过崖）释放按钮的完整路径：
 * `DiscipleFacadeImpl.releaseReflectionDisciple` 清除 reflection 标记 + 置 IDLE，
 * 随后 `syncSingleDiscipleStatus` 重新推导 —— 推导不得把状态锁回 REFLECTING
 * （deriveDiscipleStatus 中 REFLECTING 是受保护状态，若 statuses 未先置 IDLE 会被无条件保持）。
 *
 * 使用 delegate mock 模式（同 DiscipleServiceCrudTest）+ 真实 DiscipleService/
 * DiscipleStatusService，端到端验证释放后状态推导不被锁回。
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleReflectionReleaseTest {

    @get:Rule val writeGuardRule = WriteGuardRule()
    private lateinit var tables: DiscipleTables
    private lateinit var mockStore: GameStateStore
    private lateinit var facade: DiscipleFacadeImpl
    private lateinit var service: DiscipleService

    @Before
    fun setUp() {
        val store = FakeAtomicStateStore()
        mockStore = store
        tables = store.discipleTables
        service = buildDiscipleService()
        facade = buildFacade()
    }

    private fun buildDiscipleService(): DiscipleService {
        // ProductionSlotRepository 是 final 类：mock 拦截依赖类加载时机（顺序敏感 flaky），
        // 用真实实例 + mockSmart 端口（getSlots() 真实返回空列表，语义与 mock 时代一致）
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
        // 真实状态推导服务：syncSingleDiscipleStatus 必须真实执行，
        // 才能验证"释放后推导不把 REFLECTING 锁回"的完整行为
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
            inventorySystem = mockSmart(com.xianxia.sect.core.engine.system.InventorySystem::class.java)
        )
    }

    private fun buildFacade(): DiscipleFacadeImpl {
        val cultivationService = mockSmart(com.xianxia.sect.core.engine.service.CultivationService::class.java)
        Mockito.`when`(cultivationService.getHighFrequencyData())
            .thenReturn(MutableStateFlow(HighFrequencyData()))

        return DiscipleFacadeImpl(
            discipleService = service,
            stateStore = mockStore,
            cultivationService = cultivationService,
            gameEngineCore = mockSmart(),
            inventorySystem = mockSmart(),
            pillManager = mockSmart(),
            assignmentGate = mockSmart(),
            discipleSlotCleanup = mockSmart(),
            lawEnforcementProcessor = mockSmart(),
            productionCoordinator = mockSmart<com.xianxia.sect.core.engine.domain.production.ProductionCoordinator>(),
        )
    }

    // ==================== 辅助方法 ====================

    private fun insertReflectingDisciple(id: Int, age: Int = 25) {
        tables.insert(Disciple(id = id.toString(), name = "弟子$id", age = age))
        tables.isAlive[id] = 1
        tables.statuses[id] = DiscipleStatus.REFLECTING
        tables.statusData[id] = mapOf(
            "reflectionStartYear" to "1",
            "reflectionEndYear" to "3"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 核心：释放按钮路径 — REFLECTING 弟子释放为 IDLE 并清除标记
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `releaseReflectionDisciple - REFLECTING disciple becomes IDLE and clears markers`() {
        insertReflectingDisciple(1, age = 25)

        facade.releaseReflectionDisciple("1")

        assertEquals("status should be IDLE", DiscipleStatus.IDLE, tables.statuses[1])
        val data = tables.statusData[1]
        assertFalse("reflectionStartYear should be cleared", data.contains("reflectionStartYear"))
        assertFalse("reflectionEndYear should be cleared", data.contains("reflectionEndYear"))
    }

    @Test
    fun `releaseReflectionDisciple - released disciple stays released after status re-derivation`() {
        insertReflectingDisciple(1, age = 25)

        facade.releaseReflectionDisciple("1")

        // releaseReflectionDisciple 内部已触发一次推导；再次手动全量推导验证不被锁回 REFLECTING
        service.syncSingleDiscipleStatus("1")
        service.syncSingleDiscipleStatus("1")

        assertEquals(
            "re-derivation must not lock status back to REFLECTING",
            DiscipleStatus.IDLE, tables.statuses[1]
        )
    }

    @Test
    fun `releaseReflectionDisciple - released disciple returns to slot status when assigned`() {
        insertReflectingDisciple(1, age = 25)
        // 思过前弟子在灵脉矿槽位（被捕时未清槽位，释放后应推导回 MINING）
        mockStore.update {
            gameData = gameData.copy(
                spiritMineSlots = listOf(
                    com.xianxia.sect.core.model.SpiritMineSlot(
                        index = 0, discipleId = "1", discipleName = "弟子1"
                    )
                )
            )
        }

        facade.releaseReflectionDisciple("1")

        assertEquals(
            "released disciple should be derived back to MINING slot status",
            DiscipleStatus.MINING, tables.statuses[1]
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 边界：死亡弟子不释放 / 无效 id 不崩溃
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `releaseReflectionDisciple - dead disciple is derived to DEAD not released`() {
        insertReflectingDisciple(1, age = 25)
        tables.isAlive[1] = 0

        facade.releaseReflectionDisciple("1")

        // release 的 update 保护死亡弟子（不改状态），但随后的 sync 推导按死亡优先归一到 DEAD。
        // 监牢列表数据源为 aliveDisciples，死亡弟子不会出现在释放按钮上，此用例守护推导语义。
        assertEquals("dead disciple should be derived to DEAD", DiscipleStatus.DEAD, tables.statuses[1])
    }

    @Test
    fun `releaseReflectionDisciple - invalid id ignored`() {
        insertReflectingDisciple(1, age = 25)

        facade.releaseReflectionDisciple("abc")
        facade.releaseReflectionDisciple("999")

        assertEquals("invalid ids should not affect disciple", DiscipleStatus.REFLECTING, tables.statuses[1])
    }
}
