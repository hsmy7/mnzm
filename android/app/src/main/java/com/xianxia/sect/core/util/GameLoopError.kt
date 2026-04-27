package com.xianxia.sect.core.util

@Deprecated("Use AppError.Domain.GameLoop", ReplaceWith("AppError.Domain.GameLoop", "com.xianxia.sect.core.util.AppError"))
sealed class GameLoopError {
    data class TickTimeout(val elapsedMs: Long) : GameLoopError()
    data class StateInconsistency(val detail: String) : GameLoopError()
    data class EngineNotRunning(val operation: String) : GameLoopError()
    data class ConcurrentTick(val threadName: String) : GameLoopError()
    data class SaveConflict(val slot: Int) : GameLoopError()
    data class ResourceExhaustion(val resource: String) : GameLoopError()
    data class Unknown(val message: String, val cause: Throwable? = null) : GameLoopError()

    fun toMessage(): String = when (this) {
        is TickTimeout -> "游戏循环超时 (${elapsedMs}ms)"
        is StateInconsistency -> "状态不一致: $detail"
        is EngineNotRunning -> "引擎未运行，无法执行: $operation"
        is ConcurrentTick -> "并发Tick检测: $threadName"
        is SaveConflict -> "存档冲突: 槽位$slot"
        is ResourceExhaustion -> "资源耗尽: $resource"
        is Unknown -> message
    }
}
