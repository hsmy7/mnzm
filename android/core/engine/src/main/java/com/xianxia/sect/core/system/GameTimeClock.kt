package com.xianxia.sect.core.engine.system

import android.os.SystemClock
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单调时钟抽象（2026-08-01 注入化）。
 *
 * 生产使用 [SystemTimeSource]（SystemClock.elapsedRealtime()）；
 * 测试注入 FakeTimeSource 手工推进——修复旧测试依赖
 * returnDefaultValues 下 SystemClock 恒 0 的"算术恒等式假绿"。
 */
fun interface TimeSource {
    fun elapsedRealtime(): Long
}

/** 生产实现：Android 单调时钟。 */
object SystemTimeSource : TimeSource {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}

/** TimeSource Hilt 绑定（生产恒为 [SystemTimeSource]；测试直接构造 GameTimeClock(Fake)）。 */
@Module
@InstallIn(SingletonComponent::class)
object TimeSourceModule {
    @Provides
    @Singleton
    fun provideTimeSource(): TimeSource = SystemTimeSource
}

/**
 * 游戏时间时钟 — 全项目唯一的时间推进入口。
 *
 * ## 三层时间模型
 * - 单调时钟 (monotonic clock)：SystemClock.elapsedRealtime()，仅在本类调用。
 *   使用 elapsedRealtime() 而非 currentTimeMillis() 的原因：
 *   currentTimeMillis() 会因 NTP 同步/用户调整时间而跳动（甚至回退），
 *   elapsedRealtime() 是单调递增的，不受墙上时钟变化影响。
 * - 游戏时间 (game time)：单调时钟 × speed，受暂停/倍速影响
 * - 旬推进 (phase tick)：固定 2s/tick（1x 下），由累积器消费游戏时间产出
 *
 * ## 速度映射
 * | speed | 旬间隔 | 月间隔 |
 * |-------|--------|--------|
 * |   0   |   ∞    |   ∞    |  (暂停)
 * |   1   |  2.0s  |  6.0s  |
 * |   2   |  1.0s  |  3.0s  |
 */
@Singleton
class GameTimeClock @Inject constructor(
    private val timeSource: TimeSource
) {

    // ── 公开状态 ──

    /** 当前速度：0=暂停, 1=1x, 2=2x */
    @Volatile
    var speed: Int = 1
        private set

    private val _speedFlow = MutableStateFlow(1)
    val speedFlow: StateFlow<Int> = _speedFlow.asStateFlow()

    /** 当前旬的游戏时间毫秒数（随速度变化） */
    val msPerPhase: Long
        get() = when (speed) {
            0 -> Long.MAX_VALUE
            1 -> MS_PER_PHASE_1X
            2 -> MS_PER_PHASE_1X / 2
            else -> MS_PER_PHASE_1X
        }

    /** 当前旬进度 0.0~1.0（UI 进度条用） */
    val phaseProgress: Float
        get() {
            if (speed == 0) return 0f
            val denom = msPerPhase.toFloat()
            if (denom <= 0f) return 0f
            return (accumulatedGameMs.toFloat() / denom).coerceIn(0f, 1f)
        }

    /** 当前旬剩余毫秒数（UI 倒计时用） */
    val remainingPhaseMs: Long
        get() = maxOf(0L, msPerPhase - accumulatedGameMs)

    // ── 内部状态 ──

    private var accumulatedGameMs: Long = 0L
    private var lastWallMs: Long = 0L

    // ── 公开方法 ──

    /** 启动/重置时钟。游戏循环开始时调用。 */
    fun start() {
        lastWallMs = timeSource.elapsedRealtime()
        accumulatedGameMs = 0L
    }

    /**
     * 切换速度。自动保存已累积的游戏时间，防止切换时丢失进度。
     * @param newSpeed 0=暂停, 1=1x, 2=2x
     */
    fun setSpeed(newSpeed: Int) {
        val now = timeSource.elapsedRealtime()
        // 先结算从上次取样到此刻的累积量（用旧速度）
        if (speed > 0) {
            accumulatedGameMs += (now - lastWallMs) * speed
        }
        lastWallMs = now
        speed = newSpeed.coerceIn(0, 2)
        _speedFlow.value = speed
    }

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
        // 追补上限（按速度缩放）承担，此处保留原始增量供 accumulatedGameMs 累积
        val realDelta = rawDelta

        if (speed > 0) {
            accumulatedGameMs += realDelta * speed
        }

        var phases = (accumulatedGameMs / msPerPhase).toInt()

        // 2026-08-01 修复：单 tick 追补上限（1x=3 旬、2x=6 旬）——OEM 挂起/
        // 看门狗重启时单帧连续执行数十个完整事务、看门狗与业务互搏。
        // 追补源是异常挂起（非正常离线），玩家应尽快回到实时——
        // 触发上限时丢弃余量并记录，而非留存分摊。
        // 上限按速度缩放：旧固定阈值 3 在 2x 下引擎阻塞 1.5s 即触发丢弃（正常玩法误伤），
        // 缩放后按真实时间对称（两种速度下均约 6s 阻塞触发）。
        val phaseCap = MAX_PHASES_PER_TICK * speed.coerceAtLeast(1)
        if (phases > phaseCap) {
            Log.w(TAG, "tick catch-up capped at $phaseCap phases, dropped ${phases - phaseCap}")
            phases = phaseCap
            accumulatedGameMs = 0L
        } else if (phases > 0) {
            accumulatedGameMs -= phases.toLong() * msPerPhase
        }
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
        accumulatedGameMs = maxOf(0L, accumulatedGameMs - msPerPhase)
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

        /** 1x 速度下每旬对应的真实时间毫秒数 */
        const val MS_PER_PHASE_1X: Long = 2000L

        /**
         * 单 tick 最大追补旬数（2026-08-01 修复）。
         * 超过即丢弃余量并记录日志——追补源是 OEM 挂起/看门狗重启，玩家应尽快回到实时。
         * 防爆炸式跳变的唯一上限（曾另有 MAX_CATCHUP_MS 30s 裁剪，已被此按速度缩放的
         * 旬数上限完全覆盖，属冗余约束，已删除）。
         */
        const val MAX_PHASES_PER_TICK: Int = 3
    }
}
