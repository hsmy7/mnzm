package com.xianxia.sect.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 测试用 [EngineContextDispatcher] 假实现。
 *
 * 在当前协程上直接执行闭包（不切换线程），
 * 避免 Mockito 无法 stub suspend 泛型函数的问题。
 */
class FakeEngineContextDispatcher : EngineContextDispatcher {
    override suspend fun <T> withEngineContext(block: suspend CoroutineScope.() -> T): T {
        return withContext(Dispatchers.Unconfined, block)
    }
}
