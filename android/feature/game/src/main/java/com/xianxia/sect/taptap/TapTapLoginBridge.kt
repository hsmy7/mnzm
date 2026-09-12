package com.xianxia.sect.taptap

import android.app.Activity

/**
 * TapTap 登录能力桥接接口，解耦 feature:game 对 app 模块 TapTapAuthManager 的直接依赖。
 * 由 app 模块实现并经 Hilt（BridgeBindingsModule）绑定注入。
 * 排行榜云端功能要求 TapTap 登录态（未登录时 API 回调错误码 500102）。
 */
interface TapTapLoginBridge {

    /** 当前是否已建立 TapTap 登录会话（无会话时排行榜仅展示天下宗门本地榜） */
    fun isLoggedIn(): Boolean

    /**
     * 触发 TapTap 登录（首次会弹出 TapTap 授权页；已登录过时通常免交互直返）。
     * 必须在主线程调用，[activity] 用于拉起授权页。
     * 结果经 [onResult] 回调返回，不抛异常。
     */
    fun login(activity: Activity, onResult: (LoginResult) -> Unit)

    /** 登录结果 */
    sealed interface LoginResult {
        /** 登录成功（会话已建立） */
        data object Success : LoginResult

        /** 用户取消授权 */
        data object Canceled : LoginResult

        /** 登录失败（SDK 未就绪/网络错误/拒绝等），[message] 为可展示文案 */
        data class Error(val message: String) : LoginResult
    }
}
