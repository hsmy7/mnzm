package com.xianxia.sect.core.engine.system

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 游戏时间时钟 — 全项目唯一的时间推进入口。
 *
 * ## 三层时间模型
 * - 墙上时间 (wall clock)：System.currentTimeMillis()，仅在本类调用
 * - 游戏时间 (game time)：墙上时间 × speed，受暂停/倍速影响
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
class GameTimeClock @Inject constructor() {

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
        lastWallMs = System.currentTimeMillis()
        accumulatedGameMs = 0L
    }

    /**
     * 切换速度。自动保存已累积的游戏时间，防止切换时丢失进度。
     * @param newSpeed 0=暂停, 1=1x, 2=2x
     */
    fun setSpeed(newSpeed: Int) {
        val now = System.currentTimeMillis()
        // 先结算从上次取样到此刻的累积量（用旧速度）
        if (speed > 0) {
            accumulatedGameMs += (now - lastWallMs) * speed
        }
        lastWallMs = now
        speed = newSpeed.coerceIn(0, 2)
        _speedFlow.value = speed
    }

    /**
     * 每 tick 调用一次（100ms 间隔）。
     * @param isSettlementPending 当前是否有未完成的月度/年度结算
     * @return 本 tick 应推进的旬数，以及是否需要等待结算
     */
    fun tick(isSettlementPending: Boolean): TickResult {
        val now = System.currentTimeMillis()
        val realDelta = now - lastWallMs
        lastWallMs = now

        if (speed > 0) {
            accumulatedGameMs += realDelta * speed
        }

        val phases = (accumulatedGameMs / msPerPhase).toInt()
        if (phases > 0) {
            accumulatedGameMs -= phases.toLong() * msPerPhase
        }
        return TickResult(phases, isSettlementPending)
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
        /** 1x 速度下每旬对应的真实时间毫秒数 */
        const val MS_PER_PHASE_1X: Long = 2000L
    }
}
