package com.xianxia.sect.core.model.production

sealed class ProductionOutcome {
    data class Success(
        val outputItemId: String,
        val outputItemName: String,
        val outputItemRarity: Int,
        val quantity: Int = 1
    ) : ProductionOutcome()
    
    data class Failure(
        val reason: String,
        val materialsLost: Map<String, Int> = emptyMap()
    ) : ProductionOutcome()
}

object SlotStateMachine {
    fun validateTransition(
        currentStatus: ProductionSlotStatus,
        newStatus: ProductionSlotStatus
    ): Result<ProductionSlotStatus> {
        val isValid = when {
            currentStatus == ProductionSlotStatus.IDLE && 
                newStatus == ProductionSlotStatus.WORKING -> true
            currentStatus == ProductionSlotStatus.WORKING && 
                newStatus == ProductionSlotStatus.COMPLETED -> true
            currentStatus == ProductionSlotStatus.COMPLETED && 
                newStatus == ProductionSlotStatus.IDLE -> true
            currentStatus == ProductionSlotStatus.WORKING && 
                newStatus == ProductionSlotStatus.IDLE -> true
            else -> false
        }
        
        return if (isValid) {
            Result.success(newStatus)
        } else {
            Result.failure(IllegalStateException(
                "Invalid status transition: $currentStatus -> $newStatus"
            ))
        }
    }
    
    fun startProduction(
        slot: ProductionSlot,
        recipeId: String,
        recipeName: String,
        duration: Int,
        currentYear: Int,
        currentMonth: Int,
        discipleId: String?,
        discipleName: String,
        successRate: Double,
        materials: Map<String, Int>,
        outputItemId: String?,
        outputItemName: String,
        outputItemRarity: Int
    ): Result<ProductionSlot> {
        return validateTransition(slot.status, ProductionSlotStatus.WORKING).mapCatching {
            slot.copy(
                status = ProductionSlotStatus.WORKING,
                recipeId = recipeId,
                recipeName = recipeName,
                startYear = currentYear,
                startMonth = currentMonth,
                duration = duration,
                assignedDiscipleId = discipleId,
                assignedDiscipleName = discipleName,
                successRate = successRate,
                requiredMaterials = materials,
                outputItemId = outputItemId,
                outputItemName = outputItemName,
                outputItemRarity = outputItemRarity
            )
        }
    }
    
    fun completeProduction(slot: ProductionSlot): Result<ProductionSlot> {
        return validateTransition(slot.status, ProductionSlotStatus.COMPLETED).mapCatching {
            slot.copy(status = ProductionSlotStatus.COMPLETED)
        }
    }
    
    fun resetSlot(slot: ProductionSlot): Result<ProductionSlot> {
        return validateTransition(slot.status, ProductionSlotStatus.IDLE).mapCatching {
            ProductionSlot(
                id = slot.id,
                slotIndex = slot.slotIndex,
                buildingType = slot.buildingType,
                status = ProductionSlotStatus.IDLE
            )
        }
    }
}
