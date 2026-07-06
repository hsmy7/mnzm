package com.xianxia.sect.core.touch

/**
 * 触摸动作枚举 — 纯 Kotlin 跨平台，对应平台触摸事件的三阶段。
 */
enum class TouchAction {
    DOWN,
    MOVE,
    UP,
    CANCEL
}

/**
 * 跨平台触摸数据 — 由平台层（Android onTouchEvent / iOS touchesBegan）
 * 转换为该数据类后喂入 [SectMapTouchEngine]。
 *
 * @property x 触摸点的屏幕 X 坐标（像素）
 * @property y 触摸点的屏幕 Y 坐标（像素）
 * @property action 触摸动作类型
 * @property timestamp 时间戳（nanoTime），用于速度计算
 * @property pointerId 触摸点 ID，多点触摸预留
 */
data class TouchData(
    val x: Float,
    val y: Float,
    val action: TouchAction,
    val timestamp: Long = System.nanoTime(),
    val pointerId: Int = 0
)
