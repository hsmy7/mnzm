package com.xianxia.sect.data.result

sealed class StorageResult<out T> {
    data class Success<T>(val data: T) : StorageResult<T>()
    data class Failure(val error: StorageError, val message: String = "", val cause: Throwable? = null) : StorageResult<Nothing>()
    
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    
    fun getOrNull(): T? = (this as? Success)?.data
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw StorageException(error, message, cause)
    }
    
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Failure -> default
    }
    
    inline fun <R> map(transform: (T) -> R): StorageResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }
    
    inline fun <R> flatMap(transform: (T) -> StorageResult<R>): StorageResult<R> = when (this) {
        is Success -> transform(data)
        is Failure -> this
    }
    
    inline fun onSuccess(action: (T) -> Unit): StorageResult<T> {
        if (this is Success) action(data)
        return this
    }
    
    inline fun onFailure(action: (StorageError, String) -> Unit): StorageResult<T> {
        if (this is Failure) action(error, message)
        return this
    }
    
    companion object {
        fun <T> success(data: T): StorageResult<T> = Success(data)
        fun failure(error: StorageError, message: String = "", cause: Throwable? = null): StorageResult<Nothing> = 
            Failure(error, message, cause)
    }
}

enum class StorageError {
    INVALID_SLOT,
    SLOT_EMPTY,
    NOT_FOUND,
    SLOT_CORRUPTED,
    SAVE_FAILED,
    LOAD_FAILED,
    DELETE_FAILED,
    BACKUP_FAILED,
    RESTORE_FAILED,
    ENCRYPTION_ERROR,
    DECRYPTION_ERROR,
    IO_ERROR,
    DATABASE_ERROR,
    TRANSACTION_FAILED,
    TIMEOUT,
    OUT_OF_MEMORY,
    WAL_ERROR,
    CHECKSUM_MISMATCH,
    KEY_DERIVATION_ERROR,
    VALIDATION_ERROR,
    BATCH_OPERATION_FAILED,
    CONCURRENT_MODIFICATION,
    UNKNOWN;
    
    fun isRecoverable(): Boolean = when (this) {
        SLOT_CORRUPTED, IO_ERROR, TIMEOUT, OUT_OF_MEMORY, WAL_ERROR -> true
        else -> false
    }
    
    fun requiresUserAction(): Boolean = when (this) {
        ENCRYPTION_ERROR, DECRYPTION_ERROR, KEY_DERIVATION_ERROR -> true
        else -> false
    }
    
    fun getSeverity(): ErrorSeverity = when (this) {
        INVALID_SLOT, SLOT_EMPTY, NOT_FOUND -> ErrorSeverity.INFO
        SAVE_FAILED, LOAD_FAILED, DELETE_FAILED, BACKUP_FAILED, RESTORE_FAILED -> ErrorSeverity.WARNING
        SLOT_CORRUPTED, ENCRYPTION_ERROR, DECRYPTION_ERROR, TRANSACTION_FAILED -> ErrorSeverity.ERROR
        IO_ERROR, DATABASE_ERROR, TIMEOUT, OUT_OF_MEMORY, WAL_ERROR -> ErrorSeverity.CRITICAL
        else -> ErrorSeverity.ERROR
    }
}

enum class ErrorSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}

class StorageException(
    val error: StorageError,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    override fun toString(): String = "StorageException(error=$error, message=$message, cause=${cause?.message})"
}

data class StorageOperationStats(
    val bytesProcessed: Long = 0,
    val timeMs: Long = 0,
    val itemsProcessed: Int = 0,
    val wasEncrypted: Boolean = false,
    val wasCompressed: Boolean = false,
    val checksum: String = ""
)

data class BatchOperationResult(
    val totalCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val errors: Map<Int, StorageError>
) {
    val allSuccess: Boolean get() = failureCount == 0
    val successRate: Float get() = if (totalCount == 0) 0f else successCount.toFloat() / totalCount
}
