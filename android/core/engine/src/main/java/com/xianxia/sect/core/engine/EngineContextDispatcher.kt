package com.xianxia.sect.core.engine

import kotlinx.coroutines.CoroutineScope

/**
 * 引擎线程上下文调度器接口。
 *
 * 封装 [GameEngineCore.withEngineContext] 的可测试抽象。
 * 生产环境由 [GameEngineCore] 实现（调度到 GameDispatcher），
 * 测试环境注入假实现（直接在当前协程执行）。
 */
interface EngineContextDispatcher {
    /**
     * 在引擎线程上执行代码块并返回结果。
     * 若当前已在引擎线程上，则不切换上下文。
     */
    suspend fun <T> withEngineContext(block: suspend CoroutineScope.() -> T): T
}
