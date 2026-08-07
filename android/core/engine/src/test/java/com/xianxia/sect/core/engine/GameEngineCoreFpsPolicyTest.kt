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
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.core.thermal.BatteryStatusProvider
import com.xianxia.sect.core.thermal.NoopBatteryStatus
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import kotlin.coroutines.EmptyCoroutineContext
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 帧率策略单元测试（性能模式 × 场景 × 热控/电量 取 min + 动态帧率状态机）。
 *
 * 覆盖维度：
 * - [GameEngineCore.sceneFpsFor] 全矩阵（5 场景 × 3 模式）
 * - [GameEngineCore.evaluateIdleTransition] 闲置降档状态机（5s/30s 边界）
 * - [GameEngineCore.onUserActivity] 双恢复路径（IDLE/GAMEPLAY_IDLE → GAMEPLAY）
 * - [GameEngineCore.setPerformanceMode] 触发帧率/质量/装饰即时更新
 * - 低电量 fpsCap 与热控降级取 min
 * - [GameEngineCore.setObservedRenderFps] 值防御与限频（需 Robolectric：
 *   SystemClock.elapsedRealtime 测试环境恒 0，靠 ShadowSystemClock 推进）
 */
@RunWith(RobolectricTestRunner::class)
class GameEngineCoreFpsPolicyTest {

    private lateinit var core: GameEngineCore
    private lateinit var gameClock: GameTimeClock
    private lateinit var pausedFlow: MutableStateFlow<Boolean>
    private lateinit var fakeTime: FakeTimeSource

    /** 可配置的电池状态提供者（测试注入） */
    private class FakeBatteryStatus(
        override val isLowBattery: Boolean = false,
        override val fpsCap: Int = com.xianxia.sect.core.thermal.BatteryAwareController.MAX_FPS_CAP,
        override val thermalThresholdOffsetC: Float = 0f
    ) : BatteryStatusProvider

    @Before
    fun setUp() {
        OemPowerProfileProvider.manufacturerOverride = OemManufacturer.OTHER
        pausedFlow = MutableStateFlow(false)
        fakeTime = FakeTimeSource(now = 1_000_000L)
        gameClock = GameTimeClock(fakeTime)
        core = spy(createCore(createStateStore(), gameClock, NoopBatteryStatus))
    }

    @After
    fun tearDown() {
        core.stopGameLoop()
        OemPowerProfileProvider.manufacturerOverride = null
    }

    // ── 场景 × 模式帧率矩阵 ──

    @Test
    fun `sceneFpsFor - balanced mode full matrix`() {
        assertEquals(10, core.sceneFpsFor(PerformanceMode.BALANCED, GameEngineCore.GameScene.IDLE))
        assertEquals(30, core.sceneFpsFor(PerformanceMode.BALANCED, GameEngineCore.GameScene.MAP_SCROLL))
        assertEquals(60, core.sceneFpsFor(PerformanceMode.BALANCED, GameEngineCore.GameScene.GAMEPLAY))
        assertEquals(30, core.sceneFpsFor(PerformanceMode.BALANCED, GameEngineCore.GameScene.GAMEPLAY_IDLE))
        assertEquals(60, core.sceneFpsFor(PerformanceMode.BALANCED, GameEngineCore.GameScene.BATTLE))
    }

    @Test
    fun `sceneFpsFor - energy saving locks 30 except deep idle 10`() {
        assertEquals(10, core.sceneFpsFor(PerformanceMode.ENERGY_SAVING, GameEngineCore.GameScene.IDLE))
        assertEquals(30, core.sceneFpsFor(PerformanceMode.ENERGY_SAVING, GameEngineCore.GameScene.MAP_SCROLL))
        assertEquals(30, core.sceneFpsFor(PerformanceMode.ENERGY_SAVING, GameEngineCore.GameScene.GAMEPLAY))
        assertEquals(30, core.sceneFpsFor(PerformanceMode.ENERGY_SAVING, GameEngineCore.GameScene.GAMEPLAY_IDLE))
        assertEquals(30, core.sceneFpsFor(PerformanceMode.ENERGY_SAVING, GameEngineCore.GameScene.BATTLE))
    }

    @Test
    fun `sceneFpsFor - performance keeps 60 in active scenes, map scroll 60`() {
        assertEquals(10, core.sceneFpsFor(PerformanceMode.PERFORMANCE, GameEngineCore.GameScene.IDLE))
        assertEquals(60, core.sceneFpsFor(PerformanceMode.PERFORMANCE, GameEngineCore.GameScene.MAP_SCROLL))
        assertEquals(60, core.sceneFpsFor(PerformanceMode.PERFORMANCE, GameEngineCore.GameScene.GAMEPLAY))
        assertEquals(30, core.sceneFpsFor(PerformanceMode.PERFORMANCE, GameEngineCore.GameScene.GAMEPLAY_IDLE))
        assertEquals(60, core.sceneFpsFor(PerformanceMode.PERFORMANCE, GameEngineCore.GameScene.BATTLE))
    }

    // ── 持久化解析 ──

    @Test
    fun `fromStorage - parses valid names and falls back to BALANCED`() {
        assertEquals(PerformanceMode.ENERGY_SAVING, PerformanceMode.fromStorage("ENERGY_SAVING"))
        assertEquals(PerformanceMode.BALANCED, PerformanceMode.fromStorage("BALANCED"))
        assertEquals(PerformanceMode.PERFORMANCE, PerformanceMode.fromStorage("PERFORMANCE"))
        // 非法值 / null / 未知字符串 → 回退默认均衡
        assertEquals(PerformanceMode.BALANCED, PerformanceMode.fromStorage(null))
        assertEquals(PerformanceMode.BALANCED, PerformanceMode.fromStorage("TURBO"))
        assertEquals(PerformanceMode.BALANCED, PerformanceMode.fromStorage(""))
    }

    // ── 闲置降档状态机 ──

    @Test
    fun `idle transition - balanced gameplay downgrades to gameplay idle at 5s boundary`() {
        val fiveSec = TimeUnit.SECONDS.toNanos(5)
        assertNull(core.evaluateIdleTransition(
            GameEngineCore.GameScene.GAMEPLAY, fiveSec - 1, PerformanceMode.BALANCED))
        assertEquals(GameEngineCore.GameScene.GAMEPLAY_IDLE, core.evaluateIdleTransition(
            GameEngineCore.GameScene.GAMEPLAY, fiveSec, PerformanceMode.BALANCED))
    }

    @Test
    fun `idle transition - balanced gameplay idle downgrades to idle at 30s`() {
        val thirtySec = TimeUnit.SECONDS.toNanos(30)
        assertNull(core.evaluateIdleTransition(
            GameEngineCore.GameScene.GAMEPLAY_IDLE, thirtySec - 1, PerformanceMode.BALANCED))
        assertEquals(GameEngineCore.GameScene.IDLE, core.evaluateIdleTransition(
            GameEngineCore.GameScene.GAMEPLAY_IDLE, thirtySec, PerformanceMode.BALANCED))
    }

    @Test
    fun `idle transition - energy saving and performance skip middle tier`() {
        val fiveSec = TimeUnit.SECONDS.toNanos(5)
        val thirtySec = TimeUnit.SECONDS.toNanos(30)
        // 5s 时非动态模式不降档
        assertNull(core.evaluateIdleTransition(
            GameEngineCore.GameScene.GAMEPLAY, fiveSec, PerformanceMode.ENERGY_SAVING))
        assertNull(core.evaluateIdleTransition(
            GameEngineCore.GameScene.GAMEPLAY, fiveSec, PerformanceMode.PERFORMANCE))
        // 30s 深闲置全模式保留
        assertEquals(GameEngineCore.GameScene.IDLE, core.evaluateIdleTransition(
            GameEngineCore.GameScene.GAMEPLAY, thirtySec, PerformanceMode.ENERGY_SAVING))
        assertEquals(GameEngineCore.GameScene.IDLE, core.evaluateIdleTransition(
            GameEngineCore.GameScene.GAMEPLAY, thirtySec, PerformanceMode.PERFORMANCE))
    }

    @Test
    fun `idle transition - map scroll and battle`() {
        val thirtySec = TimeUnit.SECONDS.toNanos(30)
        assertEquals(GameEngineCore.GameScene.IDLE, core.evaluateIdleTransition(
            GameEngineCore.GameScene.MAP_SCROLL, thirtySec, PerformanceMode.BALANCED))
        // 战斗场景不因闲置切换
        assertNull(core.evaluateIdleTransition(
            GameEngineCore.GameScene.BATTLE, thirtySec, PerformanceMode.BALANCED))
        assertNull(core.evaluateIdleTransition(
            GameEngineCore.GameScene.IDLE, thirtySec, PerformanceMode.BALANCED))
    }

    // ── onUserActivity 恢复路径 ──

    @Test
    fun `onUserActivity - recovers from IDLE to GAMEPLAY`() {
        core.onSceneChanged(GameEngineCore.GameScene.IDLE)
        core.onUserActivity()
        assertEquals(GameEngineCore.GameScene.GAMEPLAY, core.currentScene)
    }

    @Test
    fun `onUserActivity - recovers from GAMEPLAY_IDLE to GAMEPLAY`() {
        core.onSceneChanged(GameEngineCore.GameScene.GAMEPLAY_IDLE)
        core.onUserActivity()
        assertEquals(GameEngineCore.GameScene.GAMEPLAY, core.currentScene)
    }

    @Test
    fun `onUserActivity - keeps GAMEPLAY and MAP_SCROLL unchanged`() {
        core.onSceneChanged(GameEngineCore.GameScene.GAMEPLAY)
        core.onUserActivity()
        assertEquals(GameEngineCore.GameScene.GAMEPLAY, core.currentScene)

        core.onSceneChanged(GameEngineCore.GameScene.MAP_SCROLL)
        core.onUserActivity()
        assertEquals(GameEngineCore.GameScene.MAP_SCROLL, core.currentScene)
    }

    // ── 性能模式即时生效 ──

    @Test
    fun `setPerformanceMode - switches frame rate and quality immediately`() {
        core.setPerformanceMode(PerformanceMode.ENERGY_SAVING)
        assertEquals(30, core.renderFrameRate.value)
        assertEquals(0.8f, core.renderingQualityFactor.value, 0.001f)
        assertEquals(true, core.decorationsDisabled.value)

        core.setPerformanceMode(PerformanceMode.PERFORMANCE)
        assertEquals(60, core.renderFrameRate.value)
        assertEquals(1.0f, core.renderingQualityFactor.value, 0.001f)
        assertEquals(false, core.decorationsDisabled.value)
    }

    @Test
    fun `setPerformanceMode - idle scene stays 10fps in all modes`() {
        core.onSceneChanged(GameEngineCore.GameScene.IDLE)
        core.setPerformanceMode(PerformanceMode.ENERGY_SAVING)
        assertEquals(10, core.renderFrameRate.value)
        core.setPerformanceMode(PerformanceMode.PERFORMANCE)
        assertEquals(10, core.renderFrameRate.value)
    }

    // ── setObservedRenderFps（渲染能力帧率上报） ──

    @Test
    fun `setObservedRenderFps - updates fps and clamps to bounds`() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_000))
        core.setObservedRenderFps(30f)
        assertEquals(30f, core.fps.value, 0.001f)

        // 推进时钟越过 1s 限频窗口后，超大值钳制到 240
        ShadowSystemClock.advanceBy(Duration.ofMillis(2_000))
        core.setObservedRenderFps(500f)
        assertEquals(240f, core.fps.value, 0.001f)
    }

    @Test
    fun `setObservedRenderFps - rejects NaN zero and negative`() {
        // 每次尝试前推进时钟，排除限频干扰（验证的是值防御而非限频）
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_000))
        core.setObservedRenderFps(Float.NaN)
        assertEquals(0f, core.fps.value, 0.001f)

        ShadowSystemClock.advanceBy(Duration.ofMillis(2_000))
        core.setObservedRenderFps(0f)
        assertEquals(0f, core.fps.value, 0.001f)

        ShadowSystemClock.advanceBy(Duration.ofMillis(2_000))
        core.setObservedRenderFps(-5f)
        assertEquals(0f, core.fps.value, 0.001f)
    }

    @Test
    fun `setObservedRenderFps - rate limits to 1 per second`() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(5_000))
        core.setObservedRenderFps(30f)
        assertEquals(30f, core.fps.value, 0.001f)

        // 未推进时钟：第二次上报在限频窗口内被丢弃
        core.setObservedRenderFps(60f)
        assertEquals(30f, core.fps.value, 0.001f)
    }

    // ── 低电量 / 热控取 min ──

    @Test
    fun `low battery caps frame rate at 45 in active gameplay`() {
        val lowBatteryCore = spy(createCore(createStateStore(), gameClock,
            FakeBatteryStatus(isLowBattery = true, fpsCap = 45, thermalThresholdOffsetC = -2f)))
        // 初始场景即 GAMEPLAY，需先切走再切回以触发帧率重算
        lowBatteryCore.onSceneChanged(GameEngineCore.GameScene.BATTLE)
        lowBatteryCore.onSceneChanged(GameEngineCore.GameScene.GAMEPLAY)
        assertEquals(45, lowBatteryCore.renderFrameRate.value)
        lowBatteryCore.stopGameLoop()
    }

    @Test
    fun `thermal degradation takes min with scene and mode`() {
        // 热控建议 30fps（ORANGE 档）时，即使性能模式也只能 30
        val thermal = mock(ThermalController::class.java)
        `when`(thermal.recommendedTargetFps).thenReturn(30)
        `when`(thermal.renderingQualityFactor).thenReturn(0.6f)
        `when`(thermal.particlesDisabled).thenReturn(true)
        val core2 = spy(GameEngineCore(
            stateStore = createStateStore(),
            eventBus = mock(EventBusPort::class.java),
            unifiedPerformanceMonitor = mock(UnifiedPerformanceMonitor::class.java),
            systemManager = mock(SystemManager::class.java),
            scopeProvider = scopeProvider(),
            cultivationService = mock(CultivationService::class.java),
            explorationService = mock(com.xianxia.sect.core.engine.domain.exploration.ExplorationService::class.java),
            aiSectBeastAttackProcessor = mock(AISectBeastAttackProcessor::class.java),
            gameClock = gameClock,
            thermalController = thermal,
            thermalMonitor = mock(com.xianxia.sect.core.perf.ThermalMonitor::class.java),
            spiritStoneWallet = mock(SpiritStoneWallet::class.java),
            jadeSymbolService = JadeSymbolService(
                timeSource = TimeSource { 0L },
                stateStore = createStateStore(),
                wallClock = WallClock { 0L }
            )
        ))
        core2.setPerformanceMode(PerformanceMode.PERFORMANCE)
        core2.onSceneChanged(GameEngineCore.GameScene.GAMEPLAY)
        assertEquals(30, core2.renderFrameRate.value)
        assertEquals(0.6f, core2.renderingQualityFactor.value, 0.001f)
        assertEquals(true, core2.decorationsDisabled.value)
        core2.stopGameLoop()
    }

    // ── 工具 ──

    private fun scopeProvider(): CoroutineScopeProvider {
        val scope = mock(CoroutineScope::class.java)
        `when`(scope.coroutineContext).thenReturn(EmptyCoroutineContext)
        val provider = mock(CoroutineScopeProvider::class.java)
        `when`(provider.scope).thenReturn(scope)
        return provider
    }

    private fun createCore(
        stateStore: GameStateStore,
        gameClock: GameTimeClock,
        battery: BatteryStatusProvider
    ): GameEngineCore = GameEngineCore(
        stateStore = stateStore,
        eventBus = mock(EventBusPort::class.java),
        unifiedPerformanceMonitor = mock(UnifiedPerformanceMonitor::class.java),
        systemManager = mock(SystemManager::class.java),
        scopeProvider = scopeProvider(),
        cultivationService = mock(CultivationService::class.java),
        explorationService = mock(com.xianxia.sect.core.engine.domain.exploration.ExplorationService::class.java),
        aiSectBeastAttackProcessor = mock(AISectBeastAttackProcessor::class.java),
        gameClock = gameClock,
        thermalController = mock(ThermalController::class.java).apply {
            // mock 默认返回 0——热控未降级时必须返回全性能档，否则 minOf 恒 0
            `when`(recommendedTargetFps).thenReturn(60)
            `when`(renderingQualityFactor).thenReturn(1.0f)
            `when`(particlesDisabled).thenReturn(false)
        },
        thermalMonitor = mock(com.xianxia.sect.core.perf.ThermalMonitor::class.java),
        spiritStoneWallet = mock(SpiritStoneWallet::class.java),
        jadeSymbolService = JadeSymbolService(
            timeSource = TimeSource { 0L },
            stateStore = stateStore,
            wallClock = WallClock { 0L }
        ),
        batteryStatusProvider = battery
    )

    private fun createStateStore(): GameStateStore {
        val store = mock(GameStateStore::class.java)
        `when`(store.isPaused).thenReturn(pausedFlow)
        `when`(store.isLoading).thenReturn(MutableStateFlow(false))
        `when`(store.isSaving).thenReturn(MutableStateFlow(false))
        doAnswer { pausedFlow.value = it.getArgument(0) }
            .`when`(store).setPausedDirect(org.mockito.Mockito.anyBoolean())
        `when`(store.gameDataSnapshot).thenReturn(GameData())
        `when`(store.bootPhase).thenReturn(MutableStateFlow(BootPhase.UNINITIALIZED))
        `when`(store.runState).thenReturn(MutableStateFlow(RunState.IDLE))
        return store
    }

    /** 可控时间源（对齐 WatchdogTest 的 FakeTimeSource） */
    private class FakeTimeSource(private var now: Long) : TimeSource {
        override fun elapsedRealtime(): Long = now
        fun advanceBy(ms: Long) { now += ms }
    }
}
