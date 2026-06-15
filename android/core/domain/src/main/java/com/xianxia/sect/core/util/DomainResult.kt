package com.xianxia.sect.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * 领域操作统一结果类型。复用 [AppError]（尤其 [AppError.Domain]）作为错误载体。
 *
 * 替代全项目的裸 `Boolean` 返回与 `enum AddResult`：
 * - 调用方通过 `when` 穷尽性强制处理所有分支（编译期保证）。
 * - 失败时携带具体 [AppError.Domain] 子类，UI 可据实展示失败原因。
 *
 * 三种状态：
 * - [Success]：操作成功，携带结果数据。
 * - [Partial]：部分成功（如堆叠溢出），携带已成功部分与溢出量。
 * - [Failure]：操作失败，携带具体领域错误。
 *
 * 提供 Monad 风格组合算子 [map] / [flatMap]，便于链式处理。
 *
 * @param T 成功数据类型（协变）
 */
sealed interface DomainResult<out T> {

    /** 操作成功。 */
    data class Success<out T>(val data: T) : DomainResult<T>

    /**
     * 部分成功。用于堆叠溢出等场景：[data] 为已落库部分，[overflow] 为未能容纳的数量。
     */
    data class Partial<out T>(val data: T, val overflow: Int) : DomainResult<T>

    /** 操作失败，携带具体领域错误。 */
    data class Failure(val error: AppError.Domain) : DomainResult<Nothing>

    /** 映射成功数据；失败原样透传。 */
    fun <R> map(transform: (T) -> R): DomainResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Partial -> Partial(transform(data), overflow)
        is Failure -> this
    }

    /** 链式组合；失败原样透传。Partial 也视为成功路径，参与 transform。 */
    fun <R> flatMap(transform: (T) -> DomainResult<R>): DomainResult<R> = when (this) {
        is Success -> transform(data)
        is Partial -> transform(data)
        is Failure -> this
    }

    /** 成功（含 Partial）时返回数据，否则 null。 */
    fun getOrNull(): T? = (this as? Success)?.data ?: (this as? Partial)?.data

    /** 是否成功（Success 或 Partial）。 */
    val isSuccess: Boolean get() = this is Success<*> || this is Partial<*>

    /** 是否为完全失败。 */
    val isFailure: Boolean get() = this is Failure

    /** 失败时的错误，或 null。 */
    fun errorOrNull(): AppError.Domain? = (this as? Failure)?.error

    companion object {
        /**
         * 捕获 [block] 的异常：成功返回 [Success]，失败返回 [Failure]。
         *
         * **重要**：[CancellationException] 必须重新抛出（遵守协程取消语义），
         * 不可吞入 [Failure]。此实现修复了旧 `ProductionError.catching` 的吞噬 bug。
         *
         * @param domain 发生未预期异常时包装成的领域错误（默认为通用 Unknown）
         */
        inline fun <T> catching(
            domain: AppError.Domain = AppError.Domain.GameLoop.Unknown(),
            block: () -> T
        ): DomainResult<T> = try {
            Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Failure(domain)
        }
    }
}
