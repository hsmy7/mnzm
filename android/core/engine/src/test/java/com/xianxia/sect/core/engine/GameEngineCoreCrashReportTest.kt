package com.xianxia.sect.core.engine

import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
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
        reporter = mock(EngineCrashReporter::class.java)
    }

    @After
    fun tearDown() {
        core?.stopGameLoop()
        OemPowerProfileProvider.manufacturerOverride = null
    }

    @Test
    fun `game loop exception - reporter receives exception with full context`() {
        // 触发路径：循环第一轮 deltaNs 大 → tickInternal → explorationService
        // 返回 null（mock 默认）→ for 迭代 NPE → 循环 catch → 上报
        val stateStore = createCrashingStateStore()
        core = createCore(stateStore, reporter)

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
    fun `game loop exception - crash reporter failure does not kill loop`() {
        val stateStore = createCrashingStateStore()
        val throwingReporter = object : EngineCrashReporter {
            override fun postCatchedException(throwable: Throwable, context: Map<String, String>) {
                error("reporter failed")
            }
        }
        core = createCore(stateStore, throwingReporter)

        core.startGameLoop()

        // 上报失败被循环 catch 内层 try-catch 吞掉 → 循环继续运行
        Thread.sleep(800)
        assertTrue("loop must survive reporter failure", core.isGameLoopRunning)
    }

    /** 构造循环必崩的 stateStore：状态读取正常（真实 StateFlow），explorationService 空结果触发 NPE */
    private fun createCrashingStateStore(): GameStateStore {
        val store = mock(GameStateStore::class.java)
        `when`(store.isPaused).thenReturn(MutableStateFlow(false))
        `when`(store.isLoading).thenReturn(MutableStateFlow(false))
        `when`(store.isSaving).thenReturn(MutableStateFlow(false))
        `when`(store.gameDataSnapshot).thenReturn(GameData())
        `when`(store.bootPhase).thenReturn(MutableStateFlow(BootPhase.UNINITIALIZED))
        `when`(store.runState).thenReturn(MutableStateFlow(RunState.IDLE))
        return store
    }

    private fun createCore(stateStore: GameStateStore, reporter: EngineCrashReporter): GameEngineCore {
        val scope = mock(CoroutineScope::class.java)
        `when`(scope.coroutineContext).thenReturn(EmptyCoroutineContext)
        val scopeProvider = mock(CoroutineScopeProvider::class.java)
        `when`(scopeProvider.scope).thenReturn(scope)
        return GameEngineCore(
            stateStore = stateStore,
            eventBus = mock(EventBusPort::class.java),
            unifiedPerformanceMonitor = mock(UnifiedPerformanceMonitor::class.java),
            systemManager = mock(SystemManager::class.java),
            scopeProvider = scopeProvider,
            cultivationService = mock(CultivationService::class.java),
            explorationService = mock(com.xianxia.sect.core.engine.domain.exploration.ExplorationService::class.java),
            aiSectBeastAttackProcessor = mock(AISectBeastAttackProcessor::class.java),
            gameClock = GameTimeClock(StaticTimeSource()),
            thermalController = mock(ThermalController::class.java),
            thermalMonitor = mock(com.xianxia.sect.core.perf.ThermalMonitor::class.java),
            spiritStoneWallet = mock(SpiritStoneWallet::class.java),
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
