package com.xianxia.sect.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 进度条动画间隔，与 GameEngineCore.TICK_INTERVAL_MS 对齐 */
const val PROGRESS_TICK_MS = 100L

/** 1x 速度下每旬现实毫秒数，与 GameTimeClock.MS_PER_PHASE_1X 对齐 */
const val PROGRESS_MS_PER_PHASE_1X = 2000L

/**
 * 单次 tick 后的进度值（纯函数，可单元测试）。
 */
fun nextProgressTick(current: Float, target: Float, rate: Float): Float {
    val t = target.coerceIn(0f, 1f)
    val r = rate.coerceIn(0f, 1f)
    return when {
        t <= 0f -> 0f
        t < current -> t
        current < t -> (current + r).coerceAtMost(t)
        else -> current
    }
}

/**
 * 统一的进度条动画钩子。100ms 递增，归零 snap，下降 snap。
 */
@Composable
fun rememberAnimatedProgress(
    target: Float,
    progressPerTick: Float,
    paused: Boolean = false
): State<Float> {
    val clamped = target.coerceIn(0f, 1f)
    val animated = remember { mutableFloatStateOf(clamped) }
    val targetState by rememberUpdatedState(clamped)
    val rateState by rememberUpdatedState(progressPerTick.coerceIn(0f, 1f))
    val pausedState by rememberUpdatedState(paused)

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(PROGRESS_TICK_MS)
            if (pausedState) continue
            animated.floatValue = nextProgressTick(
                current = animated.floatValue,
                target = targetState,
                rate = rateState
            )
        }
    }
    return animated
}

/** 时间驱动型进度每 tick 增长量 */
fun progressRateForMonthsDuration(
    totalDurationMonths: Int,
    gameSpeed: Int = 1
): Float {
    if (totalDurationMonths <= 0) return 0f
    val totalRealMs = totalDurationMonths.toLong() * 3L *
        PROGRESS_MS_PER_PHASE_1X / maxOf(gameSpeed, 1)
    return if (totalRealMs > 0) {
        PROGRESS_TICK_MS.toFloat() / totalRealMs
    } else 0f
}

/** 速率驱动型进度每 tick 增长量 */
fun progressRateForPerSecond(
    ratePerSecond: Double,
    maxValue: Double
): Float {
    if (maxValue <= 0.0 || ratePerSecond <= 0.0) return 0f
    return (ratePerSecond * PROGRESS_TICK_MS / 1000.0 / maxValue)
        .toFloat().coerceIn(0f, 1f)
}
