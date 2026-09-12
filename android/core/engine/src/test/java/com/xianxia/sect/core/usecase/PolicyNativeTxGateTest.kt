package com.xianxia.sect.core.usecase

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.FakeEngineContextDispatcher
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.exploration.ExplorationFacade
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.service.ProductionProcessor
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import kotlinx.coroutines.SupervisorJob
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.state.WriteGuardRule
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.coroutines.EmptyCoroutineContext

/**
 * PolicyNativeTxGateTest — 政策开关域 native 臂门控降级守卫（batch-18b）。
 *
 * 守护契约（双实现并行契约）：
 * - 降级契约：`NativeEngineFlag` OFF / AUTHORITATIVE 但 JVM 无生产 .so
 *   （`GameCoreBridge` 未加载）→ `GOV_POLICY_TOGGLE_TX` /
 *   `GOV_OPEN_RECRUITMENT_TOGGLE_TX` / `GOV_SPIRIT_MINE_BOOST_TOGGLE_TX`
 *   三臂均回退 Kotlin 原实现，**回退臂语义与下沉前逐字一致**（政策置位、
 *   首月扣费、激活计数、失败文案）。
 * - 失败零写入：灵石不足 → `ToggleResult.Error` 且政策位/计数/余额三者
 *   均不变（判定先于扣费）。
 * - 13.3 🔴 红线：生产类政策（炼丹/锻造/灵药/灵泉）开启后必须触发
 *   `checkpointAllProduction()`（经 `ProductionProcessor.getSlots()`
 *   可观测点断言）；非生产类政策不得触发。
 *
 * 镜像服务 null 降级（`stateSyncService` 可空局部守卫，handover findings 13）
 * 与本文件同模块的 `RecruitNativeTxGateTest` / `BuildingNativeTxGateTest`
 * 同款结构（本用例用真实 `GameEngineCore` 以走通 `withEngineContext`，
 * 故 sync 恒非空——该守卫由同族测试与代码结构共同守护）。
 *
 * C++ 事务本身的判定链语义由桌面 GTest `government_tx_test.cpp` 黄金用例
 * 逐位守护（含 19 政策字段映射、生产类 4 真 / 15 假矩阵）。
 */
@Suppress("DEPRECATION") // 测试需访问 GameData 镜像列（spiritMineLastSettledMonth 等）
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class PolicyNativeTxGateTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private lateinit var store: FakeAtomicStateStore
    private lateinit var core: GameEngineCore
    private lateinit var engine: GameEngine
    private lateinit var cultivationService: CultivationService
    private lateinit var productionSlotRepository: ProductionSlotRepository
    private lateinit var useCase: SectPolicyToggleUseCase

    @Before
    fun setUp() {
        store = FakeAtomicStateStore()
        core = createCore()
        // 走通真实 withEngineContext（真源 gameDispatcher 默认在引擎线程池——
        // 测试置 Unconfined 内联执行，避免跨线程等待）
        core.gameDispatcher = Dispatchers.Unconfined

        // **真实 repository 的 spy**：Mockito 对纯 mock 的 `getSlots()` 返回值校验
        // 与同名属性 getter（`val slots: StateFlow<...>`）反射匹配冲突，任何
        // mock/doReturn 组合都触发 "getSlots() should return StateFlow"。
        // spy(真实实例) 让 `getSlots()` 走真实语义（`_slots.value` = 空列表 →
        // checkpoint 路径早退），同时保留 `verify(repository).getSlots()` 的
        // 可观测点（13.3 红线断言：生产类政策开启必须触发 checkpoint）。
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        productionSlotRepository = spy(
            ProductionSlotRepository(
                dao = mockSmart(ProductionSlotDataPort::class.java),
                configService = mockSmart(BuildingConfigService::class.java),
                scopeProvider = object : CoroutineScopeProvider {
                    override val scope: CoroutineScope = repositoryScope
                    override val ioScope: CoroutineScope = repositoryScope
                }
            )
        )
        val processor = mock(ProductionProcessor::class.java)
        whenever(processor.stateStore).thenReturn(store)
        whenever(processor.productionSlotRepository).thenReturn(productionSlotRepository)
        whenever(cultivationService.productionProcessor).thenReturn(processor)

        val cultivationFacade = mock(CultivationFacade::class.java)
        whenever(cultivationFacade.cultivationService).thenReturn(cultivationService)
        // GameEngine 构造即读 productionSlots StateFlow（batch-17 起）——
        // 未 stub 会以 mock 默认 null 触发构造期 NPE
        val productionFacade = mock(
            com.xianxia.sect.core.engine.domain.production.ProductionFacade::class.java
        )
        whenever(productionFacade.productionSlots)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        whenever(cultivationFacade.productionFacade).thenReturn(productionFacade)

        engine = GameEngine(
            gameEngineCore = core,
            engineContextDispatcher = FakeEngineContextDispatcher(),
            stateStore = store,
            gameRngManager = mock(GameRngManager::class.java),
            explorationFacade = mock(ExplorationFacade::class.java),
            cultivationFacade = cultivationFacade,
            economyFacade = mock(EconomyFacade::class.java),
            battleFacade = mock(BattleFacade::class.java)
        )
        useCase = SectPolicyToggleUseCase(
            gameEngine = engine,
            spiritStoneWallet = SpiritStoneWallet(
                stateStore = store,
                ledger = SpiritStoneLedger(),
                eventBus = mock(EventBus::class.java)
            )
        )
    }

    @After
    fun restoreFlag() {
        NativeEngineFlag.mode = NativeEngineFlag.Mode.AUTHORITATIVE
    }

    /** 最小真实 GameEngineCore（其余依赖 mock；`withEngineContext` 走真实实现）。 */
    private fun createCore(): GameEngineCore {
        val scope = mock(CoroutineScope::class.java)
        whenever(scope.coroutineContext).thenReturn(EmptyCoroutineContext)
        val scopeProvider = mock(CoroutineScopeProvider::class.java)
        whenever(scopeProvider.scope).thenReturn(scope)
        cultivationService = mock(CultivationService::class.java)
        return GameEngineCore(
            stateStore = store,
            eventBus = mock(EventBusPort::class.java),
            unifiedPerformanceMonitor = mock(UnifiedPerformanceMonitor::class.java),
            systemManager = mock(SystemManager::class.java),
            scopeProvider = scopeProvider,
            cultivationService = cultivationService,
            explorationService = mock(ExplorationService::class.java),
            aiSectBeastAttackProcessor = mock(AISectBeastAttackProcessor::class.java),
            gameClock = GameTimeClock(object : TimeSource {
                override fun elapsedRealtime(): Long = 1_000_000L
            }),
            thermalController = mock(ThermalController::class.java),
            thermalMonitor = mock(ThermalMonitor::class.java),
            spiritStoneWallet = mock(SpiritStoneWallet::class.java),
            jadeSymbolService = mock(JadeSymbolService::class.java)
        )
    }

    private fun setStones(amount: Long) {
        store.update { gameData = gameData.copy(spiritStones = amount) }
    }

    private fun counter(): Long =
        store.gameDataSnapshot.guideCounters[GuideCounterKeys.POLICY_ACTIVATED] ?: -1L

    // ── 降级契约：flag OFF（Kotlin 回退臂）────────────────────────────

    @Test
    fun `flag OFF free policy falls back and keeps kotlin semantics`() = runTest {
        store.update { gameData = gameData.copy(gameYear = 1, gameMonth = 2) }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val result = useCase.toggleFrugality()
            assertEquals(SectPolicyToggleUseCase.ToggleResult.Success, result)
        }
        val gd = store.gameDataSnapshot
        assertTrue("免费政策回退臂应置位", gd.sectPolicies.frugality)
        assertEquals("激活计数 +1（Kotlin 臂语义）", 1L, counter())
    }

    @Test
    fun `flag OFF paid policy deducts first month cost`() = runTest {
        val cost = GameConfig.PolicyConfig.ENHANCED_SECURITY_MONTHLY
        setStones(cost + 500L)
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            useCase.toggleEnhancedSecurity()
        }
        val gd = store.gameDataSnapshot
        assertTrue(gd.sectPolicies.enhancedSecurity)
        assertEquals("扣首月费用后余额", 500L, gd.spiritStones)
        assertEquals(1L, counter())
    }

    @Test
    fun `flag OFF paid policy with insufficient stones errors and writes nothing`() = runTest {
        val cost = GameConfig.PolicyConfig.ENHANCED_SECURITY_MONTHLY
        setStones(cost - 1L)
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val result = useCase.toggleEnhancedSecurity()
            assertTrue(result is SectPolicyToggleUseCase.ToggleResult.Error)
            assertEquals(
                "灵石不足${cost}，无法开启政策",
                (result as SectPolicyToggleUseCase.ToggleResult.Error).message
            )
        }
        val gd = store.gameDataSnapshot
        assertFalse("失败零写入：政策位不变", gd.sectPolicies.enhancedSecurity)
        assertEquals("失败零写入：计数不变", -1L, counter())
        assertEquals("失败零写入：余额不变", cost - 1L, gd.spiritStones)
    }

    @Test
    fun `flag OFF disable path clears flag without touching counter`() = runTest {
        store.update {
            gameData = gameData.copy(
                sectPolicies = gameData.sectPolicies.copy(frugality = true),
                guideCounters = gameData.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to 7L)
            )
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            useCase.toggleFrugality()
        }
        val gd = store.gameDataSnapshot
        assertFalse("关闭路径仅翻转布尔", gd.sectPolicies.frugality)
        assertEquals("关闭不计激活", 7L, counter())
    }

    // ── 降级契约：AUTHORITATIVE 但 JVM 无生产 .so（桥未加载）──────────

    @Test
    fun `AUTHORITATIVE without native bridge falls back to kotlin arm`() = runTest {
        setStones(10_000L)
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.AUTHORITATIVE) {
            val result = useCase.toggleEnhancedSecurity()
            assertEquals(SectPolicyToggleUseCase.ToggleResult.Success, result)
        }
        val gd = store.gameDataSnapshot
        assertTrue("桥未加载 → tryExecuteNative 返回 null → 回退臂置位", gd.sectPolicies.enhancedSecurity)
        assertEquals(
            "回退臂扣费语义不变",
            10_000L - GameConfig.PolicyConfig.ENHANCED_SECURITY_MONTHLY,
            gd.spiritStones
        )
        assertEquals(1L, counter())
    }

    // ── 广纳门徒（GOV_OPEN_RECRUITMENT_TOGGLE_TX）─────────────────────

    @Test
    fun `open recruitment enable deducts fixed cost and records paid month`() = runTest {
        val cost = GameConfig.PolicyConfig.OPEN_RECRUITMENT_COST
        setStones(cost)
        store.update { gameData = gameData.copy(gameYear = 2, gameMonth = 5) }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertEquals(SectPolicyToggleUseCase.ToggleResult.Success, useCase.toggleOpenRecruitment())
        }
        val gd = store.gameDataSnapshot
        assertTrue(gd.sectPolicies.openRecruitment)
        assertEquals(0L, gd.spiritStones)
        assertEquals("付费月 = 绝对月（年*12+月）", 2 * 12 + 5, gd.openRecruitmentLastPaidMonth)
        assertEquals(1L, counter())
    }

    @Test
    fun `open recruitment insufficient stones errors and writes nothing`() = runTest {
        val cost = GameConfig.PolicyConfig.OPEN_RECRUITMENT_COST
        setStones(cost - 1L)
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            val result = useCase.toggleOpenRecruitment()
            assertTrue(result is SectPolicyToggleUseCase.ToggleResult.Error)
            assertEquals(
                "灵石不足${cost}，无法开启广纳门徒",
                (result as SectPolicyToggleUseCase.ToggleResult.Error).message
            )
        }
        val gd = store.gameDataSnapshot
        assertFalse(gd.sectPolicies.openRecruitment)
        assertEquals(cost - 1L, gd.spiritStones)
        assertEquals(-1L, counter())
    }

    // ── 灵矿增产（GOV_SPIRIT_MINE_BOOST_TOGGLE_TX，免费）──────────────

    @Test
    fun `spirit mine boost enable bumps counter and settles current month`() = runTest {
        store.update { gameData = gameData.copy(gameYear = 3, gameMonth = 4) }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertEquals(SectPolicyToggleUseCase.ToggleResult.Success, useCase.toggleSpiritMineBoost())
        }
        val gd = store.gameDataSnapshot
        assertTrue(gd.sectPolicies.spiritMineBoost)
        assertEquals("结算月戳推前到当前绝对月", 3 * 12 + 4, gd.spiritMineLastSettledMonth)
        assertEquals(1L, counter())
    }

    @Test
    fun `spirit mine boost disable keeps counter and settle month`() = runTest {
        store.update {
            gameData = gameData.copy(
                gameYear = 3, gameMonth = 4,
                sectPolicies = gameData.sectPolicies.copy(spiritMineBoost = true),
                spiritMineLastSettledMonth = 5,
                guideCounters = gameData.guideCounters + (GuideCounterKeys.POLICY_ACTIVATED to 9L)
            )
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            useCase.toggleSpiritMineBoost()
        }
        val gd = store.gameDataSnapshot
        assertFalse(gd.sectPolicies.spiritMineBoost)
        assertEquals("关闭不动结算月戳", 5, gd.spiritMineLastSettledMonth)
        assertEquals("关闭不计激活", 9L, counter())
    }

    // ── 13.3 🔴 生产类政策 checkpoint 联动 ────────────────────────────

    @Test
    fun `production class policy enable triggers checkpointAllProduction`() = runTest {
        setStones(GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_MONTHLY)
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertEquals(SectPolicyToggleUseCase.ToggleResult.Success, useCase.toggleAlchemyIncentive())
        }
        assertTrue(store.gameDataSnapshot.sectPolicies.alchemyIncentive)
        // checkpointAllProduction → ProductionProcessor.recalculateAllCompletionMonths
        // → productionSlotRepository.getSlots()：唯一可观测点
        verify(productionSlotRepository).getSlots()
    }

    @Test
    fun `non production policy enable does not trigger checkpointAllProduction`() = runTest {
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertEquals(SectPolicyToggleUseCase.ToggleResult.Success, useCase.toggleFrugality())
        }
        assertTrue(store.gameDataSnapshot.sectPolicies.frugality)
        verify(productionSlotRepository, never()).getSlots()
    }

    @Test
    fun `disabling production class policy does not trigger checkpointAllProduction`() = runTest {
        store.update {
            gameData = gameData.copy(sectPolicies = gameData.sectPolicies.copy(herbCultivation = true))
        }
        NativeEngineFlag.withMode(NativeEngineFlag.Mode.OFF) {
            assertEquals(SectPolicyToggleUseCase.ToggleResult.Success, useCase.toggleHerbCultivation())
        }
        assertFalse(store.gameDataSnapshot.sectPolicies.herbCultivation)
        verify(productionSlotRepository, never()).getSlots()
    }
}
