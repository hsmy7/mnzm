package com.xianxia.sect.core.engine

import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.service.WallClock
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSource
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.exploration.AISectBeastAttackProcessor
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 玉符读档时序交错测试（2026-08-10）：
 *
 * 玩家反馈"玉符读档/云下载后重置为旧值"——根因：
 * 游戏内读档时 loadData（快照替换）→ boot 的非等待 stopGameLoop →
 * 旧循环 finally 的 JadeSymbolService.onLoopStop()（checkpointNow 绝对值
 * 覆盖写）在引擎线程异步执行，晚于快照替换 → 读档前的旧运行时值覆盖
 * 新档玉符四字段；随后 onLoopStart 从被污染值重新锚定。
 *
 * 本测试用真实 JadeSymbolService + 真实 FakeAtomicStateStore + 真实循环
 * 复现机理（非等待 stop 时旧值覆盖）并锁死修复契约（等待 stop 后加载安全）。
 */
class GameEngineCoreJadeReloadInterleavingTest {

    private lateinit var core: GameEngineCore
    private lateinit var store: FakeAtomicStateStore
    private lateinit var jadeSymbolService: JadeSymbolService
    private lateinit var fakeTime: FakeTimeSource

    @Before
    fun setUp() {
        OemPowerProfileProvider.manufacturerOverride = OemManufacturer.OTHER
        store = FakeAtomicStateStore()
        // 循环停在暂停分支（与真实"读档加载中"场景一致：isLoading 时
        // onLoopTick 仍执行、tick 不推进），避免无关系统副作用
        store.isLoading.value = true
        fakeTime = FakeTimeSource(1_000_000L)
        jadeSymbolService = JadeSymbolService(
            timeSource = fakeTime,
            stateStore = store,
            wallClock = WallClock { 0L }
        )
        core = createCore(store)
    }

    @After
    fun tearDown() {
        core.stopGameLoop()
        OemPowerProfileProvider.manufacturerOverride = null
    }

    // ── 复现：非等待 stop 时旧循环 finally 覆盖新档玉符 ──

    @Test
    fun `non-waiting stop during load lets stale loop finally overwrite loaded jade symbols`() {
        // jadeDayAnchorMs=MAX_VALUE：onLoopStart 锚定后首帧 maybeDayReset 判定
        // "todayMidnight <= dayAnchorMs"（同一天）直接 return——消除循环线程
        // 首帧对 store 的并发 update（FakeAtomicStateStore 无锁，writeDepth 竞态
        // 损坏会让后续 checkpointNow 永不 syncFlows，机理复现被环境破坏）
        store.update {
            gameData = gameData.copy(jadeSymbols = 5, jadeDayAnchorMs = Long.MAX_VALUE)
        }
        core.startGameLoop()
        // prepareLoopStart 同步执行 onLoopStart → 运行时 totalCount=5 已锚定
        // 等循环完成第一帧（onLoopTick + 暂停分支 delay 挂起），进入稳定暂停态
        Thread.sleep(200)

        // 读档：快照替换为新档（玉符 99），旧循环仍在运行（isLoading 暂停分支）
        store.update { gameData = gameData.copy(jadeSymbols = 99) }

        // boot Step 1 的非等待 stop：cancel 后立即返回
        core.stopGameLoop()

        // 旧循环 finally 的 checkpointNow 用旧运行时值 5 绝对值覆盖新档 99
        // （cancel 恢复在 Default 线程池异步执行，先等其完成）
        Thread.sleep(1_000)
        assertEquals("非等待 stop 后旧循环 finally 必须用旧运行时值覆盖新档玉符", 5, store.gameDataSnapshot.jadeSymbols)
    }

    // ── 修复契约：等待 stop 后加载安全 ──

    @Test
    fun `waiting for loop stop before load keeps loaded jade symbols`() {
        // 同测试 1：MAX_VALUE 消除循环线程首帧 maybeDayReset 的并发 update
        store.update {
            gameData = gameData.copy(jadeSymbols = 5, jadeDayAnchorMs = Long.MAX_VALUE)
        }
        core.startGameLoop()

        // 修复路径：loadData 前 stopGameLoopAndWait（旧 finally 的 checkpoint
        // 先写旧 gameData，随即被快照替换丢弃）
        val stopped = runBlocking { core.stopGameLoopAndWait(5000) }
        assertTrue("循环必须停止", stopped)

        store.update { gameData = gameData.copy(jadeSymbols = 99) }
        assertEquals("等待停止后快照替换不再被覆盖", 99, store.gameDataSnapshot.jadeSymbols)

        // 重新启动：onLoopStart 从新档锚定运行时
        core.startGameLoop()
        assertEquals("onLoopStart 必须从新档锚定", 99, jadeSymbolService.runtimeState.value.total)

        // 再次停止：checkpoint 写回 99（运行时与存档一致，无残留旧值）
        val stopped2 = runBlocking { core.stopGameLoopAndWait(5000) }
        assertTrue("二次停止必须成功", stopped2)
        assertEquals(99, store.gameDataSnapshot.jadeSymbols)
    }

    // ── 启动前加载幂等（主菜单读档）──

    @Test
    fun `load before loop start is safe and anchors fresh runtime`() {
        store.update { gameData = gameData.copy(jadeSymbols = 7) }

        // 循环未运行：stopGameLoopAndWait 立即返回 true，零副作用
        val stopped = runBlocking { core.stopGameLoopAndWait(5000) }
        assertTrue("未启动时 stopGameLoopAndWait 必须立即返回 true", stopped)

        store.update { gameData = gameData.copy(jadeSymbols = 99) }
        assertEquals(99, store.gameDataSnapshot.jadeSymbols)

        // 启动后从新档锚定
        core.startGameLoop()
        assertEquals("启动后运行时必须锚定 99", 99, jadeSymbolService.runtimeState.value.total)

        // 显式收尾：循环在测试体内干净死亡（JUnit 会等待未死循环的 finally
        // 收尾，测试结束时有活循环会拖慢 ~4.5s，全量回归累计成本可观）
        val stoppedAtEnd = runBlocking { core.stopGameLoopAndWait(5000) }
        assertTrue("收尾 stop 必须成功", stoppedAtEnd)
    }

    // ── 冷启动读档窗口竞态（2026-08-12 新增）──
    // 玩家反馈"读档后看广告玉符 20→3"：前台服务 ACTION_START 在读档 I/O 窗口
    // 抢先启动循环，onLoopStart 从空快照锚定 totalCount=0；boot 的 startGameLoop
    // 因循环已运行直接 return（永不重锚），广告发放 0+3=3 绝对值覆盖已读入的 20。
    // 修复：startGameLoop"已运行"分支无条件重锚（startGameLoop 必须在 loadData
    // 之后再次调用——boot Step 7 保证）。

    @Test
    fun `loop started by third party during load window is re-anchored by boot startGameLoop`() {
        // jadeDayAnchorMs=MAX_VALUE：消除循环线程首帧 maybeDayReset 的并发 update
        // （FakeAtomicStateStore 无锁，见测试 1 注释）；jadeSymbols=0 模拟初始空快照
        store.update { gameData = gameData.copy(jadeDayAnchorMs = Long.MAX_VALUE) }

        // 竞态 1：前台服务 ACTION_START 抢先启动循环 → onLoopStart 从空快照锚定 0
        core.startGameLoop()
        Thread.sleep(200) // 等循环完成第一帧，进入稳定暂停态
        assertEquals("抢先启动必须从空快照锚定 0", 0, jadeSymbolService.runtimeState.value.total)

        // 竞态 2：读档完成，快照替换为新档（玉符 99）
        store.update { gameData = gameData.copy(jadeSymbols = 99) }

        // 竞态 3：boot Step 7 startGameLoop——循环已运行，必须重锚而非 no-op
        core.startGameLoop()
        assertEquals("boot 的 startGameLoop 必须重锚新档余额", 99, jadeSymbolService.runtimeState.value.total)

        // 修复后：广告发放基于新档余额，绝对值写不覆盖持久化值
        jadeSymbolService.grantFromAd(3)
        assertEquals("广告发放 99 + 3 = 102", 102, store.gameDataSnapshot.jadeSymbols)
        jadeSymbolService.checkpointNow()
        assertEquals("checkpoint 后绝对值写不回退", 102, store.gameDataSnapshot.jadeSymbols)

        // 显式收尾（同测试 3 注释：避免活循环拖慢全量回归）
        val stopped = runBlocking { core.stopGameLoopAndWait(5000) }
        assertTrue("收尾 stop 必须成功", stopped)
    }

    @Test
    fun `repeated watchdog fallback starts during load window keep final balance`() {
        store.update { gameData = gameData.copy(jadeDayAnchorMs = Long.MAX_VALUE) }
        core.startGameLoop() // 第一次：ACTION_START 抢先启动
        Thread.sleep(200)

        // watchdog 兜底多次调用：每次走"已运行"分支重锚（幂等）
        core.startGameLoop()
        core.startGameLoop()
        core.startGameLoop()

        store.update { gameData = gameData.copy(jadeSymbols = 99) } // loadData
        core.startGameLoop() // boot Step 7：重锚 99

        assertEquals("多次兜底启动后重锚余额必须为新档值", 99, jadeSymbolService.runtimeState.value.total)
        jadeSymbolService.grantFromAd(3)
        assertEquals("余额 99 + 广告 3 = 102，不被兜底启动破坏", 102, store.gameDataSnapshot.jadeSymbols)

        val stopped = runBlocking { core.stopGameLoopAndWait(5000) }
        assertTrue("收尾 stop 必须成功", stopped)
    }

    // ── 工具 ──

    private class FakeTimeSource(var nowMs: Long) : TimeSource {
        override fun elapsedRealtime(): Long = nowMs
        fun advanceBy(ms: Long) { nowMs += ms }
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
            gameClock = GameTimeClock(fakeTime),
            thermalController = mock(ThermalController::class.java),
            thermalMonitor = mock(ThermalMonitor::class.java),
            spiritStoneWallet = mock(SpiritStoneWallet::class.java),
            jadeSymbolService = jadeSymbolService,
            engineCrashReporter = mock(EngineCrashReporter::class.java)
        )
    }
}
