package com.xianxia.sect.core.util

import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.unified.SaveError

sealed class AppError {
    abstract val code: String
    abstract val message: String
    abstract val cause: Throwable?

    sealed class Domain : AppError() {

        sealed class Production : Domain() {
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

            data class DiscipleNotAvailable(
                override val message: String = "弟子不可用",
                val discipleId: String = "",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_005"
            }

            data class InvalidStateTransition(
                override val message: String = "无效的状态转换",
                val fromStatus: String = "",
                val toStatus: String = "",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_006"
            }

            data class ProductionFailed(
                override val message: String = "生产失败",
                val recipeName: String = "",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_007"
            }

            data class DatabaseError(
                override val message: String = "数据库错误",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_008"
            }

            data class Unknown(
                override val message: String = "未知生产错误",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_099"
            }
        }

        sealed class Storage : Domain() {
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

            data class IntegrityError(
                override val message: String = "数据完整性错误",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_016"
            }

            data class VerificationFailed(
                override val message: String = "验证失败",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_017"
            }

            data class Expired(
                override val message: String = "数据已过期",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_018"
            }

            data class Tampered(
                override val message: String = "数据已被篡改",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_019"
            }

            data class Unknown(
                override val message: String = "未知存储错误",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_099"
            }
        }

        sealed class Validation : Domain() {
            data class InvalidInput(
                override val message: String = "输入无效",
                override val cause: Throwable? = null
            ) : Validation() {
                override val code = "VALID_001"
            }

            data class ConfigError(
                override val message: String = "配置错误",
                override val cause: Throwable? = null
            ) : Validation() {
                override val code = "VALID_002"
            }

            data class OutOfRange(
                override val message: String = "超出范围",
                override val cause: Throwable? = null
            ) : Validation() {
                override val code = "VALID_003"
            }

            data class EmptyValue(
                override val message: String = "值为空",
                override val cause: Throwable? = null
            ) : Validation() {
                override val code = "VALID_004"
            }
        }

        sealed class GameState : Domain() {
            data class InvalidState(
                override val message: String = "无效的游戏状态",
                override val cause: Throwable? = null
            ) : GameState() {
                override val code = "GAME_001"
            }

            data class NotFound(
                override val message: String = "未找到",
                override val cause: Throwable? = null
            ) : GameState() {
                override val code = "GAME_002"
            }

            data class PermissionDenied(
                override val message: String = "权限不足",
                override val cause: Throwable? = null
            ) : GameState() {
                override val code = "GAME_003"
            }
        }

        sealed class Network : Domain() {
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

        sealed class GameLoop : Domain() {
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
    }

    @Deprecated("Use AppError.Domain.Validation", ReplaceWith("AppError.Domain.Validation.InvalidInput(message, cause)"))
    data class Validation(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError() {
        override val code = "VALIDATION_ERROR"
    }

    @Deprecated("Use AppError.Domain.GameState.PermissionDenied", ReplaceWith("AppError.Domain.GameState.PermissionDenied(message, cause)"))
    data class Permission(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError() {
        override val code = "PERMISSION_ERROR"
    }

    @Deprecated("Use AppError.Domain.GameState.NotFound", ReplaceWith("AppError.Domain.GameState.NotFound(message, cause)"))
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
                is java.net.UnknownHostException -> Domain.Network.NoConnection(cause = e)
                is java.net.SocketTimeoutException -> Domain.Network.Timeout(cause = e)
                is java.io.IOException -> Domain.Network.NoConnection(e.message ?: "网络错误", e)
                is IllegalArgumentException -> Domain.Validation.InvalidInput(e.message ?: "参数错误", e)
                is IllegalStateException -> Domain.GameLoop.StateInconsistency(e.message ?: "状态错误", e)
                is NoSuchElementException -> Domain.GameState.NotFound(e.message ?: "未找到数据", e)
                is SecurityException -> Domain.GameState.PermissionDenied(e.message ?: "权限不足", e)
                else -> Unknown(e.message ?: "未知错误", e)
            }
        }
    }
}

fun GameError.toAppError(): AppError = when (this) {
    is GameError.Validation -> AppError.Domain.Validation.InvalidInput(message, cause)
    is GameError.GameState -> AppError.Domain.GameLoop.StateInconsistency(message, cause)
    is GameError.SaveLoad -> AppError.Domain.Storage.SaveFailed(message, cause)
    is GameError.Network -> AppError.Domain.Network.NoConnection(message, cause)
    is GameError.Permission -> AppError.Domain.GameState.PermissionDenied(message, cause)
    is GameError.NotFound -> AppError.Domain.GameState.NotFound(message, cause)
    is GameError.Unknown -> AppError.Unknown(message, cause)
}

fun StorageError.toAppError(message: String = "", cause: Throwable? = null): AppError.Domain.Storage = when (this) {
    StorageError.INVALID_SLOT -> AppError.Domain.Storage.SlotNotFound(message.ifEmpty { "无效的存档槽位" }, cause)
    StorageError.SLOT_EMPTY -> AppError.Domain.Storage.SlotNotFound(message.ifEmpty { "存档槽位为空" }, cause)
    StorageError.NOT_FOUND -> AppError.Domain.Storage.SlotNotFound(message.ifEmpty { "未找到数据" }, cause)
    StorageError.SLOT_CORRUPTED -> AppError.Domain.Storage.SlotCorrupted(message.ifEmpty { "存档数据已损坏" }, cause)
    StorageError.SAVE_FAILED -> AppError.Domain.Storage.SaveFailed(message.ifEmpty { "保存失败" }, cause)
    StorageError.LOAD_FAILED -> AppError.Domain.Storage.LoadFailed(message.ifEmpty { "加载失败" }, cause)
    StorageError.DELETE_FAILED -> AppError.Domain.Storage.DeleteFailed(message.ifEmpty { "删除失败" }, cause)
    StorageError.BACKUP_FAILED -> AppError.Domain.Storage.BackupFailed(message.ifEmpty { "备份失败" }, cause)
    StorageError.RESTORE_FAILED -> AppError.Domain.Storage.RestoreFailed(message.ifEmpty { "恢复失败" }, cause)
    StorageError.ENCRYPTION_ERROR -> AppError.Domain.Storage.EncryptionError(message.ifEmpty { "加密错误" }, cause)
    StorageError.DECRYPTION_ERROR -> AppError.Domain.Storage.DecryptionError(message.ifEmpty { "解密错误" }, cause)
    StorageError.IO_ERROR -> AppError.Domain.Storage.IoError(message.ifEmpty { "IO错误" }, cause)
    StorageError.DATABASE_ERROR -> AppError.Domain.Storage.DatabaseError(message.ifEmpty { "数据库错误" }, cause)
    StorageError.TRANSACTION_FAILED -> AppError.Domain.Storage.TransactionFailed(message.ifEmpty { "事务失败" }, cause)
    StorageError.TIMEOUT -> AppError.Domain.Storage.Timeout(message.ifEmpty { "操作超时" }, cause)
    StorageError.OUT_OF_MEMORY -> AppError.Domain.Storage.IoError(message.ifEmpty { "内存不足" }, cause)
    StorageError.WAL_ERROR -> AppError.Domain.Storage.IoError(message.ifEmpty { "WAL错误" }, cause)
    StorageError.CHECKSUM_MISMATCH -> AppError.Domain.Storage.ChecksumMismatch(message.ifEmpty { "校验和不匹配" }, cause)
    StorageError.KEY_DERIVATION_ERROR -> AppError.Domain.Storage.KeyDerivationError(message.ifEmpty { "密钥派生错误" }, cause)
    StorageError.VALIDATION_ERROR -> AppError.Domain.Storage.SlotCorrupted(message.ifEmpty { "数据校验失败" }, cause)
    StorageError.BATCH_OPERATION_FAILED -> AppError.Domain.Storage.TransactionFailed(message.ifEmpty { "批量操作失败" }, cause)
    StorageError.CONCURRENT_MODIFICATION -> AppError.Domain.Storage.TransactionFailed(message.ifEmpty { "并发修改冲突" }, cause)
    StorageError.UNKNOWN -> AppError.Domain.Storage.Unknown(message.ifEmpty { "未知存储错误" }, cause)
}

fun SaveError.toAppError(message: String = "", cause: Throwable? = null): AppError.Domain.Storage = when (this) {
    SaveError.INVALID_SLOT -> AppError.Domain.Storage.SlotNotFound(message.ifEmpty { "无效的存档槽位" }, cause)
    SaveError.SLOT_EMPTY -> AppError.Domain.Storage.SlotNotFound(message.ifEmpty { "存档槽位为空" }, cause)
    SaveError.NOT_FOUND -> AppError.Domain.Storage.SlotNotFound(message.ifEmpty { "未找到数据" }, cause)
    SaveError.SLOT_CORRUPTED -> AppError.Domain.Storage.SlotCorrupted(message.ifEmpty { "存档数据已损坏" }, cause)
    SaveError.SAVE_FAILED -> AppError.Domain.Storage.SaveFailed(message.ifEmpty { "保存失败" }, cause)
    SaveError.LOAD_FAILED -> AppError.Domain.Storage.LoadFailed(message.ifEmpty { "加载失败" }, cause)
    SaveError.DELETE_FAILED -> AppError.Domain.Storage.DeleteFailed(message.ifEmpty { "删除失败" }, cause)
    SaveError.BACKUP_FAILED -> AppError.Domain.Storage.BackupFailed(message.ifEmpty { "备份失败" }, cause)
    SaveError.RESTORE_FAILED -> AppError.Domain.Storage.RestoreFailed(message.ifEmpty { "恢复失败" }, cause)
    SaveError.ENCRYPTION_ERROR -> AppError.Domain.Storage.EncryptionError(message.ifEmpty { "加密错误" }, cause)
    SaveError.DECRYPTION_ERROR -> AppError.Domain.Storage.DecryptionError(message.ifEmpty { "解密错误" }, cause)
    SaveError.IO_ERROR -> AppError.Domain.Storage.IoError(message.ifEmpty { "IO错误" }, cause)
    SaveError.DATABASE_ERROR -> AppError.Domain.Storage.DatabaseError(message.ifEmpty { "数据库错误" }, cause)
    SaveError.TRANSACTION_FAILED -> AppError.Domain.Storage.TransactionFailed(message.ifEmpty { "事务失败" }, cause)
    SaveError.TIMEOUT -> AppError.Domain.Storage.Timeout(message.ifEmpty { "操作超时" }, cause)
    SaveError.OUT_OF_MEMORY -> AppError.Domain.Storage.IoError(message.ifEmpty { "内存不足" }, cause)
    SaveError.WAL_ERROR -> AppError.Domain.Storage.IoError(message.ifEmpty { "WAL错误" }, cause)
    SaveError.CHECKSUM_MISMATCH -> AppError.Domain.Storage.ChecksumMismatch(message.ifEmpty { "校验和不匹配" }, cause)
    SaveError.KEY_DERIVATION_ERROR -> AppError.Domain.Storage.KeyDerivationError(message.ifEmpty { "密钥派生错误" }, cause)
    SaveError.UNKNOWN -> AppError.Domain.Storage.Unknown(message.ifEmpty { "未知存储错误" }, cause)
}

fun com.xianxia.sect.core.model.production.ProductionError.toAppError(): AppError.Domain.Production = when (this) {
    is com.xianxia.sect.core.model.production.ProductionError.SlotBusy ->
        AppError.Domain.Production.SlotBusy(message, slotIndex)
    is com.xianxia.sect.core.model.production.ProductionError.InsufficientMaterials ->
        AppError.Domain.Production.InsufficientMaterials(message, missingMaterials)
    is com.xianxia.sect.core.model.production.ProductionError.InvalidSlot ->
        AppError.Domain.Production.InvalidSlot(message, slotIndex)
    is com.xianxia.sect.core.model.production.ProductionError.RecipeNotFound ->
        AppError.Domain.Production.RecipeNotFound(message, recipeId)
    is com.xianxia.sect.core.model.production.ProductionError.InvalidStateTransition ->
        AppError.Domain.Production.InvalidStateTransition(message, fromStatus, toStatus)
    is com.xianxia.sect.core.model.production.ProductionError.DiscipleNotAvailable ->
        AppError.Domain.Production.DiscipleNotAvailable(message, discipleId)
    is com.xianxia.sect.core.model.production.ProductionError.ProductionFailed ->
        AppError.Domain.Production.ProductionFailed(message, recipeName)
    is com.xianxia.sect.core.model.production.ProductionError.Unknown ->
        AppError.Domain.Production.Unknown(message)
}

fun GameLoopError.toAppError(): AppError = when (this) {
    is GameLoopError.TickTimeout -> AppError.Domain.GameLoop.TickTimeout(elapsedMs)
    is GameLoopError.StateInconsistency -> AppError.Domain.GameLoop.StateInconsistency(detail)
    is GameLoopError.EngineNotRunning -> AppError.Domain.GameLoop.EngineNotRunning(operation)
    is GameLoopError.ConcurrentTick -> AppError.Domain.GameLoop.StateInconsistency("并发Tick: $threadName")
    is GameLoopError.SaveConflict -> AppError.Domain.Storage.SaveFailed("存档冲突: 槽位$slot")
    is GameLoopError.ResourceExhaustion -> AppError.Domain.GameLoop.StateInconsistency("资源耗尽: $resource")
    is GameLoopError.Unknown -> AppError.Domain.GameLoop.Unknown(message, cause)
}

fun com.xianxia.sect.data.crypto.VerificationResult.toAppError(): AppError.Domain.Storage? = when (this) {
    is com.xianxia.sect.data.crypto.VerificationResult.Valid -> null
    is com.xianxia.sect.data.crypto.VerificationResult.Invalid ->
        AppError.Domain.Storage.VerificationFailed(reason, null)
    is com.xianxia.sect.data.crypto.VerificationResult.Expired ->
        AppError.Domain.Storage.Expired("签名已过期 (签名时间: $signedAt, 当前时间: $currentTime)", null)
    is com.xianxia.sect.data.crypto.VerificationResult.Tampered ->
        AppError.Domain.Storage.Tampered(reason, null)
}

fun com.xianxia.sect.core.util.ValidationResult.toAppError(): AppError.Domain.Validation? = when (this) {
    is com.xianxia.sect.core.util.ValidationResult.Success -> null
    is com.xianxia.sect.core.util.ValidationResult.SuccessLong -> null
    is com.xianxia.sect.core.util.ValidationResult.SuccessInt -> null
    is com.xianxia.sect.core.util.ValidationResult.Error -> {
        val trimmed = message.trim()
        when {
            trimmed.contains("不能为空") || trimmed.contains("为空") ->
                AppError.Domain.Validation.EmptyValue(message)
            trimmed.contains("超过") || trimmed.contains("不能小于") || trimmed.contains("至少") ->
                AppError.Domain.Validation.OutOfRange(message)
            else ->
                AppError.Domain.Validation.InvalidInput(message)
        }
    }
}

fun com.xianxia.sect.core.config.ConfigValidator.ValidationResult.toAppError(): AppError.Domain.Validation? = when (this) {
    is com.xianxia.sect.core.config.ConfigValidator.ValidationResult.Valid -> null
    is com.xianxia.sect.core.config.ConfigValidator.ValidationResult.Invalid ->
        AppError.Domain.Validation.ConfigError(errors.joinToString("; "))
}

fun com.xianxia.sect.core.transaction.ProductionTransactionError.toAppError(): AppError.Domain.Production = when (this) {
    is com.xianxia.sect.core.transaction.ProductionTransactionError.SlotNotFound ->
        AppError.Domain.Production.InvalidSlot("槽位不存在: ${buildingType.name}[$slotIndex]", slotIndex)
    is com.xianxia.sect.core.transaction.ProductionTransactionError.SlotBusy ->
        AppError.Domain.Production.SlotBusy(message.ifEmpty { "槽位正在工作中" }, slotIndex)
    is com.xianxia.sect.core.transaction.ProductionTransactionError.InsufficientMaterials ->
        AppError.Domain.Production.InsufficientMaterials("材料不足", missing)
    is com.xianxia.sect.core.transaction.ProductionTransactionError.InvalidStateTransition ->
        AppError.Domain.Production.InvalidStateTransition(message.ifEmpty { "无效的状态转换" }, from.name, to.name)
    is com.xianxia.sect.core.transaction.ProductionTransactionError.ProductionNotReady ->
        AppError.Domain.Production.InvalidStateTransition("生产尚未完成，剩余时间: ${remainingTime}月")
    is com.xianxia.sect.core.transaction.ProductionTransactionError.DatabaseError ->
        AppError.Domain.Production.DatabaseError(message)
    is com.xianxia.sect.core.transaction.ProductionTransactionError.UnknownError ->
        AppError.Domain.Production.Unknown(message)
}
