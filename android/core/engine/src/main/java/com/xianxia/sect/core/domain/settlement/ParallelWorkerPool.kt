package com.xianxia.sect.core.engine.domain.settlement

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 并行计算辅助池 — 将独立计算任务分发到 [Dispatchers.Default] 并行执行。
 *
 * 当前用途：将 [accumulateBatch] 中的指纹计算和进度分类两路并行化。
 * 设计约束：
 * - 所有 Worker 为纯函数（只读输入 → 值输出），禁止修改状态
 * - 结果通过 [ParallelResult] 值对象返回
 *
 * @deprecated 使用 [DeviceCapabilityProfiler.parallelDispatcher] + coroutineScope 替代。
 * 此类仍保留用于兼容 SettlementCoordinator 中的现有调用。
 * 新增并行计算应使用 parallelDispatcher 或 BackgroundJobScheduler。
 */
@Singleton
@Deprecated("使用 DeviceCapabilityProfiler.parallelDispatcher + coroutineScope 替代",
    ReplaceWith("this@DeviceCapabilityProfiler.parallelDispatcher"))
class ParallelWorkerPool @Inject constructor() {

    /**
     * 并行执行两个独立计算。
     *
     * @param input 只读输入（[FingerprintSnapshot] 或其他不可变对象）
     * @param taskA 计算 A（如指纹检测）
     * @param taskB 计算 B（如进度分类）
     * @return [ParallelResult] 包含两个任务的返回值
     */
    suspend fun <T, A, B> parallelCompute(
        input: T,
        taskA: suspend (T) -> A,
        taskB: suspend (T) -> B
    ): ParallelResult<A, B> = coroutineScope {
        val defA = async(Dispatchers.Default) { taskA(input) }
        val defB = async(Dispatchers.Default) { taskB(input) }
        ParallelResult(defA.await(), defB.await())
    }
}

/**
 * 并行计算结果容器。
 */
data class ParallelResult<out A, out B>(
    val resultA: A,
    val resultB: B
)
