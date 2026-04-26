package com.xianxia.sect.core.util

import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.unified.SaveError

sealed class AppError {
    abstract val code: String
    abstract val message: String
    abstract val cause: Throwable?

    sealed class Storage : AppError() {
        data class SlotNotFound(
            override val message: String = "存档槽位不存在",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_001"
        }

        data class SlotCorrupted(
            override val message: String = "存档数据已损坏",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_002"
        }

        data class SaveFailed(
            override val message: String = "保存失败",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_003"
        }

        data class LoadFailed(
            override val message: String = "加载失败",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_004"
        }

        data class DeleteFailed(
            override val message: String = "删除失败",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_005"
        }

        data class BackupFailed(
            override val message: String = "备份失败",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_006"
        }

        data class RestoreFailed(
            override val message: String = "恢复失败",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_007"
        }

        data class EncryptionError(
            override val message: String = "加密错误",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_008"
        }

        data class DecryptionError(
            override val message: String = "解密错误",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_009"
        }

        data class IoError(
            override val message: String = "IO错误",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_010"
        }

        data class DatabaseError(
            override val message: String = "数据库错误",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_011"
        }

        data class TransactionFailed(
            override val message: String = "事务失败",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_012"
        }

        data class Timeout(
            override val message: String = "操作超时",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_013"
        }

        data class ChecksumMismatch(
            override val message: String = "校验和不匹配",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_014"
        }

        data class KeyDerivationError(
            override val message: String = "密钥错误",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_015"
        }

        data class Unknown(
            override val message: String = "未知存储错误",
            override val cause: Throwable? = null
        ) : Storage() {
            override val code = "STORAGE_099"
        }
    }

    sealed class Network : AppError() {
        data class NoConnection(
            override val message: String = "网络连接失败",
            override val cause: Throwable? = null
        ) : Network() {
            override val code = "NET_001"
        }

        data class Timeout(
            override val message: String = "网络请求超时",
            override val cause: Throwable? = null
        ) : Network() {
            override val code = "NET_002"
        }

        data class Unknown(
            override val message: String = "未知网络错误",
            override val cause: Throwable? = null
        ) : Network() {
            override val code = "NET_099"
        }
    }

    sealed class Production : AppError() {
        data class SlotBusy(
            override val message: String = "槽位正在工作中",
            val slotIndex: Int = -1,
            override val cause: Throwable? = null
        ) : Production() {
            override val code = "PROD_001"
        }

        data class InsufficientMaterials(
            override val message: String = "材料不足",
            val missingMaterials: Map<String, Int> = emptyMap(),
            override val cause: Throwable? = null
        ) : Production() {
            override val code = "PROD_002"
        }

        data class InvalidSlot(
            override val message: String = "无效的槽位",
            val slotIndex: Int = -1,
            override val cause: Throwable? = null
        ) : Production() {
            override val code = "PROD_003"
        }

        data class RecipeNotFound(
            override val message: String = "配方不存在",
            val recipeId: String = "",
            override val cause: Throwable? = null
        ) : Production() {
            override val code = "PROD_004"
        }

        data class InvalidStateTransition(
            override val message: String = "无效的状态转换",
            val fromStatus: String = "",
            val toStatus: String = "",
            override val cause: Throwable? = null
        ) : Production() {
            override val code = "PROD_005"
        }

        data class Unknown(
            override val message: String = "未知生产错误",
            override val cause: Throwable? = null
        ) : Production() {
            override val code = "PROD_099"
        }
    }

    sealed class GameLoop : AppError() {
        data class TickTimeout(
            val elapsedMs: Long,
            override val cause: Throwable? = null
        ) : GameLoop() {
            override val code = "LOOP_001"
            override val message: String = "游戏循环超时 (${elapsedMs}ms)"
        }

        data class StateInconsistency(
            val detail: String,
            override val cause: Throwable? = null
        ) : GameLoop() {
            override val code = "LOOP_002"
            override val message: String = "状态不一致: $detail"
        }

        data class EngineNotRunning(
            val operation: String,
            override val cause: Throwable? = null
        ) : GameLoop() {
            override val code = "LOOP_003"
            override val message: String = "引擎未运行，无法执行: $operation"
        }

        data class Unknown(
            override val message: String = "未知游戏循环错误",
            override val cause: Throwable? = null
        ) : GameLoop() {
            override val code = "LOOP_099"
        }
    }

    data class Validation(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError() {
        override val code = "VALIDATION_ERROR"
    }

    data class Permission(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError() {
        override val code = "PERMISSION_ERROR"
    }

    data class NotFound(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError() {
        override val code = "NOT_FOUND_ERROR"
    }

    data class Unknown(
        override val message: String = "未知错误",
        override val cause: Throwable? = null
    ) : AppError() {
        override val code = "UNKNOWN_ERROR"
    }

    fun toUiError(): UiError = UiError(
        code = code,
        userMessage = message,
        appError = this
    )

    companion object {
        fun fromException(e: Throwable): AppError {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return when (e) {
                is java.net.UnknownHostException -> Network.NoConnection(cause = e)
                is java.net.SocketTimeoutException -> Network.Timeout(cause = e)
                is java.io.IOException -> Network.NoConnection(e.message ?: "网络错误", e)
                is IllegalArgumentException -> Validation(e.message ?: "参数错误", e)
                is IllegalStateException -> GameLoop.StateInconsistency(e.message ?: "状态错误", e)
                is NoSuchElementException -> NotFound(e.message ?: "未找到数据", e)
                is SecurityException -> Permission(e.message ?: "权限不足", e)
                else -> Unknown(e.message ?: "未知错误", e)
            }
        }
    }
}

fun GameError.toAppError(): AppError = when (this) {
    is GameError.Validation -> AppError.Validation(message, cause)
    is GameError.GameState -> AppError.GameLoop.StateInconsistency(message, cause)
    is GameError.SaveLoad -> AppError.Storage.SaveFailed(message, cause)
    is GameError.Network -> AppError.Network.NoConnection(message, cause)
    is GameError.Permission -> AppError.Permission(message, cause)
    is GameError.NotFound -> AppError.NotFound(message, cause)
    is GameError.Unknown -> AppError.Unknown(message, cause)
}

fun StorageError.toAppError(message: String = "", cause: Throwable? = null): AppError.Storage = when (this) {
    StorageError.INVALID_SLOT -> AppError.Storage.SlotNotFound(message.ifEmpty { "无效的存档槽位" }, cause)
    StorageError.SLOT_EMPTY -> AppError.Storage.SlotNotFound(message.ifEmpty { "存档槽位为空" }, cause)
    StorageError.NOT_FOUND -> AppError.Storage.SlotNotFound(message.ifEmpty { "未找到数据" }, cause)
    StorageError.SLOT_CORRUPTED -> AppError.Storage.SlotCorrupted(message.ifEmpty { "存档数据已损坏" }, cause)
    StorageError.SAVE_FAILED -> AppError.Storage.SaveFailed(message.ifEmpty { "保存失败" }, cause)
    StorageError.LOAD_FAILED -> AppError.Storage.LoadFailed(message.ifEmpty { "加载失败" }, cause)
    StorageError.DELETE_FAILED -> AppError.Storage.DeleteFailed(message.ifEmpty { "删除失败" }, cause)
    StorageError.BACKUP_FAILED -> AppError.Storage.BackupFailed(message.ifEmpty { "备份失败" }, cause)
    StorageError.RESTORE_FAILED -> AppError.Storage.RestoreFailed(message.ifEmpty { "恢复失败" }, cause)
    StorageError.ENCRYPTION_ERROR -> AppError.Storage.EncryptionError(message.ifEmpty { "加密错误" }, cause)
    StorageError.DECRYPTION_ERROR -> AppError.Storage.DecryptionError(message.ifEmpty { "解密错误" }, cause)
    StorageError.IO_ERROR -> AppError.Storage.IoError(message.ifEmpty { "IO错误" }, cause)
    StorageError.DATABASE_ERROR -> AppError.Storage.DatabaseError(message.ifEmpty { "数据库错误" }, cause)
    StorageError.TRANSACTION_FAILED -> AppError.Storage.TransactionFailed(message.ifEmpty { "事务失败" }, cause)
    StorageError.TIMEOUT -> AppError.Storage.Timeout(message.ifEmpty { "操作超时" }, cause)
    StorageError.OUT_OF_MEMORY -> AppError.Storage.IoError(message.ifEmpty { "内存不足" }, cause)
    StorageError.WAL_ERROR -> AppError.Storage.IoError(message.ifEmpty { "WAL错误" }, cause)
    StorageError.CHECKSUM_MISMATCH -> AppError.Storage.ChecksumMismatch(message.ifEmpty { "校验和不匹配" }, cause)
    StorageError.KEY_DERIVATION_ERROR -> AppError.Storage.KeyDerivationError(message.ifEmpty { "密钥派生错误" }, cause)
    StorageError.VALIDATION_ERROR -> AppError.Storage.SlotCorrupted(message.ifEmpty { "数据校验失败" }, cause)
    StorageError.BATCH_OPERATION_FAILED -> AppError.Storage.TransactionFailed(message.ifEmpty { "批量操作失败" }, cause)
    StorageError.CONCURRENT_MODIFICATION -> AppError.Storage.TransactionFailed(message.ifEmpty { "并发修改冲突" }, cause)
    StorageError.UNKNOWN -> AppError.Storage.Unknown(message.ifEmpty { "未知存储错误" }, cause)
}

fun SaveError.toAppError(message: String = "", cause: Throwable? = null): AppError.Storage = when (this) {
    SaveError.INVALID_SLOT -> AppError.Storage.SlotNotFound(message.ifEmpty { "无效的存档槽位" }, cause)
    SaveError.SLOT_EMPTY -> AppError.Storage.SlotNotFound(message.ifEmpty { "存档槽位为空" }, cause)
    SaveError.NOT_FOUND -> AppError.Storage.SlotNotFound(message.ifEmpty { "未找到数据" }, cause)
    SaveError.SLOT_CORRUPTED -> AppError.Storage.SlotCorrupted(message.ifEmpty { "存档数据已损坏" }, cause)
    SaveError.SAVE_FAILED -> AppError.Storage.SaveFailed(message.ifEmpty { "保存失败" }, cause)
    SaveError.LOAD_FAILED -> AppError.Storage.LoadFailed(message.ifEmpty { "加载失败" }, cause)
    SaveError.DELETE_FAILED -> AppError.Storage.DeleteFailed(message.ifEmpty { "删除失败" }, cause)
    SaveError.BACKUP_FAILED -> AppError.Storage.BackupFailed(message.ifEmpty { "备份失败" }, cause)
    SaveError.RESTORE_FAILED -> AppError.Storage.RestoreFailed(message.ifEmpty { "恢复失败" }, cause)
    SaveError.ENCRYPTION_ERROR -> AppError.Storage.EncryptionError(message.ifEmpty { "加密错误" }, cause)
    SaveError.DECRYPTION_ERROR -> AppError.Storage.DecryptionError(message.ifEmpty { "解密错误" }, cause)
    SaveError.IO_ERROR -> AppError.Storage.IoError(message.ifEmpty { "IO错误" }, cause)
    SaveError.DATABASE_ERROR -> AppError.Storage.DatabaseError(message.ifEmpty { "数据库错误" }, cause)
    SaveError.TRANSACTION_FAILED -> AppError.Storage.TransactionFailed(message.ifEmpty { "事务失败" }, cause)
    SaveError.TIMEOUT -> AppError.Storage.Timeout(message.ifEmpty { "操作超时" }, cause)
    SaveError.OUT_OF_MEMORY -> AppError.Storage.IoError(message.ifEmpty { "内存不足" }, cause)
    SaveError.WAL_ERROR -> AppError.Storage.IoError(message.ifEmpty { "WAL错误" }, cause)
    SaveError.CHECKSUM_MISMATCH -> AppError.Storage.ChecksumMismatch(message.ifEmpty { "校验和不匹配" }, cause)
    SaveError.KEY_DERIVATION_ERROR -> AppError.Storage.KeyDerivationError(message.ifEmpty { "密钥派生错误" }, cause)
    SaveError.UNKNOWN -> AppError.Storage.Unknown(message.ifEmpty { "未知存储错误" }, cause)
}

fun com.xianxia.sect.core.model.production.ProductionError.toAppError(): AppError.Production = when (this) {
    is com.xianxia.sect.core.model.production.ProductionError.SlotBusy ->
        AppError.Production.SlotBusy(message, slotIndex)
    is com.xianxia.sect.core.model.production.ProductionError.InsufficientMaterials ->
        AppError.Production.InsufficientMaterials(message, missingMaterials)
    is com.xianxia.sect.core.model.production.ProductionError.InvalidSlot ->
        AppError.Production.InvalidSlot(message, slotIndex)
    is com.xianxia.sect.core.model.production.ProductionError.RecipeNotFound ->
        AppError.Production.RecipeNotFound(message, recipeId)
    is com.xianxia.sect.core.model.production.ProductionError.InvalidStateTransition ->
        AppError.Production.InvalidStateTransition(message, fromStatus, toStatus)
    is com.xianxia.sect.core.model.production.ProductionError.DiscipleNotAvailable ->
        AppError.Production.SlotBusy(message)
    is com.xianxia.sect.core.model.production.ProductionError.ProductionFailed ->
        AppError.Production.Unknown(message)
    is com.xianxia.sect.core.model.production.ProductionError.Unknown ->
        AppError.Production.Unknown(message)
}

fun GameLoopError.toAppError(): AppError = when (this) {
    is GameLoopError.TickTimeout -> AppError.GameLoop.TickTimeout(elapsedMs)
    is GameLoopError.StateInconsistency -> AppError.GameLoop.StateInconsistency(detail)
    is GameLoopError.EngineNotRunning -> AppError.GameLoop.EngineNotRunning(operation)
    is GameLoopError.ConcurrentTick -> AppError.GameLoop.StateInconsistency("并发Tick: $threadName")
    is GameLoopError.SaveConflict -> AppError.Storage.SaveFailed("存档冲突: 槽位$slot")
    is GameLoopError.ResourceExhaustion -> AppError.GameLoop.StateInconsistency("资源耗尽: $resource")
    is GameLoopError.Unknown -> AppError.GameLoop.Unknown(message, cause)
}
