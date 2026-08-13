package com.xianxia.sect.core.animation

import kotlinx.coroutines.delay

/**
 * Timeline — 多段缓动编排（对标 Godot AnimationPlayer / Tween 链式编排）。
 *
 * ## 设计
 * - **段（Step）**：每段在 [Step.durationMs] 内以 [Step.easing] 推进 [Step.onUpdate]
 *   （可空 = 纯等待段），段结束时触发 [Step.onTick]；编排方式：`step(...).step(...)` 链式追加
 * - **重复**：[repeat] 指定整条序列重复播放次数（≥1，默认 1）
 * - **推进模型**：与 [EngineTween] 一致——外部每帧调用 [update]，或协程内 [awaitCompletion]；
 *   0 时长段在同一次 [update] 内链式即时推进（不额外消耗帧节拍）
 * - **帧率无关**：段时长由 [timeSource] 墙钟决定，与 tick 间隔无关（TimelineTest 断言）
 *
 * ## 使用约束
 * - `step`/`repeat` 编排须在 `play()` 之前完成（play 后追加不生效）
 * - 单段 [EngineTween] 行为（暂停/恢复/取消/完成回调）逐段生效，取消为整条时间轴取消
 *
 * @param timeSource 单调时间源（默认 [TimeSource.SYSTEM]；测试注入假时间源确定性验证）
 */
class Timeline(
    private val timeSource: TimeSource = TimeSource.SYSTEM
) {

    /**
     * 时间轴单段。
     *
     * @param durationMs 段时长（毫秒）；非正视为瞬时段——不触发 [onUpdate]，到点直接触发 [onTick]
     * @param easing 段内缓动曲线（仅 [onUpdate] 使用，默认线性）
     * @param onUpdate 段内每帧回调，参数为缓动后的段内进度 0→1；null = 纯等待段
     * @param onTick 段结束回调（进入下一段/重复/完成前恰好一次）
     */
    data class Step(
        val durationMs: Long,
        val easing: EasingFunction = EasingConstants.LINEAR,
        val onUpdate: ((Float) -> Unit)? = null,
        val onTick: (() -> Unit)? = null
    )

    private val steps: MutableList<Step> = mutableListOf()
    private var repeatCount: Int = 1
    private var stepIndex: Int = 0
    private var completedRounds: Int = 0
    private var activeTween: EngineTween? = null
    private var finished: Boolean = false
    private var canceled: Boolean = false

    /** 是否正在播放（当前段在跑） */
    val isPlaying: Boolean get() = !finished && !canceled && activeTween != null

    /** 是否已整条完成 */
    val isFinished: Boolean get() = finished

    /** 是否已被 [cancel] 取消 */
    val isCanceled: Boolean get() = canceled

    /** 当前段索引（0 起；未播放为 0） */
    val currentStepIndex: Int get() = stepIndex

    /** 整条时间轴线性进度 0..1（跨段与重复累计；完成恒为 1） */
    val progress: Float
        get() {
            if (finished) return 1f
            if (steps.isEmpty()) return 0f
            // Long 中间量（对抗性审查 2026-08-13 边界#8）：repeatCount × steps.size
            // Int 乘法在极端段数下溢出为负 → 进度错乱
            val totalSteps = repeatCount.toLong() * steps.size
            val completedSteps = completedRounds.toLong() * steps.size + stepIndex
            val stepProgress = activeTween?.progress ?: 0f
            return ((completedSteps + stepProgress) / totalSteps).coerceIn(0f, 1f)
        }

    /**
     * 追加一段。链式调用：`Timeline(ts).step(100).step(200, easing) { ... }`。
     *
     * @param durationMs 段时长（毫秒）；非正视为瞬时段（仅 [onTick]）
     * @param easing 段内缓动曲线
     * @param onUpdate 段内每帧回调（缓动后段内进度 0→1）
     * @param onTick 段结束回调
     * @return this（链式调用）
     */
    fun step(
        durationMs: Long,
        easing: EasingFunction = EasingConstants.LINEAR,
        onUpdate: ((Float) -> Unit)? = null,
        onTick: (() -> Unit)? = null
    ): Timeline {
        steps += Step(durationMs, easing, onUpdate, onTick)
        return this
    }

    /**
     * 设置整条序列重复播放次数。
     *
     * @param times 重复次数（≥1；1 = 只播一遍）
     * @return this（链式调用）
     */
    fun repeat(times: Int): Timeline {
        require(times >= 1) { "repeat times must be >= 1, was $times" }
        repeatCount = times
        return this
    }

    /**
     * 开始播放（从第一段第一遍开始）。幂等：已在播放则忽略；
     * 已完成/已取消则从零重新开始；空时间轴立即完成。
     *
     * @return this（链式调用）
     */
    fun play(): Timeline {
        if (isPlaying) return this
        canceled = false
        finished = false
        completedRounds = 0
        stepIndex = 0
        if (steps.isEmpty()) {
            finished = true
        } else {
            startStep(0)
        }
        return this
    }

    /** 暂停当前段（暂停期间墙钟不推进段进度） */
    fun pause() {
        activeTween?.pause()
    }

    /** 恢复当前段 */
    fun resume() {
        activeTween?.resume()
    }

    /** 取消整条时间轴：当前段停止，后续段不再执行，[Step.onTick] 不再触发 */
    fun cancel() {
        activeTween?.cancel()
        activeTween = null
        canceled = true
    }

    /**
     * 推进一帧：由外部驱动循环每帧调用。0 时长段链在同一次调用内即时推进
     * （段结束回调 onTick 按序触发，不额外消耗帧节拍）。
     */
    fun update() {
        var tween = activeTween
        while (tween != null && isPlaying) {
            tween.update()
            // 本段完成且 onComplete 已链式启动下一段（activeTween 变化）→ 继续循环
            // 推进 0 时长段链；未完成或完成但未推进 → 结束本次调用
            tween = if (tween.isFinished && activeTween !== tween) activeTween else null
        }
    }

    /**
     * 协程驱动直至整条完成（16ms 帧节拍轮询 [update]）。
     * 调用方协程被取消时经 [kotlinx.coroutines.delay] 自然传播取消。
     */
    suspend fun awaitCompletion() {
        while (isPlaying) {
            update()
            if (!isPlaying) break
            delay(EngineTween.DEFAULT_TICK_MS)
        }
    }

    private fun startStep(index: Int) {
        val step = steps[index]
        activeTween = EngineTween(
            timeSource = timeSource,
            durationMs = step.durationMs,
            easing = step.easing,
            onUpdate = { value -> step.onUpdate?.invoke(value) },
            onComplete = {
                step.onTick?.invoke()
                advance()
            }
        ).play()
    }

    private fun advance() {
        if (stepIndex + 1 < steps.size) {
            stepIndex++
            startStep(stepIndex)
        } else if (completedRounds + 1 < repeatCount) {
            completedRounds++
            stepIndex = 0
            startStep(0)
        } else {
            activeTween = null
            finished = true
        }
    }
}
