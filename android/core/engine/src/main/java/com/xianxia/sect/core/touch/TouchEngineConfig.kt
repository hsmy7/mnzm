package com.xianxia.sect.core.touch

/**
 * 手势引擎配置参数。
 *
 * 所有参数均有行业参考来源，详见方案文档。
 *
 * @property touchSlopPx 触摸滑动阈值（像素），超过此值才视为拖拽
 *   — 来源: Android ViewConfiguration.scaledTouchSlop (典型值 8~24px)
 * @property longPressTimeoutMs 长按超时（毫秒），空地区域按下时使用
 *   — 来源: 适配长按移动建筑场景，延长至 800ms 减少误触；iOS HIG 标准 400~500ms
 * @property buildingLongPressTimeoutMs 建筑上按下时使用的长按超时（毫秒）
 *   — 建筑上需更快进入移动模式（拖拽操作节奏），故短于 longPressTimeoutMs
 * @property minFlingVelocity 最小惯性滑行触发速度 (px/s)
 *   — 来源: Android Scroller minFlingVelocity (200~600)
 * @property flingDeceleration 惯性滑行减速度 (px/s²)
 *   — 1500px/s² ≈ 3000px/s 初速 2 秒停止，参考 Clash of Clans fling 手感
 * @property flingStopThreshold 惯性停止速度阈值 (px/s)
 *   — 来源: Flutter ClampingScrollSimulation (25px/s)
 * @property edgeThicknessPx 边缘平移触发区宽度 (像素)
 * @property maxEdgePanSpeed 边缘最大平移速度 (px/s)
 */
data class TouchEngineConfig(
    val touchSlopPx: Float = 16f,
    val longPressTimeoutMs: Long = 800L,
    val buildingLongPressTimeoutMs: Long = 200L,
    val minFlingVelocity: Float = 200f,
    val flingDeceleration: Float = 1500f,
    val flingStopThreshold: Float = 25f,
    val edgeThicknessPx: Float = 100f,
    val maxEdgePanSpeed: Float = 600f
) {
    /** touchSlop 的平方，用于距离比较 */
    val touchSlopSq: Float get() = touchSlopPx * touchSlopPx
}
