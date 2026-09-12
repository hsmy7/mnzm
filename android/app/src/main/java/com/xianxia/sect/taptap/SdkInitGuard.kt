package com.xianxia.sect.taptap

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级 SDK 初始化守卫——DirichletSdk.init / TapTapAuthManager.init 全局仅执行一次。
 *
 * ## 背景
 *
 * `DirichletSdk.init`（广告聚合 SDK）仅在登录成功回调（或已登录冷启动兜底）的
 * `MainActivity.ensureSdkServicesInitialized` 中调用；`TapTapAuthManager.init`
 * （TapTap 登录 SDK）在 MainActivity 通用启动协程 `initTapTapLoginSdk` 中调用。
 * 登出后再次登录、MainActivity 重建（登出 recreate / 合规切换 recreate / 系统回收
 * 后重建）都会再次走到这些入口。若无守卫，广告聚合 SDK 与 TapTap 登录 SDK 会被
 * 重复初始化（广告公司反馈"重复初始化"）。广告 SDK 的全局初始化 API 行业标准
 * 要求进程内仅调用一次。
 *
 * ## 语义
 *
 * - [tryInitAdSdk] / [tryInitTapTapSdk]：AtomicBoolean CAS，仅进程内首次调用返回 true。
 * - [releaseAdSdkInit] / [releaseTapTapSdkInit]：SDK **同步初始化失败**时释放占用，
 *   允许下次重建重试；异步失败（广告 `onInitFail`）不复位
 *   （SDK 已执行过 init，重试意义有限）。
 * - 账号登录/登出不涉及 SDK 重新初始化（SDK 初始化与具体账号解耦，全局一次正确）。
 * - 进程销毁复用（进程被杀后 Activity 重建）时进程级状态清零，SDK 初始化入口
 *   会再次放行——这是有意的：广告 SDK 已收敛到登录成功回调，进程复用后未重新
 *   登录则不会重复调用 SDK 内部方法（"进程销毁复用不重复初始化"的根因治理）。
 */
object SdkInitGuard {

    private val adSdkInitialized = AtomicBoolean(false)
    private val tapTapSdkInitialized = AtomicBoolean(false)

    /** 尝试获取广告 SDK 初始化权；仅进程内首次调用返回 true。 */
    fun tryInitAdSdk(): Boolean = adSdkInitialized.compareAndSet(false, true)

    /** 尝试获取 TapTap 登录 SDK 初始化权；仅进程内首次调用返回 true。 */
    fun tryInitTapTapSdk(): Boolean = tapTapSdkInitialized.compareAndSet(false, true)

    /** 广告 SDK 同步初始化失败时释放占用，允许下次重建重试。 */
    fun releaseAdSdkInit() {
        adSdkInitialized.set(false)
    }

    /** TapTap 登录 SDK 初始化失败时释放占用，允许下次重建重试。 */
    fun releaseTapTapSdkInit() {
        tapTapSdkInitialized.set(false)
    }

    /** 测试辅助：复位进程级状态（仅测试调用）。 */
    fun resetForTest() {
        adSdkInitialized.set(false)
        tapTapSdkInitialized.set(false)
    }
}
