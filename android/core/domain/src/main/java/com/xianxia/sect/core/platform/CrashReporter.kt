package com.xianxia.sect.core.platform

/**
 * 崩溃上报端口。
 *
 * 现状 XianxiaApplication 直引 `com.tencent.bugly.crashreport.CrashReport` 初始化——
 * Bugly 为 Android 独占 SDK，iOS 对等需崩溃上报 SDK 替换。本接口参照 `AdService`/
 * `AudioPlayerFacade` 模式：core 层声明契约（零 Android 依赖），app 层 `BuglyCrashReporter`
 * 实现注入，iOS 对等实现映射 iOS 崩溃上报 SDK。
 *
 * ## 契约
 *
 * - 所有方法**永不抛出**（崩溃上报链路失败静默降级，不得反向影响主流程——
 *   上报通道自身崩溃是灾难场景）
 * - [initialize] 幂等，可在后台线程调用
 */
interface CrashReporter {
    /** 初始化崩溃上报通道（幂等，永不抛出） */
    fun initialize()

    /** 设置应用版本号 */
    fun setAppVersion(version: String)

    /** 设置用户标识（登录后更新） */
    fun setUserId(userId: String)

    /** 设置键值标签（设备信息等） */
    fun putUserData(key: String, value: String)

    /**
     * 上报已捕获异常（引擎循环异常等非崩溃路径归因）。
     * 实现内部静默降级（SDK 不可用时回退本地落盘）。
     */
    fun reportCaughtException(throwable: Throwable, context: Map<String, String> = emptyMap())
}
