package com.xianxia.sect.core.animation

import com.xianxia.sect.core.camera.CameraState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * 平滑动画引擎 — 驱动 [CameraState] 以 EaseOutCubic 插值动画移动到目标位置/缩放。
 *
 * ## 插值器
 * - 默认: EaseOutCubic (t → 1-(1-t)³)，快速启动柔和结束，游戏行业标准
 * - 参考: https://easings.net/ (Robert Penner's Easing Functions)
 *
 * ## 交互优先
 * - [cancel] 在用户触摸时立即调用，动画让位于直接操控
 * - 参考: Flame Camera.moveTo (https://docs.flame-engine.org/latest/flame/camera-and-viewport.html)
 * - 参考: Godot Camera2D drag margin (https://docs.godotengine.org/en/stable/classes/class_camera2d.html)
 *
 * ## 跨平台
 * - 纯 Kotlin + kotlinx.coroutines，零平台依赖
 * - 时间源: [TimeSource] 抽象（默认 System.nanoTime()），iOS 移植可注入
 *   CADisplayLink 时间源（见 [TimeSource]）
 *
 * @param cameraState 要驱动的相机状态（通过 [CameraState.setPosition]/[CameraState.applyScale] 写入）
 * @param scope 协程作用域（通常为 viewModelScope 或 engineScope）
 * @param timeSource 单调时间源（默认 [TimeSource.SYSTEM]；测试注入假时间源确定性验证时长）
 */
class CameraAnimator(
    private val cameraState: CameraState,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.SYSTEM
) {
    private var job: Job? = null

    /** 当前是否有正在运行的动画 */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 动画移动到目标位置。
     *
     * 使用 EaseOutCubic 插值，以 ~60fps（每帧 16ms）驱动 [CameraState]。
     * 动画过程中会 cancel 之前的动画，确保不会有多个动画同时运行。
     *
     * @param target 目标位置（含可选的缩放目标）
     * @param durationMs 动画时长(ms)，默认 400ms
     */
    fun animateTo(target: CameraTarget, durationMs: Long = 400L) {
        // 对抗性审查修复：非正时长直接瞬时完成——负时长下 progress 恒负 → t 恒 0 →
        // `if (t >= 1f) break` 永不触发 → 死循环占用主线程（ANR）
        if (durationMs <= 0L) {
            cameraState.setPosition(target.x, target.y)
            target.scale?.let { cameraState.applyScale(it) }
            return
        }
        job?.cancel()
        job = scope.launch {
            try {
                val startX = cameraState.cameraX
                val startY = cameraState.cameraY
                val startScale = cameraState.scale
                val startTime = timeSource.nanoTime()

                while (isActive) {
                    val elapsed = (timeSource.nanoTime() - startTime) / 1_000_000f
                    val progress = elapsed / durationMs.toFloat()
                    val t = when {
                        progress < 0f -> 0f
                        progress > 1f -> 1f
                        else -> progress
                    }
                    val eased = EaseOutCubic(t)

                    cameraState.setPosition(
                        lerp(startX, target.x, eased),
                        lerp(startY, target.y, eased)
                    )
                    target.scale?.let { scaleTarget ->
                        val newScale = lerp(startScale, scaleTarget, eased)
                        cameraState.applyScale(newScale)
                    }

                    if (t >= 1f) break
                    delay(16L) // ~60fps
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    /**
     * 取消当前动画。
     *
     * 用户触摸操作时立即调用，动画让位于直接操控。
     * 调用后 [isRunning] 返回 false。
     */
    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        /**
         * EaseOutCubic 缓动函数: t ∈ [0,1] → 1-(1-t)³
         *
         * 快速启动、柔和结束，适合相机跟随/界面弹入等场景。
         * 参考: https://easings.net/#easeOutCubic
         */
        fun EaseOutCubic(t: Float): Float = 1 - (1 - t) * (1 - t) * (1 - t)

        /** 线性插值: a + (b - a) * t，t ∈ [0,1] */
        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}

// ── 注 ──
// CameraState 接口已提供 setPosition/applyScale 方法供 CameraAnimator 写入。
// SectCameraState 的 cameraX/cameraY/scale 本身为 Compose mutableFloatStateOf
// 私有 setter，通过接口方法实现写入，对外保持只读暴露。

/**
 * 相机动画的目标参数。
 *
 * @property x 目标世界 X 坐标（相机视口左上角）
 * @property y 目标世界 Y 坐标（相机视口左上角）
 * @property scale 可选的缩放目标值，为 null 时不改变缩放
 */
data class CameraTarget(
    val x: Float,
    val y: Float,
    val scale: Float? = null
)
