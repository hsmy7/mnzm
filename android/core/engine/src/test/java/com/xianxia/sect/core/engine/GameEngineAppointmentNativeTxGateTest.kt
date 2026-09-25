package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentRegistry
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.ElderSlotType
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.usecase.ElderManagementUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * 任命/卸任 native 事务门控单测（batch-15——GameEnginePatrolNative
 * TxGateTest 同族三级降级契约守护）。
 *
 * JVM 单测环境 GameCoreBridge 恒未加载：断言 AUTHORITATIVE 稳态与 flag OFF 两
 * 模式下任命/卸任两 native 臂均降级（null）、两入口走 Kotlin 回退臂且事务外
 * 残差（Gate 登记/释放、长老槽位写入）语义不变。native 事务本身的校验链/
 * 玉符承扣语义由桌面 C++ appointment_tx_test.cpp 黄金用例守护，真机转发臂由
 * 真机验证批覆盖。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineAppointmentNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore
    private lateinit var gate: DiscipleAssignmentGate
    private lateinit var engine: GameEngine
    private lateinit var useCase: ElderManagementUseCase

    private val discipleA = "1"
    private val discipleB = "2"

    @Before
    fun setUp() {
        gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        store = FakeAtomicStateStore()
        seedDisciples()
        // 聚合流填充（UseCase 弟子存在性校验读 discipleAggregatesSnapshot——
        // FakeAtomicStateStore 无自动 assemble，测试手动播种最小聚合）
        store.discipleAggregates.value = listOf(
            aggregate(discipleA, "弟子A"),
            aggregate(discipleB, "弟子B")
        )

        val mockCore = mock<GameEngineCore>()
        // launchInScope 同步执行（仓库驻守非 suspend 入口依赖）；返回 mock Job
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        whenever(mockCore.scopeForStateIn()).thenReturn(CoroutineScope(Dispatchers.Unconfined))

        // GameEngine.assignmentGate 委托 battleFacade——槽位清理/登记残差用
        val mockBattleFacade = mock<com.xianxia.sect.core.engine.domain.battle.BattleFacade>()
        org.mockito.kotlin.whenever(mockBattleFacade.assignmentGate).thenReturn(gate)
        // updateElderSlots 生产实现 = store 写入（UseCase 回退臂写段落点）
        val mockDiscipleFacade = mock<com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade>()
        org.mockito.kotlin.whenever(mockDiscipleFacade.updateElderSlots(any())).thenAnswer { invocation ->
            val slots = invocation.getArgument<com.xianxia.sect.core.model.ElderSlots>(0)
            store.update { gameData = gameData.copy(elderSlots = slots) }
            Unit
        }
        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = GameRngManager(),
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade(mockDiscipleFacade),
            economyFacade = mockEconomyFacade(),
            battleFacade = mockBattleFacade
        )
        useCase = ElderManagementUseCase(engine, gate)
    }

    /** 构造期 Facade 访问器 stub 链（防 GameEngine 构造 NPE，对齐既有门控测试）。 */
    private fun mockCultivationFacade(
        discipleFacade: com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
    ): CultivationFacade = mock<CultivationFacade>().also {
        whenever(it.cultivationService).thenReturn(mock())
        whenever(it.discipleService).thenReturn(mock())
        whenever(it.discipleFacade).thenReturn(discipleFacade)
        val mockProductionFacade = mock<ProductionFacade>()
        whenever(mockProductionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        whenever(it.productionFacade).thenReturn(mockProductionFacade)
        val mockPC = mock<ProductionCoordinator>()
        whenever(mockPC.repository).thenReturn(mock())
        whenever(it.productionCoordinator).thenReturn(mockPC)
    }

    private fun mockEconomyFacade(): EconomyFacade = mock<EconomyFacade>().also {
        val mockInventoryFacade = mock<InventoryFacade>()
        whenever(mockInventoryFacade.inventorySystem).thenReturn(mock())
        whenever(it.inventoryFacade).thenReturn(mockInventoryFacade)
        whenever(it.mailService).thenReturn(mock())
    }

    /** 最小聚合构造（UseCase 存在性/存活校验只读 id 与 isAlive）。 */
    private fun aggregate(id: String, name: String): DiscipleAggregate =
        DiscipleAggregate(
            core = DiscipleCore(id = id, name = name).also { core ->
                core.isAlive = true
            },
            combatStats = null,
            equipment = null,
            extended = null,
            attributes = null
        )

    /** 测试弟子 A/B 播种（事务内初始化 DiscipleTables，对齐门控测试族口径）。 */
    private fun seedDisciples() {
        store.update {
            discipleTables.writeAllowed = true
            val a = discipleA.toInt()
            discipleTables.addId(a)
            discipleTables.names[a] = "弟子A"
            discipleTables.statuses[a] = DiscipleStatus.IDLE
            discipleTables.isAlive[a] = 1
            discipleTables.realms[a] = 9
            discipleTables.realmLayers[a] = 1
            val b = discipleB.toInt()
            discipleTables.addId(b)
            discipleTables.names[b] = "弟子B"
            discipleTables.statuses[b] = DiscipleStatus.IDLE
            discipleTables.isAlive[b] = 1
            discipleTables.realms[b] = 9
            discipleTables.realmLayers[b] = 1
            discipleTables.writeAllowed = false
        }
    }

    @After
    fun restoreFlag() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
    }

    // ── native 臂门控（桥未加载恒降级） ──────────────────────────

    @Test
    fun `native 臂 - AUTHORITATIVE 且桥未加载两臂均降级`() {
        assertNull(engine.tryAppointElderNative("VICE_SECT_MASTER", discipleA))
        assertNull(engine.tryDismissElderNative("VICE_SECT_MASTER"))
    }

    @Test
    fun `native 臂 - flag OFF 两臂均降级`() {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertNull(engine.tryAppointElderNative("VICE_SECT_MASTER", discipleA))
            assertNull(engine.tryDismissElderNative("VICE_SECT_MASTER"))
        }
    }

    // ── 长老任命/卸任回退臂（残差照常执行） ──────────────────────

    @Test
    fun `assignElder - 桥未加载走回退臂且残差照常`() = runTest {
        val result = useCase.assignElder(ElderSlotType.RECRUITING, discipleA)
        assertTrue("应为 Success", result is ElderManagementUseCase.ElderResult.Success)
        assertEquals(discipleA, store.latestGameData.elderSlots.recruitingElder)
        assertTrue("回退臂 confirmAssign 照常", gate.isAssigned(discipleA))

        // 顶替任命：旧长老 gate 释放（collectReplacedIds → releaseReplacedIds）
        val replaced = useCase.assignElder(ElderSlotType.RECRUITING, discipleB)
        assertTrue(replaced is ElderManagementUseCase.ElderResult.Success)
        assertEquals(discipleB, store.latestGameData.elderSlots.recruitingElder)
        assertTrue(gate.isAssigned(discipleB))
        assertFalse("被顶替者 gate 释放", gate.isAssigned(discipleA))
    }

    @Test
    fun `removeElder - 桥未加载走回退臂清空槽位并释放 gate`() = runTest {
        useCase.assignElder(ElderSlotType.VICE_SECT_MASTER, discipleA)
        assertTrue(gate.isAssigned(discipleA))

        val result = useCase.removeElder(ElderSlotType.VICE_SECT_MASTER)
        assertTrue("应为 Success", result is ElderManagementUseCase.ElderResult.Success)
        assertEquals("", store.latestGameData.elderSlots.viceSectMaster)
        assertFalse("被卸任者 gate 释放", gate.isAssigned(discipleA))
    }
}
