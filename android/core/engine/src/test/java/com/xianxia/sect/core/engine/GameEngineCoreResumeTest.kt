package com.xianxia.sect.core.engine

import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.engine.monitor.StallVerdict
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.service.WallClock
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 后台恢复时序测试 — 验证"游戏时间永久冻结"根因修复。
 *
 * 修复前：`resumeFromBackground` 遇 secretRealmPauseLock 直接 return →
 * onDispose 丢失（Activity 重建）时锁残留 → 循环永不重启 + 三层看门狗
 * 因 isPaused 豁免全部失明 → 永久冻结。
 * 修复后：无条件重启循环（保持暂停分支，S4 语义），锁残留由租约自愈兜底。
 */
class GameEngineCoreResumeTest {

    private lateinit var core: GameEngineCore
    private lateinit var stateStore: GameStateStore
    private lateinit var pausedFlow: MutableStateFlow<Boolean>
    private lateinit var fakeTime: FakeTimeSource
    private lateinit var gameClock: GameTimeClock

    @Before
    fun setUp() {
        // 纯 JVM 下 Build.MANUFACTURER 为 null，注入确定厂商（startWatchdog 依赖）
        OemPowerProfileProvider.manufacturerOverride = OemManufacturer.OTHER
        stateStore = createStateStore()
        fakeTime = FakeTimeSource(now = 1_000_000L)
        gameClock = GameTimeClock(fakeTime)
        core = createCore(stateStore, gameClock)
    }

    @After
    fun tearDown() {
        // 停止循环与看门狗线程，防止测试间泄漏
        core.stopGameLoop()
        OemPowerProfileProvider.manufacturerOverride = null
    }

    // ── 修复点 A：锁持有 + 循环停止 → 重启循环并保持暂停（S4 语义）──

    @Test
    fun `resumeFromBackground - secret realm lock held restarts loop and preserves pause`() = runTest {
        // 前置：进入秘境（置锁）→ 切后台（停循环）
        core.pauseForSecretRealm()
        assertTrue("pauseForSecretRealm should pause", pausedFlow.value)
        assertTrue("pause lock should be held", core.secretRealmPauseLock)
        core.pauseForBackground()
        assertFalse("loop should be stopped after background", core.isGameLoopRunning)

        // 回前台：锁仍持有（exitExploration 丢失场景）
        core.resumeFromBackground()

        // 修复前：直接 return，循环永不重启。修复后：循环重启 + 暂停保持
        assertTrue("loop must restart despite pause lock", core.isGameLoopRunning)
        assertTrue("pause must be preserved (S4: no month/year change during exploration)", pausedFlow.value)

        // F4：startGameLoop 内按暂停来源保留（pauseForSecretRealm/stopGameLoop/startGameLoop 均置 true）
        verify(stateStore, atLeastOnce()).setPausedDirect(true)
    }

    @Test
    fun `resumeFromBackground - no lock restarts loop normally`() = runTest {
        core.pauseForBackground()
        assertFalse(core.isGameLoopRunning)

        core.resumeFromBackground()

        assertTrue("loop must restart", core.isGameLoopRunning)
        assertFalse("no lock: pause must be released", pausedFlow.value)
    }

    @Test
    fun `pauseForBackground - secret realm pause is not recorded as user pause (F1)`() = runTest {
        // F1 回归：秘境暂停（锁持有）不记入 wasUserPausedBeforeBackground——
        // 否则后台销毁 Activity（exitExploration 清锁）后恢复会保留"无主暂停"
        core.pauseForSecretRealm()
        assertTrue(core.secretRealmPauseLock)
        // 模拟后台期间 Activity 销毁：exitExploration 清锁
        core.resumeFromSecretRealm()

        core.pauseForBackground()
        core.resumeFromBackground()

        // 锁已清且用户从未暂停 → 恢复后时间正常推进（无粘滞暂停）
        assertFalse("no sticky pause after background round-trip", pausedFlow.value)
        assertTrue(core.isGameLoopRunning)
    }

    @Test
    fun `resumeFromBackground - user paused before background keeps pause released by manual resume`() = runTest {
        // 用户主动暂停 → 切后台 → 回前台：循环重启但 isPaused 保持 true（用户暂停语义）
        pausedFlow.value = true
        core.pauseForBackground()

        core.resumeFromBackground()

        assertTrue(core.isGameLoopRunning)
        assertTrue("user pause must survive background resume", pausedFlow.value)
    }

    @Test
    fun `resumeFromBackground - was not paused by background does nothing`() = runTest {
        // 未经过 pauseForBackground：_wasPausedByBackground=false → 不动作
        core.resumeFromBackground()

        assertFalse("loop must not start", core.isGameLoopRunning)
        verify(stateStore, never()).setPausedDirect(anyBoolean())
    }

    @Test
    fun `resumeFromBackground - loop already running does not restart`() = runTest {
        core.pauseForBackground()
        // 模拟循环已被其他路径重启（如 Alarm 兜底）
        core.startGameLoop()
        assertTrue(core.isGameLoopRunning)

        core.resumeFromBackground()

        // 循环在跑：不重复启动（startGameLoop 幂等守卫 + resumeFromBackground 的 !isGameLoopRunning 条件）
        assertTrue(core.isGameLoopRunning)
        // 无秘境锁：不被重复置暂停
        assertFalse(pausedFlow.value)
    }

    // ── 修复点 B：租约自愈链路 ──

    @Test
    fun `renewSecretRealmPauseLease - refreshes lease timestamp`() {
        core.pauseForSecretRealm()
        fakeTime.now += 5_000L
        core.renewSecretRealmPauseLease()
        // 租约续约后过期判定窗口重置：看门狗 progressVerdict 应判 PausedByOwner
        val verdict = core.progressVerdict()
        assertEquals("renewed lease must be PausedByOwner", StallVerdict.PausedByOwner, verdict)
    }

    @Test
    fun `progressVerdict - lease expired returns StalePauseDetected`() {
        core.pauseForSecretRealm()
        fakeTime.now += 60_000L  // 超过 45s TTL 且无续约
        assertEquals(StallVerdict.StalePauseDetected, core.progressVerdict())
    }

    @Test
    fun `handleWatchdogVerdict - stale pause self-heals lock without force restart (V5)`() = runTest {
        core.pauseForSecretRealm()
        fakeTime.now += 60_000L
        assertEquals(StallVerdict.StalePauseDetected, core.progressVerdict())

        core.handleWatchdogVerdict(StallVerdict.StalePauseDetected)

        assertFalse("lock must be cleared", core.secretRealmPauseLock)
        assertFalse("pause must be released", pausedFlow.value)
        // V5：不主动启动循环——后台场景避免后台推进时间；回前台 resumeFromBackground 重启
        assertFalse("self-heal must not force-start loop", core.isGameLoopRunning)
    }

    @Test
    fun `handleWatchdogVerdict - user pause never recovered (a63338f3 regression)`() {
        // 用户主动暂停：PausedByOwner 永不自动恢复
        pausedFlow.value = true
        core.handleWatchdogVerdict(StallVerdict.PausedByOwner)
        assertTrue("user pause must survive watchdog", pausedFlow.value)
    }

    @Test
    fun `handleWatchdogVerdict - fake run with speed zero restores speed`() {
        core.pauseForSecretRealm()
        core.resumeFromSecretRealm()
        core.renewSecretRealmPauseLease()
        // 构造 speed=0（UI 已封死，仅测试内部语义）
        gameClock.setSpeed(0)

        core.handleWatchdogVerdict(StallVerdict.FakeRunDetected)

        assertEquals(1, gameClock.speed)
    }

    // ── 秘境正常进出（对称性回归）──

    @Test
    fun `secretRealm lifecycle - enter pause resume exit restore`() = runTest {
        core.pauseForSecretRealm()
        assertTrue(pausedFlow.value)
        core.pauseForBackground()
        core.resumeFromBackground()
        assertTrue("still paused inside exploration", pausedFlow.value)

        core.resumeFromSecretRealm()
        assertFalse("exit exploration restores time", pausedFlow.value)
        assertFalse("lock cleared", core.secretRealmPauseLock)
    }

    // ── 工具 ──

    private fun createStateStore(): GameStateStore {
        val store = mock(GameStateStore::class.java)
        pausedFlow = MutableStateFlow(false)
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
        return GameEngineCore(
            stateStore = stateStore,
            eventBus = mock(EventBusPort::class.java),
            unifiedPerformanceMonitor = mock(UnifiedPerformanceMonitor::class.java),
            systemManager = mock(com.xianxia.sect.core.engine.system.SystemManager::class.java),
            scopeProvider = scopeProvider,
            cultivationService = mock(CultivationService::class.java),
            explorationService = mock(com.xianxia.sect.core.engine.domain.exploration.ExplorationService::class.java),
            aiSectBeastAttackProcessor = mock(AISectBeastAttackProcessor::class.java),
            gameClock = gameClock,
            thermalController = mock(ThermalController::class.java),
            thermalMonitor = mock(com.xianxia.sect.core.perf.ThermalMonitor::class.java),
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
    }
}
