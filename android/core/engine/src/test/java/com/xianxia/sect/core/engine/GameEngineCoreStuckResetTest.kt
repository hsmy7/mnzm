package com.xianxia.sect.core.engine

import com.xianxia.sect.core.concurrent.ThermalController
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 看门狗病理复位测试（T12 2026-08-05）。
 *
 * 守卫契约：
 * - isSaving/isLoading 卡住超 90s（SAVE_LOAD_STUCK_TIMEOUT_MS）→ 发用户可见事件 + 复位
 * - 未超时 → 不发事件
 * - forceResetStuckStates 直调（onCleared 正常清理路径）→ 不发事件（不可弹窗）
 * - 注册的 activeLoadJob 被看门狗取消
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineCoreStuckResetTest {

    private lateinit var core: GameEngineCore
    private lateinit var stateStore: GameStateStore

    private val initialTimeMs = 1_000_000L
    private val stuckTimeoutMs = 90_000L

    @Before
    fun setUp() {
        OemPowerProfileProvider.manufacturerOverride = OemManufacturer.OTHER
        stateStore = mock(GameStateStore::class.java)
        core = createCore(stateStore)
    }

    @After
    fun tearDown() {
        core.stopGameLoop()
        OemPowerProfileProvider.manufacturerOverride = null
    }

    @Test
    fun `saving stuck over threshold emits event and resets flags`() = runTest {
        val (event, _) = collectFirstEvent()

        core.checkAndResetStuckStates(isSaving = true, isLoading = false, nowMs = initialTimeMs)
        core.checkAndResetStuckStates(isSaving = true, isLoading = false, nowMs = initialTimeMs + stuckTimeoutMs + 1)

        assertTrue("事件应说明保存超时，实际: ${event.await()}", event.await().contains("保存操作超时"))
        verify(stateStore).setSavingDirect(false)
        verify(stateStore).setLoadingDirect(false)
    }

    @Test
    fun `loading stuck over threshold emits event`() = runTest {
        val (event, _) = collectFirstEvent()

        core.checkAndResetStuckStates(isSaving = false, isLoading = true, nowMs = initialTimeMs)
        core.checkAndResetStuckStates(isSaving = false, isLoading = true, nowMs = initialTimeMs + stuckTimeoutMs + 1)

        assertTrue("事件应说明读档超时，实际: ${event.await()}", event.await().contains("读档操作超时"))
    }

    @Test
    fun `flag released before threshold emits no event`() = runTest {
        val (event, collector) = collectFirstEvent()

        core.checkAndResetStuckStates(isSaving = true, isLoading = false, nowMs = initialTimeMs)
        // 标志释放（isSaving=false 重置起始时间）
        core.checkAndResetStuckStates(isSaving = false, isLoading = false, nowMs = initialTimeMs + 10_000)
        core.checkAndResetStuckStates(isSaving = true, isLoading = false, nowMs = initialTimeMs + 20_000)

        assertNoEventEmitted(event)
        collector.cancel()
    }

    @Test
    fun `direct forceResetStuckStates call emits nothing`() = runTest {
        // onCleared 正常清理路径：不可弹窗
        val (event, collector) = collectFirstEvent()
        core.forceResetStuckStates()

        assertNoEventEmitted(event)
        collector.cancel()
    }

    @Test
    fun `registered active job cancelled on watchdog reset`() = runTest {
        val job = mock(kotlinx.coroutines.Job::class.java)
        core.registerActiveLoadJob(job)

        core.checkAndResetStuckStates(isSaving = true, isLoading = false, nowMs = initialTimeMs)
        core.checkAndResetStuckStates(isSaving = true, isLoading = false, nowMs = initialTimeMs + stuckTimeoutMs + 1)

        verify(job).cancel()
    }

    @Test
    fun `below threshold keeps saving flag untouched`() {
        core.checkAndResetStuckStates(isSaving = true, isLoading = false, nowMs = initialTimeMs)
        core.checkAndResetStuckStates(isSaving = true, isLoading = false, nowMs = initialTimeMs + stuckTimeoutMs - 1)

        verify(stateStore, never()).setSavingDirect(false)
    }

    // ============================================================
    // 辅助
    // ============================================================

    /**
     * 先启动事件收集者再触发超时——SharedFlow 无订阅者时 tryEmit 会丢弃
     * （replay=0，extraBufferCapacity 仅对有订阅者生效）。
     * 返回 (事件槽, 收集协程 Job)——无事件用例须在结尾取消收集协程。
     */
    private fun TestScope.collectFirstEvent(): Pair<CompletableDeferred<String>, kotlinx.coroutines.Job> {
        val received = CompletableDeferred<String>()
        val job = launch {
            received.complete(core.stuckResetEvents.first())
        }
        // TestDispatcher 懒执行：强制收集协程运行到订阅点，
        // 否则后续同步 tryEmit 时订阅未激活会被丢弃
        runCurrent()
        return received to job
    }

    /** 断言事件通道在超时窗口内无事件 */
    @Suppress("SwallowedException") // 测试断言：超时取消即"无事件"预期达成，异常本身无需记录
    private suspend fun assertNoEventEmitted(event: CompletableDeferred<String>) {
        val emitted = try {
            withTimeout(100) { event.await() }
            true
        } catch (e: TimeoutCancellationException) {
            false
        }
        assertTrue("不应发出看门狗复位事件", !emitted)
    }

    private fun createCore(stateStore: GameStateStore): GameEngineCore {
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
            jadeSymbolService = JadeSymbolService(
                timeSource = TimeSource { 0L },
                stateStore = stateStore,
                wallClock = WallClock { 0L }
            ),
            engineCrashReporter = mock(EngineCrashReporter::class.java)
        )
    }

    private class StaticTimeSource : TimeSource {
        override fun elapsedRealtime(): Long = 1_000_000L
    }
}
