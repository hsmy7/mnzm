package com.xianxia.sect.core.engine

import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 引擎初始化状态进程级持有守卫测试（docs/architecture.md 待办 D-31）。
 *
 * 背景：`GameForegroundService.onDestroy` 曾调 `shutdown()`——每次退出/重进游戏
 * （含 START_STICKY 系统重建）完整重跑全部 GameSystem 的 initialize/release 循环。
 * 根治后：Service 只负责循环启停（stopGameLoop），`isInitialized` 由 @Singleton
 * 进程级持有，`initializeAll()` 在进程生命周期仅执行一次。
 *
 * 守卫契约：
 * - initialize 幂等：重复调用不重跑 initializeAll
 * - stopGameLoop 不清初始化状态：stop→initialize→start 循环时 initializeAll 仍仅一次
 * - shutdown 保留完整拆除语义：shutdown 后 initialize 重新初始化（未来登出场景）
 */
class GameEngineCoreInitializeOnceTest {

    private lateinit var core: GameEngineCore
    private lateinit var systemManager: SystemManager

    @Before
    fun setUp() {
        OemPowerProfileProvider.manufacturerOverride = OemManufacturer.OTHER
        val store = FakeAtomicStateStore()
        store.isPaused.value = true
        core = createCore(store)
    }

    @After
    fun tearDown() {
        core.stopGameLoop()
        OemPowerProfileProvider.manufacturerOverride = null
    }

    private fun createCore(stateStore: GameStateStore): GameEngineCore {
        val scope = mockSmart(CoroutineScope::class.java)
        `when`(scope.coroutineContext).thenReturn(EmptyCoroutineContext)
        val scopeProvider = mockSmart(CoroutineScopeProvider::class.java)
        `when`(scopeProvider.scope).thenReturn(scope)
        systemManager = mockSmart(SystemManager::class.java)
        return GameEngineCore(
            stateStore = stateStore,
            eventBus = mockSmart(EventBusPort::class.java),
            unifiedPerformanceMonitor = mockSmart(UnifiedPerformanceMonitor::class.java),
            systemManager = systemManager,
            scopeProvider = scopeProvider,
            cultivationService = mockSmart(CultivationService::class.java),
            explorationService = mockSmart(ExplorationService::class.java),
            aiSectBeastAttackProcessor = mockSmart(AISectBeastAttackProcessor::class.java),
            gameClock = GameTimeClock(StaticTimeSource()),
            thermalController = mockSmart(ThermalController::class.java),
            thermalMonitor = mockSmart(com.xianxia.sect.core.perf.ThermalMonitor::class.java),
            spiritStoneWallet = mockSmart(SpiritStoneWallet::class.java),
            jadeSymbolService = mockSmart(JadeSymbolService::class.java),
            engineCrashReporter = mockSmart(EngineCrashReporter::class.java)
        )
    }

    private class StaticTimeSource : TimeSource {
        override fun elapsedRealtime(): Long = 1_000_000L
    }

    @Test
    fun `initialize - 重复调用仅执行一次initializeAll`() {
        core.initialize()
        core.initialize()
        core.initialize()
        verify(systemManager, times(1)).initializeAll()
    }

    @Test
    fun `stopGameLoop - 不清初始化状态,重新initialize不重跑`() {
        core.initialize()
        core.startGameLoop()
        core.stopGameLoop()
        assertFalse("stop 后循环必须停止", core.isGameLoopRunning)

        // Service 重建场景：onStartCommand 再调 initialize → 幂等跳过
        core.initialize()
        verify(systemManager, times(1)).initializeAll()
        verify(systemManager, times(0)).releaseAll()
    }

    @Test
    fun `stop-start - 循环在未重建scope上可恢复`() {
        core.initialize()
        core.startGameLoop()
        core.stopGameLoop()
        core.startGameLoop()
        assertFalse("第二次 start 后循环应运行", !core.isGameLoopRunning)
        // 无 shutdown 的 stop→start 循环：releaseAll 全程零调用（初始化状态进程级持有）
        verify(systemManager, times(0)).releaseAll()
    }

    @Test
    fun `shutdown - 保留完整拆除语义,之后initialize重新初始化`() {
        core.initialize()
        core.startGameLoop()
        core.shutdown()
        // shutdown 后重新 initialize：允许重跑（未来登出回主菜单释放资源场景）
        core.initialize()
        verify(systemManager, times(2)).initializeAll()
        verify(systemManager, times(1)).releaseAll()
    }
}
