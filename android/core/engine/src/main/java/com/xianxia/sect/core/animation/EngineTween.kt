package com.xianxia.sect.core.animation

import kotlinx.coroutines.delay

/**
 * EngineTween — 时间源驱动的单段缓动动画（对标 Godot Tween）。
 *
 * ## 设计
 * - **TimeSource 驱动**：进度由 [TimeSource.nanoTime] 单调时钟差值计算（[timeSource] 可注入），
 *   与调用频率无关——无论 60fps / 30fps / 热控 10fps，动画总时长恒等于 [durationMs]，
 *   只在更低的 tick 粒度下被观测到（帧率无关性由 EngineTweenTest 断言）
 * - **轮询模型**：外部驱动循环每帧调用 [update]（游戏循环/渲染线程/协程 [awaitCompletion] 均可），
 *   与项目 Frame-Driven 游戏循环同构，纯 Kotlin 零 Android 依赖
 * - **暂停/恢复**：暂停期间墙钟流逝不推进进度（累计运行时长模型），恢复后无缝续播
 * - **回调语义**：[onUpdate] 每帧以**缓动后**的进度值调用（含首帧 0 与完成帧缓动(1)），
 *   [onComplete] 在最后一帧 [onUpdate] 之后恰好触发一次
 *
 * ## 参考
 * - Godot Tween (https://docs.godotengine.org/en/stable/classes/class_tween.html)
 * - Robert Penner 缓动 (https://easings.net/)
 *
 * @param timeSource 单调时间源（默认 [TimeSource.SYSTEM]；测试注入假时间源确定性验证）
 * @param durationMs 动画总时长（毫秒）；非正视为瞬时完成（仅触发 [onComplete]，不触发 [onUpdate]）
 * @param easing 缓动曲线（默认 [EasingConstants.EASE_OUT_CUBIC]）
 * @param onUpdate 每帧回调，参数为缓动后的进度值（0 → 缓动(1)）
 * @param onComplete 完成回调，动画自然结束时恰好一次
 */
class EngineTween(
    private val timeSource: TimeSource = TimeSource.SYSTEM,
    private val durationMs: Long,
    private val easing: EasingFunction = EasingConstants.EASE_OUT_CUBIC,
    private val onUpdate: (Float) -> Unit = {},
    private val onComplete: () -> Unit = {}
) {

    init {
        // 构造守卫（对抗性审查 2026-08-13 边界#3）：durationMs × NANOS_PER_MS 溢出
        // 为负 → 进度恒 0 → 动画永不完成。上限取纳秒可表示的最大毫秒数。
        require(durationMs in 0..MAX_DURATION_MS) {
            "durationMs 越界: $durationMs（合法范围 0..$MAX_DURATION_MS，超出会溢出导致动画永不完成）"
        }
    }

    private enum class State { IDLE, PLAYING, PAUSED, FINISHED, CANCELED }

    private var state: State = State.IDLE
    private var startNs: Long = 0L
    private var accumulatedNs: Long = 0L

    /** 是否正在播放（未暂停、未完成、未取消） */
    val isPlaying: Boolean get() = state == State.PLAYING

    /** 是否已暂停（暂停期间墙钟不推进进度） */
    val isPaused: Boolean get() = state == State.PAUSED

    /** 是否已自然完成（[onComplete] 已触发） */
    val isFinished: Boolean get() = state == State.FINISHED

    /** 是否已被 [cancel] 取消 */
    val isCanceled: Boolean get() = state == State.CANCELED

    /** 当前线性进度 0..1（未缓动；暂停期间冻结） */
    val progress: Float
        get() {
            if (durationMs <= 0L) return 1f
            val elapsedNs = elapsedNs()
            val durationNs = durationMs * NANOS_PER_MS
            return (elapsedNs.toFloat() / durationNs.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * 开始播放。幂等：已在播放则忽略；暂停中则等价 [resume]；
     * 已完成/已取消则从零重新开始。
     *
     * @return this（链式调用）
     */
    fun play(): EngineTween {
        if (state == State.PLAYING) return this
        if (state == State.PAUSED) {
            resume()
        } else {
            accumulatedNs = 0L
            startNs = timeSource.nanoTime()
            state = State.PLAYING
        }
        return this
    }

    /** 暂停：冻结进度，墙钟流逝不累计。已暂停/未播放则忽略。 */
    fun pause() {
        if (state != State.PLAYING) return
        accumulatedNs += (timeSource.nanoTime() - startNs).coerceAtLeast(0L)
        state = State.PAUSED
    }

    /** 恢复播放：从暂停点继续。未暂停则忽略。 */
    fun resume() {
        if (state != State.PAUSED) return
        startNs = timeSource.nanoTime()
        state = State.PLAYING
    }

    /** 取消：停止播放，[onComplete] 不再触发，进度冻结。 */
    fun cancel() {
        state = State.CANCELED
    }

    /**
     * 推进一帧：由外部驱动循环每帧调用（游戏循环/渲染线程/协程）。
     * 根据 [timeSource] 当前时间计算进度并回调 [onUpdate]；进度达到 1 时触发 [onComplete] 并结束。
     */
    fun update() {
        if (state != State.PLAYING) return
        if (durationMs <= 0L) {
            finishNow()
            return
        }
        val durationNs = durationMs * NANOS_PER_MS
        val t = (elapsedNs().toFloat() / durationNs.toFloat()).coerceIn(0f, 1f)
        onUpdate(easing(t))
        if (t >= 1f) finishNow()
    }

    /**
     * 协程驱动直至完成（16ms 帧节拍轮询 [update]）。
     * 调用方协程被取消时经 [kotlinx.coroutines.delay] 自然传播取消，动画随之停止。
     */
    suspend fun awaitCompletion() {
        while (isPlaying) {
            update()
            if (!isPlaying) break
            delay(DEFAULT_TICK_MS)
        }
    }

    private fun finishNow() {
        state = State.FINISHED
        onComplete()
    }

    /** 当前累计运行时长（纳秒）：已运行段 + 当前段；暂停/取消/未播放时冻结。
     *  FINISHED 也计入当前段——完成后 [progress] 必须恒为 1（[update] 完成帧
     *  置 FINISHED 后立即读 progress 不得回落为 0） */
    private fun elapsedNs(): Long =
        accumulatedNs + if (state == State.PLAYING || state == State.FINISHED) {
            (timeSource.nanoTime() - startNs).coerceAtLeast(0L)
        } else {
            0L
        }

    companion object {
        /** 纳秒/毫秒换算 */
        private const val NANOS_PER_MS = 1_000_000L

        /** 默认帧节拍（毫秒）：~60fps 轮询粒度 */
        const val DEFAULT_TICK_MS = 16L

        /** 最大时长（毫秒）：Long.MAX_VALUE / NANOS_PER_MS——超出则纳秒乘算溢出 */
        const val MAX_DURATION_MS = Long.MAX_VALUE / NANOS_PER_MS
    }
}
