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
    @PublishedApi internal var state: GestureState = GestureState.Idle

    /** 手指按下位置 */
    private var downX = 0f
    private var downY = 0f

    /** 上一帧手指位置 */
    private var lastX = 0f
    private var lastY = 0f

    /**
     * DOWN 时刻是否触摸在建筑/预览上。
     * 若为 true，则 ANY 移动直接进入 BuildingDrag（无需长按），
     * 无移动则视为 Tap（打开建筑对话框）。
     */
    private var hasBuildingTarget = false

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
        hasBuildingTarget = false
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
        velocityTracker.clear()
        velocityTracker.addPosition(data.x, data.y, data.timestamp)
        state = GestureState.Down
        hasBuildingTarget = false

        val alreadyEditing = callbacks.isInEditMode()

        if (alreadyEditing) {
            // [编辑模式] 放置/移动中：任意触摸立即进入拖拽预览
            // 行业标准做法（CoC、Rise of Kingdoms、Unity RTS Engine）
            when (callbacks.onLongPress(data.x, data.y)) {
                LongPressResult.BuildingDrag -> {
                    // UI 已设置，等待 MOVE 或 200ms 超时进入 BuildingDrag
                }
                LongPressResult.GoldFingerDrag -> {
                    state = GestureState.GoldFingerDrag
                    callbacks.onDragStart()
                }
                LongPressResult.NotHandled -> {
                    // 放置模式下非金手指区域 → 立即进入 BuildingDrag
                    state = GestureState.BuildingDrag
                    callbacks.onDragStart()
                }
            }
            // 还未进入 BuildingDrag → 200ms 自动进入
            if (state is GestureState.Down) {
                longPressJob = scope.launch {
                    try {
                        delay(200L)
                        if (state is GestureState.Down) {
                            state = GestureState.BuildingDrag
                            callbacks.onDragStart()
                        }
                    } catch (e: CancellationException) { throw e }
                }
            }
            return
        }

        // [非编辑模式] 检测是否在建筑上
        hasBuildingTarget = callbacks.findBuildingAt(data.x, data.y) != null

        if (hasBuildingTarget) {
            // 首次触摸建筑：200ms 长按后进入 BuildingDrag
            longPressJob = scope.launch {
                try {
                    delay(200L)
                    if (state is GestureState.Down) {
                        when (callbacks.onLongPress(data.x, data.y)) {
                            LongPressResult.BuildingDrag -> {
                                state = GestureState.BuildingDrag
                                callbacks.onDragStart()
                            }
                            LongPressResult.GoldFingerDrag -> {
                                state = GestureState.GoldFingerDrag
                                callbacks.onDragStart()
                            }
                            LongPressResult.NotHandled -> { /* 保持 Down */ }
                        }
                    }
                } catch (e: CancellationException) { throw e }
            }
        } else {
            // 非建筑区域：标准长按用于金手指激活
            longPressJob = scope.launch {
                try {
                    delay(config.longPressTimeoutMs)
                    if (state is GestureState.Down) {
                        when (callbacks.onLongPress(data.x, data.y)) {
                            LongPressResult.GoldFingerDrag -> {
                                state = GestureState.GoldFingerDrag
                                callbacks.onDragStart()
                            }
                            else -> { /* NotHandled: 保持 Down */ }
                        }
                    }
                } catch (e: CancellationException) { throw e }
            }
        }
    }

    private fun handleMove(data: TouchData) {
        velocityTracker.addPosition(data.x, data.y, data.timestamp)

        when (state) {
            is GestureState.Down -> {
                val dx = data.x - downX
                val dy = data.y - downY

                // [编辑模式] 任意移动即进入 BuildingDrag，直接预览跟随手指
                if (callbacks.isInEditMode() && (dx != 0f || dy != 0f)) {
                    longPressJob?.cancel(); longPressJob = null
                    state = GestureState.BuildingDrag
                    callbacks.onDragStart()
                    val scale = callbacks.getCameraScale().coerceAtLeast(0.1f)
                    callbacks.onBuildingDragUpdate(dx / scale, dy / scale)
                    return
                }

                // 首次触摸建筑中（等待 200ms 长按）→ 移动不做任何事
                if (hasBuildingTarget) return

                val overSlop = dx * dx + dy * dy > config.touchSlopSq
                if (overSlop) {
                    longPressJob?.cancel(); longPressJob = null
                    state = if (callbacks.isGoldFingerActive()) {
                        GestureState.GoldFingerDrag
                    } else {
                        GestureState.Scrolling
                    }
                    if (state is GestureState.Scrolling) {
                        callbacks.onDragStart()
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

                // 边缘自动平移（手指移到屏幕边缘时自动滚屏）
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
        hasBuildingTarget = false

        when (state) {
            is GestureState.Down -> {
                state = GestureState.Idle
                callbacks.onTap(data.x, data.y)
            }

            is GestureState.Scrolling -> {
                callbacks.onDragEnd()
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
                callbacks.onDragEnd()
                state = GestureState.Idle
            }

            is GestureState.GoldFingerDrag -> {
                callbacks.onDragEnd()
                state = GestureState.Idle
            }

            else -> state = GestureState.Idle
        }
    }

    private fun handleCancel() {
        longPressJob?.cancel(); longPressJob = null
        flingJob?.cancel(); flingJob = null
        when (state) {
            is GestureState.Scrolling -> callbacks.onDragEnd()
            is GestureState.BuildingDrag -> callbacks.onBuildingDragEnd()
            is GestureState.Flinging -> callbacks.onFlingEnd()
            else -> {}
        }
        state = GestureState.Idle
    }

    // ==================== Fling ====================

    private fun startFling(vx: Float, vy: Float) {
        flingPhysics.start(vx, vy)
        flingJob = scope.launch {
            try {
                while (isActive && flingPhysics.isActive) {
                    val delta = flingPhysics.update(0.033f)
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
