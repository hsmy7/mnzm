package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.engine.system.WallClock
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 离线收益注入编排测试（结算改造 2026-09-27 §10 B7）：
 * - staging：lastSaveTime 折算挂起（含新档零注入守卫）
 * - consume（JVM 下 native 恒不可用 → 回退臂分支）：注入落地 + 回归报告发布
 * - 报告 ack：清空后不再重复展示
 */
class GameEngineCoreOfflineOpsTest {

    private class StaticTimeSource : TimeSource {
        override fun elapsedRealtime(): Long = 1_000_000L
    }

    /** 可编程墙钟（staging 离线时段计量输入） */
    private class FakeWallClock(var now: Long = 50_000_000_000L) : WallClock {
        override fun currentTimeMillis(): Long = now
    }

    private lateinit var core: GameEngineCore
    private lateinit var wallClock: FakeWallClock

    @Before
    fun setUp() {
        wallClock = FakeWallClock()
        core = createCore(FakeAtomicStateStore())
    }

    @After
    fun tearDown() {
        core.stopGameLoop()
    }

    // ── staging ─────────────────────────────────────────────────────

    @Test
    fun `staging 按口径折算并挂起待注入`() = runTest {
        val twelveHours = 12L * 3_600_000L
        val alignedTwelveHours = twelveHours /
            GameConfig.Time.GAME_MS_PER_PHASE * GameConfig.Time.GAME_MS_PER_PHASE
        wallClock.now = 100_000_000_000L
        core.stageOfflineProgress(wallClock.now - twelveHours)
        assertEquals(alignedTwelveHours, core.pendingOfflineGameMs)
        assertEquals(twelveHours, core.pendingOfflineWallMs)
    }

    @Test
    fun `staging 超硬顶时段封顶为 18h 游戏时间`() = runTest {
        wallClock.now = 100_000_000_000L
        core.stageOfflineProgress(wallClock.now - 72L * 3_600_000L)
        assertEquals(18L * 3_600_000L, core.pendingOfflineGameMs)
    }

    @Test
    fun `staging 新档零保存记录不注入`() = runTest {
        core.stageOfflineProgress(0L)
        assertEquals(0L, core.pendingOfflineGameMs)
        assertEquals(0L, core.pendingOfflineWallMs)
        core.stageOfflineProgress(-1L)
        assertEquals(0L, core.pendingOfflineGameMs)
    }

    // ── consume（JVM：GameCoreBridge.isLoaded=false → 回退臂分支） ──

    @Test
    fun `consume 回退臂落地注入并发布回归报告`() = runTest {
        val offlineWallMs = 6L * 3_600_000L
        wallClock.now = 100_000_000_000L
        core.stageOfflineProgress(wallClock.now - offlineWallMs)
        val staged = core.pendingOfflineGameMs
        assertEquals(6L * 3_600_000L, staged)

        core.consumePendingOfflineProgress()

        // 回退臂：注入进 Kotlin 累积器
        assertEquals(staged, core.gameClock.accumulatedGameMs)
        // 消费即清零（幂等）
        assertEquals(0L, core.pendingOfflineGameMs)
        assertEquals(0L, core.pendingOfflineWallMs)
        // 回归报告发布（现实离线时长，展示面）
        val report = core.offlineReturnReport.value
        assertEquals(offlineWallMs, report?.offlineWallMs)
    }

    @Test
    fun `consume 幂等——重复调用不重复注入`() = runTest {
        wallClock.now = 100_000_000_000L
        core.stageOfflineProgress(wallClock.now - 3_600_000L)
        core.consumePendingOfflineProgress()
        val accumulatedAfterFirst = core.gameClock.accumulatedGameMs

        core.consumePendingOfflineProgress()
        core.consumePendingOfflineProgress()

        assertEquals(accumulatedAfterFirst, core.gameClock.accumulatedGameMs)
    }

    @Test
    fun `无待注入时 consume 不发布报告`() = runTest {
        core.consumePendingOfflineProgress()
        assertNull(core.offlineReturnReport.value)
    }

    @Test
    fun `报告 ack 清空后不重复展示`() = runTest {
        wallClock.now = 100_000_000_000L
        core.stageOfflineProgress(wallClock.now - 3_600_000L)
        core.consumePendingOfflineProgress()

        core.offlineReturnReportMutable.value = null

        assertNull(core.offlineReturnReport.value)
    }

    // ── 测试工厂（模式同 GameEngineCoreInitializeOnceTest；wallClock 可编程） ──

    private fun createCore(stateStore: GameStateStore): GameEngineCore {
        val scope = Mockito.mock(CoroutineScope::class.java)
        Mockito.`when`(scope.coroutineContext).thenReturn(EmptyCoroutineContext)
        val scopeProvider = Mockito.mock(CoroutineScopeProvider::class.java)
        Mockito.`when`(scopeProvider.scope).thenReturn(scope)
        return GameEngineCore(
            stateStore = stateStore,
            eventBus = Mockito.mock(EventBusPort::class.java),
            unifiedPerformanceMonitor = Mockito.mock(UnifiedPerformanceMonitor::class.java),
            systemManager = Mockito.mock(SystemManager::class.java),
            scopeProvider = scopeProvider,
            cultivationService = Mockito.mock(CultivationService::class.java),
            explorationService = Mockito.mock(ExplorationService::class.java),
            aiSectBeastAttackProcessor = Mockito.mock(AISectBeastAttackProcessor::class.java),
            gameClock = GameTimeClock(StaticTimeSource()),
            thermalController = Mockito.mock(ThermalController::class.java),
            thermalMonitor = Mockito.mock(com.xianxia.sect.core.perf.ThermalMonitor::class.java),
            spiritStoneWallet = Mockito.mock(SpiritStoneWallet::class.java),
            jadeSymbolService = Mockito.mock(JadeSymbolService::class.java),
            engineCrashReporter = Mockito.mock(EngineCrashReporter::class.java),
            wallClock = wallClock
        )
    }
}
