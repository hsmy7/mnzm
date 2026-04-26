package com.xianxia.sect.core.util

data class UiError(
    val code: String,
    val userMessage: String,
    val appError: AppError? = null,
    val isRecoverable: Boolean = false,
    val severity: UiErrorSeverity = UiErrorSeverity.ERROR
) {
    val displayMessage: String get() = userMessage

    companion object {
        fun fromAppError(error: AppError): UiError {
            val (message, severity, recoverable) = when (error) {
                is AppError.Storage.SlotNotFound -> Triple("存档不存在", UiErrorSeverity.INFO, true)
                is AppError.Storage.SlotCorrupted -> Triple("存档数据损坏，请尝试恢复备份", UiErrorSeverity.ERROR, true)
                is AppError.Storage.SaveFailed -> Triple("保存失败，请重试", UiErrorSeverity.WARNING, true)
                is AppError.Storage.LoadFailed -> Triple("加载失败，请重试", UiErrorSeverity.WARNING, true)
                is AppError.Storage.DeleteFailed -> Triple("删除失败", UiErrorSeverity.WARNING, true)
                is AppError.Storage.BackupFailed -> Triple("备份失败", UiErrorSeverity.WARNING, true)
                is AppError.Storage.RestoreFailed -> Triple("恢复失败", UiErrorSeverity.ERROR, false)
                is AppError.Storage.EncryptionError -> Triple("数据加密错误", UiErrorSeverity.ERROR, false)
                is AppError.Storage.DecryptionError -> Triple("数据解密错误", UiErrorSeverity.ERROR, false)
                is AppError.Storage.IoError -> Triple("存储读写错误", UiErrorSeverity.ERROR, true)
                is AppError.Storage.DatabaseError -> Triple("数据库错误", UiErrorSeverity.ERROR, true)
                is AppError.Storage.TransactionFailed -> Triple("操作失败，请重试", UiErrorSeverity.WARNING, true)
                is AppError.Storage.Timeout -> Triple("操作超时，请重试", UiErrorSeverity.WARNING, true)
                is AppError.Storage.ChecksumMismatch -> Triple("数据校验失败，存档可能已损坏", UiErrorSeverity.ERROR, true)
                is AppError.Storage.KeyDerivationError -> Triple("密钥错误", UiErrorSeverity.ERROR, false)
                is AppError.Storage.Unknown -> Triple("存储错误", UiErrorSeverity.ERROR, false)

                is AppError.Network.NoConnection -> Triple("网络连接失败，请检查网络设置", UiErrorSeverity.WARNING, true)
                is AppError.Network.Timeout -> Triple("网络请求超时，请稍后重试", UiErrorSeverity.WARNING, true)
                is AppError.Network.Unknown -> Triple("网络错误", UiErrorSeverity.WARNING, true)

                is AppError.Production.SlotBusy -> Triple("槽位正在工作中", UiErrorSeverity.INFO, false)
                is AppError.Production.InsufficientMaterials -> Triple("材料不足", UiErrorSeverity.INFO, false)
                is AppError.Production.InvalidSlot -> Triple("无效的槽位", UiErrorSeverity.INFO, false)
                is AppError.Production.RecipeNotFound -> Triple("配方不存在", UiErrorSeverity.INFO, false)
                is AppError.Production.InvalidStateTransition -> Triple("操作状态不正确", UiErrorSeverity.INFO, false)
                is AppError.Production.Unknown -> Triple("生产操作失败", UiErrorSeverity.ERROR, false)

                is AppError.GameLoop.TickTimeout -> Triple("游戏运行缓慢", UiErrorSeverity.WARNING, true)
                is AppError.GameLoop.StateInconsistency -> Triple("游戏状态异常", UiErrorSeverity.ERROR, true)
                is AppError.GameLoop.EngineNotRunning -> Triple("游戏引擎未启动", UiErrorSeverity.ERROR, true)
                is AppError.GameLoop.Unknown -> Triple("游戏运行错误", UiErrorSeverity.ERROR, false)

                is AppError.Validation -> Triple(error.message, UiErrorSeverity.INFO, true)
                is AppError.Permission -> Triple("权限不足", UiErrorSeverity.ERROR, false)
                is AppError.NotFound -> Triple(error.message, UiErrorSeverity.INFO, true)
                is AppError.Unknown -> Triple("操作失败：${error.message}", UiErrorSeverity.ERROR, false)
            }
            return UiError(
                code = error.code,
                userMessage = message,
                appError = error,
                isRecoverable = recoverable,
                severity = severity
            )
        }

        fun fromGameError(error: GameError): UiError = fromAppError(error.toAppError())

        fun fromStorageError(error: StorageError, message: String = ""): UiError =
            fromAppError(error.toAppError(message))

        fun fromSaveError(error: com.xianxia.sect.data.unified.SaveError, message: String = ""): UiError =
            fromAppError(error.toAppError(message))

        fun fromProductionError(error: com.xianxia.sect.core.model.production.ProductionError): UiError =
            fromAppError(error.toAppError())

        fun fromGameLoopError(error: GameLoopError): UiError =
            fromAppError(error.toAppError())

        fun fromException(e: Throwable): UiError = fromAppError(AppError.fromException(e))
    }
}

enum class UiErrorSeverity {
    INFO,
    WARNING,
    ERROR
}
