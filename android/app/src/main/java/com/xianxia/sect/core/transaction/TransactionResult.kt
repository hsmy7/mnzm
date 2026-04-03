package com.xianxia.sect.core.transaction

sealed class TransactionResult<out T> {
    data class Success<T>(val data: T, val snapshot: TransactionSnapshot) : TransactionResult<T>()
    data class Failed<T>(val reason: String, val code: ErrorCode, val rollbackData: RollbackData? = null) : TransactionResult<T>()
    data class PartialSuccess<T>(val data: T, val warnings: List<String>) : TransactionResult<T>()
    
    enum class ErrorCode {
        ITEM_NOT_FOUND,
        INSUFFICIENT_QUANTITY,
        TARGET_FULL,
        LOCK_TIMEOUT,
        CONCURRENT_MODIFICATION,
        INTERNAL_ERROR
    }
    
    inline fun <R> map(transform: (T) -> R): TransactionResult<R> = when (this) {
        is Success -> Success(transform(data), snapshot)
        is Failed -> Failed(reason, code, rollbackData)
        is PartialSuccess -> PartialSuccess(transform(data), warnings)
    }
    
    inline fun onSuccess(action: (T) -> Unit): TransactionResult<T> {
        if (this is Success) action(data)
        return this
    }
    
    inline fun onFailure(action: (String, ErrorCode) -> Unit): TransactionResult<T> {
        if (this is Failed) action(reason, code)
        return this
    }
    
    inline fun onPartialSuccess(action: (T, List<String>) -> Unit): TransactionResult<T> {
        if (this is PartialSuccess) action(data, warnings)
        return this
    }
    
    val isSuccess: Boolean get() = this is Success
    val isFailed: Boolean get() = this is Failed
    val isPartialSuccess: Boolean get() = this is PartialSuccess
}

data class TransactionSnapshot(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val operations: List<OperationRecord> = emptyList(),
    val state: TransactionState = TransactionState.PENDING
) {
    val hasOperations: Boolean get() = operations.isNotEmpty()
    val operationCount: Int get() = operations.size
}

data class OperationRecord(
    val operationType: OperationType,
    val itemType: String,
    val itemId: String,
    val quantity: Int,
    val previousState: ItemState?,
    val timestamp: Long = System.currentTimeMillis()
)

enum class OperationType { ADD, REMOVE, UPDATE, TRANSFER }

enum class TransactionState { 
    PENDING, 
    COMMITTED, 
    ROLLED_BACK, 
    FAILED 
}

data class ItemState(
    val exists: Boolean,
    val quantity: Int,
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        val NOT_EXISTS = ItemState(false, 0)
        fun exists(quantity: Int, metadata: Map<String, Any> = emptyMap()) = ItemState(true, quantity, metadata)
    }
}

data class RollbackData(
    val operations: List<OperationRecord>,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

class TransactionException(
    message: String, 
    val code: TransactionResult.ErrorCode
) : Exception(message)
