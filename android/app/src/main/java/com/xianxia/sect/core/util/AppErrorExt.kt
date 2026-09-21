package com.xianxia.sect.core.util

import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.unified.SaveError

fun AppError.toUiError(): UiError = UiError(
    code = code,
    userMessage = message,
    appError = this
)

/** 存储错误映射：缺省文案 + 领域错误工厂；新枚举值必须显式登记 */
private val STORAGE_ERROR_MAPPINGS: Map<StorageError, Pair<String, (String, Throwable?) -> AppError.Domain.Storage>> =
    mapOf(
        StorageError.INVALID_SLOT to ("无效的存档槽位" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SlotNotFound(m, c) }),
        StorageError.SLOT_EMPTY to ("存档槽位为空" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SlotNotFound(m, c) }),
        StorageError.NOT_FOUND to ("未找到数据" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SlotNotFound(m, c) }),
        StorageError.SLOT_CORRUPTED to ("存档数据已损坏" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SlotCorrupted(m, c) }),
        StorageError.SAVE_FAILED to ("保存失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SaveFailed(m, c) }),
        StorageError.LOAD_FAILED to ("加载失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.LoadFailed(m, c) }),
        StorageError.DELETE_FAILED to ("删除失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.DeleteFailed(m, c) }),
        StorageError.BACKUP_FAILED to ("备份失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.BackupFailed(m, c) }),
        StorageError.RESTORE_FAILED to ("恢复失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.RestoreFailed(m, c) }),
        StorageError.ENCRYPTION_ERROR to ("加密错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.EncryptionError(m, c) }),
        StorageError.DECRYPTION_ERROR to ("解密错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.DecryptionError(m, c) }),
        StorageError.IO_ERROR to ("IO错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.IoError(m, c) }),
        StorageError.DATABASE_ERROR to ("数据库错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.DatabaseError(m, c) }),
        StorageError.TRANSACTION_FAILED to ("事务失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.TransactionFailed(m, c) }),
        StorageError.TIMEOUT to ("操作超时" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.Timeout(m, c) }),
        StorageError.OUT_OF_MEMORY to ("内存不足" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.IoError(m, c) }),
        StorageError.WAL_ERROR to ("WAL错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.IoError(m, c) }),
        StorageError.CHECKSUM_MISMATCH to ("校验和不匹配" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.ChecksumMismatch(m, c) }),
        StorageError.KEY_DERIVATION_ERROR to ("密钥派生错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.KeyDerivationError(m, c) }),
        StorageError.VALIDATION_ERROR to ("数据校验失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SlotCorrupted(m, c) }),
        StorageError.BATCH_OPERATION_FAILED to ("批量操作失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.TransactionFailed(m, c) }),
        StorageError.CONCURRENT_MODIFICATION to ("并发修改冲突" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.TransactionFailed(m, c) }),
        StorageError.UNKNOWN to ("未知存储错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.Unknown(m, c) })
    )

fun StorageError.toAppError(message: String = "", cause: Throwable? = null): AppError.Domain.Storage {
    val (default, factory) = STORAGE_ERROR_MAPPINGS.getValue(this)
    return factory(message.ifEmpty { default }, cause)
}

/** 统一保存错误映射：缺省文案 + 领域错误工厂；新枚举值必须显式登记 */
private val SAVE_ERROR_MAPPINGS: Map<SaveError, Pair<String, (String, Throwable?) -> AppError.Domain.Storage>> =
    mapOf(
        SaveError.INVALID_SLOT to ("无效的存档槽位" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SlotNotFound(m, c) }),
        SaveError.SLOT_EMPTY to ("存档槽位为空" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SlotNotFound(m, c) }),
        SaveError.NOT_FOUND to ("未找到数据" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SlotNotFound(m, c) }),
        SaveError.SLOT_CORRUPTED to ("存档数据已损坏" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SlotCorrupted(m, c) }),
        SaveError.SAVE_FAILED to ("保存失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.SaveFailed(m, c) }),
        SaveError.LOAD_FAILED to ("加载失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.LoadFailed(m, c) }),
        SaveError.DELETE_FAILED to ("删除失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.DeleteFailed(m, c) }),
        SaveError.BACKUP_FAILED to ("备份失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.BackupFailed(m, c) }),
        SaveError.RESTORE_FAILED to ("恢复失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.RestoreFailed(m, c) }),
        SaveError.ENCRYPTION_ERROR to ("加密错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.EncryptionError(m, c) }),
        SaveError.DECRYPTION_ERROR to ("解密错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.DecryptionError(m, c) }),
        SaveError.IO_ERROR to ("IO错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.IoError(m, c) }),
        SaveError.DATABASE_ERROR to ("数据库错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.DatabaseError(m, c) }),
        SaveError.TRANSACTION_FAILED to ("事务失败" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.TransactionFailed(m, c) }),
        SaveError.TIMEOUT to ("操作超时" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.Timeout(m, c) }),
        SaveError.OUT_OF_MEMORY to ("内存不足" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.IoError(m, c) }),
        SaveError.WAL_ERROR to ("WAL错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.IoError(m, c) }),
        SaveError.CHECKSUM_MISMATCH to ("校验和不匹配" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.ChecksumMismatch(m, c) }),
        SaveError.KEY_DERIVATION_ERROR to ("密钥派生错误" to { m: String, c: Throwable? ->
            AppError.Domain.Storage.KeyDerivationError(m, c) })
    )

fun SaveError.toAppError(message: String = "", cause: Throwable? = null): AppError.Domain.Storage {
    val (default, factory) = SAVE_ERROR_MAPPINGS.getValue(this)
    return factory(message.ifEmpty { default }, cause)
}
