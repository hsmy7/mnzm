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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/**
 * D-07 生命周期互斥测试：stopGameLoop/shutdown/emergencyRestartGameLoop 并发交错的
 * LoopState 状态机守卫（RUNNING/RESTARTING/STOPPING/STOPPED + phase/epoch 原子 CAS
 * 单赢家 + loopOpLock 工作体串行化）。
 *
 * 守卫契约：
 * - 正常重启：RESTARTING → RUNNING，循环恢复
 * - shutdown 进行中晚到 emergency：CAS 拒绝，不重建循环（孤儿循环不可能）
 * - emergency 进行中 stopGameLoop：stop 意图门抢占，拆除体等锁；emergency
 *   launch 前检查 abort（不复活），stop 拆除完成后最终 STOPPED
 * - emergency 进行中 startGameLoop：被拒（预检 active / CAS phase=RESTARTING），
 *   不追加第二循环（双循环防御）
 * - shutdown 抢占 emergency：emergency abort，shutdown 后引擎可恢复
 * - shutdown 幂等；未启动 stop 幂等且不触碰暂停状态
 * - 并发风暴（2×emergency + stop + shutdown）收敛：不崩、不锁死、可恢复
 * - 对抗性审查加固（2026-08-08）：孤儿循环/双循环/signal 跨代/phase 中毒
 *   四个窗口由 LoopState(phase+epoch) + 锁 + launch 双校验根治
 */
class GameEngineCoreLifecycleInterleavingTest {

    private lateinit var core: GameEngineCore
    private lateinit var stateStore: FakeAtomicStateStore
    private lateinit var systemManager: SystemManager
    private lateinit var jadeSymbolService: JadeSymbolService

    /**
     * 当前测试的 emergency 线程（registerEmergencySnapshotGate 用线程身份判定
     * 只阻塞 emergency；JUnit 每测试新建实例 + Thread.start() happens-before，
     * 跨线程读字段可见，无需 @Volatile）。
     */
    private var emergencyThread: Thread? = null

    @Before
    fun setUp() {
        OemPowerProfileProvider.manufacturerOverride = OemManufacturer.OTHER
        stateStore = createStateStore()
        core = createCore(stateStore)
    }

    @After
    fun tearDown() {
        core.stopGameLoop()
        OemPowerProfileProvider.manufacturerOverride = null
    }

    // ── 正常路径 ──

    @Test
    fun `normal start then emergency restart recovers to RUNNING`() {
        core.startGameLoop()
        assertTrue("首次启动后循环必须运行", core.isGameLoopRunning)

        core.emergencyRestartGameLoop()

        assertTrue("紧急重启后循环必须恢复运行", core.isGameLoopRunning)
        core.stopGameLoop()
        assertFalse("stop 后循环必须停止", core.isGameLoopRunning)
    }

    @Test
    fun `start after stop restarts loop`() {
        core.startGameLoop()
        core.stopGameLoop()
        assertFalse(core.isGameLoopRunning)

        core.startGameLoop()
        assertTrue("stop 后再 start 必须恢复", core.isGameLoopRunning)
    }

    // ── 交错场景 ──

    @Test
    fun `shutdown in progress then late emergency rejected and loop stays stopped`() {
        // systemManager 是 Mockito mockSmart，阻塞 stub 必须在 startGameLoop
        // 之前注册（Mockito stubbing 注册窗口与循环线程并发会劫持 →
        // UnfinishedStubbingException，残留 in-progress 还会污染下一测试的
        // setUp——全量回归实测复现，2026-08-10 保持预注册纪律）
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        doAnswer {
            entered.countDown()
            gate.await(5, TimeUnit.SECONDS)
            Unit
        }.`when`(systemManager).releaseAll()

        core.startGameLoop()
        assertTrue(core.isGameLoopRunning)

        // shutdown 阻塞在 systemManager.releaseAll——模拟 shutdown 进行中
        val shutdownThread = Thread { core.shutdown() }.apply { start() }
        try {
            assertTrue("shutdown 必须进入 releaseAll 阻塞点", entered.await(5, TimeUnit.SECONDS))
            // shutdown 已 CAS →STOPPED（stopGameLoopUnchecked 已完成，循环已停）
            assertFalse("shutdown 进行中循环已停止", core.isGameLoopRunning)

            // 晚到的 emergency：CAS RUNNING→RESTARTING 失败 → 拒绝，不重建
            core.emergencyRestartGameLoop()
            assertFalse("晚到 emergency 不得重建循环", core.isGameLoopRunning)
        } finally {
            gate.countDown()
            shutdownThread.join(5_000)
        }
        assertFalse("shutdown 完成后循环保持停止", core.isGameLoopRunning)
    }

    @Test
    fun `stop during emergency restart aborts restart and loop stays stopped`() {
        // 快照门控在 startGameLoop 之前注册（Fake 无 Mockito stubbing 竞态，
        // 门控注册后仅 emergency 线程读取被阻塞——循环线程 isPaused=true 走
        // 暂停分支不读 snapshot，主线程身份不匹配也不阻塞）
        registerEmergencySnapshotGate()
        core.startGameLoop()
        assertTrue(core.isGameLoopRunning)

        // emergency 阻塞在 gameDataSnapshot（锁内）——模拟 emergency 重启进行中
        // （emergency 的 CAS RUNNING→RESTARTING 成功后、锁内读 snapshot）
        emergencyThread = Thread { core.emergencyRestartGameLoop() }.apply { start() }
        val stopThread = Thread { core.stopGameLoop() }.apply { start() }
        try {
            assertTrue("emergency 必须进入 snapshot 阻塞点",
                stateStore.snapshotEnteredLatch.await(5, TimeUnit.SECONDS))
            // stop 的 CAS（RESTARTING→STOPPING，锁外意图门）立即抢占成功；
            // 拆除体等 emergency 释放锁（对抗性审查加固：工作体锁内串行化）。
            // 主线程不得在此调 stop——拆除体锁内等待会阻塞主线程
            Thread.sleep(200)
        } finally {
            stateStore.snapshotReleaseLatch.countDown()
            emergencyThread?.join(5_000)
            stopThread.join(5_000)
        }
        // emergency 锁内重建后启动前检查（phase 已 STOPPING）→ abort，循环不复活；
        // stop 的拆除体随后执行 → 最终一致 STOPPED
        assertFalse("emergency 不得复活已停止的循环", core.isGameLoopRunning)
    }

    @Test
    fun `start during emergency rejected then engine recoverable after restart`() {
        // 快照门控预注册（startGameLoop 之前）——本测试只需 emergency 阻塞语义
        registerEmergencySnapshotGate()
        core.startGameLoop()
        assertTrue(core.isGameLoopRunning)

        // emergency 锁内阻塞
        emergencyThread = Thread { core.emergencyRestartGameLoop() }.apply { start() }
        try {
            assertTrue("emergency 必须进入 snapshot 阻塞点",
                stateStore.snapshotEnteredLatch.await(5, TimeUnit.SECONDS))

            // emergency 进行中 start：预检（旧循环仍 active）或 CAS（phase=
            // RESTARTING）被拒，立即返回不阻塞主线程——不追加第二循环
            core.startGameLoop()
        } finally {
            stateStore.snapshotReleaseLatch.countDown()
            emergencyThread?.join(5_000)
        }

        // emergency 完整完成 → RUNNING + 循环运行中（start 被拒未产生双循环）
        assertTrue("emergency 完成后循环必须运行", core.isGameLoopRunning)
        // 双循环防御：再次 start 被"already running"预检拒绝（不追加第二个循环）
        core.startGameLoop()
        assertTrue("重复 start 不得追加循环", core.isGameLoopRunning)
    }

    @Test
    fun `shutdown during emergency aborts restart and engine remains recoverable`() {
        // 快照门控预注册（startGameLoop 之前）——本测试只需 emergency 阻塞语义
        registerEmergencySnapshotGate()
        core.startGameLoop()
        assertTrue(core.isGameLoopRunning)

        // emergency 锁内阻塞
        emergencyThread = Thread { core.emergencyRestartGameLoop() }.apply { start() }
        val shutdownThread = Thread { core.shutdown() }.apply { start() }
        try {
            assertTrue("emergency 必须进入 snapshot 阻塞点",
                stateStore.snapshotEnteredLatch.await(5, TimeUnit.SECONDS))
            Thread.sleep(200)
        } finally {
            stateStore.snapshotReleaseLatch.countDown()
            emergencyThread?.join(5_000)
            shutdownThread.join(5_000)
        }

        // shutdown CAS（→STOPPED，接受 RESTARTING）锁外立即抢占；emergency abort
        // 不复活；shutdown 拆除体完成 → 引擎可再次启动（shutdown 已重建 scope）
        assertFalse("shutdown 后循环必须停止", core.isGameLoopRunning)
        core.startGameLoop()
        assertTrue("shutdown 后引擎必须可恢复", core.isGameLoopRunning)
    }

    @Test
    fun `emergency aborted by stop does not leak dispatcher - engine restarts cleanly`() {
        core.startGameLoop()
        // 连续 emergency + 并发 stop 交错多次：abort 路径的 dispatcher 重建不得
        // 破坏后续正常启动（abort 泄漏的新线程由下次 recreate 回收，有界）
        repeat(3) {
            val emergencyThread = Thread { core.emergencyRestartGameLoop() }.apply { start() }
            Thread.sleep(30)
            core.stopGameLoop()
            emergencyThread.join(5_000)
            assertFalse("第 ${it + 1} 次交错后循环必须停止", core.isGameLoopRunning)
        }
        core.startGameLoop()
        assertTrue("多次 abort 后引擎必须可正常启动", core.isGameLoopRunning)
    }

    @Test
    fun `shutdown after emergency restart stops loop and is idempotent`() {
        core.startGameLoop()
        core.emergencyRestartGameLoop()
        assertTrue(core.isGameLoopRunning)

        core.shutdown()
        assertFalse("shutdown 必须停止循环", core.isGameLoopRunning)

        // 幂等：第二次 shutdown 直接返回不崩
        core.shutdown()
        assertFalse(core.isGameLoopRunning)
    }

    // ── 幂等与并发收敛 ──

    @Test
    fun `stop without start is idempotent and does not touch pause state`() {
        core.stopGameLoop()
        assertTrue("STOPPED 时 stop 幂等且不写暂停状态", stateStore.setPausedDirectCalls.isEmpty())
    }

    @Test
    fun `stopGameLoopAndWait returns only after loop finally onLoopStop executed`() {
        // 玉符防回退契约（2026-08-10）：读档/云下载在 loadData 前依赖
        // stopGameLoopAndWait 返回 ⟺ 循环 finally 的 onLoopStop（checkpointNow）
        // 已执行完毕——此后引擎线程无任何玉符写，快照替换才安全。
        // 验证：onLoopStop 阻塞期间 wait 不得返回；释放后 wait 返回 true。
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            Unit
        }.`when`(jadeSymbolService).onLoopStop()

        core.startGameLoop()
        assertTrue("循环必须运行", core.isGameLoopRunning)
        // 等循环进入稳定运行态（协程启动与 cancel 的竞态：startGameLoop 返回后
        // 循环协程可能尚未真正启动，立即 stop 会丢弃协程体 → finally 永不执行
        // → onLoopStop 不被调用，测试 flaky——JadeReload 测试实测复现，2026-08-10）
        Thread.sleep(200)

        val waitResult = AtomicReference<Boolean?>()
        val waitThread = Thread {
            waitResult.set(kotlinx.coroutines.runBlocking {
                core.stopGameLoopAndWait(5000)
            })
        }.apply { start() }

        try {
            // onLoopStop 已进入 = 循环 finally 开始执行；此时 wait 必须仍挂起
            assertTrue("onLoopStop 必须被循环 finally 调用", entered.await(5, TimeUnit.SECONDS))
            // 短暂窗口确认 wait 未提前返回（onLoopStop 尚未完成）
            Thread.sleep(100)
            assertNull("onLoopStop 完成前 stopGameLoopAndWait 不得返回", waitResult.get())
        } finally {
            release.countDown()
            waitThread.join(5_000)
        }
        assertTrue("onLoopStop 完成后 wait 必须返回 true", waitResult.get() == true)
    }

    @Test
    fun `stopGameLoopAndWait returns true immediately when loop never started`() {
        // 主菜单读档场景：循环未运行 → 立即返回 true（零开销，无副作用）
        val result = kotlinx.coroutines.runBlocking { core.stopGameLoopAndWait(5000) }
        assertTrue("未启动时 stopGameLoopAndWait 必须立即返回 true", result)
    }

    @Test
    fun `concurrent storm of restart stop shutdown converges and engine recoverable`() {
        core.startGameLoop()
        assertTrue(core.isGameLoopRunning)

        val pool = Executors.newFixedThreadPool(4)
        val startGate = CountDownLatch(1)
        try {
            val futures = listOf(
                pool.submit { startGate.await(); core.emergencyRestartGameLoop() },
                pool.submit { startGate.await(); core.emergencyRestartGameLoop() },
                pool.submit { startGate.await(); core.stopGameLoop() },
                pool.submit { startGate.await(); core.shutdown() }
            )
            startGate.countDown()
            // 任一动作抛异常（状态机非法组合崩溃）→ ExecutionException → 测试失败
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // 最终一致性：无论竞态谁赢，stop 必生效、start 必可恢复（状态机不锁死）
        core.stopGameLoop()
        assertFalse("风暴后 stop 必须生效", core.isGameLoopRunning)
        core.startGameLoop()
        assertTrue("风暴后引擎必须可恢复", core.isGameLoopRunning)
    }

    // ── 工具 ──

    /**
     * 注册 emergency 线程专用快照阻塞点（模拟 emergency 重启进行中）。
     *
     * 在 [GameEngineCore.startGameLoop] **之前**调用（emergency 启动前门控
     * 必须已生效；Fake 无 Mockito stubbing 竞态，循环线程 isPaused=true 走
     * 暂停分支不读 snapshot，无注册窗口问题）。
     *
     * 线程身份判定：startGameLoopInternal 的启动快照读取（调用线程）与循环
     * 线程调用立即返回；仅 [emergencyThread] 锁内读取（L1254）进入阻塞
     * （snapshotEnteredLatch 上报 + snapshotReleaseLatch 放行）。releaseLatch
     * 归零后 emergency 内部第二次快照读取（startGameLoopInternal 启动体）
     * 不再阻塞（CountDownLatch 归零即放行，等价 mock 时代 blockOnce）。
     */
    private fun registerEmergencySnapshotGate() {
        stateStore.snapshotGate = { it === emergencyThread }
    }

    private fun createStateStore(): FakeAtomicStateStore {
        val store = FakeAtomicStateStore()
        // isPaused 恒 true → 循环永远停暂停分支（sampleProgressSnapshot 只读
        // systemManager/flows，不读 gameDataSnapshot）——循环线程不读 snapshot；
        // 快照门控（snapshotGate）因此只会被 emergency 线程触达
        store.isPaused.value = true
        return store
    }

    private fun createCore(stateStore: GameStateStore): GameEngineCore {
        val scope = mockSmart(CoroutineScope::class.java)
        `when`(scope.coroutineContext).thenReturn(EmptyCoroutineContext)
        val scopeProvider = mockSmart(CoroutineScopeProvider::class.java)
        `when`(scopeProvider.scope).thenReturn(scope)
        systemManager = mockSmart(SystemManager::class.java)
        // mock 玉符服务（2026-08-08）：真实实例的 onLoopTick 每帧读
        // gameDataSnapshot（跨天检查），与测试注册的阻塞门控并发无冲突；
        // 生命周期互斥测试不涉及玉符逻辑，mockSmart 根除循环线程对 store
        // 的访问；字段持有供契约测试（stopGameLoopAndWait 含 finally）断言
        jadeSymbolService = mockSmart(JadeSymbolService::class.java)
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
            jadeSymbolService = jadeSymbolService,
            engineCrashReporter = mockSmart(EngineCrashReporter::class.java)
        )
    }

    private class StaticTimeSource : TimeSource {
        override fun elapsedRealtime(): Long = 1_000_000L
    }
}
