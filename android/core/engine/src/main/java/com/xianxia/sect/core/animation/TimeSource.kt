package com.xianxia.sect.core.animation

/**
 * 时间源抽象 — 动画/渲染的单调时钟来源。
 *
 * 默认实现为 [System.nanoTime]（Android 单调时钟）；iOS 移植可注入
 * `CADisplayLink.timestamp` 驱动的时间源，保证动画时长跨平台一致。
 *
 * ## 契约
 * - 返回值必须是单调递增的纳秒时间戳（不得回拨），否则动画插值会倒退
 * - 具体时间基准（epoch）无关紧要，只使用差值
 *
 * ## 可测性
 * 测试注入假时间源即可确定性验证动画时长/插值进度（见 CameraAnimatorTest）。
 */
fun interface TimeSource {
    /** 返回单调递增的纳秒时间戳 */
    fun nanoTime(): Long

    companion object {
        /** 系统默认单调时钟（Android [System.nanoTime]） */
        val SYSTEM: TimeSource = TimeSource { System.nanoTime() }
    }
}
