package com.xianxia.sect.core.perf

/**
 * 热状态读取端口（平台能力接口化）。
 *
 * Android 实现见 app 层 `AndroidThermalStatusReader`（PowerManager.currentThermalStatus）。
 * 引擎侧保持零 Android 依赖。
 */
interface ThermalStatusReader {
    /** 当前热状态等级（PowerManager 语义：0=NONE 1=LIGHT 2=MODERATE 3=SEVERE 4=EMERGENCY）；不支持时返回 0 */
    val currentThermalStatus: Int
}

/**
 * ADPF PerformanceHint 会话端口（PerformanceHintManager 的平台抽象）。
 *
 * Session 以不透明句柄（Any）跨端口传递：全部线程绑定守卫与并发语义
 * （Bugly #3114，见 [ThermalMonitor]）保留在引擎侧；实现层只做裸 API 调用，
 * 异常由引擎侧统一 catch 降级，不在实现层吞异常。
 */
interface PerformanceHintPort {
    /** ADPF 会话能力是否可用（API 31+ 且 performance_hint 服务已发布） */
    val isSupported: Boolean

    /** 创建 hint session（tids=属主线程 id 数组）；服务缺失/不可用时返回 null */
    fun acquire(tids: IntArray, targetDurationNanos: Long): Any?

    fun reportWorkDuration(session: Any, durationNanos: Long)

    fun updateTargetDuration(session: Any, targetDurationNanos: Long)

    fun release(session: Any)

    /** 当前线程 OS tid（android.os.Process.myTid 的平台抽象） */
    fun currentThreadId(): Int
}
