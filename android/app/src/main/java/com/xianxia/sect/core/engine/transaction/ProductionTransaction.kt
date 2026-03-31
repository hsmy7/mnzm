package com.xianxia.sect.core.engine.transaction

import com.xianxia.sect.core.model.production.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MaterialSnapshot(
    val materials: Map<String, Int>
)

data class SlotSnapshot(
    val slot: ProductionSlot
)

class ProductionTransaction(
    private val initialMaterials: Map<String, Int>,
    private val initialSlot: ProductionSlot
) {
    private var materialChanges: MutableMap<String, Int> = mutableMapOf()
    private var slotChanges: ProductionSlot? = null
    private var isCommitted: Boolean = false
    private var isRolledBack: Boolean = false
    
    val materials: Map<String, Int>
        get() {
            val result = initialMaterials.toMutableMap()
            materialChanges.forEach { (id, delta) ->
                val current = result[id] ?: 0
                val new = current + delta
                if (new <= 0) {
                    result.remove(id)
                } else {
                    result[id] = new
                }
            }
            return result
        }
    
    val slot: ProductionSlot
        get() = slotChanges ?: initialSlot
    
    fun consumeMaterials(materials: Map<String, Int>): Result<Unit> {
        if (isCommitted || isRolledBack) {
            return Result.failure(IllegalStateException("Transaction already finalized"))
        }
        
        val currentMaterials = this.materials
        val insufficient = materials.filter { (id, required) ->
            (currentMaterials[id] ?: 0) < required
        }
        
        if (insufficient.isNotEmpty()) {
            return Result.failure(IllegalArgumentException(
                "Insufficient materials: $insufficient"
            ))
        }
        
        materials.forEach { (id, amount) ->
            val currentDelta = materialChanges[id] ?: 0
            materialChanges[id] = currentDelta - amount
        }
        
        return Result.success(Unit)
    }
    
    fun updateSlot(newSlot: ProductionSlot): Result<Unit> {
        if (isCommitted || isRolledBack) {
            return Result.failure(IllegalStateException("Transaction already finalized"))
        }
        
        slotChanges = newSlot
        return Result.success(Unit)
    }
    
    fun commit(): Result<TransactionResult> {
        if (isCommitted) {
            return Result.failure(IllegalStateException("Transaction already committed"))
        }
        if (isRolledBack) {
            return Result.failure(IllegalStateException("Transaction already rolled back"))
        }
        
        isCommitted = true
        return Result.success(TransactionResult(
            materials = materials,
            slot = slot,
            materialChanges = materialChanges.toMap()
        ))
    }
    
    fun rollback() {
        if (isCommitted || isRolledBack) return
        isRolledBack = true
        materialChanges.clear()
        slotChanges = null
    }
}

data class TransactionResult(
    val materials: Map<String, Int>,
    val slot: ProductionSlot,
    val materialChanges: Map<String, Int>
)

class ProductionTransactionManager {
    private val mutex = Mutex()
    
    suspend fun <T> executeTransaction(
        initialMaterials: Map<String, Int>,
        initialSlot: ProductionSlot,
        block: suspend (ProductionTransaction) -> Result<T>
    ): Result<Pair<T, TransactionResult>> {
        return mutex.withLock {
            val transaction = ProductionTransaction(initialMaterials, initialSlot)
            
            val result = block(transaction)
            
            result.fold(
                onSuccess = { value ->
                    transaction.commit().fold(
                        onSuccess = { txResult ->
                            Result.success(value to txResult)
                        },
                        onFailure = { error ->
                            transaction.rollback()
                            Result.failure(error)
                        }
                    )
                },
                onFailure = { error ->
                    transaction.rollback()
                    Result.failure(error)
                }
            )
        }
    }
    
    suspend fun executeProductionStart(
        materials: Map<String, Int>,
        slot: ProductionSlot,
        requiredMaterials: Map<String, Int>,
        newSlot: ProductionSlot
    ): Result<TransactionResult> {
        return executeTransaction(materials, slot) { transaction ->
            transaction.consumeMaterials(requiredMaterials).mapCatching {
                transaction.updateSlot(newSlot)
            }
        }.map { it.second }
    }
}

object ProductionTransactionScope {
    private val manager = ProductionTransactionManager()
    
    suspend fun startProduction(
        currentMaterials: Map<String, Int>,
        currentSlot: ProductionSlot,
        requiredMaterials: Map<String, Int>,
        newSlot: ProductionSlot
    ): Result<TransactionResult> {
        return manager.executeProductionStart(
            materials = currentMaterials,
            slot = currentSlot,
            requiredMaterials = requiredMaterials,
            newSlot = newSlot
        )
    }
}
