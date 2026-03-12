package com.xianxia.sect.core.util

sealed class GameResult<out T> {
    data class Success<out T>(val data: T) : GameResult<T>()
    data class Error(val message: String, val exception: Throwable? = null) : GameResult<Nothing>()
    
    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }
    
    fun getErrorMessage(): String? = when (this) {
        is Success -> null
        is Error -> message
    }
    
    inline fun <R> map(transform: (T) -> R): GameResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }
    
    inline fun onSuccess(action: (T) -> Unit): GameResult<T> {
        if (this is Success) action(data)
        return this
    }
    
    inline fun onError(action: (String) -> Unit): GameResult<T> {
        if (this is Error) action(message)
        return this
    }
    
    companion object {
        inline fun <T> of(block: () -> T): GameResult<T> {
            return try {
                Success(block())
            } catch (e: Exception) {
                Error(e.message ?: "Unknown error", e)
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
            GameResult.Error(handleException(e), e)
        }
    }
    
    suspend fun <T> safeCallSuspend(block: suspend () -> T): GameResult<T> {
        return try {
            GameResult.Success(block())
        } catch (e: Exception) {
            GameResult.Error(handleException(e), e)
        }
    }
}
