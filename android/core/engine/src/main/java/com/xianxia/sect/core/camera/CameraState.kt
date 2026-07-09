package com.xianxia.sect.core.camera

/**
 * 跨平台相机状态接口 — 由 [SectCameraState] 和 [WorldCameraState] 实现。
 *
 * 提供世界坐标 ↔ 屏幕坐标的转换方法，以及平移/缩放/居中/边界检测等相机控制。
 *
 * ## 坐标系统
 * - 世界坐标 (World): 游戏世界的逻辑坐标（无单位）
 * - 屏幕坐标 (Screen): 视口内像素位置，原点在视口左上角
 * - 转换: screen = (world - cameraX/Y) × scale
 *
 * ## 实现要求
 * - [setPosition] 和 [applyScale] 的实现必须对值进行 clamp（边界约束）
 * - [pan]/[zoom]/[centerOn] 推荐基于 [setPosition]/[applyScale] 实现
 *
 * ## updateViewport 契约
 * - 首次调用时（初始化）应重新计算默认缩放策略
 * - 屏幕旋转/多窗口尺寸变化时应再次评估默认缩放
 * - [zoom] 方法调用后应设置内部标记，使后续 [updateViewport] 不覆盖用户缩放
 *
 * ## 跨平台
 * - 纯 Kotlin 接口，零平台依赖
 * - iOS 移植无需修改接口定义
 */
interface CameraState {
    companion object {
        /** 最小缩放（最远鸟瞰） */
        const val MIN_ZOOM = 0.3f
        /** 最大缩放（最近特写） */
        const val MAX_ZOOM = 3.0f
    }
    /** 相机在世界空间中的 X 偏移 */
    val cameraX: Float
    /** 相机在世界空间中的 Y 偏移 */
    val cameraY: Float
    /** 缩放比例 (1.0 = 原始大小) */
    val scale: Float
    /** 视口宽度（像素） */
    val viewportWidth: Int
    /** 视口高度（像素） */
    val viewportHeight: Int
    /** 世界宽度（逻辑单位） */
    val worldWidth: Float
    /** 世界高度（逻辑单位） */
    val worldHeight: Float

    // ── 坐标转换 ──

    /** 世界 X → 屏幕 X：screen = (world - cameraX) × scale */
    fun worldToScreenX(wx: Float): Float = (wx - cameraX) * scale

    /** 世界 Y → 屏幕 Y：screen = (world - cameraY) × scale */
    fun worldToScreenY(wy: Float): Float = (wy - cameraY) * scale

    /** 屏幕 X → 世界 X：world = screen / scale + cameraX */
    fun screenToWorldX(sx: Float): Float = sx / scale + cameraX

    /** 屏幕 Y → 世界 Y：world = screen / scale + cameraY */
    fun screenToWorldY(sy: Float): Float = sy / scale + cameraY

    // ── 相机控制 ──

    /**
     * 更新视口尺寸。
     * 实现应根据策略调整 scale 或仅记录视口尺寸。
     */
    fun updateViewport(w: Int, h: Int)

    /**
     * 直接设置相机位置。
     * 实现必须对 (x, y) 进行边界约束（clamp），
     * 确保相机不会移出世界边界。
     */
    fun setPosition(x: Float, y: Float)

    /**
     * 直接设置缩放比例。
     * 实现必须对 [newScale] 进行边界约束（clamp），
     * 确保缩放不超过最小/最大限制。
     */
    fun applyScale(newScale: Float)

    /**
     * 平移相机视口。
     * @param dx 屏幕空间 X 增量（正数=右移视口=世界左移）
     * @param dy 屏幕空间 Y 增量（正数=下移视口=世界上移）
     */
    fun pan(dx: Float, dy: Float)

    /**
     * 将相机中心对准世界坐标 (wx, wy)。
     * 等效于 setPosition(wx - viewportWidth/2/scale, wy - viewportHeight/2/scale)。
     */
    fun centerOn(wx: Float, wy: Float)

    /**
     * 以焦点为中心进行缩放。
     * @param delta 缩放因子倍数 (>1 放大, <1 缩小)
     * @param focusX 焦点屏幕 X 坐标
     * @param focusY 焦点屏幕 Y 坐标
     */
    fun zoom(delta: Float, focusX: Float, focusY: Float)

    /**
     * 检测世界坐标点是否在视口可视范围内。
     * @param margin 额外边距（像素），用于预加载检测
     */
    fun isVisible(wx: Float, wy: Float, margin: Float = 0f): Boolean

    /** 重置相机到初始位置/缩放 */
    fun reset()
}
