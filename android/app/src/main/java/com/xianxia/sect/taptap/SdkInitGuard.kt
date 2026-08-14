package com.xianxia.sect.taptap

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级 SDK 初始化守卫——DirichletSdk.init / TapTapAuthManager.init 全局仅执行一次。
 *
 * ## 背景
 *
 * MainActivity 每次重建（登出 recreate / 合规切换 recreate / 系统回收后重建）都会走到
 * `onLoadingComplete → initTapTapSDK → initAdSdk` 链路。若无守卫，广告聚合 SDK
 * （DirichletSdk.init）与 TapTap 登录 SDK 会被重复初始化（广告公司反馈"重复初始化"）。
 * 广告 SDK 的全局初始化 API 行业标准要求进程内仅调用一次。
 *
 * ## 语义
 *
 * - [tryInitAdSdk] / [tryInitTapTapSdk]：AtomicBoolean CAS，仅进程内首次调用返回 true。
 * - [releaseAdSdkInit] / [releaseTapTapSdkInit]：SDK **同步初始化失败**时释放占用，
 *   允许下次 MainActivity 重建重试；异步失败（广告 `onInitFail`）不复位
 *   （SDK 已执行过 init，重试意义有限）。
 * - 账号登录/登出不涉及 SDK 重新初始化（SDK 初始化与具体账号解耦，全局一次正确）。
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
