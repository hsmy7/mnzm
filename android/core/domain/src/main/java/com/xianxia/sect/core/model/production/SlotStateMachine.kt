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
        spec: ProductionStartSpec
    ): Result<ProductionSlot> {
        return validateTransition(slot.status, ProductionSlotStatus.WORKING).mapCatching {
            val absoluteMonth = (spec.currentYear - 1) * 12 + spec.currentMonth
            val completionPhase = when (slot.buildingType) {
                BuildingType.FORGE, BuildingType.ALCHEMY -> 2  // 锻造/炼丹中旬
                BuildingType.HERB_GARDEN, BuildingType.MINING -> 3  // 种植/灵矿下旬
                else -> 1
            }
            slot.copy(
                status = ProductionSlotStatus.WORKING,
                recipeId = spec.recipeId,
                recipeName = spec.recipeName,
                startYear = spec.currentYear,
                startMonth = spec.currentMonth,
                duration = spec.duration,
                assignedDiscipleId = spec.discipleId,
                assignedDiscipleName = spec.discipleName,
                successRate = spec.successRate,
                requiredMaterials = spec.materials,
                outputItemId = spec.outputItemId,
                outputItemName = spec.outputItemName,
                outputItemRarity = spec.outputItemRarity,
                completionMonth = absoluteMonth + spec.duration.coerceAtLeast(1),
                completionPhase = completionPhase
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
            slot.copy(
                status = ProductionSlotStatus.IDLE,
                recipeId = null,
                recipeName = "",
                startYear = 0,
                startMonth = 0,
                duration = 0,
                requiredMaterials = emptyMap(),
                outputItemId = null,
                outputItemName = "",
                outputItemRarity = 1,
                outputItemSlot = "",
                expectedYield = 0
            )
        }
    }
}
