package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.DomainLog
import android.os.Build
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.JadeSymbolRuntimeState
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.service.PolicyCostResult
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.loop.JitterSmoother
import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.secretRealmMemberIds
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.thermal.BatteryStatusProvider
import com.xianxia.sect.core.thermal.NoopBatteryStatus
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.engine.monitor.GameTimeProgressMonitor
import com.xianxia.sect.core.engine.monitor.GameTimeProgressSnapshot
import com.xianxia.sect.core.engine.monitor.StallVerdict
import kotlinx.coroutines.*
import com.xianxia.sect.core.overflow.OverflowMailHandler
import kotlin.concurrent.withLock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.overflow.NoOpOverflowMailHandler



/**
 * D-07 游戏循环生命周期状态机——stop/shutdown/emergencyRestart 并发交错的唯一真相源。
 *
 * 转换规则（全部经 [transitionLoopPhase] CAS，单赢家语义）：
 * - 正常启动：STOPPED → RUNNING（startGameLoop）
 * - 紧急重启：RUNNING → RESTARTING（emergencyRestartGameLoop 独占）
 *   → 重启完成 → RUNNING；中途被 stop/shutdown 抢占则放弃启动（不复活）
 * - 停止：RUNNING | RESTARTING → STOPPING → STOPPED（stopGameLoop）
 * - 关闭：RUNNING | RESTARTING | STOPPING → STOPPED（shutdown，幂等）
 *
 * 对抗性审查加固（2026-08-08）：phase 与启动世代 epoch 原子绑定为单一
 * [LoopState]——emergency 每次抢占 RUNNING 时递增 epoch；startGameLoop 从
 * CAS 结果原子取得 epoch，启动前/后校验世代未变，闭合"emergency 在 start
 * 启动窗口内完整跑完仍被 start 追加第二循环"的 ABA 窗口（孤儿循环/双循环
 * 根除，见 GameEngineCoreLifecycleInterleavingTest）。
 */
private enum class LoopPhase {
    RUNNING,
    RESTARTING,
    STOPPING,
    STOPPED
}

/** D-07 状态机原子载体：phase + epoch（紧急重启世代）单一 CAS 目标。 */
private data class LoopState(val phase: LoopPhase, val epoch: Int)

/**
 * ## GameEngineCore - 游戏循环控制器
 *
 * ### 架构层级定位（两层状态架构）
 *
 * ```
 * ┌─────────────────────────────────────────────────────────────────┐
 * │ Layer 2: UI (ViewModel/Compose)                                 │
 * │   - 通过 StateFlow 订阅 GameStateStore.unifiedState              │
 * │   - 开销: collectAsState() 触发 Compose 重组                     │
 * ├─────────────────────────────────────────────────────────────────┤
 * │ Layer 1: GameEngineCore + GameEngine                             │
 * │   - EngineCore: 游戏循环控制 (start/stop/tick)                   │
 * │   - Engine: 核心业务逻辑 (修炼/战斗/生产等)                        │
 * │   - 状态写入 GameStateStore → unifiedState Flow 自动派生          │
 * │   - 开销: MutableStateFlow.value 赋值触发下游订阅者               │
 * └─────────────────────────────────────────────────────────────────┘
 * ```
 *
 * ### 状态同步机制
 *
 * GameEngine 直接写入 GameStateStore 的各个 MutableStateFlow，
 * GameStateStore.unifiedState 通过 combine 自动派生，
 * UI 层订阅 unifiedState 即可获得最新状态，无需手动同步。
 */
/** D-17 帧循环跨帧累计状态（gameLoopIteration 参数/返回值，替代多参数漂移） */
private data class LoopIterationState(
    val accumulatorNs: Long,
    val lastFrameTimeNs: Long
)

@Singleton
@Suppress("LongParameterList") // 引擎核心 14 个真实依赖（含 EngineCrashReporter 端口），原 12 参数已 baseline 豁免
class GameEngineCore @Inject constructor(

    private val stateStore: GameStateStore,
    private val eventBus: EventBusPort,
    private val unifiedPerformanceMonitor: UnifiedPerformanceMonitor,
    private val systemManager: SystemManager,
    private val scopeProvider: CoroutineScopeProvider,
    private val cultivationService: CultivationService,
    private val explorationService: ExplorationService,
    private val aiSectBeastAttackProcessor: com.xianxia.sect.core.exploration.AISectBeastAttackProcessor,
    private val gameClock: GameTimeClock,
    private val thermalController: ThermalController,
    private val thermalMonitor: com.xianxia.sect.core.perf.ThermalMonitor,
    private val spiritStoneWallet: SpiritStoneWallet,
    /** 玉符（氪金货币）在线时长结算服务 */
    private val jadeSymbolService: JadeSymbolService,
    /** 引擎异常上报端口（app 层提供 Bugly 实现；默认 Noop 供测试/无基建场景） */
    private val engineCrashReporter: EngineCrashReporter = NoopEngineCrashReporter,
    /** 电池状态感知（低电量未充电时主动降帧/提前降载；默认 Noop 供测试） */
    private val batteryStatusProvider: BatteryStatusProvider = NoopBatteryStatus,
    /** 溢出邮件处理器（D-01 崩溃恢复：启动时排空持久化草稿；默认 Noop 供测试） */
    private val overflowMailHandler: OverflowMailHandler = NoOpOverflowMailHandler
) : EngineContextDispatcher {

    /**
     * 任务完成检测回调，由 GameEngine 在构造后注入。
     * 每月结算时被调用，确保空闲期间任务完成也能被及时检测。
     */
    @Volatile
    internal var missionCheck: (suspend () -> Unit)? = null

    // ──────── 场景感知帧率控制（P1.3） ────────

    /**
     * 游戏场景枚举 — 用于动态调整帧率预算。
     */
    enum class GameScene(val displayName: String, val targetFrameTimeMs: Long) {
        /** 后台/息屏/无操作 — 最低帧率保电 */
        IDLE("后台", 100L),
        /** 地图滚动/惯性滑行 — 30fps 足够 */
        MAP_SCROLL("地图滚动", 33L),
        /** 正常游戏（Tab、对话框操作）— 活跃 60fps */
        GAMEPLAY("游戏", 16L),
        /** 挂机静止（均衡模式动态帧率中间档）— 30fps 保电 */
        GAMEPLAY_IDLE("游戏挂机", 33L),
        /** 战斗动画 — 60fps 优先 */
        BATTLE("战斗", 16L)
    }

    /** 当前游戏场景（UI 层通过 [onSceneChanged] 设置） */
    @Volatile
    var currentScene: GameScene = GameScene.GAMEPLAY
        private set

    /** 当前性能模式（设置界面三档；引擎帧率计算的输入之一） */
    @Volatile
    var performanceMode: PerformanceMode = PerformanceMode.BALANCED
        private set

    /**
     * 设置性能模式（UI 层/启动路径调用），立即重算帧率与质量。
     */
    fun setPerformanceMode(mode: PerformanceMode) {
        if (performanceMode != mode) {
            DomainLog.i(TAG, "Performance mode: ${performanceMode.displayName} → ${mode.displayName}")
            performanceMode = mode
            updateRenderFrameRate()
        }
    }

    /** 设置游戏场景，引擎据此调整帧率预算和等待时间 */
    fun onSceneChanged(scene: GameScene) {
        if (currentScene != scene) {
            DomainLog.i(TAG, "Scene changed: ${currentScene.displayName} → ${scene.displayName}")
            currentScene = scene
            // 先更新帧率再发布场景流：Game State 上报与帧率生效保持同序，
            // 避免系统收到新场景时读到旧帧率
            updateRenderFrameRate()
            sceneStateFlow.value = scene
        }
    }

    /** 玉符运行时状态（1Hz 节流，UI 徽章/倒计时订阅入口） */
    val jadeSymbolState: StateFlow<JadeSymbolRuntimeState>
        get() = jadeSymbolService.runtimeState

    /** 玉符服务访问器（引擎扩展方法事务内扣减/刷新用；构造参数不动，测试命名参数构造兼容） */
    internal val jadeSymbolServiceRef: JadeSymbolService
        get() = jadeSymbolService

    /** 场景帧时间预算（单位：ns，用于游戏等待自适应） */
    private val sceneFrameBudgetNs: Long
        get() = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(currentScene.targetFrameTimeMs)

    /** 渲染帧率发布（供 NativeSurfaceView/SoftwareCanvasBackend 参考） */
    private val _renderFrameRate = MutableStateFlow(60)
    val renderFrameRate: StateFlow<Int> = _renderFrameRate.asStateFlow()

    /** 渲染质量因子发布（供 SoftwareCanvasBackend/Compose UI 参考） */
    private val _renderingQualityFactor = MutableStateFlow(1.0f)
    val renderingQualityFactor: StateFlow<Float> = _renderingQualityFactor.asStateFlow()

    /** 是否关闭装饰层（热控降级时） */
    private val _decorationsDisabled = MutableStateFlow(false)
    val decorationsDisabled: StateFlow<Boolean> = _decorationsDisabled.asStateFlow()

    private val sceneStateFlow = MutableStateFlow(GameScene.GAMEPLAY)

    /** 当前场景状态流（Game State API 上报用） */
    val sceneState: StateFlow<GameScene> = sceneStateFlow

    /**
     * 场景 × 性能模式基准帧率（纯函数，测试直接覆盖）。
     */
    internal fun sceneFpsFor(mode: PerformanceMode, scene: GameScene): Int = when (scene) {
        GameScene.IDLE -> FPS_IDLE
        GameScene.MAP_SCROLL -> if (mode == PerformanceMode.PERFORMANCE) FPS_ACTIVE else FPS_STILL
        GameScene.GAMEPLAY -> if (mode == PerformanceMode.ENERGY_SAVING) FPS_STILL else FPS_ACTIVE
        GameScene.GAMEPLAY_IDLE -> FPS_STILL
        GameScene.BATTLE -> if (mode == PerformanceMode.ENERGY_SAVING) FPS_STILL else FPS_ACTIVE
    }

    /**
     * 计算有效渲染帧率：场景 × 性能模式 × 热控/电量 三者取 min（降级优先）。
     */
    private fun updateRenderFrameRate() {
        val thermalFps = thermalController.recommendedTargetFps
        val mode = performanceMode
        val sceneFps = sceneFpsFor(mode, currentScene)
        val batteryCap = batteryStatusProvider.fpsCap
        val effectiveFps = minOf(thermalFps, sceneFps, batteryCap)
        _renderFrameRate.value = effectiveFps
        _renderingQualityFactor.value = minOf(thermalController.renderingQualityFactor, mode.qualityFactor)
        _decorationsDisabled.value = thermalController.particlesDisabled || mode == PerformanceMode.ENERGY_SAVING
    }

    /**
     * UI 层通知引擎：用户活跃（有触摸/操作），自动切换到 GAMEPLAY。
     * 均衡模式：闲置 5s 降 GAMEPLAY_IDLE(30fps)，闲置 30s 降 IDLE(10fps)。
     * 节能/性能模式：闲置 30s 直接降 IDLE(10fps)（深闲置是通用省电层，全模式保留）。
     */
    @Volatile
    private var lastUserActivityTimeNs: Long = 0L
    private val IDLE_TIMEOUT_NS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
    private val activityDowngradeTimeoutNs = java.util.concurrent.TimeUnit.SECONDS.toNanos(5)

    /** fps 上报限频锁与时间戳（[setObservedRenderFps]，单调时钟 elapsedRealtime） */
    private val fpsReportLock = Any()
    @Volatile
    private var lastFpsReportMs = 0L
    private val fpsReportIntervalMs = 1_000L

    /** 通知引擎用户有操作 */
    fun onUserActivity() {
        lastUserActivityTimeNs = System.nanoTime()
        if (currentScene == GameScene.IDLE || currentScene == GameScene.GAMEPLAY_IDLE) {
            onSceneChanged(GameScene.GAMEPLAY)
        }
    }

    /**
     * 闲置降档纯函数：返回应切换的目标场景（null = 不切换）。
     *
     * 状态机：
     * - 均衡模式：GAMEPLAY(60) ──5s──> GAMEPLAY_IDLE(30) ──30s──> IDLE(10)
     * - 节能/性能：GAMEPLAY ──30s──> IDLE（无中间档）
     * - GAMEPLAY_IDLE / MAP_SCROLL：无操作 30s 后统一降 IDLE
     * - BATTLE / IDLE：不因闲置切换
     */
    internal fun evaluateIdleTransition(
        scene: GameScene,
        idleNs: Long,
        mode: PerformanceMode
    ): GameScene? = when (scene) {
        GameScene.GAMEPLAY -> when {
            mode.dynamic && idleNs >= activityDowngradeTimeoutNs -> GameScene.GAMEPLAY_IDLE
            !mode.dynamic && idleNs >= IDLE_TIMEOUT_NS -> GameScene.IDLE
            else -> null
        }
        GameScene.GAMEPLAY_IDLE, GameScene.MAP_SCROLL ->
            if (idleNs >= IDLE_TIMEOUT_NS) GameScene.IDLE else null
        else -> null
    }

    /** 检查是否需要因闲置而降帧（两级降档：5s 静止 → 30fps，30s 深闲置 → 10fps） */
    private fun checkIdleTimeout(nowNs: Long) {
        if (lastUserActivityTimeNs <= 0) return
        val target = evaluateIdleTransition(currentScene, nowNs - lastUserActivityTimeNs, performanceMode)
        if (target != null) {
            // 二次验证：判定与切换之间若发生触摸（时间戳已刷新、当前评估不再满足
            // 降档条件），放弃降档——否则"降档瞬间触摸"会被吞，玩家需再触摸一次才恢复
            if (evaluateIdleTransition(currentScene, nowNs - lastUserActivityTimeNs, performanceMode) == null) return
            onSceneChanged(target)
        }
    }

    companion object {
        private const val TAG = "GameEngineCore"
        private const val TICK_INTERVAL_MS = 100L
        private const val MIN_TICK_DELAY_MS = 16L
        private const val TICK_WARNING_THRESHOLD_MS = 100f
        // isSaving/isLoading 病理级死锁最终兜底超时（90s，T12 2026-08-05）。
        // 历史教训：10s 会把低端机正常慢保存（>3-5s）误判为卡死并打断，导致反复冻结。
        // 正常保存的豁免由 GameTimeProgressMonitor（lastLoopActivityMs 判据）承担。
        // T12：60s 时友好超时（performLoadToSlot withTimeoutOrNull(60s)）与看门狗竞态——
        // 低端机+大档时看门狗抢先取消 loadJob 且取消路径静默失败。阈值提升至 90s 使
        // 友好超时（读档 60s / 保存 35s）先触发并复位标志，看门狗只拦截真正病理卡死。
        private const val SAVE_LOAD_STUCK_TIMEOUT_MS = 90_000L
        private const val ADAPTIVE_MAX_INTERVAL_MS = 1000L
        private const val TICK_TIME_BUDGET_MS = 50L
        // ★ 帧驱动 Accumulator 常量
        private val LOGIC_DT_NS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100)  // 逻辑步长 100ms
        private val MAX_ACCUMULATOR_NS = LOGIC_DT_NS * 5  // 最多累积 5 步
        // ADPF Performance Hint 目标帧时长（2026-08-14 动态化：按实际帧率换算，
        // 替代硬编码 60fps——挂机 30/10fps 时系统按真实预算调度，不再保留 60fps 性能）
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val ADPF_MIN_FPS = 10
        private const val ADPF_MAX_FPS = 60

        /** ADPF 目标帧时长纯函数：帧率 → 纳秒预算（10-60fps 钳制防除零/越界；internal 供单测） */
        internal fun frameDurationNs(fps: Int): Long =
            NANOS_PER_SECOND / fps.coerceIn(ADPF_MIN_FPS, ADPF_MAX_FPS)
        // 自适应忙等等阈值
        private const val ANTI_FREEZE_TRIGGER_THRESHOLD = 3
        private const val ANTI_FREEZE_NORMAL_THRESHOLD = 20

        // processTickPhases 返回值的 bitmask 标志
        private const val FLAG_MONTH_CHANGED = 1
        private const val FLAG_YEAR_CHANGED = 2

        // 帧率档位（场景 × 性能模式基准，sceneFpsFor 使用）
        private const val FPS_IDLE = 10
        private const val FPS_STILL = 30
        private const val FPS_ACTIVE = 60
        // 渲染能力帧率上报钳制（setObservedRenderFps）
        private const val MIN_REPORTED_FPS = 1f
        private const val MAX_REPORTED_FPS = 240f

        // 游戏引擎线程 — 非守护线程 + 最高优先级。
        // 红米K80 (HyperOS 2.0) 实测：守护线程会被电源管理挂起，
        // 导致触摸后游戏时间冻结。与看门狗线程对齐为非守护线程，
        // 优先级从 NORM-1 提升至 MAX，防止主线程重组抢占导致 delay() 续体饥饿。
        private val gameThreadFactory = ThreadFactory {
            val thread = Thread(it, "GameEngine-Thread")
            thread.priority = Thread.MAX_PRIORITY
            thread.isDaemon = false
            thread
        }

        private val GAME_DISPATCHER = Executors.newSingleThreadExecutor(gameThreadFactory)
            .asCoroutineDispatcher()

        // 看门狗专用线程工厂 — 非守护线程，独立于 Dispatchers.Default。
        // 荣耀 MagicOS 等 OEM 电源管理会挂起守护线程池中的线程，
        // 非守护线程可防止看门狗自身被冻结，确保检测→恢复链路完整。
        private val watchdogThreadFactory = ThreadFactory {
            val thread = Thread(it, "GameEngine-Watchdog")
            thread.priority = Thread.NORM_PRIORITY
            thread.isDaemon = false
            thread
        }

        private val WATCHDOG_DISPATCHER = Executors.newSingleThreadExecutor(watchdogThreadFactory)
            .asCoroutineDispatcher()

        /** 看门狗指数退避上限（毫秒） */
        internal const val WATCHDOG_MAX_BACKOFF_MS = 30_000L

        /** 秘境暂停租约续约间隔（UI 探索界面每 15s 续约一次，引擎与 UI 契约） */
        const val SECRET_REALM_RENEW_INTERVAL_MS = 15_000L

        /** 看门狗恢复动作最小间隔：防止 OEM 反复挂起时雪崩式换线程（与 Alarm 限频一致） */
        internal const val MIN_WATCHDOG_RECOVERY_INTERVAL_MS = 60_000L

        /**
         * 计算看门狗指数退避的下一个间隔。
         *
         * 规则：
         * - tick 有推进（已恢复）→ 重置为 [baseIntervalMs]
         * - tick 停滞 → 当前间隔翻倍，上限 [WATCHDOG_MAX_BACKOFF_MS]
         *
         * @param currentBackoffMs 当前退避间隔
         * @param baseIntervalMs 初始间隔（由 OemPowerProfile 驱动）
         * @param hasRecovered 本次检查 tick 是否有推进
         * @return 下一次检查的等待间隔
         */
        internal fun computeWatchdogBackoff(
            currentBackoffMs: Long,
            baseIntervalMs: Long,
            hasRecovered: Boolean
        ): Long {
            return if (hasRecovered) {
                baseIntervalMs
            } else {
                (currentBackoffMs * 2).coerceAtMost(WATCHDOG_MAX_BACKOFF_MS)
            }
        }
    }

    /** 看门狗异常处理器 — 防止看门狗因未处理异常静默死亡 */
    private val watchdogExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            DomainLog.e(TAG, "Watchdog: unhandled exception — watchdog may have died", throwable)
        }
    }

    private var currentTickInterval = TICK_INTERVAL_MS

    /** 可变的游戏循环调度器，紧急重启时可替换为新线程 */
    @Volatile
    private var gameDispatcher: CoroutineDispatcher = GAME_DISPATCHER

    /** 紧急重启中标志 — AtomicBoolean CAS 防重入（S2/F3：check-then-act 必须原子，
     *  否则三个看门狗线程并发通过检查 → 双循环双倍速） */
    private val isEmergencyRestarting = AtomicBoolean(false)

    /**
     * D-07 生命周期状态机（初始 STOPPED：构造后未启动）。
     * phase 与 epoch 原子绑定（对抗性审查 2026-08-08）：emergency 每次抢占
     * RUNNING 递增 epoch，start 从 CAS 结果原子取得并校验世代未变——闭合
     * "emergency 在 start 启动窗口内完整跑完仍被追加第二循环"的 ABA 窗口。
     */
    private val engineLoopState = AtomicReference(LoopState(LoopPhase.STOPPED, 0))

    private val engineLoopPhase: LoopPhase get() = engineLoopState.get().phase

    /**
     * D-07 工作体互斥锁（对抗性审查 2026-08-08）：CAS 状态机只门控"转换意图"，
     * 不门控 CAS 之后的破坏性/启动性工作体（stop 的拆除、shutdown 的 releaseAll/
     * engineJob.cancel、emergency 的重建+launch、start 的 launch）。stop 的过期
     * 拆除体误杀新循环、shutdown 拆除体与并发 start 交错毒化态等窗口由本锁
     * 串行化根除——锁内全部为非挂起同步操作（无挂起点，无死锁风险）。
     */
    private val loopOpLock = java.util.concurrent.locks.ReentrantLock()

    // ★ 插值因子（供 UI 平滑渲染，由 frame-driven 循环维护）
    @Volatile
    var currentAlpha: Float = 0f
        private set

    // ★ 插值因子时间平滑器（2026-08-13 批次 3；循环重启时 reset 防残留滤波状态）
    private val jitterSmoother = JitterSmoother()

    // ── 自适应忙等 ──
    @Volatile
    private var antiFreezeEnabled = false
    private var antiFreezeTriggerCount = 0
    private var consecutiveNormalTicks = 0
    private var lastActualElapsedMs = 0L

    private val engineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            DomainLog.e(TAG, "Unhandled exception in engine coroutine", throwable)
        }
    }
    private var engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
    private var engineScope: CoroutineScope = CoroutineScope(engineJob + gameDispatcher + engineExceptionHandler)

    /** ADPF 目标帧时长联动协程（2026-08-14；prepareLoopStart 重启前取消防堆积） */
    private var adpfTargetJob: Job? = null

    fun launchInScope(block: suspend CoroutineScope.() -> Unit): Job = engineScope.launch(block = block)

    /**
     * 在引擎线程上执行指定代码块并返回结果。
     * 若当前已在引擎线程上，则不切换上下文（coroutines 自动优化）。
     * 用于确保 [stateStore.update] 调用在引擎线程上执行，避免主线程 ANR。
     */
    override suspend fun <T> withEngineContext(block: suspend CoroutineScope.() -> T): T {
        return withContext(gameDispatcher, block)
    }

    fun scopeForStateIn(): CoroutineScope = engineScope
    /** @Volatile（F3）：三个看门狗线程并发读 isActive，弱内存模型下非 volatile 可能读到陈旧 job */
    @Volatile
    private var gameLoopJob: Job? = null
    private var gameLoopStoppedSignal = CompletableDeferred<Unit>()
    
    private val _tickCount = MutableStateFlow(0L)
    val tickCount: StateFlow<Long> = _tickCount.asStateFlow()
    
    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()
    
    private var lastFrameTime = System.currentTimeMillis()
    
    val events: Flow<DomainEvent> get() = eventBus.events

    private var isInitialized = false

    /** 记录 isSaving 变为 true 的时间戳，用于看门狗检测 */
    @Volatile
    private var savingStartTime: Long = 0L

    /** 记录 isLoading 变为 true 的时间戳，用于看门狗检测 */
    @Volatile
    private var loadingStartTime: Long = 0L

    /** 当前正在运行的加载协程 Job，用于看门狗强制取消 */
    @Volatile
    private var activeLoadJob: Job? = null

    /**
     * 看门狗病理复位事件通道（T12 2026-08-05）。
     * forceResetStuckStates 被看门狗触发（非 onCleared 正常清理）时发出用户可见事件，
     * SaveLoadViewModel 收集后弹错误提示——此前取消路径静默失败。
     * replay=1（对抗性审查整改 2026-08-05）：VM 空窗期（主菜单/未创建）的复位事件
     * 不丢失——replay=0 时无订阅者 tryEmit 直接丢弃，"不再静默"承诺在空窗期失效。
     */
    private val _stuckResetEvents = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val stuckResetEvents: SharedFlow<String> = _stuckResetEvents.asSharedFlow()

    /** 独立看门狗 Job — 运行在 Dispatchers.Default 上，监控游戏线程是否卡死 */
    private var watchdogJob: Job? = null

    /** 看门狗恢复尝试次数（跨重启累计，仅在 tick 推进时重置）——@Volatile（F6：跨线程可见性） */
    @Volatile
    private var watchdogRecoveryAttempts = 0

    /** 看门狗连续失败次数达到此阈值后使用更长间隔，避免 OEM 永久挂起时频繁重启 */
    private val watchdogDegradedThreshold = 10

    // ── 游戏时间推进监控（第一类监控：看门狗统一判据） ──

    /** 停滞判定器（纯函数组件，三层看门狗共享同一判定出口） */
    private val gameTimeProgressMonitor = GameTimeProgressMonitor()

    /** 游戏循环体最近活动墙钟（每次迭代更新，含暂停/保存跳过路径） */
    @Volatile
    private var lastLoopActivityMs: Long = 0L

    /** 上次 tick 实际耗时（异常上报上下文用） */
    @Volatile
    private var lastTickDurationMs: Long = 0L

    /** 引擎循环最近一次真实 tick 的进度快照（假运行时也更新） */
    @Volatile
    private var lastProgressSnapshot: GameTimeProgressSnapshot? = null

    /** 秘境暂停租约最后续约墙钟（renewSecretRealmPauseLease 刷新） */
    @Volatile
    private var secretRealmPauseRenewedAtMs: Long = 0L

    /** 看门狗上次恢复动作墙钟（60s 限频） */
    @Volatile
    private var lastWatchdogRecoveryMs: Long = 0L

    fun initialize() {
        if (isInitialized) {
            DomainLog.w(TAG, "GameEngineCore already initialized")
            return
        }
        systemManager.initializeAll()
        isInitialized = true
        DomainLog.i(TAG, "GameEngineCore initialized")
        DomainLog.i(TAG, "GameEngineCore initialized successfully")
    }
    
    // catch Throwable 为 D-07 phase 中毒回滚语义必需（任何异常都须回滚状态机）
    @Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
    fun startGameLoop(resetWatchdogAttempts: Boolean = true) {
        if (gameLoopJob?.isActive == true) {
            // 冷启动读档竞态修复：循环已被第三方（前台服务 ACTION_START / watchdog
            // 兜底 / START_STICKY 重投递）抢先启动，而运行时玉符仍锚定在读档前的
            // 旧/空快照——boot 的 startGameLoop 走到此处若直接 return，后续
            // grantFromAd/checkpointNow/settleGrants 的绝对值写会覆盖已持久化余额
            // （玩家反馈：读档后看广告玉符 20→3）。无条件重锚（幂等：volatile
            // 内存写 + UI 发布，写库延迟到引擎线程首帧 tick，主线程调用安全）。
            jadeSymbolService.onLoopStart()
            DomainLog.w(TAG, "startGameLoop: 循环已运行，重锚玉符运行时")
            return
        }
        // D-07 状态机：仅 STOPPED → RUNNING。RESTARTING（emergency 独占）/
        // STOPPING/重复 RUNNING 一律拒绝——防止 emergency 与正常启动交错出双循环
        val won = transitionLoopPhase(setOf(LoopPhase.STOPPED), LoopPhase.RUNNING)
        if (won == null) {
            DomainLog.w(TAG, "startGameLoop rejected (phase=${engineLoopPhase})")
            return
        }
        // 对抗性审查（孤儿循环）：CAS 只赢下转换意图，启动体必须在锁内执行——
        // stop/shutdown/emergency 的工作体与 start 的启动体串行化，过期拆除体
        // 不误杀新循环；epoch 取自 CAS 结果（原子），启动前/后校验世代未变
        val epoch = won.epoch
        loopOpLock.withLock {
            try {
                startGameLoopInternal(resetWatchdogAttempts, LoopPhase.RUNNING, epoch)
            } catch (t: Throwable) {
                // 对抗性审查（phase 中毒）：启动体异常（drain/onLoopStart/
                // startWatchdog/launch 拒绝）→ 回滚 RUNNING → STOPPED，避免
                // phase 停 RUNNING 无 job 导致后续 start 永久被拒（原实现
                // 无状态残留可自然重试，属行为退化，此处恢复该语义）
                engineLoopState.compareAndSet(
                    LoopState(LoopPhase.RUNNING, epoch),
                    LoopState(LoopPhase.STOPPED, epoch)
                )
                throw t
            }
        }
    }

    /**
     * D-07 启动体——公共 [startGameLoop]（expectedPhase=RUNNING）与
     * emergencyRestartGameLoop（expectedPhase=RESTARTING，由 emergency 自行
     * 收尾转 RUNNING）经 CAS + 锁后直调。launch 前/后双重校验 phase 与 epoch
     * 未变（对抗性审查 2026-08-08 根治孤儿循环/双循环）：
     * - launch 前：CAS 与锁之间被抢占（stop 已移走 phase / emergency 已递增
     *   epoch / emergency 的循环已启动）→ 放弃启动，不复活
     * - launch 后：防御性二次校验（锁纪律破坏时兜底）→ 撤销自身 job
     */
    private fun startGameLoopInternal(
        resetWatchdogAttempts: Boolean,
        expectedPhase: LoopPhase,
        expectedEpoch: Int
    ) {
        val preLaunchState = engineLoopState.get()
        if (preLaunchState.phase != expectedPhase || preLaunchState.epoch != expectedEpoch) {
            DomainLog.w(TAG, "startGameLoopInternal aborted (phase=${preLaunchState.phase}, " +
                "epoch=${preLaunchState.epoch}, expected=$expectedPhase/$expectedEpoch)")
            return
        }
        // 防御：emergency 的循环已在 CAS 与锁之间启动（仅理论 ABA 残留窗口）
        if (gameLoopJob?.isActive == true) {
            DomainLog.w(TAG, "startGameLoopInternal aborted (loop already active)")
            return
        }
        prepareLoopStart(resetWatchdogAttempts)

        // 对抗性审查（signal 跨代完成）：捕获局部变量——旧循环 finally 完成的是
        // 捕获时点的 signal，而非字段。字段在每次启动时重建，旧 job 若读字段
        // 会完成新一代 signal：等待方挂满超时（重启中断）或提前放行（与运行中
        // 循环并发写状态）。捕获局部变量使跨代引用不可能发生
        val signal = gameLoopStoppedSignal
        val job = engineScope.launch { gameLoopMainLoop(signal) }
        gameLoopJob = job
        // 对抗性审查（孤儿循环）launch 后二次校验：CAS 与 launch 之间被抢占
        // （stop/shutdown 移走 phase / emergency 递增 epoch）→ 撤销自身 job，
        // 循环不复活。锁内串行化下恒真，保留为锁纪律的防御性兜底
        if (engineLoopState.get().phase != expectedPhase ||
            engineLoopState.get().epoch != expectedEpoch
        ) {
            job.cancel()
            if (gameLoopJob === job) gameLoopJob = null
            DomainLog.w(TAG, "startGameLoopInternal cancelled after launch " +
                "(phase=${engineLoopPhase})")
        }
    }

    /**
     * D-17 启动前置初始化（startGameLoopInternal 拆分）：时钟/停止信号/监控/
     * 草稿排空/玉符锚定/暂停保留/看门狗启动。
     *
     * @param resetWatchdogAttempts 全新启动 true；emergencyRestart 传 false
     * （F6：保留降级计数，否则降级模式永不生效）
     */
    private fun prepareLoopStart(resetWatchdogAttempts: Boolean) {
        // 全新启动时重置看门狗累计失败计数，防止跨 session 残留
        // 导致第二次进入游戏时看门狗以降级模式（30s 间隔）启动
        if (resetWatchdogAttempts) {
            watchdogRecoveryAttempts = 0
        }

        gameClock.start()
        gameLoopStoppedSignal = CompletableDeferred()
        unifiedPerformanceMonitor.start()
        // D-08 接线（2026-08-08）：热状态监控绑定当前 engineScope——start 与
        // emergencyRestart 均经本函数（emergency 锁内已重建 scope，ThermalMonitor
        // start 无条件重建轮询 job 绑定新 scope）。stop 侧对称见
        // stopGameLoopUnchecked（stopGameLoop/shutdown/pauseForBackground 全经此）
        thermalMonitor.start(engineScope)
        // ADPF 目标帧时长联动（2026-08-14 平板省电）：有效帧率（场景/模式/热控/
        // 电量四层 min）任何一次重算 → 系统性能预算同步。专用 job 重启前取消，
        // 防 stop→start 周期在单 scope 内堆积多个 collect 协程
        adpfTargetJob?.cancel()
        adpfTargetJob = engineScope.launch {
            renderFrameRate.collect { fps ->
                thermalMonitor.setTargetWorkDuration(frameDurationNs(fps))
            }
        }
        // D-01 崩溃恢复：启动/重启（含 emergencyRestart）必经路径——排空上次
        // 崩溃遗留的持久化草稿（幂等，无草稿即空转；非挂起异步调度不阻塞启动）
        overflowMailHandler.drainPersistedDrafts()

        // 玉符在线时长结算：从 GameData 快照恢复运行时字段（读档/切档/重启天然正确）。
        // 只读快照零写入——跨天检查/首次锚定的 GameData 写入延迟到引擎线程首帧 tick
        // （startGameLoop 可能在主线程被调，update 有主线程运行时守卫）
        jadeSymbolService.onLoopStart()

        // 零触摸会话修复：游戏循环启动即视为活跃起点——否则 lastUserActivityTimeNs
        // 恒 0 导致 checkIdleTimeout 提前返回，动态帧率在无人值守挂机时永不降档
        lastUserActivityTimeNs = System.nanoTime()

        // F4：无条件置 false 会静默丢掉秘境暂停/用户暂停——按暂停来源保留，
        // 作为 startGameLoop 的通用职责（所有重启路径统一语义，不再只在
        // resumeFromBackground 一处补偿）
        val preservePause = secretRealmPauseLock || wasUserPausedBeforeBackground
        stateStore.setPausedDirect(preservePause)
        DomainLog.i(TAG, "Game state resumed (isPaused=$preservePause)")

        val gd = stateStore.gameDataSnapshot
        DomainLog.i(TAG, "startGameLoop: lifecycle=${stateStore.bootPhase.value}/${stateStore.runState.value}, " +
            "speed=${gameClock.speed}, " +
            "year=${gd.gameYear}, month=${gd.gameMonth}, " +
            "sectName=${gd.sectName}")

        // 启动独立看门狗，监控游戏线程是否被 PowerGenie 等 OEM 挂起
        startWatchdog()
    }

    /**
     * D-17 循环主循环（startGameLoopInternal 拆分）——launch 块内容：线程优先级、
     * ADPF session 创建、帧循环、finally 收尾（玉符 checkpoint + 停止信号完成）。
     * 单帧迭代提取 [gameLoopIteration]（跨帧累计状态经 [LoopIterationState] 传递）。
     *
     * @param signal 捕获于启动时点的停止信号（防跨代完成，见 startGameLoopInternal）
     */
    private suspend fun CoroutineScope.gameLoopMainLoop(signal: CompletableDeferred<Unit>) {
        DomainLog.i(TAG, "Starting game loop")

        // 双重保险：线程工厂已设 MAX_PRIORITY，但部分 OEM 覆盖线程优先级。
        // Process.setThreadPriority(THREAD_PRIORITY_URGENT_DISPLAY) 是 Linux
        // nice 值 -8，低于音频线程 THREAD_PRIORITY_AUDIO (-16)，
        // 防止游戏线程优先级高于音频混音线程导致 buffer underrun（红米/小米等设备上音频断续）
        try {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            )
            DomainLog.d(TAG, "Game thread priority: URGENT_DISPLAY (-8)")
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            DomainLog.w(TAG, "Cannot set thread priority: ${e.message}")
        }

        // ★ 帧驱动 Accumulator 循环
        var state = LoopIterationState(accumulatorNs = 0L, lastFrameTimeNs = System.nanoTime())
        // 循环启动/换线程重启：清插值平滑状态（防旧线程残留滤波值污染新循环首帧）
        jitterSmoother.reset()
        // ADPF: 创建 Performance Hint Session（API 31+，低版本自动跳过）。
        // 目标按当前有效帧率换算（2026-08-14 动态化；后续帧率变化经
        // prepareLoopStart 的 renderFrameRate collect 联动更新）
        thermalMonitor.createHintSession(frameDurationNs(_renderFrameRate.value))
        try {
            while (isActive) {
                state = gameLoopIteration(state)
            }
        } finally {
            thermalMonitor.closeHintSession()
            // 玉符 checkpoint 放循环协程 finally（引擎线程）：覆盖 cancel/
            // emergencyRestart/正常退出全部停止路径。不能放在 stopGameLoop
            // 同步调用——主线程链路（onPause 后台切换）调用时 update 会命中
            // stateStore 主线程运行时守卫（Debug 崩 / Release 静默丢进度）
            jadeSymbolService.onLoopStop()
            signal.complete(Unit)
            DomainLog.i(TAG, "Game loop stopped signal sent")
        }
    }

    /**
     * D-17 单帧迭代（gameLoopMainLoop 拆分）——原 while 体内逻辑：心跳/玉符 tick/
     * delta 累计/暂停分支/固定步长 tick/插值因子/闲置超时/场景感知自适应等待。
     *
     * @param state 跨帧累计状态（accumulatorNs/lastFrameTimeNs）
     * @return 更新后的累计状态（暂停分支与异常路径会清零 accumulatorNs，语义同原 continue）
     */
    private suspend fun gameLoopIteration(state: LoopIterationState): LoopIterationState {
        var accumulatorNs = state.accumulatorNs
        var lastFrameTimeNs = state.lastFrameTimeNs
        try {
            // 循环活动心跳：每次迭代更新（含暂停分支与 skip 路径），
            // 供看门狗区分"正常慢保存/暂停"与"循环停滞/引擎死亡"
            lastLoopActivityMs = gameClock.nowMs()

            // 玉符在线时长累计：循环顶部挂钩（暂停分支 continue 在其后执行 →
            // 挂机/暂停照常累计；切后台循环整体停止 → 自然不累计）
            jadeSymbolService.onLoopTick()

            // Step 1: 计算 deltaTime
            val nowNs = System.nanoTime()
            var deltaNs = nowNs - lastFrameTimeNs
            lastFrameTimeNs = nowNs
            deltaNs = deltaNs.coerceAtMost(MAX_ACCUMULATOR_NS)

            // Step 2: 暂停/加载时不积累
            if (stateStore.isPaused.value || stateStore.isLoading.value) {
                return handlePausedIteration(lastFrameTimeNs)
            }

            // Step 3: Accumulate + FixedUpdate
            accumulatorNs += deltaNs
            var stepsExecuted = 0
            while (accumulatorNs >= LOGIC_DT_NS && stepsExecuted < 5) {
                tickInternal()
                accumulatorNs -= LOGIC_DT_NS
                stepsExecuted++
            }

            // Step 4: 插值因子（2026-08-13 批次 3：经 JitterSmoother 一阶滤波——
            // 只平滑渲染契约 alpha，游戏时间由 GameTimeClock 独立累积不受影响）
            val rawAlpha = (accumulatorNs.toFloat() / LOGIC_DT_NS.toFloat()).coerceIn(0f, 1f)
            currentAlpha = jitterSmoother.filter(rawAlpha)

            // Step 5: 闲置超时检测
            checkIdleTimeout(nowNs)

            // Step 6: 场景感知自适应等待（P1.3）
            adaptiveWait(stepsExecuted, deltaNs, nowNs)
            updateRenderFrameRate()
            // ADPF: 报告帧实际耗时（deltaNs 为帧间实际间隔）
            thermalMonitor.reportActualWorkDuration(deltaNs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleTickCrash(e)
            gameClock.consumeDeadTime()
            accumulatorNs = 0L
        }
        return LoopIterationState(accumulatorNs = accumulatorNs, lastFrameTimeNs = lastFrameTimeNs)
    }

    /** D-17 单帧崩溃兜底（gameLoopIteration 拆分）：归因上下文采集 + 日志 + 异常上报，自身永不抛异常 */
    private fun handleTickCrash(e: Exception) {
        // 第一性原理：catch 块自身必须永不抛异常——任何状态读取失败
        // 都不能杀死游戏循环（测试曾复现：catch 内读 isPaused 抛异常 → 协程死亡）
        @Suppress("TooGenericExceptionCaught") // 防御性兜底：状态读取异常类型不可预期
        val crashContext = try {
            val gd = stateStore.gameDataSnapshot
            mapOf(
                "year" to gd.gameYear.toString(),
                "month" to gd.gameMonth.toString(),
                "phase" to gd.gamePhase.toString(),
                "tickCount" to _tickCount.value.toString(),
                "scene" to currentScene.name,
                "isPaused" to stateStore.isPaused.value.toString(),
                "isSaving" to stateStore.isSaving.value.toString(),
                "isLoading" to stateStore.isLoading.value.toString(),
                "speed" to gameClock.speed.toString(),
                "lastTickMs" to lastTickDurationMs.toString(),
                "watchdogAttempts" to watchdogRecoveryAttempts.toString(),
                "oem" to OemPowerProfileProvider.currentManufacturer.name
            )
        } catch (contextFailure: Exception) {
            mapOf("contextError" to (contextFailure.message ?: "unknown"))
        }
        DomainLog.e(TAG,
            "Game loop tick crashed: year=${crashContext["year"] ?: "?"} " +
            "tickCount=${_tickCount.value} scene=${currentScene}", e)
        // 异常归因上报：让下次回归有证据可循（历史多次"吞异常+重启"修复无归因）
        // 上报失败必须被吞——上报通道异常不得杀死游戏循环
        @Suppress("TooGenericExceptionCaught") // 上报通道失败类型不可预期，必须全吞
        try {
            engineCrashReporter.postCatchedException(e, crashContext)
        } catch (reportFailure: Exception) {
            DomainLog.w(TAG, "Crash reporter failed", reportFailure)
        }
    }

    /**
     * D-17 暂停/加载迭代（gameLoopIteration 拆分）：暂停期间也刷新进度快照
     * （flags 实时化）——否则快照过期，看门狗无法判定秘境锁残留/用户暂停豁免。
     */
    private suspend fun handlePausedIteration(lastFrameTimeNs: Long): LoopIterationState {
        sampleProgressSnapshot()
        // 激活 60s 病理兜底（2026-08-04 修复）：原实现 continue 绕过
        // skipTickIfNeeded → checkAndResetStuckStates 从未执行。
        // isLoading 卡死时看门狗对其豁免且循环心跳持续跳动 →
        // 时间永久冻结、tick 驱动按钮全部无效且永不自愈。
        // checkAndResetStuckStates 为非挂起纯函数，仅复位
        // isSaving/isLoading 标志 + 取消加载 Job，不触碰 isPaused。
        checkAndResetStuckStates(
            isSaving = stateStore.isSaving.value,
            isLoading = stateStore.isLoading.value
        )
        gameClock.consumeDeadTime()
        delay(50)
        return LoopIterationState(accumulatorNs = 0L, lastFrameTimeNs = lastFrameTimeNs)
    }

    /** D-17 场景感知自适应等待（gameLoopIteration 拆分，P1.3）：无 tick 按帧预算等待，有 tick 防超预算 */
    private suspend fun adaptiveWait(stepsExecuted: Int, deltaNs: Long, nowNs: Long) {
        if (stepsExecuted == 0 && deltaNs < LOGIC_DT_NS) {
            // 无 tick 执行 → 按场景帧预算等待
            val budgetMs = currentScene.targetFrameTimeMs
            val consumedMs = (System.nanoTime() - nowNs) / 1_000_000
            val waitMs = (budgetMs - consumedMs).coerceIn(1L, budgetMs)
            delay(waitMs)
        } else {
            // 有 tick 执行 → 确保不超过场景帧预算
            val frameElapsedMs = (System.nanoTime() - nowNs) / 1_000_000
            val budgetMs = currentScene.targetFrameTimeMs
            if (frameElapsedMs < budgetMs) {
                antiFreezeDelay(budgetMs - frameElapsedMs, deltaNs / 1_000_000)
            }
        }
    }

    fun stopGameLoop() {
        // D-07 状态机：RUNNING/RESTARTING → STOPPING 单赢家；已 STOPPING/STOPPED
        // 幂等返回（重复调用不重复执行）。emergency 重启中调 stop → 抢占成功，
        // emergency 的启动前检查（phase 已离开 RESTARTING）将放弃启动（不复活）
        if (transitionLoopPhase(setOf(LoopPhase.RUNNING, LoopPhase.RESTARTING), LoopPhase.STOPPING) == null) {
            DomainLog.d(TAG, "stopGameLoop: already stopping/stopped (phase=${engineLoopPhase})")
            return
        }
        // 对抗性审查（过期拆除体误杀新循环）：拆除体在锁内执行——stop 的 CAS 与
        // 拆除体之间若 shutdown+start 完成，stop 恢复后的取消会杀掉新循环且
        // CAS2 失败静默返回（永久冻结）。锁串行化后拆除体只作用于"自己赢下
        // STOPPING 时的循环"，新循环在 stop 释放锁后启动，不受影响
        loopOpLock.withLock {
            stopGameLoopUnchecked()
            transitionLoopPhase(setOf(LoopPhase.STOPPING), LoopPhase.STOPPED)
            DomainLog.i(TAG, "Game loop stop requested, isPaused=true")
        }
    }

    /** D-07 停止体——调用方（stopGameLoop/shutdown）已完成状态机 CAS 且持锁后执行 */
    private fun stopGameLoopUnchecked() {
        unifiedPerformanceMonitor.stop()
        // D-08 接线：与 startGameLoopInternal 的 thermalMonitor.start(engineScope)
        // 对称——stopGameLoop/shutdown/pauseForBackground（后台切换走 stopGameLoop）
        // 全经本函数，热监控轮询不残留
        thermalMonitor.stop()
        stopWatchdog()
        stateStore.setPausedDirect(true)
        gameLoopJob?.cancel()
        gameLoopJob = null
    }

    suspend fun stopGameLoopAndWait(timeoutMs: Long = 5000): Boolean {
        // 对抗性审查（边界语义）：循环未运行即已停止——空态立即返回 true，
        // 避免挂满超时返回 false 误导调用方（signal 无循环永不完成）
        if (!isGameLoopRunning) return true
        stopGameLoop()
        return waitForLoopStopped(timeoutMs)
    }

    /**
     * D-17 等待循环停止（stopGameLoopAndWait 拆分）。
     * 对抗性审查（边界语义）：kotlinx-coroutines 1.9.0 的 withTimeoutOrNull
     * 在 timeMillis<=0 时直接返回 null 不执行 block——停止实际已完成却报
     * false 误导调用方。显式处理：0/负超时视为"不等待，返回当前停止状态"
     */
    private suspend fun waitForLoopStopped(timeoutMs: Long): Boolean {
        if (timeoutMs <= 0) return !isGameLoopRunning
        return withTimeoutOrNull(timeoutMs) {
            gameLoopStoppedSignal.await()
            true
        } ?: run {
            DomainLog.w(TAG, "Game loop did not stop within ${timeoutMs}ms")
            false
        }
    }

    fun shutdown() {
        // D-07 状态机：RUNNING/RESTARTING/STOPPING → STOPPED 单赢家；已 STOPPED
        // 幂等返回。抢占 emergency 的重启意图——emergency 启动前 phase 检查 abort
        if (transitionLoopPhase(
                setOf(LoopPhase.RUNNING, LoopPhase.RESTARTING, LoopPhase.STOPPING),
                LoopPhase.STOPPED
            ) == null
        ) {
            DomainLog.i(TAG, "shutdown: already stopped, idempotent")
            return
        }
        // 对抗性审查（毒化态）：拆除体（releaseAll/engineJob.cancel/重建 scope）
        // 在锁内执行——并发 start 的启动体等待锁，launch 必发生在拆除完成后
        // 的新 scope 上，不再出现"start 启动在旧 engineJob 上被迟到的 cancel
        // 连带取消 → phase=RUNNING 但循环死"的毒化态
        loopOpLock.withLock {
            stopGameLoopUnchecked()
            systemManager.releaseAll()
            engineJob.cancel()
            // 不关闭 GAME_DISPATCHER：shutdown 后可能重新 start，需保持线程池可用
            // 若必须关闭，需同时重建 GAME_DISPATCHER（静态 val 无法替换，故此处仅 cancel job）
            // WATCHDOG_DISPATCHER 同理：shutdown 后可能重新 startGameLoop → startWatchdog
            engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
            engineScope = CoroutineScope(engineJob + gameDispatcher + engineExceptionHandler)
            isInitialized = false
            DomainLog.i(TAG, "GameEngineCore shutdown complete")
        }
    }

    // ── 独立看门狗 — 监控游戏线程是否被 PowerGenie 等 OEM 机制挂起 ──

    /**
     * 启动独立看门狗协程，运行在专用非守护线程上。
     *
     * 每 3-5 秒检查一次 tickCount 是否有推进。如果在游戏循环声明为活跃
     * 的状态下 tickCount 停滞，说明游戏线程可能被 OEM 省电机制挂起，
     * 触发恢复流程。
     *
     * 看门狗线程：
     * - 非守护线程（isDaemon=false）：荣耀 MagicOS 等 OEM 会挂起守护线程池
     *   中的线程，非守护线程可防止看门狗自身被冻结。
     * - 独立单线程执行器：与 Dispatchers.Default 线程池完全隔离。
     * - 检查间隔由 [OemPowerProfileProvider.current] 数据驱动：
     *   激进 OEM（Honor/vivo）3s，中等 OEM（Xiaomi/OPPO）4s，保守 OEM 5s。
     */
    private fun startWatchdog() {
        stopWatchdog()
        // 跨重启累计恢复尝试次数（仅在 tick 推进时清零，不在重启时重置）
        // 连续失败超过阈值时进入降级模式，使用更长间隔减少频繁重启
        val baseIntervalMs = OemPowerProfileProvider.current.watchdogIntervalMs
        val degradedMode = watchdogRecoveryAttempts >= watchdogDegradedThreshold
        val effectiveBaseMs = if (degradedMode) {
            (baseIntervalMs * 4).coerceAtLeast(30_000L)  // 降级模式：至少 30s 间隔
        } else {
            baseIntervalMs
        }
        if (degradedMode && watchdogRecoveryAttempts == watchdogDegradedThreshold) {
            DomainLog.w(TAG,
                "Watchdog: entering degraded mode after $watchdogRecoveryAttempts " +
                "consecutive failures — increasing check interval to ${effectiveBaseMs / 1000}s")
        }
        watchdogJob = CoroutineScope(
            WATCHDOG_DISPATCHER + SupervisorJob() + watchdogExceptionHandler
        ).launch {
            // 当前退避间隔：失败后翻倍递增（如 3s→6s→12s→24s→30s 上限），成功后重置为初始间隔
            var currentBackoffMs = effectiveBaseMs
            while (isActive) {
                try {
                delay(currentBackoffMs)
                when (val verdict = progressVerdict()) {
                    StallVerdict.Healthy, StallVerdict.PausedByOwner -> {
                        // 引擎健康 / 暂停有主（用户主动暂停或秘境租约有效）：重置失败计数
                        watchdogRecoveryAttempts = 0
                        currentBackoffMs = computeWatchdogBackoff(
                            currentBackoffMs, baseIntervalMs, hasRecovered = true
                        )
                    }
                    StallVerdict.LoopStalled, StallVerdict.FakeRunDetected,
                    StallVerdict.StalePauseDetected -> {
                        watchdogRecoveryAttempts++
                        val atMaxBackoff = currentBackoffMs >= WATCHDOG_MAX_BACKOFF_MS
                        val shouldLog = !atMaxBackoff || watchdogRecoveryAttempts % 10 == 0
                        if (shouldLog) {
                            DomainLog.w(TAG,
                                "Watchdog: verdict=$verdict no progress in " +
                                "${currentBackoffMs / 1000}s " +
                                "(attempt $watchdogRecoveryAttempts" +
                                if (degradedMode) ", degraded" else "" +
                                ")")
                        }
                        handleWatchdogVerdict(verdict)
                        currentBackoffMs = computeWatchdogBackoff(
                            currentBackoffMs, effectiveBaseMs, hasRecovered = false
                        )
                    }
                }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    DomainLog.e(TAG, "Watchdog: loop error, continuing", e)
                    delay(1000)
                }
            }
        }
    }

    /**
     * 游戏时间推进判定 — 三层看门狗（引擎内/主线程 HealthCheck/Alarm）统一出口。
     *
     * 判据为"tickCount + totalPhases + accumulatedGameMs"三元组（由
     * [GameTimeProgressMonitor] 纯函数判定），覆盖历史失明的两类冻结形态：
     * isPaused 卡死（StalePauseDetected）与 speed=0 假运行（FakeRunDetected）。
     */
    fun progressVerdict(): StallVerdict {
        val snapshot = lastProgressSnapshot
            ?: sampleProgressSnapshot()  // 循环从未 tick：flags-only 快照（暂停租约等仍可判定）
        val current = snapshot.copy(
            loopActive = gameLoopJob?.isActive == true,
            isPaused = stateStore.isPaused.value,
            isSaving = stateStore.isSaving.value,
            isLoading = stateStore.isLoading.value,
            secretRealmPauseLock = secretRealmPauseLock,
            secretRealmPauseRenewedAtMs = secretRealmPauseRenewedAtMs,
            loopActiveAtMs = lastLoopActivityMs,
            recordedAtMs = gameClock.nowMs()
        )
        return gameTimeProgressMonitor.evaluate(current)
    }

    /**
     * 采样进度快照：看门狗统一判据输入（tickCount + totalPhases + accumulatedGameMs + flags）。
     * 循环体每次迭代（含暂停/加载分支）调用，保证快照新鲜。
     */
    private fun sampleProgressSnapshot(): GameTimeProgressSnapshot {
        // V4：getSystem 缺失时抛 IllegalStateException（不是返回 null——`?: 0L` 是死代码），
        // 采样本身不得成为崩溃源——回退上次快照值
        val totalPhases = try {
            systemManager.getSystem(TimeSystem::class)?.getTotalPhases()?.toLong() ?: 0L
        } catch (e: Exception) {
            DomainLog.w(TAG, "getSystem(TimeSystem) failed, using last snapshot totalPhases", e)
            lastProgressSnapshot?.totalPhases ?: 0L
        }
        val snapshot = GameTimeProgressSnapshot(
            tickCount = _tickCount.value,
            totalPhases = totalPhases,
            accumulatedGameMs = gameClock.accumulatedGameMs,
            loopActive = gameLoopJob?.isActive == true,
            isPaused = stateStore.isPaused.value,
            isSaving = stateStore.isSaving.value,
            isLoading = stateStore.isLoading.value,
            speed = gameClock.speed,
            secretRealmPauseLock = secretRealmPauseLock,
            secretRealmPauseRenewedAtMs = secretRealmPauseRenewedAtMs,
            loopActiveAtMs = lastLoopActivityMs,
            recordedAtMs = gameClock.nowMs()
        )
        lastProgressSnapshot = snapshot
        return snapshot
    }

    /**
     * 看门狗自愈动作 — 引擎内看门狗与 Alarm 兜底共享的单一入口。
     *
     * @param verdict [progressVerdict] 的判定结果（仅需处理非健康/非豁免判定）
     */
    fun handleWatchdogVerdict(verdict: StallVerdict) {
        when (verdict) {
            StallVerdict.Healthy, StallVerdict.PausedByOwner -> Unit
            StallVerdict.FakeRunDetected -> {
                if (gameClock.speed == 0) {
                    // speed=0 假运行：时钟暂停但循环健康，直接恢复 1x（不动线程）
                    DomainLog.w(TAG,
                        "Watchdog: fake run detected (speed=0), restoring speed to 1x")
                    gameClock.setSpeed(1)
                    gameClock.consumeDeadTime()
                } else {
                    // speed>0 但世界时间冻结：时钟异常，走换线程恢复
                    DomainLog.w(TAG,
                        "Watchdog: fake run detected (time frozen at speed=${gameClock.speed}), " +
                        "recovering with new thread")
                    performWatchdogRecovery()
                }
            }
            StallVerdict.StalePauseDetected -> {
                // 秘境暂停锁残留（界面已销毁但 exitExploration 丢失）→ 自愈
                val staleSeconds = (gameClock.nowMs() - secretRealmPauseRenewedAtMs) / 1000
                DomainLog.w(TAG,
                    "Watchdog: stale secret-realm pause detected ($staleSeconds" +
                    "s without renewal), self-healing")
                secretRealmPauseLock = false
                secretRealmPauseRenewedAtMs = 0L
                stateStore.setPausedDirect(false)
                // V5：不主动重启循环——后台场景避免后台推进游戏时间；
                // 回前台由 resumeFromBackground 重启（_wasPausedByBackground 已置位），
                // 引擎挂起场景由看门狗下一轮 LoopStalled → 换线程恢复
            }
            StallVerdict.LoopStalled -> performWatchdogRecovery()
        }
    }

    /**
     * 看门狗恢复动作（换全新线程），带 60s 限频防雪崩。
     * 仅看门狗触发路径经由此限频；外部显式调用（onResume/主线程 HealthCheck/Alarm）
     * 直接调 [emergencyRestartGameLoop] 不受限。
     */
    private fun performWatchdogRecovery() {
        val now = gameClock.nowMs()
        // S8：lastWatchdogRecoveryMs==0 表示开机后首次恢复——不拦截（否则开机 1 分钟内
        // 首次停滞恢复被延迟最长 60s）
        val throttled = lastWatchdogRecoveryMs != 0L &&
            now - lastWatchdogRecoveryMs < MIN_WATCHDOG_RECOVERY_INTERVAL_MS
        if (throttled) {
            DomainLog.w(TAG,
                "Watchdog recovery throttled (interval=${MIN_WATCHDOG_RECOVERY_INTERVAL_MS}ms)")
            return
        }
        val previous = lastWatchdogRecoveryMs
        lastWatchdogRecoveryMs = now
        // 对抗性审查（数据篡改者 F5）：被拒的恢复（stop/shutdown 已抢占，phase
        // 非 RUNNING）也消耗 60s 限频预算 → 后续真实停滞恢复被限频拖延。
        // 拒绝时回滚预算——恢复动作未执行，不应计为一次有效恢复
        if (!emergencyRestartGameLoop()) {
            lastWatchdogRecoveryMs = previous
        }
    }

    /**
     * 重启看门狗（如果已死亡）。
     * 由主线程健康监控器调用。
     */
    fun restartWatchdog() {
        if (watchdogJob?.isActive == true) return
        DomainLog.w(TAG, "Watchdog: restarting (was active=${watchdogJob?.isActive})")
        startWatchdog()
    }

    /**
     * 创建一个全新的游戏调度器（新线程）。
     * 用于 [emergencyRestartGameLoop]，当原 GAME_DISPATCHER 的线程被
     * OEM 电源管理挂起时，用全新线程替代。
     */
    private fun recreateGameDispatcher(): CoroutineDispatcher {
        val oldDispatcher = gameDispatcher
        val newDispatcher = Executors.newSingleThreadExecutor(ThreadFactory {
            val thread = Thread(it, "GameEngine-Thread")
            thread.priority = Thread.MAX_PRIORITY
            thread.isDaemon = false
            thread
        }).asCoroutineDispatcher()
        gameDispatcher = newDispatcher
        // F5/S9：旧 executor 队列清空并 shutdown——被 OEM 挂起的线程无法中断，
        // 但清理任务队列防止残留任务与新循环竞争；非静态单例才关闭
        // （GAME_DISPATCHER 是静态单例，shutdown 后未来引用会 RejectedExecutionException）
        if (oldDispatcher !== GAME_DISPATCHER) {
            (oldDispatcher as? ExecutorCoroutineDispatcher)?.executor
                ?.let { it as? ExecutorService }?.shutdown()
        }
        DomainLog.w(TAG, "Created new game dispatcher (old thread may be suspended by OEM)")
        return newDispatcher
    }

    /**
     * 紧急重启游戏循环——创建全新调度器线程，绕过 OEM 线程挂起。
     *
     * 全恢复路径统一入口（引擎看门狗/主线程 HealthCheck/Alarm 兜底均调用）：
     * 创建一个全新的 GAME_DISPATCHER（新线程），确保不被 HyperOS 等
     * OEM 电源管理挂起的旧线程影响。可从任意线程调用（内部经协程协作式
     * 取消，重启流程可完整执行；看门狗触发路径经 [performWatchdogRecovery]
     * 60s 限频）。
     */
    /**
     * 紧急重启游戏循环——创建全新调度器线程，绕过 OEM 线程挂起。
     *
     * 全恢复路径统一入口（引擎看门狗/主线程 HealthCheck/Alarm 兜底均调用）：
     * 创建一个全新的 GAME_DISPATCHER（新线程），确保不被 HyperOS 等
     * OEM 电源管理挂起的旧线程影响。可从任意线程调用（内部经协程协作式
     * 取消，重启流程可完整执行；看门狗触发路径经 [performWatchdogRecovery]
     * 60s 限频）。
     *
     * @return true=重启完成（RESTARTING→RUNNING 成功）；false=被拒绝
     * （stop/shutdown 已抢占/重入中/abort）——调用方据此回滚限频预算。
     */
    // catch Throwable 为 D-07 phase 中毒回滚语义必需（任何异常都须回滚 RESTARTING）
    @Suppress("TooGenericExceptionCaught")
    fun emergencyRestartGameLoop(): Boolean {
        // S2：CAS 原子防重入——三个看门狗线程可能并发触发，非原子 check-then-act
        // 会让两个线程同时进入 → 双循环双倍速
        if (!isEmergencyRestarting.compareAndSet(false, true)) {
            DomainLog.w(TAG, "EMERGENCY restart already in progress, skipping")
            return false
        }
        return try {
            performEmergencyRestart()
        } catch (t: Throwable) {
            // 对抗性审查（phase 中毒）：启动体异常（recreate/snapshot/forceReset）
            // → 回滚 RESTARTING → STOPPED，避免 phase 永久停在 RESTARTING
            //（emergency 的后续 CAS 也被拒，恢复链路死透）。stop/shutdown 已
            // 抢占时 CAS 失败，由抢占方自行收尾
            engineLoopState.compareAndSet(
                LoopState(LoopPhase.RESTARTING, engineLoopState.get().epoch),
                LoopState(LoopPhase.STOPPED, engineLoopState.get().epoch)
            )
            throw t
        } finally {
            isEmergencyRestarting.set(false)
        }
    }

    /**
     * D-17 紧急重启工作体（emergencyRestartGameLoop 拆分）：CAS 状态机闸 + 锁内
     * 拆除/重建/重启 + RESTARTING→RUNNING 收尾。
     *
     * @return true=重启完成（RESTARTING→RUNNING 成功）；false=被 stop/shutdown
     * 抢占（循环保持停止）——调用方据此回滚限频预算。
     */
    private fun performEmergencyRestart(): Boolean {
        // D-07 状态机闸：仅 RUNNING → RESTARTING，且递增启动世代 epoch
        //（对抗性审查 2026-08-08：与 phase 原子绑定——start 从自身 CAS 结果
        // 取得的 epoch 一旦落后即放弃启动，闭合双循环 ABA 窗口）。
        // stop/shutdown 已抢占（STOPPING/STOPPED）时拒绝——否则 shutdown
        // 完成后 emergency 又重建循环（孤儿循环）。拒绝路径零副作用：
        // 未取消循环、未重建 dispatcher（不泄漏线程）
        val won = transitionLoopPhase(
            setOf(LoopPhase.RUNNING), LoopPhase.RESTARTING, bumpEpoch = true
        )
        if (won == null) {
            DomainLog.w(TAG, "EMERGENCY restart rejected (phase=${engineLoopPhase})")
            return false
        }
        val epoch = won.epoch
        // 对抗性审查（孤儿循环/双循环/毒化态）：紧急重启工作体在锁内执行，
        // 与 start/stop/shutdown 工作体串行化——重建的 dispatcher/launch 的
        // 循环不会与并发启动体交错；被 stop 抢占的拆除体等锁，不误杀新循环
        return loopOpLock.withLock {
            val gd = stateStore.gameDataSnapshot
            DomainLog.e(TAG, "EMERGENCY restart: year=${gd.gameYear}, " +
                "month=${gd.gameMonth}, recruitList.size=" +
                "${gd.recruitList.size}, sectName=${gd.sectName}")

            // 1. 取消旧游戏循环和看门狗
            gameLoopJob?.cancel()
            gameLoopJob = null
            stopWatchdog()

            // 2. 重置卡住的状态
            forceResetStuckStates()

            // 3. 用全新调度器重建 engineScope
            engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
            engineScope = CoroutineScope(engineJob + recreateGameDispatcher() + engineExceptionHandler)

            // 4. 消耗死区时间，防止时间跳变
            gameClock.consumeDeadTime()

            // 5. 重启循环——保留降级计数（F6：startGameLoop 不清零，
            //    否则每次紧急重启后的新看门狗都从激进模式起步，降级模式永不生效）
            //    launch 前/后双重校验由 startGameLoopInternal 承担（phase/epoch
            //    未变才启动；被抢占即 abort，循环不复活）
            startGameLoopInternal(
                resetWatchdogAttempts = false,
                expectedPhase = LoopPhase.RESTARTING,
                expectedEpoch = epoch
            )

            // 6. 重启完成收尾：RESTARTING → RUNNING；被 stop 抢占（STOPPING/STOPPED）
            //    则保持——循环已由 stop 停止，不强行复位
            val restarted = transitionLoopPhase(
                setOf(LoopPhase.RESTARTING), LoopPhase.RUNNING
            )
            if (restarted == null) {
                DomainLog.w(TAG, "EMERGENCY restart: phase moved away " +
                    "(${engineLoopPhase}), loop stays stopped")
            } else {
                DomainLog.i(TAG, "EMERGENCY restart complete")
            }
            restarted != null
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * D-07 状态机 CAS 迁移：当前 phase ∈ [expected] → 迁移到 [new]。
     * 并发抢占方（stop/shutdown/emergency）先完成迁移时返回 null。
     * [bumpEpoch] 仅在 emergency 抢占 RUNNING 时置 true——递增启动世代，
     * 使并发 start 的 epoch 校验失败（放弃启动），闭合双循环 ABA 窗口。
     * 成功时返回新状态（原子）：startGameLoop/emergency 从中读取 epoch，
     * 避免"CAS 后另读 epoch"的读取窗口。
     * 循环重试处理 compareAndSet 的瞬时失败（期望态未被抢占时必然成功）。
     */
    private fun transitionLoopPhase(
        expected: Collection<LoopPhase>,
        new: LoopPhase,
        bumpEpoch: Boolean = false
    ): LoopState? {
        while (true) {
            val current = engineLoopState.get()
            if (current.phase !in expected) return null
            val next = if (bumpEpoch) {
                current.copy(phase = new, epoch = current.epoch + 1)
            } else {
                current.copy(phase = new)
            }
            if (engineLoopState.compareAndSet(current, next)) return next
        }
    }

    val isGameLoopRunning: Boolean get() = gameLoopJob?.isActive == true

    /** 直接读取暂停状态，绕过 unifiedState 的 50ms 采样延迟 */
    val isPausedDirect: Boolean get() = stateStore.isPaused.value

    /** P-8：暂停状态窄流（替代 unifiedState.map{isPaused}，消除 20Hz 锁竞争依赖） */
    val isPaused: StateFlow<Boolean> get() = stateStore.isPaused

    suspend fun pause() = withEngineContext {
        stateStore.update { isPaused = true }
        cultivationService.resetHighFrequencyData()
    }

    suspend fun resume() = withEngineContext {
        stateStore.update { isPaused = false }
    }

    /** 远古秘境探索暂停锁：进入探索界面时若未暂停则由秘境持有暂停，退出时恢复 */
    @Volatile
    var secretRealmPauseLock: Boolean = false

    /**
     * 秘境探索暂停：若当前未暂停则暂停游戏时间并记录锁（退出探索时恢复）。
     * 用户在探索前手动暂停的场景不抢占。
     *
     * 同时记录暂停租约续约时间戳：UI 探索界面每 [SECRET_REALM_RENEW_INTERVAL_MS]
     * 调用 [renewSecretRealmPauseLease] 续约；续约中断超过
     * [GameTimeProgressMonitor.STALE_PAUSE_TTL_MS] 视为界面已销毁（onDispose 丢失），
     * 由看门狗以 StalePauseDetected 自愈。
     */
    fun pauseForSecretRealm() {
        if (!stateStore.isPaused.value) {
            stateStore.setPausedDirect(true)
            secretRealmPauseLock = true
            secretRealmPauseRenewedAtMs = gameClock.nowMs()
        }
    }

    /** 秘境探索恢复：仅当暂停由秘境持有（进入探索时记录）时才恢复游戏时间。 */
    fun resumeFromSecretRealm() {
        if (secretRealmPauseLock) {
            stateStore.setPausedDirect(false)
            secretRealmPauseLock = false
            secretRealmPauseRenewedAtMs = 0L
            // 自检：若游戏循环未运行则重启（防御探索期间后台恢复等路径导致循环丢失，
            // 不依赖 GameForegroundService 的隐式重启——代码质量审查问题 6）
            if (!isGameLoopRunning) {
                startGameLoop()
            }
        }
    }

    /**
     * 续约秘境暂停租约：由 UI 探索界面每 [SECRET_REALM_RENEW_INTERVAL_MS] 调用，
     * 证明"秘境界面仍打开中"（暂停由 UI 持有）。
     * 续约中断超过 [GameTimeProgressMonitor.STALE_PAUSE_TTL_MS] 后，
     * 看门狗判定锁残留并自愈，消除 Activity 重建导致 exitExploration 丢失的永久冻结路径。
     */
    fun renewSecretRealmPauseLease() {
        secretRealmPauseRenewedAtMs = gameClock.nowMs()
    }

    fun pauseForBackground() {
        // 记录用户暂停状态（stopGameLoop 之前）——恢复时必须区分：
        // 后台暂停（引擎自置，恢复清除） vs 用户/秘境暂停（恢复保留）。
        // F1：秘境暂停（secretRealmPauseLock）不记入用户暂停——否则后台销毁
        // Activity 时 exitExploration 清锁后，恢复会保留一个"无主的暂停"
        // （用户没暂停过却一直暂停，锁已丢无法恢复）
        wasUserPausedBeforeBackground = stateStore.isPaused.value && !secretRealmPauseLock
        stopGameLoop()
        DomainLog.i(TAG, "Game loop stopped for background")
        engineScope.launch {
            cultivationService.resetHighFrequencyData()
        }
        _wasPausedByBackground = true
    }

    fun resumeFromBackground() {
        // 无论 secretRealmPauseLock 状态如何，循环因后台被停一律重启。
        // 重启后：isPaused 仍为 true（F4：startGameLoop 内部按暂停来源补回）→
        // 循环进暂停分支（consumeDeadTime + delay(50)），时间不推进、无月变/年变
        // —— 保持 S4 语义（秘境界面打开期间不发生月变），
        // 待 exitExploration → resumeFromSecretRealm 恢复正常推进。
        // 历史修复（对抗性审查 S4 结论）在此的"提前 return"正是锁残留 → 永久冻结的根源：
        // onDispose 丢失（Activity 重建）时 exitExploration 永不调用，锁卡 true。
        if (_wasPausedByBackground && !isGameLoopRunning) {
            startGameLoop()
            DomainLog.i(TAG, "Game loop restarted from background (pause preserved)")
        }
        wasUserPausedBeforeBackground = false
        clearBackgroundPauseFlag()
    }

    @Volatile
    private var _wasPausedByBackground = false
    val wasPausedByBackground: Boolean get() = _wasPausedByBackground

    /** 后台前用户是否已主动暂停（pauseForBackground 记录，恢复时决定是否保留暂停） */
    @Volatile
    private var wasUserPausedBeforeBackground = false

    fun clearBackgroundPauseFlag() {
        _wasPausedByBackground = false
        wasUserPausedBeforeBackground = false
    }

    /**
     * UI 层调用：通知引擎用户活跃。
     * 连续无操作 30s 后自动切到 IDLE 场景降帧保电。
     */
    fun onUserInteraction() {
        onUserActivity()
    }

    private suspend fun tick() {
        val tickStartTime = System.currentTimeMillis()
        val tickStartNanos = System.nanoTime()

        tickInternal()

        val tickDurationNanos = System.nanoTime() - tickStartNanos

        val tickTime = (System.currentTimeMillis() - tickStartTime).toFloat()
        unifiedPerformanceMonitor.recordTick(tickTime)

        val entityCount = stateStore.disciplesSnapshot.size
        unifiedPerformanceMonitor.recordEntityCount(entityCount)

        if (tickTime > TICK_TIME_BUDGET_MS) {
            DomainLog.w(TAG, "Tick over budget: ${tickTime}ms (budget=${TICK_TIME_BUDGET_MS}ms)")
        }

        if (tickTime > TICK_WARNING_THRESHOLD_MS) {
            DomainLog.w(TAG, "Slow tick detected: ${tickTime}ms")
        }
    }
    
    private suspend fun tickInternal() {
        if (skipTickIfNeeded()) return
        _tickCount.value++
        val tickStartNanos = System.nanoTime()
        val tickStartDiagnostic = if (OemPowerProfileProvider.currentManufacturer == OemManufacturer.XIAOMI)
            System.currentTimeMillis() else 0L
        // 进度快照采样：看门狗统一判据输入（tickCount + totalPhases + accumulatedGameMs）
        sampleProgressSnapshot()
        val tickResult = gameClock.tick(isSettlementPending = false)
        // 电量感知热控阈值偏移（低电量未充电提前 2°C 降载）；checkAndAdjust 10s 间隔检查，
        // 此处仅浮点赋值无锁开销
        thermalController.setThresholdOffsetC(batteryStatusProvider.thermalThresholdOffsetC)
        thermalController.checkAndAdjust(_fps.value)
        val tickFlags = processTickPhases(tickResult.phasesToAdvance)
        processMonthYearChange(
            monthChanged = (tickFlags and FLAG_MONTH_CHANGED) != 0,
            yearChanged = (tickFlags and FLAG_YEAR_CHANGED) != 0
        )
        // L3a 年变分帧：延迟组按 30ms 预算逐 tick drain（1 月重活分摊到后续 tick；
        // 非 1 月残留由 forceDrain 兜底不跨月）
        cultivationService.drainYearlyOpsQueue()
        val patrolResults = explorationService.consumePendingPatrolResults()
        for (result in patrolResults) {
            stateStore.setPendingBattleResult(result)
        }
        lastTickDurationMs = (System.nanoTime() - tickStartNanos) / 1_000_000
        if (tickStartDiagnostic > 0) {
            val tickDuration = System.currentTimeMillis() - tickStartDiagnostic
            if (tickDuration > TICK_TIME_BUDGET_MS) {
                DomainLog.w(TAG,
                    "tick over budget: ${tickDuration}ms " +
                    "(budget=${TICK_TIME_BUDGET_MS}ms, " +
                    "month=${stateStore.gameData.value.gameMonth}, " +
                    "year=${stateStore.gameData.value.gameYear})")
            }
        }
    }
    
    private suspend fun skipTickIfNeeded(): Boolean {
        val isPaused = stateStore.isPaused.value
        val isLoading = stateStore.isLoading.value
        val isSaving = stateStore.isSaving.value
        if (!isPaused && !isLoading && !isSaving) return false
        checkAndResetStuckStates(isSaving, isLoading)
        gameClock.consumeDeadTime()
        if (_tickCount.value % 100 == 0L) {
            DomainLog.d(TAG, "tickInternal: tick #${_tickCount.value} skipped " +
                "(isPaused=$isPaused, isLoading=$isLoading, isSaving=$isSaving)")
        }
        return true
    }
    
    private suspend fun processTickPhases(phasesToAdvance: Int): Int {
        // 2026-08-01 双保险：时钟层已截断 MAX_PHASES_PER_TICK，此处防御未来
        // 新调用点绕过时钟直接调用（防止单帧连续执行数十个完整事务卡死）
        val capped = phasesToAdvance.coerceAtMost(GameTimeClock.MAX_PHASES_PER_TICK)
        var flags = 0
        // P1-A（G 项）：掉帧追旬场景 N 次独立事务 → 1 次合并事务。
        // 省去 N-1 次 COW deepCopy + 锁竞争 + dispatchAssemble；配合 P0-1 的
        // RNG 事务快照/恢复，任一句异常整批回滚（状态 + RNG 同步恢复），
        // 与逐句独立事务的确定性语义逐位一致（有确定性对照守卫测试）。
        try {
            stateStore.update {
                for (phaseIndex in 1..capped) {
                    // ★ 必须在 onPhaseTick 前捕获 prevMonth/prevYear，
                    // 否则 onPhaseTick 已修改 gameMonth/gameYear，后续比较永远相等。
                    val prevMonth = this.gameData.gameMonth
                    val prevYear = this.gameData.gameYear
                    systemManager.getSystem(TimeSystem::class)
                        .onPhaseTick(this, phasesToSettle = 1)
                    checkBreakthroughsAndPills(this)
                    if (this.gameData.gameMonth != prevMonth) flags = flags or FLAG_MONTH_CHANGED
                    if (this.gameData.gameYear != prevYear) flags = flags or FLAG_YEAR_CHANGED
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // F2 对抗性审查修复：合并事务整批回滚——状态时间回退 N 旬，
            // 但时钟已按墙钟消费 N 旬（累积消费模式无自动追补）；
            // 归还未落地旬数，保持状态时间与墙钟一致（否则永久落后）
            gameClock.refundPhases(capped)
            throw e
        }
        return flags
    }
    
    private suspend fun processMonthYearChange(monthChanged: Boolean, yearChanged: Boolean) {
        if (yearChanged) {
            cultivationService.processYearlyEvents()
            if (stateStore.gameData.value.gameMonth == 1) {
                cultivationService.processAnnualSalary(
                    stateStore.gameData.value.gameYear
                )
            }
        }
        if (monthChanged) {
            // 第一步：计算策略成本 + 触发系统月变 + 血炼完成检测（单事务）
            // 策略成本结果需要在事务外检查以决定是否重算生产 checkpoints
            var policyResult: PolicyCostResult = PolicyCostResult.AllPaid
            stateStore.update {
                policyResult = cultivationService.processPolicyCosts(this)
                // 政策月度非消耗效果（道德/忠诚增减等，与扣费同事务）
                cultivationService.processPolicyMonthlyEffects(this)
                // AI 预计算进攻目标（写入 aiSectBeastDirectTargets），巡视楼处理时会查看
                aiSectBeastAttackProcessor.precomputeTargets(this, gameData.gameYear, gameData.gameMonth)
                systemManager.onMonthlyEvent(this)
                processBloodRefinementCompletions()
                // P0.2: 自动排班 + 住所忠诚度合入同一事务，减少月度独立事务数量
                cultivationService.processMonthlyAutoAssignments(this)
                // ★ 月度事件合并到同一事务（单原子提交 policy + 月变 + 重算 checkpoints）
                cultivationService.processMonthlyEventsOnState(this)
            }
            if (policyResult is PolicyCostResult.SomeDisabled) {
                val disabledList = (policyResult as PolicyCostResult.SomeDisabled).disabledPolicies
                cultivationService.checkpointAllProduction()
                DomainLog.w(TAG, "tickInternal: policies auto-disabled due to insufficient spirit stones: " +
                    "${disabledList.joinToString(", ")}")
            }
            missionCheck?.invoke()
            // 事务外 flush 灵石变更事件，避免 UI 层读到部分状态窗口
            spiritStoneWallet.flushPendingEvents(eventBus)
        }
    }

    /**
     * Level 1: 每旬最小检查 — 对标 RimWorld Rare Tick 模式。
     *
     * 每旬执行的操作：
     * 1) HP/MP 恢复
     * 2) 自动装备/学习
     * 3) 修炼经验累积（确保月中速率变化自动生效）
     * 4) 自动丹药到期补服
     * 5) 突破检测
     */
    private fun checkBreakthroughsAndPills(state: MutableGameState) {
        cultivationService.processAutoFromWarehouseRealtime(state)

        // 合并遍历：HP/MP 恢复 + 修炼累积 + 功法熟练度 + 装备孕养
        // 原 7 次独立遍历 → 1 次合并遍历（P0.1 优化）
        // 每旬共享映射：所有弟子复用同一份，避免每弟子 O(N) 重建（O(D×N) → O(D+N)）
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val manualProficiencies = state.gameData.manualProficiencies
        // P-1：藏经阁弟子预构建 Set（替代每弟子 O(L) 线性扫描）+ 熟练度批量累积
        val libraryDiscipleIds = state.gameData.librarySlots.mapTo(HashSet()) { it.discipleId }
        val pendingProficiencies = mutableMapOf<String, List<ManualProficiencyData>?>()
        // P-2：装备孕养共享累积 Map（循环后单次 List 重建，O(D×E) → O(E)）
        val pendingEquipmentUpdates = mutableMapOf<String, EquipmentInstance>()
        // P-4：住所/建筑预构建索引（消除每弟子 O(R)+O(B) 线性扫描 + id.toString() 分配）
        val residenceByDiscipleId = HashMap<Int, ResidenceSlot>()
        for (r in state.gameData.residenceSlots) {
            val rid = r.discipleId.toIntOrNull() ?: continue
            if (rid !in residenceByDiscipleId) residenceByDiscipleId[rid] = r
        }
        val buildingByInstanceId = state.gameData.placedBuildings.associateBy { it.instanceId }
        // P-6：realtimeCultivation 投影批量累积（D 次发射 → 1 次）
        val pendingRealtime = mutableMapOf<String, Double>()
        // 远古秘境：探索中弟子不参与恢复/修炼/熟练度/孕养（不可突破、不可恢复状态）
        val secretRealmMemberIds = state.gameData.secretRealmMemberIds()

        for (id in state.discipleTables.ids) {
            // 存活 + 非秘境成员才参与恢复/修炼（合并跳转条件，保持循环单跳转）
            if (state.discipleTables.isAlive[id] != 1 ||
                id in secretRealmMemberIds
            ) continue
            // 1) HP/MP 恢复（2026-08-01 列直读版：无 assemble，满血提前退出）
            cultivationService.recoverHpMpSingleColumn(
                state, id, phasesToSettle = 1,
                equipmentMap = equipmentMap, manualMap = manualMap,
                manualProficiencies = manualProficiencies
            )
            // 2) 修炼累积（列级快速跳过：cultivation >= 1e8 表示已满，凡界最大值约 2e7）
            if (state.discipleTables.cultivations.getOrDefault(id, 0.0) < 1e8) {
                cultivationService.accumulateCultivationPerPhase(
                    id, state, pendingRealtime, residenceByDiscipleId, buildingByInstanceId
                )
            }
            // 3) 功法熟练度增长（P-1 批量模式：只累积不写 state）
            cultivationService.processManualProficiencySingle(
                state, id, manualMap, pendingProficiencies, libraryDiscipleIds
            )
            // 4) 装备孕养增长（P-2 批量模式：只累积不重建 List）
            cultivationService.processEquipmentNurtureSingle(
                state, id, equipmentMap, pendingEquipmentUpdates
            )
        }

        // P-1：单次提交熟练度（O(D²) 全量 Map 拷贝 → O(D)）
        cultivationService.commitManualProficiencies(state, pendingProficiencies)
        // P-2：单次重建装备实例列表（O(D×E) → O(E)）
        cultivationService.applyEquipmentUpdates(state, pendingEquipmentUpdates)
        // P-6：单次发射 realtimeCultivation 投影（D 次发射 → 1 次）
        cultivationService.flushRealtimeCultivation(pendingRealtime)

        cultivationService.processAutoPillsRealtime(state)
        cultivationService.processBreakthroughs(state)
    }

    /**
     * 看门狗：检测 isSaving/isLoading 是否卡住超时，如果超时则强制重置。
     * 在 tickInternal() 每次跳过 tick 时调用。
     * internal（T12 2026-08-05）：供 core/engine 测试驱动超时路径。
     */
    internal fun checkAndResetStuckStates(
        isSaving: Boolean,
        isLoading: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        // 对抗性审查整改（2026-08-05）：nowMs<=0 会静默失效（savingStartTime==0 判据恒真）
        // 或立即误触发（负值减出超大间隔）——防御性回退真实时钟
        val now = if (nowMs > 0) nowMs else System.currentTimeMillis()

        // 跟踪 isSaving 变为 true 的时间
        if (isSaving) {
            if (savingStartTime == 0L) {
                savingStartTime = now
            } else if (now - savingStartTime > SAVE_LOAD_STUCK_TIMEOUT_MS) {
                DomainLog.e(TAG, "isSaving has been true for ${now - savingStartTime}ms, force resetting")
                watchdogForceResetStuckStates("保存操作超时(${SAVE_LOAD_STUCK_TIMEOUT_MS / 1000}s)，已自动复位，请重试")
            }
        } else {
            savingStartTime = 0L
        }

        // 跟踪 isLoading 变为 true 的时间
        if (isLoading) {
            if (loadingStartTime == 0L) {
                loadingStartTime = now
            } else if (now - loadingStartTime > SAVE_LOAD_STUCK_TIMEOUT_MS) {
                DomainLog.e(TAG, "isLoading has been true for ${now - loadingStartTime}ms, force resetting")
                watchdogForceResetStuckStates("读档操作超时(${SAVE_LOAD_STUCK_TIMEOUT_MS / 1000}s)，已自动复位，请重试")
            }
        } else {
            loadingStartTime = 0L
        }
    }

    /**
     * 看门狗专用复位（T12 2026-08-05）：先发用户可见事件再复位。
     * [forceResetStuckStates] 保持静默——onCleared 正常清理路径不可弹窗。
     */
    private fun watchdogForceResetStuckStates(reason: String) {
        _stuckResetEvents.tryEmit(reason)
        forceResetStuckStates()
    }

    /**
     * 注册当前正在运行的加载协程 Job，供看门狗强制取消。
     * 在 finally 块中应调用 [clearActiveLoadJob] 清除引用。
     *
     * 对抗性审查整改（2026-08-05）：读-改-写加锁原子化——主线程注册与看门狗线程
     * 复位交错时，陈旧 cancel 会误杀新注册操作、`= null` 会使在途操作脱离看门狗监管。
     */
    fun registerActiveLoadJob(job: Job) {
        synchronized(activeLoadJobLock) {
            if (activeLoadJob === job) return  // C4：防自注册自杀
            activeLoadJob?.cancel()
            activeLoadJob = job
        }
    }

    /**
     * 清除加载协程 Job 引用（协程正常结束时调用）。
     * C4 修复（2026-08-05）：归属判定 + 清理原子完成——仅当 [activeLoadJob] === job
     * 时置 null 并返回 true；被新操作取代的旧 job（owned=false）不清理，
     * 避免旧协程 finally 抹掉新操作的在途状态与看门狗监管。
     * 看门狗 [forceResetStuckStates] 为全能路径，不受归属约束。
     *
     * @param job 发起清理的协程 Job
     * @return 是否归本 job（true 表示本次清理生效）
     */
    fun clearActiveLoadJob(job: Job): Boolean = synchronized(activeLoadJobLock) {
        if (activeLoadJob === job) {
            activeLoadJob = null
            true
        } else {
            false
        }
    }

    /** activeLoadJob 互斥锁（注册/清除/看门狗复位三处共享） */
    private val activeLoadJobLock = Any()

    /**
     * 强制重置 isSaving 和 isLoading 为 false。
     * 用于看门狗检测到状态卡住时调用，也可从外部调用作为紧急恢复手段。
     * 同时取消正在运行的 save/load 协程，防止并发写入。
     */
    fun forceResetStuckStates() {
        DomainLog.w(TAG, "Force resetting stuck states: isSaving and isLoading -> false, cancelling active jobs")
        synchronized(activeLoadJobLock) {
            activeLoadJob?.cancel()
            activeLoadJob = null
        }
        stateStore.setSavingDirect(false)
        stateStore.setLoadingDirect(false)
        savingStartTime = 0L
        loadingStartTime = 0L
    }
    
    /**
     * 渲染线程上报渲染能力帧率（EWMA 反推，非墙钟帧率——挂机主动降帧时
     * 能力仍高，不会误触发热控降级），激活 [ThermalController] 的帧率驱动降级分支。
     *
     * 与引擎 tick 解耦：热控判据基于渲染能力帧率而非引擎逻辑帧率。
     * 渲染线程高频回调必须限频，避免 StateFlow 通知风暴。
     *
     * @param fps 渲染线程能力帧率（每秒回调一次）
     */
    fun setObservedRenderFps(fps: Float) {
        if (fps <= 0f || fps.isNaN()) return
        // 限频用单调时钟（SystemClock.elapsedRealtime）：墙钟回拨（NTP 校正/手动改时间）
        // 会让差值变负导致限频失效，热控 fps 输入被长时间阻塞
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(fpsReportLock) {
            if (now - lastFpsReportMs < fpsReportIntervalMs) return
            lastFpsReportMs = now
            _fps.value = fps.coerceIn(MIN_REPORTED_FPS, MAX_REPORTED_FPS)
        }
    }

    /**
     * 防挂起延迟：将等待时间拆分为微延迟 + 忙等循环。
     *
     * 华为 EMUI/HarmonyOS 的 PowerGenie（省电精灵）、荣耀 MagicOS、
     * vivo/iQOO OriginOS、小米 MIUI 神隐模式、OPPO ColorOS 等 OEM
     * 省电机制会检测线程"空闲"状态并将游戏线程挂起。
     *
     * 将 delay 拆分为 2ms 微间隔（远低于所有 OEM 的空闲检测窗口），
     * 并按 [OemPowerProfile] 配置周期性执行忙等循环，以 [SystemClock.elapsedRealtime]
     * 轮询保持线程 RUNNABLE，打破 OEM 空闲检测。
     *
     * API 33+：忙等循环内额外调用 [Thread.onSpinWait] 作为 CPU 优化提示。
     *
     * ## 参数来源
     * busyInterval / busyDuration 由 [OemPowerProfileProvider.current] 提供，
     * 数据驱动各厂商差异化配置：
     * - vivo/iQOO OriginOS 5：busyInterval=12, busyDuration=4ms（占空比 16.7%）
     * - Honor MagicOS / OPPO ColorOS：busyInterval=16, busyDuration=4ms（占空比 12.5%）
     * - 中等 OEM（Xiaomi MIUI）：busyInterval=32, busyDuration=3ms
     * - 保守 OEM（Samsung / 原生）：busyInterval=64, busyDuration=2ms
     *
     * 成本：保守 OEM 约 6-7% CPU；vivo 约 14% 单核 CPU（游戏线程），
     * 远优于游戏线程被 OEM 挂起导致时间完全冻结。
     *
     * 参考：
     * - dontkillmyapp.com — 各厂商电源管理机制分析
     * - Kotlin Slack #coroutines: delay() 精度 >30ms 抖动
     *   (https://slack-chats.kotlinlang.org/t/26866719)
     */
    /**
     * 自适应忙等延迟。
     *
     * 正常运行时不执行忙等（纯 delay），仅在检测到 tick 间隔异常（可能被 OEM 挂起）时
     * 自动启用分片忙等。恢复正常后自动禁用。
     *
     * @param totalMs 需要等待的总时长（ms）
     * @param actualElapsedMs 从上次 tick 到现在的实际墙钟间隔（ms），用于检测 OEM 挂起
     */
    private suspend fun antiFreezeDelay(totalMs: Long, actualElapsedMs: Long = 0L) {
        // ★ 自适应忙等检测
        if (actualElapsedMs > TICK_INTERVAL_MS * 2 && totalMs > 0) {
            antiFreezeTriggerCount++
            consecutiveNormalTicks = 0
            if (antiFreezeTriggerCount >= ANTI_FREEZE_TRIGGER_THRESHOLD && !antiFreezeEnabled) {
                antiFreezeEnabled = true
                DomainLog.w(TAG, "Anti-freeze enabled: ${antiFreezeTriggerCount} trigger events")
            }
        } else {
            consecutiveNormalTicks++
            antiFreezeTriggerCount = maxOf(0, antiFreezeTriggerCount - 1)
            if (antiFreezeEnabled && consecutiveNormalTicks >= ANTI_FREEZE_NORMAL_THRESHOLD) {
                antiFreezeEnabled = false
                DomainLog.i(TAG, "Anti-freeze disabled: ${consecutiveNormalTicks} normal ticks")
            }
        }

        if (antiFreezeEnabled) {
            doBusyWait(totalMs)
        } else {
            delay(totalMs.coerceAtLeast(1L))
        }
    }

    /** 分片忙等 — 仅在 antiFreezeEnabled 时执行 */
    private suspend fun doBusyWait(totalMs: Long) {
        val profile = OemPowerProfileProvider.current
        val microInterval = 2L
        val busyInterval = profile.antiFreezeBusyInterval
        val busyDuration = profile.antiFreezeBusyDuration
        var remaining = totalMs
        var cycleCount = 0L
        while (remaining > 0 && currentCoroutineContext().isActive) {
            val step = minOf(microInterval, remaining)
            delay(step)
            remaining -= step
            cycleCount++
            if (remaining > 0 && cycleCount % busyInterval == 0L) {
                val busyEnd = android.os.SystemClock.elapsedRealtime() + busyDuration
                while (android.os.SystemClock.elapsedRealtime() < busyEnd) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        Thread.onSpinWait()
                    } else {
                        @Suppress("UNUSED_EXPRESSION")
                        _tickCount.value
                    }
                }
            }
        }
    }

    fun createSnapshot(): GameStateSnapshot {
        val currentData = stateStore.gameData.value
        return GameStateSnapshot(
            gameData = currentData,
            disciples = stateStore.disciples.value,
            equipmentStacks = stateStore.equipmentStacks.value,
            equipmentInstances = stateStore.equipmentInstances.value,
            manualStacks = stateStore.manualStacks.value,
            manualInstances = stateStore.manualInstances.value,
            pills = stateStore.pills.value,
            materials = stateStore.materials.value,
            herbs = stateStore.herbs.value,
            seeds = stateStore.seeds.value,
            battleLogs = stateStore.battleLogs.value,
            alliances = currentData.alliances
        )
    }

    suspend fun loadSnapshot(snapshot: GameStateSnapshot) {
        stateStore.loadFromSnapshot(
            gameData = snapshot.gameData,
            disciples = snapshot.disciples,
            equipmentStacks = snapshot.equipmentStacks,
            equipmentInstances = snapshot.equipmentInstances,
            manualStacks = snapshot.manualStacks,
            manualInstances = snapshot.manualInstances,
            pills = snapshot.pills,
            materials = snapshot.materials,
            herbs = snapshot.herbs,
            seeds = snapshot.seeds,
            storageBags = snapshot.storageBags,
            battleLogs = snapshot.battleLogs
        )
    }
    
}

