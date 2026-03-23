package com.xianxia.sect.core.util

sealed class GameResult<out T> {
    data class Success<out T>(val data: T) : GameResult<T>()
    data class Failure(val error: GameError) : GameResult<Nothing>()
    
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
    
    fun getErrorMessage(): String? = when (this) {
        is Success -> null
        is Failure -> error.message
    }
    
    fun errorOrNull(): GameError? = when (this) {
        is Success -> null
        is Failure -> error
    }
    
    inline fun <R> map(transform: (T) -> R): GameResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }
    
    inline fun <R> flatMap(transform: (T) -> GameResult<R>): GameResult<R> = when (this) {
        is Success -> transform(data)
        is Failure -> this
    }
    
    inline fun onSuccess(action: (T) -> Unit): GameResult<T> {
        if (this is Success) action(data)
        return this
    }
    
    inline fun onFailure(action: (GameError) -> Unit): GameResult<T> {
        if (this is Failure) action(error)
        return this
    }
    
    inline fun recover(transform: (GameError) -> @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Failure -> transform(error)
    }
    
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw ResultException(error)
    }
    
    class ResultException(val error: GameError) : Exception(error.message, error.cause)
    
    companion object {
        inline fun <T> of(block: () -> T): GameResult<T> {
            return try {
                Success(block())
            } catch (e: Exception) {
                Failure(GameError.fromException(e))
            }
        }
        
        inline fun <T> ofNullable(block: () -> T?): GameResult<T> {
            return try {
                val result = block()
                if (result != null) Success(result) else Failure(GameError.NotFound("结果为空"))
            } catch (e: Exception) {
                Failure(GameError.fromException(e))
            }
        }
    }
}

object ErrorHandler {
    
    fun handleException(e: Throwable): String {
        return when (e) {
            is java.net.UnknownHostException -> "网络连接失败，请检查网络设置"
            is java.net.SocketTimeoutException -> "网络请求超时，请稍后重试"
            is java.io.IOException -> "网络错误，请检查网络连接"
            is kotlinx.coroutines.CancellationException -> "操作已取消"
            is IllegalArgumentException -> "参数错误：${e.message}"
            is IllegalStateException -> "状态错误：${e.message}"
            is NoSuchElementException -> "未找到相关数据"
            is SecurityException -> "权限不足"
            else -> "操作失败：${e.message ?: "未知错误"}"
        }
    }
    
    fun <T> safeCall(block: () -> T): GameResult<T> {
        return try {
            GameResult.Success(block())
        } catch (e: Exception) {
            GameResult.Failure(GameError.fromException(e))
        }
    }
    
    suspend fun <T> safeCallSuspend(block: suspend () -> T): GameResult<T> {
        return try {
            GameResult.Success(block())
        } catch (e: Exception) {
            GameResult.Failure(GameError.fromException(e))
        }
    }
    
    inline fun <T> runCatching(tag: String, operation: String, block: () -> T): GameResult<T> {
        return runCatching(tag, operation, ErrorContext.empty(), block)
    }
    
    inline fun <T> runCatching(
        tag: String,
        operation: String,
        context: ErrorContext,
        block: () -> T
    ): GameResult<T> {
        return try {
            GameResult.Success(block())
        } catch (e: Exception) {
            GameLogger.e(tag, "Error in $operation", context, e)
            GameResult.Failure(GameError.fromException(e))
        }
    }
}
