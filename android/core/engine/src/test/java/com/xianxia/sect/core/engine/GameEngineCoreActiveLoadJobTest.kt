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
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import kotlin.coroutines.EmptyCoroutineContext

/**
 * activeLoadJob 归属语义测试（C4，2026-08-05）。
 *
 * 守卫契约：
 * - 新操作注册时取消旧 job（既有行为）
 * - 被取代的旧 job 调 clearActiveLoadJob(自己) 返回 false 且不清除新 job 注册
 * - 当前 job 调 clearActiveLoadJob(自己) 返回 true 且清除注册
 * - 自注册同一 job 不死锁（=== 早退）
 * - forceResetStuckStates（看门狗）后旧 job 的 clear 返回 false（全能路径不受归属约束）
 */
class GameEngineCoreActiveLoadJobTest {

    private lateinit var core: GameEngineCore

    @Before
    fun setUp() {
        core = createCore(FakeAtomicStateStore())
    }

    @After
    fun tearDown() {
        core.stopGameLoop()
    }

    @Test
    fun `register second job cancels first`() = runTest {
        val job1 = Job()
        val job2 = Job()
        core.registerActiveLoadJob(job1)
        core.registerActiveLoadJob(job2)

        assertTrue("新注册应取消旧 job", job1.isCancelled)
        assertFalse("新 job 不应被取消", job2.isCancelled)
        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `superseded job clear returns false and keeps new registration`() = runTest {
        val job1 = Job()
        val job2 = Job()
        core.registerActiveLoadJob(job1)
        core.registerActiveLoadJob(job2)

        // 旧 job 的 finally 清理：不得抹掉新 job 的注册（C4 根因场景）
        val owned = core.clearActiveLoadJob(job1)

        assertFalse("被取代的 job 清理应返回 false", owned)
        // 新 job 仍可被自身清理（注册未被旧 job 抹掉）
        assertTrue("新 job 清理应返回 true", core.clearActiveLoadJob(job2))
    }

    @Test
    fun `current job clear returns true and clears registration`() = runTest {
        val job = Job()
        core.registerActiveLoadJob(job)

        assertTrue("当前 job 清理应返回 true", core.clearActiveLoadJob(job))
    }

    @Test
    fun `clear of unknown job returns false`() = runTest {
        val job = Job()
        // 从未注册的 job 清理：无归属 → false
        assertFalse("未注册 job 清理应返回 false", core.clearActiveLoadJob(job))
        job.cancel()
    }

    @Test
    fun `self re-register is no-op not self-cancel`() = runTest {
        val job = Job()
        core.registerActiveLoadJob(job)
        // C4 防自注册自杀：同一 job 重复注册 === 早退，不取消自己
        core.registerActiveLoadJob(job)

        assertFalse("重复注册同一 job 不应取消自己", job.isCancelled)
        assertTrue("重复注册后仍可清理", core.clearActiveLoadJob(job))
    }

    @Test
    fun `after forceReset superseded clear returns false`() = runTest {
        val job1 = Job()
        val job2 = Job()
        core.registerActiveLoadJob(job1)
        core.registerActiveLoadJob(job2)

        // 看门狗全能路径：直接 cancel + null + 复位标志
        core.forceResetStuckStates()

        assertTrue("看门狗应取消当前 job", job2.isCancelled)
        assertFalse("看门狗复位后旧 job 清理应返回 false", core.clearActiveLoadJob(job1))
        assertFalse("看门狗复位后当前 job 清理也应返回 false（已由看门狗清空）", core.clearActiveLoadJob(job2))
    }

    // ============================================================
    // 辅助（与 GameEngineCoreStuckResetTest 同构造模式）
    // ============================================================

    private fun createCore(stateStore: GameStateStore): GameEngineCore {
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
            explorationService = mockSmart(ExplorationService::class.java),
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
            engineCrashReporter = mockSmart(EngineCrashReporter::class.java)
        )
    }

    private class StaticTimeSource : TimeSource {
        override fun elapsedRealtime(): Long = 1_000_000L
    }
}
