package com.xianxia.sect.core

import android.util.Log
import kotlin.UninitializedPropertyAccessException

/**
 * TapTap SDK lateinit 崩溃守卫（防御层）。
 *
 * ## 背景
 *
 * TapTap SDK（闭源）会在 `com.taptap.sdk.kit.internal.TapTapKit.context`
 * （lateinit 属性）未赋值时，仍通过内部协程在主线程弹 Toast
 * （`TapToast.showToast` → `TapTapKit.getContext`）。本项目为隐私合规移除了
 * `TapTapKitInitProvider`（ContentProvider 自动初始化，见 AndroidManifest），
 * 因此在用户同意隐私政策之前该 context 从未赋值——任何这类内部 Toast 都会抛
 * `UninitializedPropertyAccessException` 并沿主线程 Looper 冒泡到默认崩溃处理器。
 *
 * ## 处理策略
 *
 * 拦截并吞掉这类**已知无害**的崩溃（进程不退出、不写入崩溃记录、不上报），
 * 其余崩溃原样转发给下一级处理器（Bugly / CrashHandler / 平台默认）。
 *
 * ## 为什么必须多层安装（Bugly #17002 复盘）
 *
 * `Thread.setDefaultUncaughtExceptionHandler` 是"后写覆盖"：Bugly 的
 * `CrashReport.initCrashReport` 与 `CrashHandler.register()` 都会覆盖默认处理器。
 * 仅靠 Application 一次性安装的守卫会在以下两条路径失效：
 * 1. Bugly 后台异步初始化完成 → 覆盖守卫 → 崩溃直达 Bugly 上报（进程退出）；
 * 2. GameActivity 注册 CrashHandler → 即使守卫仍在链上，CrashHandler 已先行
 *    落盘/上报后才转发给守卫（崩溃仍被记录）。
 *
 * 因此本守卫在**每个崩溃处理器边界**重复应用：
 * - [install]：包装当前默认处理器（Bugly 初始化完成后需重新安装，防覆盖）；
 * - [isSuppressible]：供 [CrashHandler.uncaughtException] 在记录/转发前先行判定。
 */
object TapTapCrashGuard {

    private const val TAG = "TapTapCrashGuard"

    /** 当前已安装的守卫处理器（防重复安装自环/链式堆积） */
    @Volatile
    private var installed: Thread.UncaughtExceptionHandler? = null

    /**
     * 判定是否为可抑制的 TapTap lateinit 崩溃（含混淆后变体与包装 cause 链）。
     *
     * 要求 cause 链上某一环**同时**满足：栈帧含 taptap 类 + lateinit 异常（类型或消息），
     * 避免误吞其它 taptap 帧但非 lateinit 的真实崩溃。
     */
    fun isSuppressible(throwable: Throwable): Boolean {
        var t: Throwable? = throwable
        while (t != null) {
            val hasTapTapFrame = t.stackTrace.any {
                it.className?.contains("taptap", ignoreCase = true) == true
            }
            if (hasTapTapFrame && (
                t is UninitializedPropertyAccessException ||
                t.message?.contains("lateinit", ignoreCase = true) == true
            )) {
                return true
            }
            t = t.cause
        }
        return false
    }

    /**
     * 安装守卫为进程默认崩溃处理器，包装当前下一级处理器。
     *
     * 幂等语义：若当前默认处理器已是本守卫（未被覆盖），重复调用直接返回；
     * 否则重新包装最新默认处理器（典型场景：Bugly 初始化完成后调用一次，
     * 让守卫位于 Bugly 之外层）。
     */
    fun install() {
        val next = Thread.getDefaultUncaughtExceptionHandler()
        if (next === installed) return
        val guard = Thread.UncaughtExceptionHandler { thread, throwable ->
            if (isSuppressible(throwable)) {
                Log.w(TAG, "Suppressed TapTap lateinit crash (SDK not yet consented)", throwable)
            } else {
                next?.uncaughtException(thread, throwable)
            }
        }
        installed = guard
        Thread.setDefaultUncaughtExceptionHandler(guard)
    }
}
