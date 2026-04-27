package com.xianxia.sect.core.util

@Deprecated("Use AppError.Domain", ReplaceWith("AppError.Domain", "com.xianxia.sect.core.util.AppError"))
sealed class GameError {
    abstract val code: String
    abstract val message: String
    abstract val cause: Throwable?

    @Deprecated("Use AppError.Domain.Validation.InvalidInput", ReplaceWith("AppError.Domain.Validation.InvalidInput(message, cause)", "com.xianxia.sect.core.util.AppError"))
    data class Validation(
        override val message: String,
        override val cause: Throwable? = null
    ) : GameError() {
        override val code = "VALIDATION_ERROR"
    }

    @Deprecated("Use AppError.Domain.GameState.InvalidState", ReplaceWith("AppError.Domain.GameState.InvalidState(message, cause)", "com.xianxia.sect.core.util.AppError"))
    data class GameState(
        override val message: String,
        override val cause: Throwable? = null
    ) : GameError() {
        override val code = "GAME_STATE_ERROR"
    }

    @Deprecated("Use AppError.Domain.Storage.SaveFailed", ReplaceWith("AppError.Domain.Storage.SaveFailed(message, cause)", "com.xianxia.sect.core.util.AppError"))
    data class SaveLoad(
        override val message: String,
        override val cause: Throwable? = null
    ) : GameError() {
        override val code = "SAVE_LOAD_ERROR"
    }

    @Deprecated("Use AppError.Domain.Network.NoConnection", ReplaceWith("AppError.Domain.Network.NoConnection(message, cause)", "com.xianxia.sect.core.util.AppError"))
    data class Network(
        override val message: String,
        override val cause: Throwable? = null
    ) : GameError() {
        override val code = "NETWORK_ERROR"
    }

    @Deprecated("Use AppError.Domain.GameState.PermissionDenied", ReplaceWith("AppError.Domain.GameState.PermissionDenied(message, cause)", "com.xianxia.sect.core.util.AppError"))
    data class Permission(
        override val message: String,
        override val cause: Throwable? = null
    ) : GameError() {
        override val code = "PERMISSION_ERROR"
    }

    @Deprecated("Use AppError.Domain.GameState.NotFound", ReplaceWith("AppError.Domain.GameState.NotFound(message, cause)", "com.xianxia.sect.core.util.AppError"))
    data class NotFound(
        override val message: String,
        override val cause: Throwable? = null
    ) : GameError() {
        override val code = "NOT_FOUND_ERROR"
    }

    @Deprecated("Use AppError.Unknown", ReplaceWith("AppError.Unknown(message, cause)", "com.xianxia.sect.core.util.AppError"))
    data class Unknown(
        override val cause: Throwable? = null
    ) : GameError() {
        override val code = "UNKNOWN_ERROR"
        override val message: String = cause?.message ?: "未知错误"
    }
    
    companion object {
        fun fromException(e: Throwable): GameError {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            return when (e) {
                is java.net.UnknownHostException,
                is java.net.SocketTimeoutException,
                is java.io.IOException -> Network(e.message ?: "网络错误", e)
                
                is IllegalArgumentException -> Validation(e.message ?: "参数错误", null)
                is IllegalStateException -> GameState(e.message ?: "状态错误", e)
                is NoSuchElementException -> NotFound(e.message ?: "未找到数据", e)
                is SecurityException -> Permission(e.message ?: "权限不足", e)
                
                else -> Unknown(e)
            }
        }
    }
}
