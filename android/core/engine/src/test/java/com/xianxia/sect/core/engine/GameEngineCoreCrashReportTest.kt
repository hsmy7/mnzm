package com.xianxia.sect.core.engine

import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.service.WallClock
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.argumentCaptor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 循环异常归因上报测试。
 *
 * 历史教训：27 次"游戏时间停止"修复中 4 次为"吞异常+重启"模式——
 * 循环 catch 只记日志、无上报、无归因，异常源从未被系统性定位。
 * 本测试守卫：循环体异常必须通过 [EngineCrashReporter] 上报且携带上下文，
 * 且 catch 块自身永不抛异常（任何状态读取失败不得杀死循环）。
 */
class GameEngineCoreCrashReportTest {

    private lateinit var core: GameEngineCore
    private lateinit var reporter: EngineCrashReporter

    @Before
    fun setUp() {
        // 纯 JVM 下 Build.MANUFACTURER 为 null，注入确定厂商（startWatchdog 依赖）
        OemPowerProfileProvider.manufacturerOverride = OemManufacturer.OTHER
        reporter = mockSmart(EngineCrashReporter::class.java)
    }

    @After
    fun tearDown() {
        core?.stopGameLoop()
        OemPowerProfileProvider.manufacturerOverride = null
    }

    @Test
    fun `game loop exception - reporter receives exception with full context`() = kotlinx.coroutines.test.runTest {
        // 触发路径：循环第一轮 deltaNs 大 → tickInternal → explorationService
        // 返回 null（显式 stub）→ for 迭代 NPE → 循环 catch → 上报
        val stateStore = createCrashingStateStore()
        core = createCore(stateStore, reporter, crashingExplorationService())

        core.startGameLoop()

        // 循环崩溃 → 上报（不吞掉）。timeout+captor：轮询等待真实循环线程首次上报
        val exceptionCaptor = argumentCaptor<Throwable>()
        val contextCaptor = argumentCaptor<Map<String, String>>()
        verify(reporter, timeout(5_000).atLeastOnce())
            .postCatchedException(exceptionCaptor.capture(), contextCaptor.capture())

        // 上下文键完整性：年/月/旬/tickCount/speed/isPaused 等必须齐备
        val ctx = contextCaptor.allValues.last()
        for (key in EXPECTED_CONTEXT_KEYS) {
            assertTrue("context must contain key '$key', got: $ctx", ctx.containsKey(key))
        }
        assertTrue("reported exception must be the loop crash", exceptionCaptor.allValues.isNotEmpty())
    }

    @Test
    fun `game loop exception - crash reporter failure does not kill loop`() = kotlinx.coroutines.test.runTest {
        val stateStore = createCrashingStateStore()
        val throwingReporter = object : EngineCrashReporter {
            override fun postCatchedException(throwable: Throwable, context: Map<String, String>) {
                error("reporter failed")
            }
        }
        core = createCore(stateStore, throwingReporter, crashingExplorationService())

        core.startGameLoop()

        // 上报失败被循环 catch 内层 try-catch 吞掉 → 循环继续运行
        // 轮询等待：循环存活即通过（原 sleep 800ms 固定等待，改条件等待防抖动）
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline && !core.isGameLoopRunning) {
            Thread.sleep(20)
        }
        assertTrue("loop must survive reporter failure", core.isGameLoopRunning)
    }

    /**
     * 构造循环必崩的 store：Fake 状态读取全真实（循环能启动推进），崩溃点由
     * 显式 stub 的 explorationService 制造——consumePendingPatrolResults 返回
     * null → for 迭代 NPE → 循环 catch → 上报。（mockSmart 对 List 返回空集合
     * 不崩，须显式 stub 恢复原 mock 默认 null 语义）
     */
    private fun createCrashingStateStore(): GameStateStore = FakeAtomicStateStore()

    /**
     * 构造循环必崩的探索服务。consumePendingPatrolResults 是 suspend 函数，
     * 须在协程上下文注册 stub（runTest 内调用本 helper）；thenAnswer 允许返回
     * null → 循环内 for 迭代 NPE。
     */
    private suspend fun crashingExplorationService(): ExplorationService {
        val svc = mockSmart(
            ExplorationService::class.java
        )
        `when`(svc.consumePendingPatrolResults()).thenAnswer { null }
        return svc
    }

    private fun createCore(
        stateStore: GameStateStore,
        reporter: EngineCrashReporter,
        explorationService: ExplorationService
    ): GameEngineCore {
        val scope = mockSmart(CoroutineScope::class.java)
        `when`(scope.coroutineContext).thenReturn(EmptyCoroutineContext)
        val scopeProvider = mockSmart(CoroutineScopeProvider::class.java)
        `when`(scopeProvider.scope).thenReturn(scope)
        return GameEngineCore(
            stateStore = stateStore,
            eventBus = mockSmart(EventBusPort::class.java),
            unifiedPerformanceMonitor = mockSmart(UnifiedPerformanceMonitor::class.java),
            systemManager = mockSmart(SystemManager::class.java),
            scopeProvider = scopeProvider,
            cultivationService = mockSmart(CultivationService::class.java),
            explorationService = explorationService,
            aiSectBeastAttackProcessor = mockSmart(AISectBeastAttackProcessor::class.java),
            gameClock = GameTimeClock(StaticTimeSource()),
            thermalController = mockSmart(ThermalController::class.java),
            thermalMonitor = mockSmart(com.xianxia.sect.core.perf.ThermalMonitor::class.java),
            spiritStoneWallet = mockSmart(SpiritStoneWallet::class.java),
            jadeSymbolService = JadeSymbolService(
                timeSource = TimeSource { 0L },
                stateStore = stateStore,
                wallClock = WallClock { 0L }
            ),
            engineCrashReporter = reporter
        )
    }

    private class StaticTimeSource : TimeSource {
        override fun elapsedRealtime(): Long = 1_000_000L
    }

    private companion object {
        val EXPECTED_CONTEXT_KEYS = setOf(
            "year", "month", "phase", "tickCount", "scene",
            "isPaused", "isSaving", "isLoading", "speed",
            "lastTickMs", "watchdogAttempts", "oem"
        )
    }
}
