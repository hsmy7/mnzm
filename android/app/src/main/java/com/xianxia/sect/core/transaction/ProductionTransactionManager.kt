package com.xianxia.sect.core.transaction

import android.util.Log
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionOutcome
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.SlotStateMachine
import com.xianxia.sect.core.repository.ProductionSlotRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class ProductionTransactionResult(
    val success: Boolean,
    val slot: ProductionSlot? = null,
    val outcome: ProductionOutcome? = null,
    val error: ProductionTransactionError? = null,
    val rollbackData: ProductionRollbackData? = null
)

data class ProductionRollbackData(
    val materialsToRestore: Map<String, Int> = emptyMap(),
    val previousSlotState: ProductionSlot? = null
)

sealed class ProductionTransactionError {
    data class SlotNotFound(val buildingType: BuildingType, val slotIndex: Int) : ProductionTransactionError()
    data class SlotBusy(val slotIndex: Int, val message: String = "") : ProductionTransactionError()
    data class InsufficientMaterials(val missing: Map<String, Int>) : ProductionTransactionError()
    data class InvalidStateTransition(
        val from: ProductionSlotStatus, 
        val to: ProductionSlotStatus,
        val message: String = ""
    ) : ProductionTransactionError()
    data class ProductionNotReady(val remainingTime: Int) : ProductionTransactionError()
    data class DatabaseError(val message: String) : ProductionTransactionError()
    data class UnknownError(val message: String) : ProductionTransactionError()
}

@Singleton
class ProductionTransactionManager @Inject constructor(
    private val repository: ProductionSlotRepository
) {
    companion object {
        private const val TAG = "ProductionTxManager"
    }
    
    suspend fun executeStartProduction(
        buildingType: BuildingType,
        slotIndex: Int,
        recipeId: String,
        recipeName: String,
        duration: Int,
        currentYear: Int,
        currentMonth: Int,
        discipleId: String?,
        discipleName: String,
        successRate: Double,
        materials: Map<String, Int>,
        availableMaterials: Map<String, Int>,
        outputItemId: String?,
        outputItemName: String,
        outputItemRarity: Int
    ): ProductionTransactionResult {
        Log.d(TAG, "Starting production: ${buildingType.name}[$slotIndex] recipe=$recipeId")
        
        val slot = repository.getSlotByIndex(buildingType, slotIndex)
        if (slot == null) {
            Log.w(TAG, "Slot not found: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.SlotNotFound(buildingType, slotIndex)
            )
        }
        
        if (slot.status == ProductionSlotStatus.WORKING) {
            Log.w(TAG, "Slot busy: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.SlotBusy(slotIndex, "Slot is already working")
            )
        }
        
        val missingMaterials = mutableMapOf<String, Int>()
        materials.forEach { (materialId, required) ->
            val available = availableMaterials[materialId] ?: 0
            if (available < required) {
                missingMaterials[materialId] = required - available
            }
        }
        
        if (missingMaterials.isNotEmpty()) {
            Log.w(TAG, "Insufficient materials: $missingMaterials")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.InsufficientMaterials(missingMaterials)
            )
        }
        
        val previousState = slot
        
        val result = repository.updateSlotAtomic(buildingType, slotIndex) { currentSlot ->
            SlotStateMachine.startProduction(
                slot = currentSlot,
                recipeId = recipeId,
                recipeName = recipeName,
                duration = duration,
                currentYear = currentYear,
                currentMonth = currentMonth,
                discipleId = discipleId,
                discipleName = discipleName,
                successRate = successRate,
                materials = materials,
                outputItemId = outputItemId,
                outputItemName = outputItemName,
                outputItemRarity = outputItemRarity
            ).getOrThrow()
        }
        
        return if (result.isSuccess) {
            Log.d(TAG, "Production started successfully: ${buildingType.name}[$slotIndex]")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                rollbackData = ProductionRollbackData(
                    materialsToRestore = materials,
                    previousSlotState = previousState
                )
            )
        } else {
            val exception = result.exceptionOrNull()
            Log.e(TAG, "Failed to start production: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.InvalidStateTransition(
                    previousState.status,
                    ProductionSlotStatus.WORKING,
                    exception?.message ?: "Unknown error"
                )
            )
        }
    }
    
    suspend fun executeStartProductionByBuildingId(
        buildingId: String,
        slotIndex: Int,
        recipeId: String,
        recipeName: String,
        duration: Int,
        currentYear: Int,
        currentMonth: Int,
        discipleId: String?,
        discipleName: String,
        successRate: Double,
        materials: Map<String, Int>,
        availableMaterials: Map<String, Int>,
        outputItemId: String?,
        outputItemName: String,
        outputItemRarity: Int
    ): ProductionTransactionResult {
        Log.d(TAG, "Starting production by buildingId: $buildingId[$slotIndex] recipe=$recipeId")
        
        val slot = repository.getSlotByBuildingId(buildingId, slotIndex)
        if (slot == null) {
            Log.w(TAG, "Slot not found: $buildingId[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.SlotNotFound(BuildingType.ALCHEMY, slotIndex)
            )
        }
        
        if (slot.status == ProductionSlotStatus.WORKING) {
            Log.w(TAG, "Slot busy: $buildingId[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.SlotBusy(slotIndex, "Slot is already working")
            )
        }
        
        val missingMaterials = mutableMapOf<String, Int>()
        materials.forEach { (materialId, required) ->
            val available = availableMaterials[materialId] ?: 0
            if (available < required) {
                missingMaterials[materialId] = required - available
            }
        }
        
        if (missingMaterials.isNotEmpty()) {
            Log.w(TAG, "Insufficient materials: $missingMaterials")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.InsufficientMaterials(missingMaterials)
            )
        }
        
        val previousState = slot
        
        val result = repository.updateSlotByBuildingId(buildingId, slotIndex) { currentSlot ->
            SlotStateMachine.startProduction(
                slot = currentSlot,
                recipeId = recipeId,
                recipeName = recipeName,
                duration = duration,
                currentYear = currentYear,
                currentMonth = currentMonth,
                discipleId = discipleId,
                discipleName = discipleName,
                successRate = successRate,
                materials = materials,
                outputItemId = outputItemId,
                outputItemName = outputItemName,
                outputItemRarity = outputItemRarity
            ).getOrThrow()
        }
        
        return if (result.isSuccess) {
            Log.d(TAG, "Production started successfully: $buildingId[$slotIndex]")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                rollbackData = ProductionRollbackData(
                    materialsToRestore = materials,
                    previousSlotState = previousState
                )
            )
        } else {
            val exception = result.exceptionOrNull()
            Log.e(TAG, "Failed to start production: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.InvalidStateTransition(
                    previousState.status,
                    ProductionSlotStatus.WORKING,
                    exception?.message ?: "Unknown error"
                )
            )
        }
    }
    
    suspend fun executeCompleteProduction(
        buildingType: BuildingType,
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int
    ): ProductionTransactionResult {
        Log.d(TAG, "Completing production: ${buildingType.name}[$slotIndex]")
        
        val slot = repository.getSlotByIndex(buildingType, slotIndex)
        if (slot == null) {
            Log.w(TAG, "Slot not found: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.SlotNotFound(buildingType, slotIndex)
            )
        }
        
        if (slot.status != ProductionSlotStatus.WORKING) {
            Log.w(TAG, "Invalid state: ${slot.status}")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.InvalidStateTransition(
                    slot.status, 
                    ProductionSlotStatus.COMPLETED,
                    "Slot is not in WORKING state"
                )
            )
        }
        
        val remaining = slot.remainingTime(currentYear, currentMonth)
        if (remaining > 0) {
            Log.w(TAG, "Production not ready: $remaining months remaining")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.ProductionNotReady(remaining)
            )
        }
        
        val previousState = slot
        
        val result = repository.updateSlotAtomic(buildingType, slotIndex) { currentSlot ->
            SlotStateMachine.completeProduction(currentSlot).getOrThrow()
        }
        
        return if (result.isSuccess) {
            val outcome = determineOutcome(slot)
            Log.d(TAG, "Production completed: ${buildingType.name}[$slotIndex] success=${outcome is ProductionOutcome.Success}")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                outcome = outcome,
                rollbackData = ProductionRollbackData(previousSlotState = previousState)
            )
        } else {
            val exception = result.exceptionOrNull()
            Log.e(TAG, "Failed to complete production: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.InvalidStateTransition(
                    previousState.status,
                    ProductionSlotStatus.COMPLETED,
                    exception?.message ?: "Unknown error"
                )
            )
        }
    }
    
    suspend fun executeCompleteProductionByBuildingId(
        buildingId: String,
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int
    ): ProductionTransactionResult {
        Log.d(TAG, "Completing production by buildingId: $buildingId[$slotIndex]")
        
        val slot = repository.getSlotByBuildingId(buildingId, slotIndex)
        if (slot == null) {
            Log.w(TAG, "Slot not found: $buildingId[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.SlotNotFound(BuildingType.ALCHEMY, slotIndex)
            )
        }
        
        if (slot.status != ProductionSlotStatus.WORKING) {
            Log.w(TAG, "Invalid state: ${slot.status}")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.InvalidStateTransition(
                    slot.status, 
                    ProductionSlotStatus.COMPLETED,
                    "Slot is not in WORKING state"
                )
            )
        }
        
        val remaining = slot.remainingTime(currentYear, currentMonth)
        if (remaining > 0) {
            Log.w(TAG, "Production not ready: $remaining months remaining")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.ProductionNotReady(remaining)
            )
        }
        
        val previousState = slot
        
        val result = repository.updateSlotByBuildingId(buildingId, slotIndex) { currentSlot ->
            SlotStateMachine.completeProduction(currentSlot).getOrThrow()
        }
        
        return if (result.isSuccess) {
            val outcome = determineOutcome(slot)
            Log.d(TAG, "Production completed: $buildingId[$slotIndex] success=${outcome is ProductionOutcome.Success}")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                outcome = outcome,
                rollbackData = ProductionRollbackData(previousSlotState = previousState)
            )
        } else {
            val exception = result.exceptionOrNull()
            Log.e(TAG, "Failed to complete production: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.InvalidStateTransition(
                    previousState.status,
                    ProductionSlotStatus.COMPLETED,
                    exception?.message ?: "Unknown error"
                )
            )
        }
    }
    
    suspend fun executeResetSlot(
        buildingType: BuildingType,
        slotIndex: Int
    ): ProductionTransactionResult {
        Log.d(TAG, "Resetting slot: ${buildingType.name}[$slotIndex]")
        
        val slot = repository.getSlotByIndex(buildingType, slotIndex)
        if (slot == null) {
            Log.w(TAG, "Slot not found: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.SlotNotFound(buildingType, slotIndex)
            )
        }
        
        val previousState = slot
        
        val result = repository.updateSlotAtomic(buildingType, slotIndex) { currentSlot ->
            SlotStateMachine.resetSlot(currentSlot).getOrThrow()
        }
        
        return if (result.isSuccess) {
            Log.d(TAG, "Slot reset: ${buildingType.name}[$slotIndex]")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                rollbackData = ProductionRollbackData(previousSlotState = previousState)
            )
        } else {
            val exception = result.exceptionOrNull()
            Log.e(TAG, "Failed to reset slot: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.InvalidStateTransition(
                    previousState.status,
                    ProductionSlotStatus.IDLE,
                    exception?.message ?: "Unknown error"
                )
            )
        }
    }
    
    suspend fun executeAssignDisciple(
        buildingType: BuildingType,
        slotIndex: Int,
        discipleId: String,
        discipleName: String
    ): ProductionTransactionResult {
        Log.d(TAG, "Assigning disciple: ${buildingType.name}[$slotIndex] disciple=$discipleId")
        
        val slot = repository.getSlotByIndex(buildingType, slotIndex)
        if (slot == null) {
            Log.w(TAG, "Slot not found: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.SlotNotFound(buildingType, slotIndex)
            )
        }
        
        val previousState = slot
        
        val result = repository.updateSlotAtomic(buildingType, slotIndex) { currentSlot ->
            currentSlot.copy(
                assignedDiscipleId = discipleId,
                assignedDiscipleName = discipleName
            )
        }
        
        return if (result.isSuccess) {
            Log.d(TAG, "Disciple assigned: ${buildingType.name}[$slotIndex]")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                rollbackData = ProductionRollbackData(previousSlotState = previousState)
            )
        } else {
            val exception = result.exceptionOrNull()
            Log.e(TAG, "Failed to assign disciple: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.UnknownError(exception?.message ?: "Unknown error")
            )
        }
    }
    
    suspend fun executeRemoveDisciple(
        buildingType: BuildingType,
        slotIndex: Int
    ): ProductionTransactionResult {
        Log.d(TAG, "Removing disciple: ${buildingType.name}[$slotIndex]")
        
        val slot = repository.getSlotByIndex(buildingType, slotIndex)
        if (slot == null) {
            Log.w(TAG, "Slot not found: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.SlotNotFound(buildingType, slotIndex)
            )
        }
        
        val previousState = slot
        
        val result = repository.updateSlotAtomic(buildingType, slotIndex) { currentSlot ->
            currentSlot.copy(
                assignedDiscipleId = null,
                assignedDiscipleName = ""
            )
        }
        
        return if (result.isSuccess) {
            Log.d(TAG, "Disciple removed: ${buildingType.name}[$slotIndex]")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                rollbackData = ProductionRollbackData(previousSlotState = previousState)
            )
        } else {
            val exception = result.exceptionOrNull()
            Log.e(TAG, "Failed to remove disciple: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = ProductionTransactionError.UnknownError(exception?.message ?: "Unknown error")
            )
        }
    }
    
    suspend fun rollback(rollbackData: ProductionRollbackData): Result<ProductionSlot?> {
        Log.d(TAG, "Rolling back transaction")
        return rollbackData.previousSlotState?.let { previousSlot ->
            repository.updateSlot(previousSlot.buildingType, previousSlot.slotIndex) {
                previousSlot
            }
        } ?: Result.success(null)
    }
    
    private fun determineOutcome(slot: ProductionSlot): ProductionOutcome {
        val success = Random.nextDouble() <= slot.successRate
        return if (success) {
            ProductionOutcome.Success(
                outputItemId = slot.outputItemId ?: "",
                outputItemName = slot.outputItemName,
                outputItemRarity = slot.outputItemRarity,
                quantity = 1
            )
        } else {
            ProductionOutcome.Failure(
                reason = "Production failed",
                materialsLost = slot.requiredMaterials
            )
        }
    }
}
