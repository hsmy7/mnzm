package com.xianxia.sect.core.engine.system

import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单调时钟抽象（构造注入；Android 实现位于 app 模块）。
 *
 * 生产绑定见 app 模块 `di/PlatformTimeModule`（SystemClock.elapsedRealtime）；
 * 测试注入 FakeTimeSource 手工推进——修复旧测试依赖 returnDefaultValues 下
 * SystemClock 恒 0 的"算术恒等式假绿"。
 */
fun interface TimeSource {
    fun elapsedRealtime(): Long
}

/**
 * 游戏时间时钟 — 全项目唯一的时间推进入口。
 *
 * ## 三层时间模型
 * - 单调时钟 (monotonic clock)：SystemClock.elapsedRealtime()，仅在本类调用。
 *   使用 elapsedRealtime() 而非 currentTimeMillis() 的原因：
 *   currentTimeMillis() 会因 NTP 同步/用户调整时间而跳动（甚至回退），
 *   elapsedRealtime() 是单调递增的，不受墙上时钟变化影响。
 * - 游戏时间 (game time)：单调时钟差值直接累加（单一时速，无倍率）
 * - 旬推进 (phase tick)：固定 2s/tick，由累积器消费游戏时间产出
 *
 * 暂停语义唯一载体是 GameStateStore.isPaused（引擎循环暂停分支消费死区，
 * 本时钟不承载暂停状态；暂停帧计划的 accumulatedGameMs=0 镜像使进度恒 0）。
 */
@Singleton
class GameTimeClock @Inject constructor(
    private val timeSource: TimeSource
) {

    // ── 公开状态 ──

    // ── 旬内连续进度（B8 时间进度投影源；结算改造 2026-09-27 方案 §10 B8）──

    /**
     * 旬内进度流 [0,1]——UI 进度条的连续时间真源。
     *
     * AUTHORITATIVE 下由 [mirrorFromNative] 每帧推送（native 帧计划
     * accumulatedGameMs，INV-2 未截断轴的旬内分量）；OFF 回退臂由 [tick]
     * 累积器刷新。暂停帧计划 accumulatedGameMs=0 → 进度恒 0。消费面经
     * GameEngine 暴露给 GameViewModel 的月进度投影（§6.5 map+stateIn 模式），
     * UI 不驱动 tick。
     */
    private val _phaseProgressFlow = MutableStateFlow(0f)
    val phaseProgressFlow: StateFlow<Float> = _phaseProgressFlow.asStateFlow()

    /** 当前旬的游戏时间毫秒数（单一时速常量） */
    val msPerPhase: Long
        get() = MS_PER_PHASE

    /** 当前旬进度 0.0~1.0（UI 进度条用；与 [phaseProgressFlow] 同一真源） */
    val phaseProgress: Float
        get() = _phaseProgressFlow.value

    /** 当前旬剩余毫秒数（UI 倒计时用） */
    val remainingPhaseMs: Long
        get() = maxOf(0L, msPerPhase - accumulatedGameMsInternal)

    // ── 内部状态 ──

    /** @Volatile（S3）：引擎线程 tick() 写、看门狗/UI 镜像跨线程读——
     *  非 volatile 在 32 位设备上 Long 撕裂读 */
    @Volatile
    private var accumulatedGameMsInternal: Long = 0L
    private var lastWallMs: Long = 0L

    /** 当旬已累积的游戏时间毫秒数（进度监控快照输入，awake 帧单调增长） */
    val accumulatedGameMs: Long
        get() = accumulatedGameMsInternal

    // ── 公开方法 ──

    /** 启动/重置时钟。游戏循环开始时调用。 */
    fun start() {
        lastWallMs = timeSource.elapsedRealtime()
        accumulatedGameMsInternal = 0L
        refreshPhaseProgress()
    }

    /**
     * AUTHORITATIVE 镜像推送：native 引擎循环为时间真相源，
     * 本时钟降级为 UI 展示镜像（phaseProgress/remainingPhaseMs 消费方
     * 不变）。镜像同时刷新墙钟基准——回退 OFF 模式时无缝接管。
     */
    fun mirrorFromNative(newAccumulatedGameMs: Long) {
        accumulatedGameMsInternal = newAccumulatedGameMs
        lastWallMs = timeSource.elapsedRealtime()
        refreshPhaseProgress()
    }

    /**
     * 离线收益注入端口（结算改造 2026-09-27 B7，方案 §2.4）：
     * 把 Kotlin 折算好的离线游戏毫秒一次性交给时钟。
     *
     * 回退臂语义：直接加进当旬累积器——回退臂的 [tick] 按追补上限消化，
     * 超限余量丢弃（现状"追补超限丢弃"语义延续；单引擎终态下本分支仅
     * 测试/降级触达）。AUTHORITATIVE 生产路径不消费本端口——离线注入由
     * GameEngineCoreOfflineOps 分流到 `GameCoreBridge.nativeInjectOfflineGameMs`
     * （C++ 三轴推进 + 积分全额结算），见该文件线程契约登记。
     *
     * @param gameMs 折算后的离线游戏毫秒（GameConfig.Time.offlineGameMs 产物，
     *   旬长整数倍）
     */
    fun addOfflineGameMs(gameMs: Long) {
        if (gameMs <= 0) return
        accumulatedGameMsInternal += gameMs
        refreshPhaseProgress()
    }

    /**
     * 当前墙钟毫秒（单调时钟）。
     * 供暂停租约、进度监控快照等外部时间基准使用（与内部累积同一时钟源）。
     */
    fun nowMs(): Long = timeSource.elapsedRealtime()

    /**
     * 每 tick 调用一次（由 frame-driven 游戏循环驱动，~100ms 间隔，accumulator 模式）。
     * @param isSettlementPending 当前是否有未完成的月度/年度结算
     * @return 本 tick 应推进的旬数，以及是否需要等待结算
     */
    fun tick(isSettlementPending: Boolean): TickResult {
        val now = timeSource.elapsedRealtime()
        val rawDelta = now - lastWallMs
        lastWallMs = now

        // rawDelta 不做单次上限裁剪——防爆炸式跳变由下方 MAX_PHASES_PER_TICK
        // 追补上限（单一常量）承担，此处保留原始增量供 accumulatedGameMs 累积
        // 理论负值边界防御（单调时钟不该回拨——负值会反向扣减累积）
        val realDelta = rawDelta.coerceAtLeast(0)

        accumulatedGameMsInternal += realDelta

        var phases = (accumulatedGameMsInternal / msPerPhase).toInt()

        // 单 tick 追补上限（3 旬）——OEM 挂起/
        // 看门狗重启时单帧连续执行数十个完整事务、看门狗与业务互搏。
        // 追补源是异常挂起（非正常离线），玩家应尽快回到实时——
        // 触发上限时丢弃余量并记录，而非留存分摊。
        val phaseCap = MAX_PHASES_PER_TICK  // D6 单一来源
        if (phases > phaseCap) {
            DomainLog.w(TAG, "tick catch-up capped at $phaseCap phases, dropped ${phases - phaseCap}")
            phases = phaseCap
            accumulatedGameMsInternal = 0L
        } else if (phases > 0) {
            accumulatedGameMsInternal -= phases.toLong() * msPerPhase
        }
        refreshPhaseProgress()
        return TickResult(phases, isSettlementPending)
    }

    /**
     * 消耗死区时间：更新 lastWallMs 但不累积游戏时间。
     *
     * 用于 tick 被阻止执行期间（保存/加载/暂停时），
     * 确保 wall clock 基准保持最新，防止恢复后产生虚高 delta。
     * accumulatedGameMs 保持不变 — 阻塞期间不产生游戏时间。
     */
    fun consumeDeadTime() {
        lastWallMs = timeSource.elapsedRealtime()
    }

    /**
     * 强制消费 1 旬的游戏时间（不推进游戏世界时间）。
     * 用于下旬结算等待时：时间已过但不应推进。
     */
    fun forceConsumeOnePhase() {
        accumulatedGameMsInternal = maxOf(0L, accumulatedGameMsInternal - msPerPhase)
        refreshPhaseProgress()
    }

    /**
     * 归还已消费的旬数到累积。
     *
     * 旬结算事务异常时整批回滚（生产 AUTHORITATIVE 路径 processAuthoritativeTick
     * 的失败分支）——状态时间回退 N 旬，但 [tick] 已按墙钟消费 N 旬
     * （累积被扣减）。不归还则状态时间永久落后墙钟（累积消费模式无自动
     * 追补），月度事件/生产结算错位。本方法把未落地的旬数加回累积，下次
     * tick 自然重新推进。
     *
     * @param count 需归还的旬数（整批回滚时 = 本次尝试的全部旬数）
     */
    fun refundPhases(count: Int) {
        if (count <= 0) return
        accumulatedGameMsInternal += count.toLong() * msPerPhase
        refreshPhaseProgress()
    }

    /** 刷新旬内进度流（累积器每个变更点调用——单一真源，禁止旁路写） */
    private fun refreshPhaseProgress() {
        val denom = msPerPhase.toFloat()
        _phaseProgressFlow.value = if (denom <= 0f) 0f
        else (accumulatedGameMsInternal.toFloat() / denom).coerceIn(0f, 1f)
    }

    // ── 类型 ──

    data class TickResult(
        /** 本 tick 应推进的旬数（可能为 0） */
        val phasesToAdvance: Int,
        /** 是否有未完成的结算（true 表示下旬应阻塞等待） */
        val isSettlementPending: Boolean
    )

    companion object {
        private const val TAG = "GameTimeClock"

        /** 每旬对应的真实时间毫秒数（单一时速） */
        const val MS_PER_PHASE: Long = 2000L

        /**
         * 单 tick 最大追补旬数。
         * 超过即丢弃余量并记录日志——追补源是 OEM 挂起/看门狗重启，玩家应尽快回到实时。
         * 防爆炸式跳变的唯一上限（双端单一来源 = 本常量；C++ 锚点
         * settlement.h kMaxPhasesPerTick，改值须双端同步）。
         */
        const val MAX_PHASES_PER_TICK: Int = 3
    }
}
