package com.xianxia.sect.core.event

/**
 * 事件丢弃上报器（2026-08-13 批次 5）。
 *
 * core/domain 零 Android 依赖——app 层注入 Bugly 等崩溃上报实现；
 * 调用方（EventBus）已按 5s 节流，实现无需再节流。
 */
fun interface EventDropReporter {

    /**
     * 事件丢弃发生（已节流）。
     *
     * @param totalDropped 累计丢弃计数
     * @param eventType 最近丢弃的事件类型名
     */
    fun onEventDropped(totalDropped: Long, eventType: String)
}
