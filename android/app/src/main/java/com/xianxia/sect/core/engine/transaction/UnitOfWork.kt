package com.xianxia.sect.core.engine.transaction

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class TransactionResult<T>(
    val success: Boolean,
    val data: T? = null,
    val error: TransactionError? = null,
    val rollbackData: RollbackData? = null
)

sealed class TransactionError {
    data class ValidationFailed(val message: String, val field: String? = null) : TransactionError()
    data class ResourceInsufficient(val resourceType: String, val required: Int, val available: Int) : TransactionError()
    data class StateConflict(val message: String, val currentState: String, val expectedState: String) : TransactionError()
    data class ConcurrencyConflict(val message: String) : TransactionError()
    data class SystemError(val message: String, val cause: Throwable? = null) : TransactionError()
}

data class RollbackData(
    val materials: Map<String, Int> = emptyMap(),
    val spiritStones: Long = 0,
    val slotId: String? = null,
    val previousStatus: String? = null
)

interface TransactionOperation<T> {
    suspend fun validate(): Boolean
    suspend fun execute(): T
    suspend fun rollback()
}

@Singleton
class UnitOfWork @Inject constructor() {
    private val mutex = Mutex()
    private val operations = mutableListOf<TransactionOperation<*>>()
    private val executedOperations = mutableListOf<TransactionOperation<*>>()
    private var isActive = false

    suspend fun <T> begin(operations: List<TransactionOperation<T>>): TransactionResult<T> {
        return mutex.withLock {
            if (isActive) {
                return TransactionResult(
                    success = false,
                    error = TransactionError.ConcurrencyConflict("Transaction already in progress")
                )
            }
            
            isActive = true
            this.operations.clear()
            this.executedOperations.clear()
            this.operations.addAll(operations)
            
            try {
                for (op in operations) {
                    if (!op.validate()) {
                        rollbackAll()
                        return TransactionResult(
                            success = false,
                            error = TransactionError.ValidationFailed("Validation failed for operation")
                        )
                    }
                }
                
                var lastResult: T? = null
                for (op in operations) {
                    @Suppress("UNCHECKED_CAST")
                    lastResult = (op as TransactionOperation<T>).execute()
                    executedOperations.add(op)
                }
                
                isActive = false
                TransactionResult(success = true, data = lastResult)
            } catch (e: Exception) {
                rollbackAll()
                TransactionResult(
                    success = false,
                    error = TransactionError.SystemError(e.message ?: "Unknown error", e)
                )
            }
        }
    }

    suspend fun <T> executeAtomic(block: suspend TransactionContext.() -> T): TransactionResult<T> {
        return mutex.withLock {
            val context = TransactionContext()
            try {
                val result = block(context)
                TransactionResult(success = true, data = result)
            } catch (e: TransactionValidationException) {
                TransactionResult(
                    success = false,
                    error = TransactionError.ValidationFailed(e.message ?: "Validation failed")
                )
            } catch (e: TransactionStateException) {
                TransactionResult(
                    success = false,
                    error = TransactionError.StateConflict(
                        e.message ?: "State conflict",
                        e.currentState ?: "unknown",
                        e.expectedState ?: "unknown"
                    )
                )
            } catch (e: Exception) {
                context.rollback()
                TransactionResult(
                    success = false,
                    error = TransactionError.SystemError(e.message ?: "Unknown error", e),
                    rollbackData = context.getRollbackData()
                )
            }
        }
    }

    private suspend fun rollbackAll() {
        executedOperations.reversed().forEach { op ->
            try {
                op.rollback()
            } catch (e: Exception) {
                Log.e("UnitOfWork", "Rollback operation failed", e)
            }
        }
        executedOperations.clear()
        operations.clear()
        isActive = false
    }
}

class TransactionContext {
    private val rollbackActions = mutableListOf<suspend () -> Unit>()
    private var rollbackData: RollbackData? = null

    fun addRollbackAction(action: suspend () -> Unit) {
        rollbackActions.add(action)
    }

    fun setRollbackData(data: RollbackData) {
        rollbackData = data
    }

    fun getRollbackData(): RollbackData? = rollbackData

    suspend fun rollback() {
        rollbackActions.reversed().forEach { action ->
            try {
                action()
            } catch (e: Exception) {
                // Continue with other rollback actions
            }
        }
    }
}

class TransactionValidationException(message: String) : Exception(message)
class TransactionStateException(
    message: String,
    val currentState: String? = null,
    val expectedState: String? = null
) : Exception(message)

class ProductionTransactionBuilder {
    private val operations = mutableListOf<TransactionOperation<*>>()
    
    fun <T> addOperation(operation: TransactionOperation<T>): ProductionTransactionBuilder {
        operations.add(operation)
        return this
    }
    
    fun build(): List<TransactionOperation<*>> = operations.toList()
}
