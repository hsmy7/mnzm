package com.xianxia.sect.core.transaction

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionOutcome
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.model.production.SlotStateMachine
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.util.AppError
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class ProductionTransactionResult(
    val success: Boolean,
    val slot: ProductionSlot? = null,
    val outcome: ProductionOutcome? = null,
    val error: AppError.Domain.Production? = null,
    val rollbackData: ProductionRollbackData? = null
)

data class ProductionRollbackData(
    val materialsToRestore: Map<String, Int> = emptyMap(),
    val previousSlotState: ProductionSlot? = null
)

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
        DomainLog.d(TAG, "Starting production: ${buildingType.name}[$slotIndex] recipe=$recipeId")

        val slot = repository.getSlotByIndex(buildingType, slotIndex)
        if (slot == null) {
            DomainLog.w(TAG, "Slot not found: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidSlot("槽位不存在: ${buildingType.name}[$slotIndex]", slotIndex)
            )
        }

        if (slot.status == ProductionSlotStatus.WORKING) {
            DomainLog.w(TAG, "Slot busy: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.SlotBusy("Slot is already working", slotIndex)
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
            DomainLog.w(TAG, "Insufficient materials: $missingMaterials")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InsufficientMaterials("材料不足", missingMaterials)
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
            ).getOrElse { e ->
                DomainLog.w(TAG, "startProduction state transition failed: ${e.message}")
                return@updateSlotAtomic currentSlot
            }
        }

        return if (result.isSuccess) {
            DomainLog.d(TAG, "Production started successfully: ${buildingType.name}[$slotIndex]")
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
            DomainLog.e(TAG, "Failed to start production: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidStateTransition(
                    exception?.message ?: "Unknown error",
                    previousState.status.name,
                    ProductionSlotStatus.WORKING.name
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
        DomainLog.d(TAG, "Starting production by buildingId: $buildingId[$slotIndex] recipe=$recipeId")

        var slot = repository.getSlotByBuildingId(buildingId, slotIndex)
        if (slot == null) {
            val buildingType = ProductionSlot.resolveBuildingType(buildingId)
            slot = ProductionSlot.createIdle(
                slotIndex = slotIndex,
                buildingType = buildingType,
                buildingId = buildingId
            )
            val addResult = repository.addSlot(slot)
            if (addResult.isFailure) {
                slot = repository.getSlotByBuildingId(buildingId, slotIndex)
                if (slot == null) {
                    DomainLog.e(TAG, "Failed to create slot for $buildingId[$slotIndex]: ${addResult.exceptionOrNull()?.message}")
                    return ProductionTransactionResult(
                        success = false,
                        error = AppError.Domain.Production.InvalidSlot("槽位不存在: ${buildingType.name}[$slotIndex]", slotIndex)
                    )
                }
            } else {
                DomainLog.d(TAG, "Created idle slot for $buildingId[$slotIndex]")
            }
        }

        if (slot.status == ProductionSlotStatus.WORKING) {
            DomainLog.w(TAG, "Slot busy: $buildingId[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.SlotBusy("Slot is already working", slotIndex)
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
            DomainLog.w(TAG, "Insufficient materials: $missingMaterials")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InsufficientMaterials("材料不足", missingMaterials)
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
            ).getOrElse { e ->
                DomainLog.w(TAG, "startProductionByBuildingId state transition failed: ${e.message}")
                return@updateSlotByBuildingId currentSlot
            }
        }

        return if (result.isSuccess) {
            DomainLog.d(TAG, "Production started successfully: $buildingId[$slotIndex]")
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
            DomainLog.e(TAG, "Failed to start production: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidStateTransition(
                    exception?.message ?: "Unknown error",
                    previousState.status.name,
                    ProductionSlotStatus.WORKING.name
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
        DomainLog.d(TAG, "Completing production: ${buildingType.name}[$slotIndex]")

        val slot = repository.getSlotByIndex(buildingType, slotIndex)
        if (slot == null) {
            DomainLog.w(TAG, "Slot not found: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidSlot("槽位不存在: ${buildingType.name}[$slotIndex]", slotIndex)
            )
        }

        if (slot.status != ProductionSlotStatus.WORKING) {
            DomainLog.w(TAG, "Invalid state: ${slot.status}")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidStateTransition(
                    "Slot is not in WORKING state",
                    slot.status.name,
                    ProductionSlotStatus.COMPLETED.name
                )
            )
        }

        val remaining = slot.remainingTime(currentYear, currentMonth)
        if (remaining > 0) {
            DomainLog.w(TAG, "Production not ready: $remaining months remaining")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidStateTransition("生产尚未完成，剩余时间: ${remaining}月")
            )
        }

        val previousState = slot

        val result = repository.updateSlotAtomic(buildingType, slotIndex) { currentSlot ->
            SlotStateMachine.completeProduction(currentSlot).getOrElse { e ->
                DomainLog.w(TAG, "completeProduction state transition failed: ${e.message}")
                return@updateSlotAtomic currentSlot
            }
        }

        return if (result.isSuccess) {
            val outcome = determineOutcome(slot)
            DomainLog.d(TAG, "Production completed: ${buildingType.name}[$slotIndex] success=${outcome is ProductionOutcome.Success}")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                outcome = outcome,
                rollbackData = ProductionRollbackData(previousSlotState = previousState)
            )
        } else {
            val exception = result.exceptionOrNull()
            DomainLog.e(TAG, "Failed to complete production: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidStateTransition(
                    exception?.message ?: "Unknown error",
                    previousState.status.name,
                    ProductionSlotStatus.COMPLETED.name
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
        DomainLog.d(TAG, "Completing production by buildingId: $buildingId[$slotIndex]")

        val slot = repository.getSlotByBuildingId(buildingId, slotIndex)
        if (slot == null) {
            DomainLog.w(TAG, "Slot not found: $buildingId[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidSlot("槽位不存在: ALCHEMY[$slotIndex]", slotIndex)
            )
        }

        if (slot.status != ProductionSlotStatus.WORKING) {
            DomainLog.w(TAG, "Invalid state: ${slot.status}")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidStateTransition(
                    "Slot is not in WORKING state",
                    slot.status.name,
                    ProductionSlotStatus.COMPLETED.name
                )
            )
        }

        val remaining = slot.remainingTime(currentYear, currentMonth)
        if (remaining > 0) {
            DomainLog.w(TAG, "Production not ready: $remaining months remaining")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidStateTransition("生产尚未完成，剩余时间: ${remaining}月")
            )
        }

        val previousState = slot

        val result = repository.updateSlotByBuildingId(buildingId, slotIndex) { currentSlot ->
            SlotStateMachine.completeProduction(currentSlot).getOrElse { e ->
                DomainLog.w(TAG, "completeProductionByBuildingId state transition failed: ${e.message}")
                return@updateSlotByBuildingId currentSlot
            }
        }

        return if (result.isSuccess) {
            val outcome = determineOutcome(slot)
            DomainLog.d(TAG, "Production completed: $buildingId[$slotIndex] success=${outcome is ProductionOutcome.Success}")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                outcome = outcome,
                rollbackData = ProductionRollbackData(previousSlotState = previousState)
            )
        } else {
            val exception = result.exceptionOrNull()
            DomainLog.e(TAG, "Failed to complete production: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidStateTransition(
                    exception?.message ?: "Unknown error",
                    previousState.status.name,
                    ProductionSlotStatus.COMPLETED.name
                )
            )
        }
    }

    suspend fun executeResetSlot(
        buildingType: BuildingType,
        slotIndex: Int
    ): ProductionTransactionResult {
        DomainLog.d(TAG, "Resetting slot: ${buildingType.name}[$slotIndex]")

        val slot = repository.getSlotByIndex(buildingType, slotIndex)
        if (slot == null) {
            DomainLog.w(TAG, "Slot not found: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidSlot("槽位不存在: ${buildingType.name}[$slotIndex]", slotIndex)
            )
        }

        val previousState = slot

        val result = repository.updateSlotAtomic(buildingType, slotIndex) { currentSlot ->
            SlotStateMachine.resetSlot(currentSlot).getOrElse { e ->
                DomainLog.w(TAG, "resetSlot state transition failed: ${e.message}")
                return@updateSlotAtomic currentSlot
            }
        }

        return if (result.isSuccess) {
            DomainLog.d(TAG, "Slot reset: ${buildingType.name}[$slotIndex]")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                rollbackData = ProductionRollbackData(previousSlotState = previousState)
            )
        } else {
            val exception = result.exceptionOrNull()
            DomainLog.e(TAG, "Failed to reset slot: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidStateTransition(
                    exception?.message ?: "Unknown error",
                    previousState.status.name,
                    ProductionSlotStatus.IDLE.name
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
        DomainLog.d(TAG, "Assigning disciple: ${buildingType.name}[$slotIndex] disciple=$discipleId")

        val slot = repository.getSlotByIndex(buildingType, slotIndex)
        if (slot == null) {
            DomainLog.w(TAG, "Slot not found: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidSlot("槽位不存在: ${buildingType.name}[$slotIndex]", slotIndex)
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
            DomainLog.d(TAG, "Disciple assigned: ${buildingType.name}[$slotIndex]")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                rollbackData = ProductionRollbackData(previousSlotState = previousState)
            )
        } else {
            val exception = result.exceptionOrNull()
            DomainLog.e(TAG, "Failed to assign disciple: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.Unknown(exception?.message ?: "Unknown error")
            )
        }
    }

    suspend fun executeRemoveDisciple(
        buildingType: BuildingType,
        slotIndex: Int
    ): ProductionTransactionResult {
        DomainLog.d(TAG, "Removing disciple: ${buildingType.name}[$slotIndex]")

        val slot = repository.getSlotByIndex(buildingType, slotIndex)
        if (slot == null) {
            DomainLog.w(TAG, "Slot not found: ${buildingType.name}[$slotIndex]")
            return ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.InvalidSlot("槽位不存在: ${buildingType.name}[$slotIndex]", slotIndex)
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
            DomainLog.d(TAG, "Disciple removed: ${buildingType.name}[$slotIndex]")
            ProductionTransactionResult(
                success = true,
                slot = result.getOrNull(),
                rollbackData = ProductionRollbackData(previousSlotState = previousState)
            )
        } else {
            val exception = result.exceptionOrNull()
            DomainLog.e(TAG, "Failed to remove disciple: ${exception?.message}")
            ProductionTransactionResult(
                success = false,
                error = AppError.Domain.Production.Unknown(exception?.message ?: "Unknown error")
            )
        }
    }

    suspend fun rollback(rollbackData: ProductionRollbackData): Result<ProductionSlot?> {
        DomainLog.d(TAG, "Rolling back transaction")
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
