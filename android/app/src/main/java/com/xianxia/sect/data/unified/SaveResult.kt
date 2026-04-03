package com.xianxia.sect.data.unified

sealed class SaveResult<out T> {
    data class Success<T>(val data: T) : SaveResult<T>()
    data class Failure(val error: SaveError, val message: String = "", val cause: Throwable? = null) : SaveResult<Nothing>()
    
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    
    fun getOrNull(): T? = (this as? Success)?.data
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw SaveException(error, message, cause)
    }
    
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Failure -> default
    }
    
    inline fun <R> map(transform: (T) -> R): SaveResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> this
    }
    
    inline fun <R> flatMap(transform: (T) -> SaveResult<R>): SaveResult<R> = when (this) {
        is Success -> transform(data)
        is Failure -> this
    }
    
    inline fun onSuccess(action: (T) -> Unit): SaveResult<T> {
        if (this is Success) action(data)
        return this
    }
    
    inline fun onFailure(action: (SaveError, String) -> Unit): SaveResult<T> {
        if (this is Failure) action(error, message)
        return this
    }
    
    companion object {
        fun <T> success(data: T): SaveResult<T> = Success(data)
        fun failure(error: SaveError, message: String = "", cause: Throwable? = null): SaveResult<Nothing> = 
            Failure(error, message, cause)
    }
}

enum class SaveError {
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
    UNKNOWN;
    
    fun isRecoverable(): Boolean = when (this) {
        SLOT_CORRUPTED, IO_ERROR, TIMEOUT, OUT_OF_MEMORY, WAL_ERROR -> true
        else -> false
    }
    
    fun requiresUserAction(): Boolean = when (this) {
        ENCRYPTION_ERROR, DECRYPTION_ERROR, KEY_DERIVATION_ERROR -> true
        else -> false
    }
}

class SaveException(
    val error: SaveError,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    override fun toString(): String = "SaveException(error=$error, message=$message, cause=${cause?.message})"
}

data class SaveOperationStats(
    val bytesWritten: Long = 0,
    val timeMs: Long = 0,
    val compressionRatio: Double = 1.0,
    val wasEncrypted: Boolean = false,
    val wasIncremental: Boolean = false,
    val checksum: String = ""
)

data class LoadOperationStats(
    val bytesRead: Long = 0,
    val timeMs: Long = 0,
    val wasDecrypted: Boolean = false,
    val checksumValid: Boolean = true,
    val appliedDeltas: Int = 0
)
