package com.xianxia.sect.core.engine

import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.system.WallClock
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import kotlin.coroutines.EmptyCoroutineContext

/**
 * SR-4 月变完整结算发布测试——**月变触发时序门**（方案 §4 SR-4「月副作用完成后才存」）。
 *
 * 守卫契约：
 * 1. 尾务次序 = 任务检测 → 灵石事件事务外 flush → 月变发布（发布必须是最后一件，
 *    保存侧据此保证"月副作用完整结算之后才落盘"）；
 * 2. 非月变 tick 零发布（月变分支之外的路径不得触发自动存档）；
 * 3. 通道 replay=0：无订阅者期的月变不重放（新建收集者不被陈旧事件多触发一次保存）。
 *
 * 覆盖边界（如实登记）：本批覆盖月分支**尾务段**的时序与发布语义；"C++/Kotlin 月结算
 * 本体 → 尾务"的端到端串接由结构保证（发布点在被提取的 [finalizeMonthBoundary] 末句，
 * 而该函数只在 `settleMonthNative()`/`monthSettlementExecutor` 返回后被调用），
 * 真机端到端 = pending-device（卡 §5）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineCoreMonthSettledTest {

    private lateinit var core: GameEngineCore
    private lateinit var stateStore: FakeAtomicStateStore
    private val order = mutableListOf<String>()

    @Before
    fun setUp() {
        OemPowerProfileProvider.manufacturerOverride = OemManufacturer.OTHER
        stateStore = FakeAtomicStateStore()
        core = createCore(stateStore)
    }

    @After
    fun tearDown() {
        if (::core.isInitialized) core.stopGameLoop()
        OemPowerProfileProvider.manufacturerOverride = null
    }

    @Test
    fun `month boundary tail publishes after mission check and wallet flush`() = runTest {
        val published = CompletableDeferred<Unit>()
        val collectJob = collectMonthSettled(published)

        core.missionCheck = { order += "mission" }
        core.finalizeMonthBoundary()

        withTimeout(TIMEOUT_MS) { published.await() }
        assertEquals(
            "发布必须在任务检测与灵石 flush 之后（月副作用完整结算才触发自动存档）",
            listOf("mission", "flush", "publish"),
            order
        )
        collectJob.cancel()
    }

    @Test
    fun `tick without month change publishes nothing`() = runTest {
        val collectJob = collectMonthSettled()
        runCurrent()

        core.processMonthYearChange(monthChanged = false, yearChanged = false)
        runCurrent()

        assertFalse("非月变路径不得发布月变事件", order.contains("publish"))
        collectJob.cancel()
    }

    @Test
    fun `month settled events are not replayed to a later subscriber`() = runTest {
        // 无订阅者期发布（主菜单/VM 空窗）——容量 1 DROP_OLDEST + replay=0 ⇒ 陈旧月变不复活
        core.notifyMonthSettled()
        runCurrent()

        val published = CompletableDeferred<Unit>()
        val collectJob = collectMonthSettled(published)
        runCurrent()

        assertFalse("空窗期月变事件不得重放给新订阅者", published.isCompleted)
        collectJob.cancel()
    }

    // ============================================================
    // 辅助
    // ============================================================

    /**
     * 启动月变事件收集者：收到事件即按 [COLLECT_TAG] 记账并完成 [published]。
     * `CoroutineStart.UNDISPATCHED` 强制先跑到订阅点——SharedFlow replay=0 时
     * 未订阅的 tryEmit 直接丢弃（与 [GameEngineCoreStuckResetTest] 同口径）。
     */
    private fun TestScope.collectMonthSettled(
        published: CompletableDeferred<Unit>? = null
    ): kotlinx.coroutines.Job = launch(start = CoroutineStart.UNDISPATCHED) {
        core.monthSettledEvents.first()
        order += COLLECT_TAG
        published?.complete(Unit)
    }

    private fun createCore(stateStore: GameStateStore): GameEngineCore {
        val scope = mockSmart(CoroutineScope::class.java)
        Mockito.`when`(scope.coroutineContext).thenReturn(EmptyCoroutineContext)
        val scopeProvider = mockSmart(CoroutineScopeProvider::class.java)
        Mockito.`when`(scopeProvider.scope).thenReturn(scope)
        val eventBus = mockSmart(EventBusPort::class.java)
        val wallet = mockSmart(SpiritStoneWallet::class.java)
        // 用真实注入实例而非 any() 匹配器：ArgumentMatchers.any() 返回 null，
        // 传给 Kotlin 非空形参在调用点即抛 "any(...) must not be null"
        Mockito.doAnswer {
            order += "flush"
            null
        }.`when`(wallet).flushPendingEvents(eventBus)
        return GameEngineCore(
            stateStore = stateStore,
            eventBus = eventBus,
            unifiedPerformanceMonitor = mockSmart(UnifiedPerformanceMonitor::class.java),
            systemManager = mockSmart(SystemManager::class.java),
            scopeProvider = scopeProvider,
            cultivationService = mockSmart(CultivationService::class.java),
            explorationService = mockSmart(ExplorationService::class.java),
            aiSectBeastAttackProcessor = mockSmart(AISectBeastAttackProcessor::class.java),
            gameClock = GameTimeClock(StaticTimeSource()),
            thermalController = mockSmart(ThermalController::class.java),
            thermalMonitor = mockSmart(com.xianxia.sect.core.perf.ThermalMonitor::class.java),
            spiritStoneWallet = wallet,
            jadeSymbolService = JadeSymbolService(
                timeSource = TimeSource { 0L },
                stateStore = stateStore,
                wallClock = WallClock { 0L }
            ),
            engineCrashReporter = mockSmart(EngineCrashReporter::class.java)
        )
    }

    private class StaticTimeSource : TimeSource {
        override fun elapsedRealtime(): Long = 1_000_000L
    }

    private companion object {
        const val TIMEOUT_MS = 1_000L
        const val COLLECT_TAG = "publish"
    }
}
