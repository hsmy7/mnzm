package com.xianxia.sect.core.model.production

data class ProductionParams(
    val buildingType: BuildingType,
    val slotIndex: Int,
    val recipe: RecipeInfo,
    val gameTime: GameTime,
    val disciple: DiscipleInfo? = null,
    val materials: Map<String, Int> = emptyMap(),
    val availableMaterials: Map<String, Int> = emptyMap()
) {
    init {
        require(slotIndex >= 0) { "Slot index must be >= 0" }
        require(recipe.id.isNotBlank()) { "Recipe ID cannot be blank" }
        require(recipe.name.isNotBlank()) { "Recipe name cannot be blank" }
        require(recipe.duration > 0) { "Duration should be positive" }
        require(recipe.successRate >= 0.0 && recipe.successRate <= 1.0) { "Success rate should be in [0.0, 1.0]" }
        require(availableMaterials.isNotEmpty()) { "Available materials cannot be empty" }
        if (disciple != null) {
            require(disciple.id.isNotBlank()) { "Disciple ID cannot be blank" }
            require(disciple.name.isNotBlank()) { "Disciple name cannot be blank" }
        }
        if (recipe.outputItemId != null) {
            require(recipe.outputItemId.isNotBlank()) { "Output item ID cannot be blank" }
        }
        require(recipe.outputItemRarity in 1..5) { "Output item rarity should be in range [1, 5]" }
    }

    data class RecipeInfo(
        val id: String,
        val name: String,
        val duration: Int,
        val successRate: Double,
        val outputItemId: String?,
        val outputItemName: String,
        val outputItemRarity: Int,
        val outputItemSlot: String = ""
    )

    data class DiscipleInfo(
        val id: String,
        val name: String
    )

    companion object {
        fun create(
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
            outputItemRarity: Int,
            outputItemSlot: String = ""
        ): ProductionParams = ProductionParams(
            buildingType = buildingType,
            slotIndex = slotIndex,
            recipe = RecipeInfo(
                id = recipeId,
                name = recipeName,
                duration = duration,
                successRate = successRate,
                outputItemId = outputItemId,
                outputItemName = outputItemName,
                outputItemRarity = outputItemRarity,
                outputItemSlot = outputItemSlot
            ),
            gameTime = GameTime(currentYear, currentMonth),
            disciple = discipleId?.let { DiscipleInfo(it, discipleName) },
            materials = materials,
            availableMaterials = availableMaterials
        )
    }
}

data class ProductionResult(
    val success: Boolean,
    val slot: ProductionSlot? = null,
    val outcome: ProductionOutcome? = null,
    val error: ProductionError? = null
) {
    sealed class ProductionError {
        data class SlotNotFound(val buildingType: BuildingType, val slotIndex: Int) : ProductionError()
        data class SlotBusy(val slotIndex: Int, val message: String = "") : ProductionError()
        data class InsufficientMaterials(val missing: Map<String, Int>) : ProductionError()
        data class InvalidStateTransition(
            val from: ProductionSlotStatus,
            val to: ProductionSlotStatus,
            val message: String = ""
        ) : ProductionError()
        data class ProductionNotReady(val remainingTime: Int) : ProductionError()
        data class DatabaseError(val message: String) : ProductionError()
        data class UnknownError(val message: String) : ProductionError()
    }

    companion object {
        fun success(slot: ProductionSlot, outcome: ProductionOutcome? = null): ProductionResult =
            ProductionResult(success = true, slot = slot, outcome = outcome)

        fun failure(error: ProductionError): ProductionResult =
            ProductionResult(success = false, error = error)
    }
}
