package com.xianxia.sect.core.util

/**
 * 账号绑定因子提供者接口，解耦 data 模块对 app 模块 TapTapAuthManager 的直接依赖。
 * 由 app 模块在运行时注入实现。
 */
interface AccountBindingProvider {
    fun isLoggedIn(): Boolean
    fun getAccountUserId(): String?
}
