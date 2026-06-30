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

/** 进度条 lerp 追赶默认因子：每 100ms 关闭 30% 差距，~1 秒收敛到 97% */
const val CHASE_LERP_FACTOR_DEFAULT = 0.3f

/**
 * 单次 lerp 追赶 tick 后的进度值（纯函数，可单元测试）。
 *
 * - target 下降时立即 snap 到新值
 * - target 上升时通过 lerp 平滑收敛
 * - 差距 < 0.001 时精确 snap 到 target，防止浮点累积永不收敛
 */
fun nextChasingProgressTick(
    current: Float,
    target: Float,
    lerpFactor: Float
): Float {
    val t = target.coerceIn(0f, 1f)
    val lf = lerpFactor.coerceIn(0f, 1f)
    return when {
        t <= 0f -> 0f
        t < current -> t
        current < t -> {
            if (t - current < 0.001f) t
            else (current + (t - current) * lf).coerceAtMost(t)
        }
        else -> current
    }
}

/**
 * 统一的进度条动画钩子。每 100ms 通过 lerp 向目标收敛。
 *
 * 与旧 rememberAnimatedProgress 的关键区别：不再需要调用方提供
 * progressPerTick 速率参数。动画直接追赶 target，消除了"双重真相源"
 * （数据 target vs 动画预测速率）导致的整类进度条不准确 bug。
 *
 * @param target 真实数据值 [0, 1]，进度条的唯一真相源
 * @param lerpFactor 每 tick 收敛速率，默认 0.3（~1 秒到 97%）
 * @param paused 暂停动画（冻结当前值，恢复后继续追赶）
 */
@Composable
fun rememberChasingProgress(
    target: Float,
    lerpFactor: Float = CHASE_LERP_FACTOR_DEFAULT,
    paused: Boolean = false
): State<Float> {
    val clamped = target.coerceIn(0f, 1f)
    val animated = remember { mutableFloatStateOf(clamped) }
    val targetState by rememberUpdatedState(clamped)
    val factorState by rememberUpdatedState(lerpFactor.coerceIn(0f, 1f))
    val pausedState by rememberUpdatedState(paused)

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(PROGRESS_TICK_MS)
            if (pausedState) continue
            animated.floatValue = nextChasingProgressTick(
                current = animated.floatValue,
                target = targetState,
                lerpFactor = factorState
            )
        }
    }
    return animated
}
