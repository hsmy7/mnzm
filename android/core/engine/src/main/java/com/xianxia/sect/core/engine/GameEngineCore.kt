package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.JadeSymbolRuntimeState
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.engine.service.MonthSettlementExecutor
import com.xianxia.sect.core.engine.service.MonthSettlementResidualExecutor
import com.xianxia.sect.core.engine.service.YearSettlementExecutor
import com.xianxia.sect.core.engine.service.YearSettlementResidualExecutor
import com.xianxia.sect.core.engine.service.PhaseSettlementExecutor
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.loop.JitterSmoother
import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.thermal.BatteryStatusProvider
import com.xianxia.sect.core.thermal.NoopBatteryStatus
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.engine.monitor.GameTimeProgressMonitor
import com.xianxia.sect.core.engine.monitor.GameTimeProgressSnapshot
import kotlinx.coroutines.*
import com.xianxia.sect.core.overflow.OverflowMailHandler
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import com.xianxia.sect.core.overflow.NoOpOverflowMailHandler
import kotlin.concurrent.withLock



/**
 * 游戏循环生命周期状态机——stop/shutdown/emergencyRestart 并发交错的唯一真相源。
 *
 * 转换规则（全部经 [transitionLoopPhase] CAS，单赢家语义）：
 * - 正常启动：STOPPED → RUNNING（startGameLoop）
 * - 紧急重启：RUNNING → RESTARTING（emergencyRestartGameLoop 独占）
 *   → 重启完成 → RUNNING；中途被 stop/shutdown 抢占则放弃启动（不复活）
 * - 停止：RUNNING | RESTARTING → STOPPING → STOPPED（stopGameLoop）
 * - 关闭：RUNNING | RESTARTING | STOPPING → STOPPED（shutdown，幂等）
 *
 * phase 与启动世代 epoch 原子绑定为单一
 * [LoopState]——emergency 每次抢占 RUNNING 时递增 epoch；startGameLoop 从
 * CAS 结果原子取得 epoch，启动前/后校验世代未变，闭合"emergency 在 start
 * 启动窗口内完整跑完仍被 start 追加第二循环"的 ABA 窗口（孤儿循环/双循环
 * 根除，见 GameEngineCoreLifecycleInterleavingTest）。
 */
internal enum class LoopPhase {
    RUNNING,
    RESTARTING,
    STOPPING,
    STOPPED

    // catch Throwable 为 phase 中毒回滚语义必需（任何异常都须回滚状态机）

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
    // catch Throwable 为 phase 中毒回滚语义必需（任何异常都须回滚 RESTARTING）


}

/** 状态机原子载体：phase + epoch（紧急重启世代）单一 CAS 目标。 */
internal data class LoopState(val phase: LoopPhase, val epoch: Int)

/**
 * ## GameEngineCore - 游戏循环控制器
 *
 * ### 架构层级定位（两层状态架构）
 *
 * ```
 * ┌─────────────────────────────────────────────────────────────────┐
 * │ Layer 2: UI (ViewModel/Compose)                                 │
 * │   - 通过 StateFlow 订阅 GameStateStore 逐字段流 / 三层派生流       │
 * │     (highFreqState / entityState / configState)                 │
 * │   - 开销: collectAsState() 触发 Compose 重组                     │
 * ├─────────────────────────────────────────────────────────────────┤
 * │ Layer 1: GameEngineCore + GameEngine                             │
 * │   - EngineCore: 游戏循环控制 (start/stop/tick)                   │
 * │   - Engine: 核心业务逻辑 (修炼/战斗/生产等)                        │
 * │   - 状态写入 GameStateStore → 逐字段 StateFlow 自动发射            │
 * │   - 开销: MutableStateFlow.value 赋值触发下游订阅者               │
 * └─────────────────────────────────────────────────────────────────┘
 * ```
 *
 * ### 状态同步机制
 *
 * GameEngine 直接写入 GameStateStore 的各个逐字段 MutableStateFlow；
 * 高频 UI 消费走三层派生流（highFreqState/entityState/configState，
 * distinctUntilChanged + sample + stateIn 节流合并），
 * UI 层订阅对应流即可获得最新状态，无需手动同步。
 */
/** 帧循环跨帧累计状态（gameLoopIteration 参数/返回值）。
 *  internal（GameEngineCoreLoopOps AUTHORITATIVE 帧迭代复用同一载体） */
internal data class LoopIterationState(
    val accumulatorNs: Long,
    val lastFrameTimeNs: Long
)

@Singleton
@Suppress("LongParameterList") // 引擎核心 14 个真实依赖（含 EngineCrashReporter 端口），原 12 参数已 baseline 豁免
class GameEngineCore @Inject constructor(

    internal val stateStore: GameStateStore,
    internal val eventBus: EventBusPort,
    internal val unifiedPerformanceMonitor: UnifiedPerformanceMonitor,
    internal val systemManager: SystemManager,
    internal val scopeProvider: CoroutineScopeProvider,
    internal val cultivationService: CultivationService,
    internal val explorationService: ExplorationService,
    private val aiSectBeastAttackProcessor: com.xianxia.sect.core.exploration.AISectBeastAttackProcessor,
    internal val gameClock: GameTimeClock,
    internal val thermalController: ThermalController,
    internal val thermalMonitor: com.xianxia.sect.core.perf.ThermalMonitor,
    internal val spiritStoneWallet: SpiritStoneWallet,
    /** 玉符（氪金货币）在线时长结算服务 */
    internal val jadeSymbolService: JadeSymbolService,
    /** 引擎异常上报端口（app 层提供 Bugly 实现；默认 Noop 供测试/无基建场景） */
    internal val engineCrashReporter: EngineCrashReporter = NoopEngineCrashReporter,
    /** 电池状态感知（低电量未充电时主动降帧/提前降载；默认 Noop 供测试） */
    internal val batteryStatusProvider: BatteryStatusProvider = NoopBatteryStatus,
    /** 溢出邮件处理器（启动时排空上次崩溃遗留的持久化草稿；默认 Noop 供测试） */
    internal val overflowMailHandler: OverflowMailHandler = NoOpOverflowMailHandler,
    /**
     * RNG 分区管理器（AUTHORITATIVE 委托通道挂载点；Hilt 单例与
     * GameEngine/存档链路同实例）。默认新建仅供测试直构——生产由 Hilt
     * 注入全局单例。
     */
    internal val gameRngManager: GameRngManager = GameRngManager(),
    /** 突破埋点观察器（C++ 每旬突破判定后由镜像差分上报；默认 Noop 供测试直构） */
    internal val breakthroughAnalyticsObserver: com.xianxia.sect.core.engine.BreakthroughAnalyticsObserver =
        com.xianxia.sect.core.engine.BreakthroughAnalyticsObserver(
            com.xianxia.sect.core.engine.NoopAnalyticsTracker
        )
) : EngineContextDispatcher {

    init {
        // AUTHORITATIVE 下速度真相源在 native 引擎循环——
        // UI/看门狗经 gameClock.setSpeed 的变更由钩子推送（OFF 模式无消费者）
        gameClock.onSpeedChanged = { speed ->
            if (NativeEngineFlag.authoritative && GameCoreBridge.isLoaded) {
                runCatching { GameCoreBridge.nativeLoopSetSpeed(speed) }
            }
        }
    }

    /**
     * AUTHORITATIVE 帧计划管线活跃标志：refund 语义按真相源分流——
     * native 管线活跃时归还 native PhaseClock；否则归还 Kotlin gameClock
     * （遗留纯 Kotlin 路径）。引擎线程写/看门狗线程不读，volatile 仅作安全发布。
     */
    @Volatile
    internal var nativeLoopPipelineActive: Boolean = false

    /**
     * 任务完成检测回调，由 GameEngine 在构造后注入。
     * 每月结算时被调用，确保空闲期间任务完成也能被及时检测。
     */
    @Volatile
    internal var missionCheck: (suspend () -> Unit)? = null

    /**
     * 玩家占领宗门被 AI 夺回的建筑没收回调，由 GameEngine 在构造后注入
     * （buildingFacade.seizeBuildingsOfSect——建筑特性注册表/Room 生产槽位
     * 保留 Kotlin 平台域）。月结事务外调用；默认 null 仅供测试直构。
     */
    @Volatile
    internal var seizedBuildingsHandler: ((List<String>) -> Unit)? = null

    // ──────── 场景感知帧率控制（P1.3） ────────

    /**
     * 游戏场景枚举 — 用于动态调整帧率预算。
     */
    enum class GameScene(val displayName: String, val targetFrameTimeMs: Long) {
        /** 后台/息屏/无操作 — 最低帧率保电 */
        IDLE("后台", 100L),
        /** 地图滚动/惯性滑行 — 60fps（惯性滚动是高频交互，30fps 视觉丢帧） */
        MAP_SCROLL("地图滚动", 16L),
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
        internal set

    /** 当前性能模式（设置界面三档；引擎帧率计算的输入之一） */
    @Volatile
    var performanceMode: PerformanceMode = PerformanceMode.BALANCED
        internal set

    /** 当前自选清晰度（设置界面五档；分辨率/纹理/装饰 LOD 的输入之一，默认中） */
    @Volatile
    var clarityMode: com.xianxia.sect.core.render.ClarityMode = com.xianxia.sect.core.render.ClarityMode.MEDIUM
        internal set

    val jadeSymbolState: StateFlow<JadeSymbolRuntimeState>
        get() = jadeSymbolService.runtimeState

    /** 玉符服务访问器（引擎扩展方法事务内扣减/刷新用；构造参数不动，测试命名参数构造兼容） */
    internal val jadeSymbolServiceRef: JadeSymbolService
        get() = jadeSymbolService

    /**
     * C++ 引擎镜像同步服务（StateSyncService）。
     * 构造参数不动（测试命名参数构造兼容）——默认参数直接建于注入的
     * stateStore 之上；Hilt 场景由 GameEngine 经此访问器取用。
     */
    internal val stateSyncServiceRef: StateSyncService =
        StateSyncService(stateStore)

    /** 渲染帧率发布（供 NativeSurfaceView/SoftwareCanvasBackend 参考） */
    @Suppress("VariableNaming")  // 下划线前缀沿袭 Kotlin 私有后备字段惯例（internal 化供拆分域文件跨文件读写）
    internal val _renderFrameRate = MutableStateFlow(60)
    val renderFrameRate: StateFlow<Int> = _renderFrameRate.asStateFlow()

    /** 渲染质量因子发布（供 SoftwareCanvasBackend/Compose UI 参考） */
    @Suppress("VariableNaming")  // 下划线前缀沿袭 Kotlin 私有后备字段惯例（internal 化供拆分域文件跨文件读写）
    internal val _renderingQualityFactor = MutableStateFlow(1.0f)
    val renderingQualityFactor: StateFlow<Float> = _renderingQualityFactor.asStateFlow()

    /** 是否关闭装饰层（热控降级时） */
    @Suppress("VariableNaming")  // 下划线前缀沿袭 Kotlin 私有后备字段惯例（internal 化供拆分域文件跨文件读写）
    internal val _decorationsDisabled = MutableStateFlow(false)
    val decorationsDisabled: StateFlow<Boolean> = _decorationsDisabled.asStateFlow()

    internal val sceneStateFlow = MutableStateFlow(GameScene.GAMEPLAY)

    /** 当前场景状态流（Game State API 上报用） */
    val sceneState: StateFlow<GameScene> = sceneStateFlow

    /**
     * UI 层通知引擎：用户活跃（有触摸/操作），自动切换到 GAMEPLAY。
     * 均衡模式：闲置 5s 降 GAMEPLAY_IDLE(30fps)，闲置 30s 降 IDLE(10fps)。
     * 节能/性能模式：闲置 30s 直接降 IDLE(10fps)（深闲置是通用省电层，全模式保留）。
     */
    @Volatile
    internal var lastUserActivityTimeNs: Long = 0L
    internal val idleTimeoutNs = java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
    internal val activityDowngradeTimeoutNs = java.util.concurrent.TimeUnit.SECONDS.toNanos(5)

    /** fps 上报限频锁与时间戳（[setObservedRenderFps]，单调时钟 elapsedRealtime） */
    internal val fpsReportLock = Any()
    @Volatile
    internal var lastFpsReportMs = 0L
    internal val fpsReportIntervalMs = 1_000L

    /** 通知引擎用户有操作 */
    companion object {
        /**
         * 单用户定向补偿邮件（MailService 扩展，独立文件）。
         *
         * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
         * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
         * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
         */
        internal const val TAG = "GameEngineCore"
        internal const val TICK_INTERVAL_MS = 100L
        // isSaving/isLoading 病理级死锁最终兜底超时（90s）。
        // 阈值依据：10s 会把低端机正常慢保存（>3-5s）误判为卡死并打断，导致反复冻结；
        // 60s 会与友好超时（performLoadToSlot withTimeoutOrNull(60s)）竞态——
        // 低端机+大档时看门狗抢先取消 loadJob 且取消路径静默失败。90s 使
        // 友好超时（读档 60s / 保存 35s）先触发并复位标志，看门狗只拦截真正病理卡死。
        // 正常保存的豁免由 GameTimeProgressMonitor（lastLoopActivityMs 判据）承担。
        internal const val SAVE_LOAD_STUCK_TIMEOUT_MS = 90_000L
        internal const val TICK_TIME_BUDGET_MS = 50L
        // ★ 帧驱动 Accumulator 常量
        internal val LOGIC_DT_NS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100)  // 逻辑步长 100ms
        internal val MAX_ACCUMULATOR_NS = LOGIC_DT_NS * 5  // 最多累积 5 步
        // ADPF Performance Hint 目标帧时长（动态：按实际帧率换算，
        // 替代硬编码 60fps——挂机 30/10fps 时系统按真实预算调度，不再保留 60fps 性能）
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val ADPF_MIN_FPS = 10
        private const val ADPF_MAX_FPS = 60

        /** ADPF 目标帧时长纯函数：帧率 → 纳秒预算（10-60fps 钳制防除零/越界；internal 供单测） */
        internal fun frameDurationNs(fps: Int): Long =
            NANOS_PER_SECOND / fps.coerceIn(ADPF_MIN_FPS, ADPF_MAX_FPS)
        // 自适应忙等等阈值
        internal const val ANTI_FREEZE_TRIGGER_THRESHOLD = 3
        internal const val ANTI_FREEZE_NORMAL_THRESHOLD = 20

        // 帧率档位（场景 × 性能模式基准，sceneFpsFor 使用）
        internal const val FPS_IDLE = 10
        internal const val FPS_STILL = 30
        internal const val FPS_ACTIVE = 60
        // 渲染能力帧率上报钳制（setObservedRenderFps）
        internal const val MIN_REPORTED_FPS = 1f
        internal const val MAX_REPORTED_FPS = 240f

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

        internal val GAME_DISPATCHER = Executors.newSingleThreadExecutor(gameThreadFactory)
            .asCoroutineDispatcher()

        /** 旧游戏线程有限 join 截止（秒） */
        internal const val GAME_THREAD_JOIN_TIMEOUT_S = 2L

        /** 被替换的旧 GameEngine-Thread 在截止内未退出计数（OEM 挂起病理观测，
         *  对应审计验证点 11 的 ps -T 线程峰值口径） */
        internal val leakedGameThreadCount = AtomicLong(0)

        // 看门狗专用线程工厂 — 非守护线程，独立于 Dispatchers.Default。
        // 荣耀 MagicOS 等 OEM 电源管理会挂起守护线程池中的线程，
        // 非守护线程可防止看门狗自身被冻结，确保检测→恢复链路完整。
        private val watchdogThreadFactory = ThreadFactory {
            val thread = Thread(it, "GameEngine-Watchdog")
            thread.priority = Thread.NORM_PRIORITY
            thread.isDaemon = false
            thread
        }

        internal val WATCHDOG_DISPATCHER = Executors.newSingleThreadExecutor(watchdogThreadFactory)
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
    internal val watchdogExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            DomainLog.e(TAG, "Watchdog: unhandled exception — watchdog may have died", throwable)
        }
    }

    /** 可变的游戏循环调度器，紧急重启时可替换为新线程 */
    @Volatile
    internal var gameDispatcher: CoroutineDispatcher = GAME_DISPATCHER

    /** 紧急重启中标志 — AtomicBoolean CAS 防重入（S2/F3：check-then-act 必须原子，
     *  否则三个看门狗线程并发通过检查 → 双循环双倍速） */
    internal val isEmergencyRestarting = AtomicBoolean(false)

    /**
     * 生命周期状态机（初始 STOPPED：构造后未启动）。
     * phase 与 epoch 原子绑定：emergency 每次抢占
     * RUNNING 递增 epoch，start 从 CAS 结果原子取得并校验世代未变——闭合
     * "emergency 在 start 启动窗口内完整跑完仍被追加第二循环"的 ABA 窗口。
     */
    internal val engineLoopState = AtomicReference(LoopState(LoopPhase.STOPPED, 0))

    internal val engineLoopPhase: LoopPhase get() = engineLoopState.get().phase

    /**
     * 工作体互斥锁：CAS 状态机只门控"转换意图"，
     * 不门控 CAS 之后的破坏性/启动性工作体（stop 的拆除、shutdown 的 releaseAll/
     * engineJob.cancel、emergency 的重建+launch、start 的 launch）。stop 的过期
     * 拆除体误杀新循环、shutdown 拆除体与并发 start 交错毒化态等窗口由本锁
     * 串行化根除——锁内全部为非挂起同步操作（无挂起点，无死锁风险）。
     */
    internal val loopOpLock = java.util.concurrent.locks.ReentrantLock()

    // ★ 插值因子（供 UI 平滑渲染，由 frame-driven 循环维护）
    @Volatile
    var currentAlpha: Float = 0f
        internal set

    // 插值因子时间平滑器（循环重启时 reset 防残留滤波状态）
    //   internal（共享辅助 publishNativeAlpha 同包访问）
    internal val jitterSmoother = JitterSmoother()

    // ── 自适应忙等 ──
    @Volatile
    internal var antiFreezeEnabled = false
    internal var antiFreezeTriggerCount = 0
    internal var consecutiveNormalTicks = 0

    /**
     * Thread.onSpinWait 可用性（API 33+ / JDK 9+；反射探测替代
     * android.os.Build.VERSION.SDK_INT——消除 engine 模块 Android 依赖）
     */
    internal val supportsOnSpinWait: Boolean =
        try { Thread::class.java.getMethod("onSpinWait"); true } catch (_: NoSuchMethodException) { false }

    internal val engineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            DomainLog.e(TAG, "Unhandled exception in engine coroutine", throwable)
        }
    }
    internal var engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
    internal var engineScope: CoroutineScope = CoroutineScope(engineJob + gameDispatcher + engineExceptionHandler)

    /** ADPF 目标帧时长联动协程（prepareLoopStart 重启前取消防堆积） */
    internal var adpfTargetJob: Job? = null

    /**
     * 在引擎线程上执行指定代码块并返回结果。
     * 若当前已在引擎线程上，则不切换上下文（coroutines 自动优化）。
     * 用于确保 [stateStore.update] 调用在引擎线程上执行，避免主线程 ANR。
     */
    override suspend fun <T> withEngineContext(block: suspend CoroutineScope.() -> T): T {
        return withContext(gameDispatcher, block)
    }

    /** @Volatile（F3）：三个看门狗线程并发读 isActive，弱内存模型下非 volatile 可能读到陈旧 job */
    @Volatile
    internal var gameLoopJob: Job? = null
    internal var gameLoopStoppedSignal = CompletableDeferred<Unit>()
    
    /** tick 计数真相源（AUTHORITATIVE 下由 native 帧计划镜像回推；internal：共享辅助 publishNativeTickTotal 使用） */
    @Suppress("VariableNaming")  // 下划线前缀沿袭 Kotlin 内部状态惯例（MutableStateFlow 私有后备字段，public 读经 tickCount）
    internal val _tickCount = MutableStateFlow(0L)
    /** 循环 tick 计数（假运行时也递增，不能单独作为推进判据） */
    val tickCount: StateFlow<Long> = _tickCount.asStateFlow()
    
    /** 帧率上报值（internal：共享辅助 tickThermalControl 使用） */
    @Suppress("VariableNaming")  // 同上：_fps 为私有后备字段，public 读经 fps
    internal val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()
    
    
    val events: Flow<DomainEvent> get() = eventBus.events

    internal var isInitialized = false

    /** 记录 isSaving 变为 true 的时间戳，用于看门狗检测 */
    @Volatile
    internal var savingStartTime: Long = 0L

    /** 记录 isLoading 变为 true 的时间戳，用于看门狗检测 */
    @Volatile
    internal var loadingStartTime: Long = 0L

    /** 当前正在运行的加载协程 Job，用于看门狗强制取消 */
    @Volatile
    internal var activeLoadJob: Job? = null

    /**
     * 看门狗病理复位事件通道。
     * forceResetStuckStates 被看门狗触发（非 onCleared 正常清理）时发出用户可见事件，
     * SaveLoadViewModel 收集后弹错误提示（否则取消路径静默失败）。
     * replay=1：VM 空窗期（主菜单/未创建）的复位事件
     * 不丢失——replay=0 时无订阅者 tryEmit 直接丢弃，空窗期事件会静默消失。
     */
    @Suppress("VariableNaming")  // 下划线前缀沿袭 Kotlin 私有后备字段惯例
    internal val _stuckResetEvents = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val stuckResetEvents: SharedFlow<String> = _stuckResetEvents.asSharedFlow()

    /** 独立看门狗 Job — 运行在 Dispatchers.Default 上，监控游戏线程是否卡死 */
    internal var watchdogJob: Job? = null

    /** 看门狗恢复尝试次数（跨重启累计，仅在 tick 推进时重置）——@Volatile（F6：跨线程可见性） */
    @Volatile
    internal var watchdogRecoveryAttempts = 0

    /** 看门狗连续失败次数达到此阈值后使用更长间隔，避免 OEM 永久挂起时频繁重启 */
    internal val watchdogDegradedThreshold = 10

    // ── 游戏时间推进监控（第一类监控：看门狗统一判据） ──

    /** 停滞判定器（纯函数组件，三层看门狗共享同一判定出口） */
    internal val gameTimeProgressMonitor = GameTimeProgressMonitor()

    /** 游戏循环体最近活动墙钟（每次迭代更新，含暂停/保存跳过路径；internal：共享辅助 notifyLoopActivity 使用） */
    @Volatile
    internal var lastLoopActivityMs: Long = 0L

    /** 上次 tick 实际耗时（异常上报上下文用） */
    @Volatile
    internal var lastTickDurationMs: Long = 0L

    /** 引擎循环最近一次真实 tick 的进度快照（假运行时也更新） */
    @Volatile
    internal var lastProgressSnapshot: GameTimeProgressSnapshot? = null

    /** 秘境暂停租约最后续约墙钟（renewSecretRealmPauseLease 刷新） */
    @Volatile
    internal var secretRealmPauseRenewedAtMs: Long = 0L

    /** 看门狗上次恢复动作墙钟（60s 限频） */
    @Volatile
    internal var lastWatchdogRecoveryMs: Long = 0L

    /**
     * 循环主循环——launch 块内容：线程优先级、
     * ADPF session 创建、帧循环、finally 收尾（玉符 checkpoint + 停止信号完成）。
     * 单帧迭代提取 [gameLoopIteration]（跨帧累计状态经 [LoopIterationState] 传递）。
     *
     * @param signal 捕获于启动时点的停止信号（防跨代完成，见 startGameLoopInternal）
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    internal suspend fun CoroutineScope.gameLoopMainLoop(signal: CompletableDeferred<Unit>) {
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

        // 帧驱动 Accumulator 循环
        var state = LoopIterationState(accumulatorNs = 0L, lastFrameTimeNs = System.nanoTime())
        // 循环启动/换线程重启：清插值平滑状态（防旧线程残留滤波值污染新循环首帧）
        jitterSmoother.reset()
        // ADPF: 创建 Performance Hint Session（API 31+，低版本自动跳过）。
        // 目标按当前有效帧率换算（动态；后续帧率变化经
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

    // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    val isGameLoopRunning: Boolean get() = gameLoopJob?.isActive == true

    /** 直接读取暂停状态，绕过 unifiedState 的 50ms 采样延迟 */
    val isPausedDirect: Boolean get() = stateStore.isPaused.value

    /** 暂停状态窄流（替代 unifiedState.map{isPaused}，消除 20Hz 锁竞争依赖） */
    val isPaused: StateFlow<Boolean> get() = stateStore.isPaused

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
    @Volatile
    @Suppress("VariableNaming")  // 下划线前缀沿袭 Kotlin 私有后备字段惯例
    internal var _wasPausedByBackground = false
    val wasPausedByBackground: Boolean get() = _wasPausedByBackground

    /** 后台前用户是否已主动暂停（pauseForBackground 记录，恢复时决定是否保留暂停） */
    @Volatile
    internal var wasUserPausedBeforeBackground = false

    /** 每旬结算纯编排器（无状态，懒初始化复用同一实例；从 checkBreakthroughsAndPills 提取）。
     *
     * 生产 AUTHORITATIVE 每旬由 C++
     * runPhaseSettlementCore 完整结算，本属性与
     * [PhaseSettlementExecutor.execute] 完整版保留为跨语言对拍 Kotlin 基准
     * （DiffAuthoritativeTickTest 侧 B 使用）。
     */
    internal val phaseSettlementExecutor: PhaseSettlementExecutor by lazy {
        PhaseSettlementExecutor(cultivationService)
    }

    /** 月变结算纯编排器（无状态，懒初始化复用同一实例）。 */
    internal val monthSettlementExecutor: MonthSettlementExecutor by lazy {
        MonthSettlementExecutor(
            cultivationService, aiSectBeastAttackProcessor, systemManager
        )
    }

    /**
     * 月变真相源切换残留执行器：nativeSettleMonth（C++ 完整月变）
     * 之后的 Kotlin 未下沉扇出 + 平台效应草稿应用（生产结算/战斗三件/邮件/
     * 秘境邮件与 gate/购买日志）。手动构造（与 MonthSettlementExecutor
     * 同风格）；依赖经 cultivationService.eventProcessor 访问事件域服务。
     */
    internal val monthSettlementResidualExecutor: MonthSettlementResidualExecutor by lazy {
        MonthSettlementResidualExecutor(
            eventProcessor = cultivationService.eventProcessorForMonthSettlement,
            systemManager = systemManager
        )
    }

    /**
     * 年变真相源切换残留执行器：nativeSettleYear（C++ 完整年变）
     * 之后的 Kotlin 未下沉扇出（死亡链 ③ + 招募生成 ④ + AI 招募 ② + 商人收购
     * ③ + 交易刷新 ④）。手动构造；依赖经 cultivationService.eventProcessor 访问
     * 事件域服务。
     */
    internal val yearSettlementResidualExecutor: YearSettlementResidualExecutor by lazy {
        YearSettlementResidualExecutor(
            eventProcessor = cultivationService.eventProcessorForMonthSettlement
        )
    }

    /** 年变结算纯编排器（无状态，懒初始化复用同一实例）。 */
    internal val yearSettlementExecutor: YearSettlementExecutor by lazy {
        YearSettlementExecutor(cultivationService)
    }

    /** activeLoadJob 互斥锁（注册/清除/看门狗复位三处共享） */
    internal val activeLoadJobLock = Any()

    /**
     * 强制重置 isSaving 和 isLoading 为 false。
     * 用于看门狗检测到状态卡住时调用，也可从外部调用作为紧急恢复手段。
     * 同时取消正在运行的 save/load 协程，防止并发写入。
     */

    fun launchInScope(block: suspend CoroutineScope.() -> Unit): Job = engineScope.launch(block = block)

    fun scopeForStateIn(): CoroutineScope = engineScope


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
        // 状态机：仅 STOPPED → RUNNING。RESTARTING（emergency 独占）/
        // STOPPING/重复 RUNNING 一律拒绝——防止 emergency 与正常启动交错出双循环
        val won = transitionLoopPhase(setOf(LoopPhase.STOPPED), LoopPhase.RUNNING)
        if (won == null) {
            DomainLog.w(TAG, "startGameLoop rejected (phase=${engineLoopPhase})")
            return
        }
        // CAS 只赢下转换意图，启动体必须在锁内执行——
        // stop/shutdown/emergency 的工作体与 start 的启动体串行化，过期拆除体
        // 不误杀新循环；epoch 取自 CAS 结果（原子），启动前/后校验世代未变
        val epoch = won.epoch
        loopOpLock.withLock {
            try {
                startGameLoopInternal(resetWatchdogAttempts, LoopPhase.RUNNING, epoch)
            } catch (t: Throwable) {
                // phase 中毒防御：启动体异常（drain/onLoopStart/
                // startWatchdog/launch 拒绝）→ 回滚 RUNNING → STOPPED，避免
                // phase 停 RUNNING 无 job 导致后续 start 永久被拒
                engineLoopState.compareAndSet(
                    LoopState(LoopPhase.RUNNING, epoch),
                    LoopState(LoopPhase.STOPPED, epoch)
                )
                throw t
            }
        }
    }

    fun stopGameLoop() {
        // 状态机：RUNNING/RESTARTING → STOPPING 单赢家；已 STOPPING/STOPPED
        // 幂等返回（重复调用不重复执行）。emergency 重启中调 stop → 抢占成功，
        // emergency 的启动前检查（phase 已离开 RESTARTING）将放弃启动（不复活）
        if (transitionLoopPhase(setOf(LoopPhase.RUNNING, LoopPhase.RESTARTING), LoopPhase.STOPPING) == null) {
            DomainLog.d(TAG, "stopGameLoop: already stopping/stopped (phase=${engineLoopPhase})")
            return
        }
        // 拆除体在锁内执行——stop 的 CAS 与
        // 拆除体之间若 shutdown+start 完成，stop 恢复后的取消会杀掉新循环且
        // CAS2 失败静默返回（永久冻结）。锁串行化后拆除体只作用于"自己赢下
        // STOPPING 时的循环"，新循环在 stop 释放锁后启动，不受影响
        loopOpLock.withLock {
            stopGameLoopUnchecked()
            transitionLoopPhase(setOf(LoopPhase.STOPPING), LoopPhase.STOPPED)
            DomainLog.i(TAG, "Game loop stop requested, isPaused=true")
        }
    }

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
            // phase 中毒防御：启动体异常（recreate/snapshot/forceReset）
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


    suspend fun stopGameLoopAndWait(timeoutMs: Long = 5000): Boolean {
        // 边界语义：循环未运行即已停止——空态立即返回 true，
        // 避免挂满超时返回 false 误导调用方（signal 无循环永不完成）
        if (!isGameLoopRunning) return true
        stopGameLoop()
        return waitForLoopStopped(timeoutMs)
    }

    /**
     * 注册当前正在运行的加载协程 Job，供看门狗强制取消。
     * 在 finally 块中应调用 [clearActiveLoadJob] 清除引用。
     *
     * 读-改-写加锁原子化——主线程注册与看门狗线程
     * 复位交错时，陈旧 cancel 会误杀新注册操作、`= null` 会使在途操作脱离看门狗监管。
     */
    fun registerActiveLoadJob(job: Job) {
        synchronized(activeLoadJobLock) {
            if (activeLoadJob === job) return  // C4：防自注册自杀
            activeLoadJob?.cancel()
            activeLoadJob = job
        }
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
        // 此处禁止"提前 return"：提前返回会使秘境锁残留——
        // onDispose 丢失（Activity 重建）时 exitExploration 永不调用，锁卡 true → 永久冻结。
        if (_wasPausedByBackground && !isGameLoopRunning) {
            startGameLoop()
            DomainLog.i(TAG, "Game loop restarted from background (pause preserved)")
        }
        wasUserPausedBeforeBackground = false
        clearBackgroundPauseFlag()
    }

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

}

