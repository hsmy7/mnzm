package com.xianxia.sect.core.engine

import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.engine.monitor.StallVerdict
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.service.WallClock
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import kotlin.coroutines.EmptyCoroutineContext


/**
 * 看门狗指数退避算法 + 自愈判据动作单元测试。
 *
 * 退避部分测试 [GameEngineCore.computeWatchdogBackoff] 的退避计算逻辑：
 * - tick 停滞时退避间隔翻倍
 * - 退避间隔上限截断
 * - tick 恢复后重置为基础间隔
 *
 * 自愈部分测试 [GameEngineCore.handleWatchdogVerdict] 的判据动作：
 * - FakeRunDetected：speed=0 直接恢复 1x；time 冻结走换线程恢复
 * - LoopStalled：换线程恢复（60s 限频）
 * - StalePauseDetected：清锁 + 恢复 + 重启循环
 * - PausedByOwner/Healthy：无动作（用户暂停永不自动恢复）
 */
class GameEngineCoreWatchdogTest {

    private lateinit var core: GameEngineCore
    private lateinit var gameClock: GameTimeClock
    private lateinit var pausedFlow: MutableStateFlow<Boolean>
    private lateinit var fakeTime: FakeTimeSource
    /** D-08 接线验证：start/stop 时机（startGameLoop → start，stopGameLoop → stop） */
    private lateinit var thermalMonitor: ThermalMonitor

    @Before
    fun setUp() {
        OemPowerProfileProvider.manufacturerOverride = OemManufacturer.OTHER
        pausedFlow = MutableStateFlow(false)
        fakeTime = FakeTimeSource(now = 1_000_000L)
        gameClock = GameTimeClock(fakeTime)
        core = spy(createCore(createStateStore(), gameClock))
        // 2026-08-08：performWatchdogRecovery 对被拒的恢复回滚 60s 限频预算。
        // 本测试 core 未 start（phase=STOPPED），真实 emergency 会因 CAS 被拒
        // 返回 false 并回滚预算 → 限频测试失效。stub 为"恢复成功"语义
        //（performWatchdogRecovery 的调用次数验证不受 stub 影响）
        org.mockito.Mockito.doReturn(true).`when`(core).emergencyRestartGameLoop()
    }

    @After
    fun tearDown() {
        core.stopGameLoop()
        OemPowerProfileProvider.manufacturerOverride = null
    }

    // ── 自愈判据动作 ──

    @Test
    fun `handleWatchdogVerdict - fake run with speed zero restores 1x without touching loop`() {
        gameClock.setSpeed(0)

        core.handleWatchdogVerdict(StallVerdict.FakeRunDetected)

        assertEquals("speed must restore to 1x", 1, gameClock.speed)
        verify(core, never()).emergencyRestartGameLoop()
    }

    @Test
    fun `handleWatchdogVerdict - fake run with frozen time triggers new-thread recovery`() {
        core.handleWatchdogVerdict(StallVerdict.FakeRunDetected)

        verify(core, times(1)).emergencyRestartGameLoop()
    }

    @Test
    fun `handleWatchdogVerdict - loop stalled triggers new-thread recovery`() {
        core.handleWatchdogVerdict(StallVerdict.LoopStalled)

        verify(core, times(1)).emergencyRestartGameLoop()
    }

    @Test
    fun `handleWatchdogVerdict - recovery throttled within 60s`() {
        core.handleWatchdogVerdict(StallVerdict.LoopStalled)
        verify(core, times(1)).emergencyRestartGameLoop()

        // 30s 后再次触发：被 60s 限频拦截
        fakeTime.advanceBy(30_000L)
        core.handleWatchdogVerdict(StallVerdict.LoopStalled)
        verify(core, times(1)).emergencyRestartGameLoop()

        // 再 60s 后：限频窗口已过，恢复执行
        fakeTime.advanceBy(60_000L)
        core.handleWatchdogVerdict(StallVerdict.LoopStalled)
        verify(core, times(2)).emergencyRestartGameLoop()
    }

    @Test
    fun `handleWatchdogVerdict - stale pause self-heals lock without force restarting loop (V5)`() {
        core.pauseForSecretRealm()
        assertTrue("pause lock should be held", core.secretRealmPauseLock)
        fakeTime.advanceBy(60_000L)

        core.handleWatchdogVerdict(StallVerdict.StalePauseDetected)

        assertFalse("lock must be cleared", core.secretRealmPauseLock)
        assertFalse("pause must be released", pausedFlow.value)
        // V5：不主动启动循环——后台场景避免后台推进时间；回前台 resumeFromBackground 重启
        assertFalse("self-heal must not start loop (background-safe)", core.isGameLoopRunning)
        verify(core, never()).emergencyRestartGameLoop()
    }

    @Test
    fun `handleWatchdogVerdict - paused by owner no-op (user pause never auto-recovered)`() {
        pausedFlow.value = true

        core.handleWatchdogVerdict(StallVerdict.PausedByOwner)
        core.handleWatchdogVerdict(StallVerdict.Healthy)

        assertTrue("user pause must survive", pausedFlow.value)
        verify(core, never()).emergencyRestartGameLoop()
    }

    // ── D-08 热监控接线 ──

    @Test
    fun `startGameLoop starts thermal monitor and stopGameLoop stops it（D-08 接线）`() {
        core.startGameLoop()
        // mockito-kotlin any()：标准 Mockito matcher 返回 null，对 Kotlin 非空
        // 参数触发 NullResultGuardian NPE（Mockito 5 已知坑，项目先例见
        // AISectBattleProcessorTest）；mockito-kotlin any() 返回非 null 哨兵
        verify(thermalMonitor).start(any())

        core.stopGameLoop()
        verify(thermalMonitor).stop()
    }

    @Test
    fun `startGameLoop twice rebuilds thermal monitor job on new scope（D-08 重建语义）`() {
        // emergencyRestart 场景等价：start 无条件重建轮询 job——二次 start
        // 不因旧 job active 而跳过（否则旧 scope 轮询残留 + 新 scope 无监控）
        core.startGameLoop()
        core.stopGameLoop()
        core.startGameLoop()
        verify(thermalMonitor, times(2)).start(any())
        core.stopGameLoop()
    }

    // ── 工具 ──

    private fun createStateStore(): GameStateStore {
        val store = mock(GameStateStore::class.java)
        `when`(store.isPaused).thenReturn(pausedFlow)
        `when`(store.isLoading).thenReturn(MutableStateFlow(false))
        `when`(store.isSaving).thenReturn(MutableStateFlow(false))
        doAnswer { pausedFlow.value = it.getArgument(0) }
            .`when`(store).setPausedDirect(anyBoolean())
        `when`(store.gameDataSnapshot).thenReturn(GameData())
        `when`(store.bootPhase).thenReturn(MutableStateFlow(BootPhase.UNINITIALIZED))
        `when`(store.runState).thenReturn(MutableStateFlow(RunState.IDLE))
        return store
    }

    private fun createCore(stateStore: GameStateStore, gameClock: GameTimeClock): GameEngineCore {
        val scope = mock(CoroutineScope::class.java)
        `when`(scope.coroutineContext).thenReturn(EmptyCoroutineContext)
        val scopeProvider = mock(CoroutineScopeProvider::class.java)
        `when`(scopeProvider.scope).thenReturn(scope)
        thermalMonitor = mock(ThermalMonitor::class.java)
        return GameEngineCore(
            stateStore = stateStore,
            eventBus = mock(EventBusPort::class.java),
            unifiedPerformanceMonitor = mock(UnifiedPerformanceMonitor::class.java),
            systemManager = mock(SystemManager::class.java),
            scopeProvider = scopeProvider,
            cultivationService = mock(CultivationService::class.java),
            explorationService = mock(com.xianxia.sect.core.engine.domain.exploration.ExplorationService::class.java),
            aiSectBeastAttackProcessor = mock(AISectBeastAttackProcessor::class.java),
            gameClock = gameClock,
            thermalController = mock(ThermalController::class.java),
            thermalMonitor = thermalMonitor,
            spiritStoneWallet = mock(SpiritStoneWallet::class.java),
            jadeSymbolService = JadeSymbolService(
                timeSource = TimeSource { 0L },
                stateStore = stateStore,
                wallClock = WallClock { 0L }
            )
        )
    }

    private class FakeTimeSource(var now: Long) : TimeSource {
        override fun elapsedRealtime(): Long = now
        fun advanceBy(ms: Long) { now += ms }
    }


    private val baseIntervalMs = 3_000L
    private val maxBackoffMs = GameEngineCore.WATCHDOG_MAX_BACKOFF_MS

    // ── 停滞 → 翻倍 ──

    @Test
    fun `backoff - first stall doubles from base`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = baseIntervalMs,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        assertEquals(6_000L, result)
    }

    @Test
    fun `backoff - second stall doubles to 4x base`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 6_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        assertEquals(12_000L, result)
    }

    @Test
    fun `backoff - third stall doubles to 8x base`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 12_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        assertEquals(24_000L, result)
    }

    @Test
    fun `backoff - fourth stall doubles to 16x base`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 24_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        // 48000 > 30000，截断为上限
        assertEquals(maxBackoffMs, result)
    }

    // ── 上限截断 ──

    @Test
    fun `backoff - already at max stays at max`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = maxBackoffMs,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        assertEquals(maxBackoffMs, result)
    }

    @Test
    fun `backoff - near max doubles and caps`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 20_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = false
        )
        // 40000 > 30000，截断为上限
        assertEquals(maxBackoffMs, result)
    }

    // ── 恢复 → 重置 ──

    @Test
    fun `backoff - recovery resets to base after one stall`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 6_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = true
        )
        assertEquals(baseIntervalMs, result)
    }

    @Test
    fun `backoff - recovery resets to base after multiple stalls`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = 24_000L,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = true
        )
        assertEquals(baseIntervalMs, result)
    }

    @Test
    fun `backoff - recovery resets from max backoff`() {
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = maxBackoffMs,
            baseIntervalMs = baseIntervalMs,
            hasRecovered = true
        )
        assertEquals(baseIntervalMs, result)
    }

    // ── 边界 ──

    @Test
    fun `backoff - conservative OEM 5s base doubles to 10s`() {
        val conservativeBase = 5_000L
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = conservativeBase,
            baseIntervalMs = conservativeBase,
            hasRecovered = false
        )
        assertEquals(10_000L, result)
    }

    @Test
    fun `backoff - base already equals max, no doubling`() {
        // 极端情况：基础间隔已等于上限（30s）
        val result = GameEngineCore.computeWatchdogBackoff(
            currentBackoffMs = maxBackoffMs,
            baseIntervalMs = maxBackoffMs,
            hasRecovered = false
        )
        assertEquals(maxBackoffMs, result)
    }
}
