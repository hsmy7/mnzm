package com.xianxia.sect.core.touch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * 宗门地图手势引擎 — 纯 Kotlin 跨平台核心。
 *
 * ## 设计思路
 *
 * 参考游戏引擎（KorGE、Flame）的输入架构：平台层只做原始数据采集 → 转换为
 * [TouchData] → 喂入此引擎。引擎内维护显式状态机、物理衰减、边缘检测等所有
 * 手势逻辑，完全独立于平台。
 *
 * ## 状态机
 *
 * ```
 * Idle ──DOWN──→ Down ──MOVE(>slop)──→ Scrolling ──UP(有速度)──→ Flinging
 *                   │                                              │
 *                   │ 超时(长按)                          UP(无速度) │
 *                   │                                              │
 *                   ├──→ BuildingDrag / GoldFingerDrag              │
 *                   │                                              │
 *                   └──→ [TAP] ← UP(短触无移动)                   │
 *                                                                  │
 *             Flinging ──新DOWN──→ Down (中断惯性) ←───────────────┘
 * ```
 *
 * 参考来源：
 * - Flutter GestureArena (Drag > LongPress 优先级)
 * - Android GestureDetector (touchSlop 判决)
 * - KorGE Input State Machine (per-frame state + events)
 * - GameNative C++ StatefulTouchHandler
 */
class SectMapTouchEngine(
    private val callbacks: TouchEngineCallbacks,
    private val scope: CoroutineScope,
    private val config: TouchEngineConfig = TouchEngineConfig()
) {
    /** 当前手势状态 */
    private var state: GestureState = GestureState.Idle

    /** 长按模式（由 UI 层在 onLongPress 中设置） */
    private var longPressMode: LongPressMode = LongPressMode.NONE

    /** 手指按下位置 */
    private var downX = 0f
    private var downY = 0f

    /** 上一帧手指位置 */
    private var lastX = 0f
    private var lastY = 0f

    /** 速度追踪器 */
    private val velocityTracker = CustomVelocityTracker()

    /** 惯性滑行物理 */
    private val flingPhysics = FlingPhysics(
        deceleration = config.flingDeceleration,
        velocityThreshold = config.flingStopThreshold
    )

    /** 边缘自动平移 */
    private val edgeDetector = EdgePanDetector(
        edgeThickness = config.edgeThicknessPx,
        maxPanSpeed = config.maxEdgePanSpeed
    )

    /** 协程 Jobs */
    private var longPressJob: Job? = null
    private var flingJob: Job? = null

    /** 当前视口 */
    private var viewportW = 0f
    private var viewportH = 0f

    // ==================== 公开 API ====================

    fun updateViewport(w: Float, h: Float) {
        viewportW = w
        viewportH = h
        edgeDetector.updateViewport(w, h)
    }

    fun reset() {
        longPressJob?.cancel(); longPressJob = null
        flingJob?.cancel(); flingJob = null
        state = GestureState.Idle
        longPressMode = LongPressMode.NONE
        velocityTracker.clear()
        flingPhysics.stop()
    }

    /** 触摸事件入口 */
    fun onTouch(data: TouchData) {
        when (data.action) {
            TouchAction.DOWN -> handleDown(data)
            TouchAction.MOVE -> handleMove(data)
            TouchAction.UP -> handleUp(data)
            TouchAction.CANCEL -> handleCancel()
        }
        lastX = data.x
        lastY = data.y
    }

    // ==================== 事件处理 ====================

    private fun handleDown(data: TouchData) {
        // 中断 Fling
        if (state is GestureState.Flinging) {
            flingJob?.cancel(); flingJob = null
            callbacks.onFlingEnd()
        }

        downX = data.x
        downY = data.y
        lastX = data.x
        lastY = data.y
        longPressMode = LongPressMode.NONE
        velocityTracker.clear()
        velocityTracker.addPosition(data.x, data.y, data.timestamp)
        state = GestureState.Down

        // 长按检测
        longPressJob = scope.launch {
            try {
                delay(config.longPressTimeoutMs)
                if (state is GestureState.Down) {
                    val handled = callbacks.onLongPress(data.x, data.y)
                    if (handled) {
                        // UI 层会在回调中调用 setLongPressMode
                    }
                }
            } catch (e: CancellationException) { throw e }
        }
    }

    private fun handleMove(data: TouchData) {
        velocityTracker.addPosition(data.x, data.y, data.timestamp)

        when (state) {
            is GestureState.Down -> {
                val dx = data.x - downX
                val dy = data.y - downY
                if (dx * dx + dy * dy > config.touchSlopSq) {
                    longPressJob?.cancel(); longPressJob = null
                    state = if (callbacks.isGoldFingerActive()) {
                        GestureState.GoldFingerDrag
                    } else {
                        GestureState.Scrolling
                    }
                    if (state is GestureState.Scrolling) {
                        callbacks.onPanCamera(dx, dy)
                    }
                }
            }

            is GestureState.Scrolling -> {
                callbacks.onPanCamera(data.x - lastX, data.y - lastY)
            }

            is GestureState.BuildingDrag -> {
                val scale = callbacks.getCameraScale().coerceAtLeast(0.1f)
                val worldDx = (data.x - lastX) / scale
                val worldDy = (data.y - lastY) / scale
                callbacks.onBuildingDragUpdate(worldDx, worldDy)

                // 边缘自动平移
                val ep = edgeDetector.computePanVelocity(data.x, data.y)
                if (ep.dx != 0f || ep.dy != 0f) {
                    callbacks.onPanCamera(ep.dx * 0.016f, ep.dy * 0.016f)
                }
            }

            is GestureState.GoldFingerDrag -> {
                callbacks.onGoldFingerUpdate(data.x, data.y)
            }

            else -> {} // Idle / Flinging 忽略 Move
        }
    }

    private fun handleUp(data: TouchData) {
        longPressJob?.cancel(); longPressJob = null
        longPressMode = LongPressMode.NONE

        when (state) {
            is GestureState.Down -> {
                // 短触无移动 → Tap
                state = GestureState.Idle
                callbacks.onTap(data.x, data.y)
            }

            is GestureState.Scrolling -> {
                val vel = velocityTracker.computeVelocity()
                val speed = kotlin.math.sqrt(vel.x * vel.x + vel.y * vel.y)
                if (speed >= config.minFlingVelocity) {
                    state = GestureState.Flinging
                    callbacks.onFlingStart()
                    startFling(vel.x, vel.y)
                } else {
                    state = GestureState.Idle
                }
            }

            is GestureState.BuildingDrag -> {
                callbacks.onBuildingDragEnd()
                state = GestureState.Idle
            }

            is GestureState.GoldFingerDrag -> {
                state = GestureState.Idle
            }

            else -> state = GestureState.Idle
        }
    }

    private fun handleCancel() {
        longPressJob?.cancel(); longPressJob = null
        flingJob?.cancel(); flingJob = null
        longPressMode = LongPressMode.NONE
        state = GestureState.Idle
    }

    // ==================== Fling ====================

    private fun startFling(vx: Float, vy: Float) {
        flingPhysics.start(vx, vy)
        flingJob = scope.launch {
            try {
                while (isActive && flingPhysics.isActive) {
                    val delta = flingPhysics.update(0.016f)
                    if (delta.dx != 0f || delta.dy != 0f) {
                        callbacks.onPanCamera(delta.dx, delta.dy)
                    }
                    delay(FlingPhysics.DEFAULT_FRAME_INTERVAL_MS)
                }
            } catch (e: CancellationException) { throw e }
            finally {
                flingPhysics.stop()
                callbacks.onFlingEnd()
                if (state is GestureState.Flinging) state = GestureState.Idle
            }
        }
    }
}
