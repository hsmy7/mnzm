package com.xianxia.sect.core.util

import kotlinx.coroutines.CoroutineScope

/**
 * 协程作用域提供者接口
 *
 * 在 :core:domain 中定义，由 :app 中的 ApplicationScopeProvider 实现。
 * 解除 domain 对 Android/Hilt 的依赖。
 */
interface CoroutineScopeProvider {
    val scope: CoroutineScope
    val ioScope: CoroutineScope
}
