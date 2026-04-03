package com.xianxia.sect.core.model.production

import java.util.UUID

object SlotStateAdapter {
    
    fun toLegacy(state: SlotStateV2, existingId: String? = null): ProductionSlot {
        val id = existingId ?: UUID.randomUUID().toString()
        return when (state) {
            is SlotStateV2.Idle -> ProductionSlot(
                id = id,
                slotIndex = state.slotIndex,
                buildingType = state.buildingType,
                buildingId = state.buildingId,
                status = ProductionSlotStatus.IDLE,
                assignedDiscipleId = state.assignedDiscipleId,
                assignedDiscipleName = state.assignedDiscipleName
            )
            is SlotStateV2.Working -> ProductionSlot(
                id = id,
                slotIndex = state.slotIndex,
                buildingType = state.buildingType,
                buildingId = state.buildingId,
                status = ProductionSlotStatus.WORKING,
                recipeId = state.recipeId,
                recipeName = state.recipeName,
                startYear = state.startYear,
                startMonth = state.startMonth,
                duration = state.duration,
                assignedDiscipleId = state.assignedDiscipleId,
                assignedDiscipleName = state.assignedDiscipleName,
                successRate = state.successRate,
                requiredMaterials = state.requiredMaterials,
                outputItemId = state.outputItemId,
                outputItemName = state.outputItemName,
                outputItemRarity = state.outputItemRarity,
                outputItemSlot = state.outputItemSlot
            )
            is SlotStateV2.Completed -> ProductionSlot(
                id = id,
                slotIndex = state.slotIndex,
                buildingType = state.buildingType,
                buildingId = state.buildingId,
                status = ProductionSlotStatus.COMPLETED,
                recipeId = state.recipeId,
                recipeName = state.recipeName,
                outputItemId = state.outputItemId,
                outputItemName = state.outputItemName,
                outputItemRarity = state.outputItemRarity,
                outputItemSlot = state.outputItemSlot,
                successRate = state.successRate,
                requiredMaterials = state.requiredMaterials
            )
        }
    }
    
    fun fromLegacy(slot: ProductionSlot): SlotStateV2 = when (slot.status) {
        ProductionSlotStatus.IDLE -> SlotStateV2.Idle(
            slotIndex = slot.slotIndex,
            buildingType = slot.buildingType,
            buildingId = slot.buildingId,
            assignedDiscipleId = slot.assignedDiscipleId,
            assignedDiscipleName = slot.assignedDiscipleName
        )
        ProductionSlotStatus.WORKING -> SlotStateV2.Working(
            slotIndex = slot.slotIndex,
            buildingType = slot.buildingType,
            buildingId = slot.buildingId,
            recipeId = slot.recipeId ?: "",
            recipeName = slot.recipeName,
            startYear = slot.startYear,
            startMonth = slot.startMonth,
            duration = slot.duration,
            assignedDiscipleId = slot.assignedDiscipleId,
            assignedDiscipleName = slot.assignedDiscipleName,
            successRate = slot.successRate,
            requiredMaterials = slot.requiredMaterials,
            outputItemId = slot.outputItemId,
            outputItemName = slot.outputItemName,
            outputItemRarity = slot.outputItemRarity,
            outputItemSlot = slot.outputItemSlot
        )
        ProductionSlotStatus.COMPLETED -> SlotStateV2.Completed(
            slotIndex = slot.slotIndex,
            buildingType = slot.buildingType,
            buildingId = slot.buildingId,
            recipeId = slot.recipeId ?: "",
            recipeName = slot.recipeName,
            outputItemId = slot.outputItemId,
            outputItemName = slot.outputItemName,
            outputItemRarity = slot.outputItemRarity,
            outputItemSlot = slot.outputItemSlot,
            successRate = slot.successRate,
            requiredMaterials = slot.requiredMaterials
        )
    }
    
    fun toLegacyList(states: List<SlotStateV2>): List<ProductionSlot> {
        return states.map { toLegacy(it) }
    }
    
    fun fromLegacyList(slots: List<ProductionSlot>): List<SlotStateV2> {
        return slots.map { fromLegacy(it) }
    }
    
    fun toLegacyWithId(state: SlotStateV2, slotId: String): ProductionSlot {
        return toLegacy(state, slotId)
    }
    
    fun validateConversion(slot: ProductionSlot, state: SlotStateV2): Boolean {
        if (slot.status != state.toStatus()) return false
        if (slot.slotIndex != state.slotIndex) return false
        if (slot.buildingType != state.buildingType) return false
        if (slot.buildingId != state.buildingId) return false
        
        return when (state) {
            is SlotStateV2.Idle -> {
                slot.assignedDiscipleId == state.assignedDiscipleId &&
                slot.assignedDiscipleName == state.assignedDiscipleName
            }
            is SlotStateV2.Working -> {
                slot.recipeId == state.recipeId &&
                slot.recipeName == state.recipeName &&
                slot.startYear == state.startYear &&
                slot.startMonth == state.startMonth &&
                slot.duration == state.duration &&
                slot.successRate == state.successRate &&
                slot.outputItemSlot == state.outputItemSlot
            }
            is SlotStateV2.Completed -> {
                slot.recipeId == state.recipeId &&
                slot.recipeName == state.recipeName &&
                slot.outputItemSlot == state.outputItemSlot
            }
        }
    }
    
    fun validateRoundTrip(slot: ProductionSlot): Boolean {
        val state = fromLegacy(slot)
        val converted = toLegacy(state, slot.id)
        return slot == converted
    }
    
    fun validateRoundTrip(state: SlotStateV2): Boolean {
        val legacy = toLegacy(state)
        val converted = fromLegacy(legacy)
        return state == converted
    }
}
