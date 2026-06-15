package com.xianxia.sect.core.util

import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.unified.SaveError

fun AppError.toUiError(): UiError = UiError(
    code = code,
    userMessage = message,
    appError = this
)

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

fun com.xianxia.sect.data.crypto.VerificationResult.toAppError(): AppError.Domain.Storage? = when (this) {
    is com.xianxia.sect.data.crypto.VerificationResult.Valid -> null
    is com.xianxia.sect.data.crypto.VerificationResult.Invalid ->
        AppError.Domain.Storage.VerificationFailed(reason, null)
    is com.xianxia.sect.data.crypto.VerificationResult.Expired ->
        AppError.Domain.Storage.Expired("签名已过期 (签名时间: $signedAt, 当前时间: $currentTime)", null)
    is com.xianxia.sect.data.crypto.VerificationResult.Tampered ->
        AppError.Domain.Storage.Tampered(reason, null)
}
