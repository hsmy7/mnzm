package com.xianxia.sect.data.result

sealed class StorageResult<out T> {
    data class Success<T>(val data: T) : StorageResult<T>()
    data class Failure(val error: StorageError) : StorageResult<Nothing>()
    
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
    
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw StorageException(error)
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
    
    inline fun onFailure(action: (StorageError) -> Unit): StorageResult<T> {
        if (this is Failure) action(error)
        return this
    }
    
    companion object {
        fun <T> success(data: T): StorageResult<T> = Success(data)
        fun failure(error: StorageError): StorageResult<Nothing> = Failure(error)
    }
}

sealed class StorageError {
    abstract val message: String
    
    object InvalidSlot : StorageError() {
        override val message: String = "Invalid save slot"
    }
    object SlotEmpty : StorageError() {
        override val message: String = "Save slot is empty"
    }
    data class CorruptedData(val reason: String) : StorageError() {
        override val message: String get() = "Data corrupted: $reason"
    }
    data class IOError(val exception: Exception) : StorageError() {
        override val message: String get() = "IO error: ${exception.message}"
    }
    data class EncryptionError(val reason: String) : StorageError() {
        override val message: String get() = "Encryption error: $reason"
    }
    data class DecryptionError(val reason: String) : StorageError() {
        override val message: String get() = "Decryption error: $reason"
    }
    data class ValidationFailed(val reason: String) : StorageError() {
        override val message: String get() = "Validation failed: $reason"
    }
    data class TransactionFailed(val reason: String) : StorageError() {
        override val message: String get() = "Transaction failed: $reason"
    }
    data class DatabaseError(val exception: Exception) : StorageError() {
        override val message: String get() = "Database error: ${exception.message}"
    }
    data class CacheError(val reason: String) : StorageError() {
        override val message: String get() = "Cache error: $reason"
    }
    data class WALRecoveryFailed(val reason: String) : StorageError() {
        override val message: String get() = "WAL recovery failed: $reason"
    }
    data class Unknown(override val message: String) : StorageError()
}

class StorageException(val error: StorageError) : Exception(error.message) {
    constructor(message: String) : this(StorageError.Unknown(message))
}

data class SaveResult(
    val success: Boolean,
    val slot: Int,
    val bytesWritten: Long = 0,
    val elapsedMs: Long = 0,
    val isIncremental: Boolean = false,
    val error: StorageError? = null
) {
    companion object {
        fun success(slot: Int, bytesWritten: Long, elapsedMs: Long, isIncremental: Boolean = false) = 
            SaveResult(true, slot, bytesWritten, elapsedMs, isIncremental)
        
        fun failure(slot: Int, error: StorageError) = 
            SaveResult(false, slot, error = error)
    }
}

data class LoadResult(
    val success: Boolean,
    val slot: Int,
    val elapsedMs: Long = 0,
    val appliedDeltas: Int = 0,
    val wasFromSnapshot: Boolean = false,
    val error: StorageError? = null
) {
    companion object {
        fun success(slot: Int, elapsedMs: Long, appliedDeltas: Int = 0, wasFromSnapshot: Boolean = false) = 
            LoadResult(true, slot, elapsedMs, appliedDeltas, wasFromSnapshot)
        
        fun failure(slot: Int, error: StorageError) = 
            LoadResult(false, slot, error = error)
    }
}

data class DeleteResult(
    val success: Boolean,
    val slot: Int,
    val error: StorageError? = null
) {
    companion object {
        fun success(slot: Int) = DeleteResult(true, slot)
        fun failure(slot: Int, error: StorageError) = DeleteResult(false, slot, error)
    }
}

data class ValidationResult(
    val isValid: Boolean,
    val databaseValid: Boolean = false,
    val cacheValid: Boolean = false,
    val partitionsValid: Boolean = false,
    val errors: List<String> = emptyList()
)

fun StorageError.toGameError(): com.xianxia.sect.core.util.GameError {
    return when (this) {
        is StorageError.InvalidSlot -> com.xianxia.sect.core.util.GameError.Validation(message)
        is StorageError.SlotEmpty -> com.xianxia.sect.core.util.GameError.NotFound(message)
        is StorageError.CorruptedData -> com.xianxia.sect.core.util.GameError.SaveLoad(message)
        is StorageError.IOError -> com.xianxia.sect.core.util.GameError.SaveLoad(message, exception)
        is StorageError.EncryptionError -> com.xianxia.sect.core.util.GameError.SaveLoad(message)
        is StorageError.DecryptionError -> com.xianxia.sect.core.util.GameError.SaveLoad(message)
        is StorageError.ValidationFailed -> com.xianxia.sect.core.util.GameError.Validation(message)
        is StorageError.TransactionFailed -> com.xianxia.sect.core.util.GameError.SaveLoad(message)
        is StorageError.DatabaseError -> com.xianxia.sect.core.util.GameError.SaveLoad(message, exception)
        is StorageError.CacheError -> com.xianxia.sect.core.util.GameError.GameState(message)
        is StorageError.WALRecoveryFailed -> com.xianxia.sect.core.util.GameError.SaveLoad(message)
        is StorageError.Unknown -> com.xianxia.sect.core.util.GameError.Unknown(null)
    }
}

fun <T> StorageResult<T>.toGameResult(): com.xianxia.sect.core.util.GameResult<T> {
    return when (this) {
        is StorageResult.Success -> com.xianxia.sect.core.util.GameResult.Success(data)
        is StorageResult.Failure -> com.xianxia.sect.core.util.GameResult.Failure(error.toGameError())
    }
}
