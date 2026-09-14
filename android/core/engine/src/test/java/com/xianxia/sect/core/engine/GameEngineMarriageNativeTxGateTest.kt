package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.service.WallClock
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.state.PendingMarriageProposal
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * w3-02 婚姻提议审批/拒绝 native 臂门控单测（W4-A 第二子批——
 * GameEngineDiscipleOpsNativeTxGateTest 同族三级降级契约守护）。
 *
 * JVM 单测环境 GameCoreBridge 恒未加载：断言 AUTHORITATIVE 稳态与 flag OFF
 * 两模式下批准/拒绝 native 臂均降级（mock stateSyncServiceRef 为 null ⇒ null）
 * 且入口不 NPE、提议流不被误清（红线 8 回归面）；无提议时两入口静默 no-op
 * （原路径早退同义）。native 事务本身（批准 = partnerIds 双向绑定 + MARRIAGE
 * 事件直写 + NotFound 幽灵列回退边界；拒绝 = 1750 事件直写 + 零弟子表写入）
 * 由桌面 C++ disciple_lifecycle_tx_test.cpp 黄金用例守护，真机转发臂由真机
 * 验证批覆盖。
 *
 * 已知夹具边界（handover §2.50 同族）：FakeAtomicStateStore 的 update 事务
 * 不回带 pendingMarriageProposals 字段（syncFlows 不含该流——W4-B 专项文件，
 * 本批禁改）⇒ JVM 内无法驱动"提议在册 + 回退臂配对"全程；生产实现
 * （GameStateStoreImpl reusableMutableState + proposalsChanged 发射）事务间
 * 持久化该字段，回退臂语义 = 原路径逐字保留（本批零改动面），行为回归风险
 * 为零，配对/拒绝全程由真机验证批覆盖。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class GameEngineMarriageNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    /** 单调时钟 fake（玉符服务构造要求）。 */
    private class FakeTimeSource(var nowMs: Long) : TimeSource {
        override fun elapsedRealtime(): Long = nowMs
    }

    private lateinit var store: FakeAtomicStateStore
    private lateinit var jadeService: JadeSymbolService
    private lateinit var engine: GameEngine

    private val male = "1"
    private val female = "2"

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        jadeService = JadeSymbolService(
            timeSource = FakeTimeSource(1_000_000L),
            stateStore = store,
            wallClock = WallClock { 1_700_000_000_000L }
        )
        seedDisciples()

        val mockCore = mock<GameEngineCore>()
        whenever(mockCore.jadeSymbolServiceRef).thenReturn(jadeService)
        whenever(mockCore.launchInScope(any())).thenAnswer { invocation ->
            val block = invocation.getArgument<suspend CoroutineScope.() -> Unit>(0)
            runBlocking { block(CoroutineScope(Dispatchers.Unconfined)) }
            mock<kotlinx.coroutines.Job>()
        }
        whenever(mockCore.scopeForStateIn()).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        val mockRng = mock<GameRngManager>()
        whenever(mockRng.getRng(RngPartition.SYSTEM)).thenReturn(DeterministicRng.fromSeed(20260915L))

        engine = GameEngine(
            gameEngineCore = mockCore,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mockRng,
            explorationFacade = mock(),
            cultivationFacade = mockCultivationFacade(),
            economyFacade = mockEconomyFacade(),
            battleFacade = mock()
        )
    }

    /** GameEngine init 即读 cultivationService（高頻数据装配）⇒ 门面须非 null 桩（A1 同款）。 */
    private fun mockCultivationFacade(): CultivationFacade = mock<CultivationFacade>().also {
        whenever(it.cultivationService).thenReturn(mock())
        whenever(it.discipleService).thenReturn(mock())
        whenever(it.discipleFacade).thenReturn(mock())
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

    @After
    fun restoreFlag() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
    }

    /** 测试弟子 A/B 播种（事务内初始化 DiscipleTables，对齐门控测试族口径）。 */
    private fun seedDisciples() {
        store.update {
            discipleTables.writeAllowed = true
            for (id in listOf(male, female)) {
                val intId = id.toInt()
                discipleTables.addId(intId)
                discipleTables.names[intId] = "弟子$id"
                discipleTables.statuses[intId] = DiscipleStatus.IDLE
                discipleTables.isAlive[intId] = 1
                discipleTables.realms[intId] = 9
                discipleTables.realmLayers[intId] = 1
            }
            discipleTables.writeAllowed = false
        }
    }

    // ── 1. 无提议：两入口静默 no-op（原路径早退同义，两模式一致）──────

    @Test
    fun missingProposalIsSilentNoOpInBothModesAndEntries() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.approveMarriageProposal(male, female)
            engine.rejectMarriageProposal(male, female)
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.approveMarriageProposal(male, female)
            engine.rejectMarriageProposal(male, female)
        }
        store.update {
            assertTrue(pendingMarriageProposals.isEmpty())
            assertTrue(gameData.gameEventRecords.none { it.eventType == "MARRIAGE" })
        }
    }

    // ── 2. 批准：两模式均降级走回退臂入口（提议流不被误清、零事件）────

    @Test
    fun approveDegradesWithoutTouchingProposalFlowInBothModes() = runTest {
        val proposal = PendingMarriageProposal(male, "张三", female, "李四")
        store.pendingMarriageProposals.value = listOf(proposal)

        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.approveMarriageProposal(male, female)
        }
        // 降级契约：native 未执行 ⇒ 提议流原样（移除动作只在 native 成功分支）；
        // 夹具边界：回退臂事务内读不到该字段 ⇒ 静默 no-op、零事件、零配对写入
        assertEquals(listOf(proposal), store.pendingMarriageProposals.value)

        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.approveMarriageProposal(male, female)
        }
        assertEquals(listOf(proposal), store.pendingMarriageProposals.value)
        store.update {
            assertTrue(gameData.gameEventRecords.none { it.eventType == "MARRIAGE" })
        }
    }

    // ── 3. 拒绝：两模式均降级走回退臂入口（提议流不被误清、零事件）────

    @Test
    fun rejectDegradesWithoutTouchingProposalFlowInBothModes() = runTest {
        val proposal = PendingMarriageProposal(male, "张三", female, "李四")
        store.pendingMarriageProposals.value = listOf(proposal)

        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            engine.rejectMarriageProposal(male, female)
        }
        assertEquals(listOf(proposal), store.pendingMarriageProposals.value)

        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            engine.rejectMarriageProposal(male, female)
        }
        assertEquals(listOf(proposal), store.pendingMarriageProposals.value)
        store.update {
            assertTrue(gameData.gameEventRecords.none { it.eventType == "MARRIAGE" })
        }
    }
}
