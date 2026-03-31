package com.xianxia.sect.core.engine.operations

import com.xianxia.sect.core.model.production.*
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

data class MaterialInventory(
    val materials: Map<String, Int>
) {
    fun hasEnough(required: Map<String, Int>): Boolean {
        return required.all { (id, amount) ->
            (materials[id] ?: 0) >= amount
        }
    }
    
    fun getMissing(required: Map<String, Int>): Map<String, Int> {
        return required.mapValues { (id, amount) ->
            val have = materials[id] ?: 0
            (amount - have).coerceAtLeast(0)
        }.filterValues { it > 0 }
    }
    
    fun consume(required: Map<String, Int>): MaterialInventory {
        val newMaterials = materials.toMutableMap()
        required.forEach { (id, amount) ->
            val current = newMaterials[id] ?: 0
            val new = current - amount
            if (new <= 0) {
                newMaterials.remove(id)
            } else {
                newMaterials[id] = new
            }
        }
        return MaterialInventory(newMaterials)
    }
    
    fun restore(restored: Map<String, Int>): MaterialInventory {
        val newMaterials = materials.toMutableMap()
        restored.forEach { (id, amount) ->
            val current = newMaterials[id] ?: 0
            newMaterials[id] = current + amount
        }
        return MaterialInventory(newMaterials)
    }
}

class ProductionOperation(
    private val materialsFlow: MutableStateFlow<Map<String, Int>>,
    private val slotsFlow: MutableStateFlow<List<ProductionSlot>>
) {
    private val mutex = Mutex()
    
    suspend fun startProductionAtomic(
        buildingType: BuildingType,
        slotIndex: Int,
        recipeId: String,
        recipeName: String,
        duration: Int,
        currentYear: Int,
        currentMonth: Int,
        requiredMaterials: Map<String, Int>,
        successRate: Double,
        outputItemId: String?,
        outputItemName: String,
        outputItemRarity: Int
    ): ProductionOperationResult<ProductionSlot> = mutex.withLock {
        val currentMaterials = MaterialInventory(materialsFlow.value)
        
        if (!currentMaterials.hasEnough(requiredMaterials)) {
            val missing = currentMaterials.getMissing(requiredMaterials)
            return ProductionOperationResult.Failure(
                ProductionError.InsufficientMaterials(
                    message = "材料不足",
                    missingMaterials = missing
                )
            )
        }
        
        val currentSlots = slotsFlow.value
        val existingSlot = currentSlots.find { 
            it.buildingType == buildingType && it.slotIndex == slotIndex 
        }
        
        if (existingSlot != null && existingSlot.status == ProductionSlotStatus.WORKING) {
            return ProductionOperationResult.Failure(
                ProductionError.SlotBusy(
                    message = "槽位正在工作中",
                    slotIndex = slotIndex
                )
            )
        }
        
        if (!BuildingConfigs.isValidSlotIndex(buildingType, slotIndex)) {
            return ProductionOperationResult.Failure(
                ProductionError.InvalidSlot(
                    message = "无效的槽位",
                    slotIndex = slotIndex
                )
            )
        }
        
        val materialSnapshot = materialsFlow.value.toMap()
        
        try {
            val newMaterials = currentMaterials.consume(requiredMaterials)
            materialsFlow.value = newMaterials.materials
            
            val newSlot = ProductionSlot(
                id = existingSlot?.id ?: java.util.UUID.randomUUID().toString(),
                slotIndex = slotIndex,
                buildingType = buildingType,
                status = ProductionSlotStatus.WORKING,
                recipeId = recipeId,
                recipeName = recipeName,
                startYear = currentYear,
                startMonth = currentMonth,
                duration = duration,
                successRate = successRate,
                requiredMaterials = requiredMaterials,
                outputItemId = outputItemId,
                outputItemName = outputItemName,
                outputItemRarity = outputItemRarity
            )
            
            val newSlots = if (existingSlot != null) {
                currentSlots.map { if (it.id == existingSlot.id) newSlot else it }
            } else {
                currentSlots + newSlot
            }
            
            slotsFlow.value = newSlots
            
            ProductionOperationResult.Success(newSlot)
        } catch (e: Exception) {
            materialsFlow.value = materialSnapshot
            ProductionOperationResult.Failure(
                ProductionError.InvalidStateTransition(
                    message = "生产启动失败: ${e.message}",
                    fromStatus = "IDLE",
                    toStatus = "WORKING"
                )
            )
        }
    }
    
    suspend fun completeProductionAtomic(
        buildingType: BuildingType,
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int
    ): ProductionOperationResult<Pair<ProductionSlot, ProductionOutcome>> = mutex.withLock {
        val currentSlots = slotsFlow.value
        val slot = currentSlots.find { 
            it.buildingType == buildingType && it.slotIndex == slotIndex 
        } ?: return ProductionOperationResult.Failure(
            ProductionError.InvalidSlot(
                message = "槽位不存在",
                slotIndex = slotIndex
            )
        )
        
        if (slot.status != ProductionSlotStatus.WORKING) {
            return ProductionOperationResult.Failure(
                ProductionError.InvalidStateTransition(
                    message = "槽位未工作中",
                    fromStatus = slot.status.name,
                    toStatus = "COMPLETED"
                )
            )
        }
        
        if (!slot.isFinished(currentYear, currentMonth)) {
            return ProductionOperationResult.Failure(
                ProductionError.InvalidStateTransition(
                    message = "生产尚未完成",
                    fromStatus = slot.status.name,
                    toStatus = "COMPLETED"
                )
            )
        }
        
        val success = Random.nextDouble() <= slot.successRate
        val result: ProductionOutcome = if (success) {
            ProductionOutcome.Success(
                outputItemId = slot.outputItemId ?: "",
                outputItemName = slot.outputItemName,
                outputItemRarity = slot.outputItemRarity,
                quantity = 1
            )
        } else {
            ProductionOutcome.Failure(
                reason = "炼制失败",
                materialsLost = slot.requiredMaterials
            )
        }
        
        val completedSlot = slot.copy(status = ProductionSlotStatus.COMPLETED)
        val newSlots = currentSlots.map { 
            if (it.id == slot.id) completedSlot else it 
        }
        slotsFlow.value = newSlots
        
        ProductionOperationResult.Success(completedSlot to result)
    }
    
    suspend fun resetSlotAtomic(
        buildingType: BuildingType,
        slotIndex: Int
    ): ProductionOperationResult<ProductionSlot> = mutex.withLock {
        val currentSlots = slotsFlow.value
        val slot = currentSlots.find { 
            it.buildingType == buildingType && it.slotIndex == slotIndex 
        } ?: return ProductionOperationResult.Failure(
            ProductionError.InvalidSlot(
                message = "槽位不存在",
                slotIndex = slotIndex
            )
        )
        
        val transitionResult = SlotStateMachine.validateTransition(
            slot.status, 
            ProductionSlotStatus.IDLE
        )
        
        if (transitionResult.isFailure) {
            return ProductionOperationResult.Failure(
                ProductionError.InvalidStateTransition(
                    message = transitionResult.exceptionOrNull()?.message ?: "无效的状态转换",
                    fromStatus = slot.status.name,
                    toStatus = "IDLE"
                )
            )
        }
        
        val resetSlot = ProductionSlot(
            id = slot.id,
            slotIndex = slot.slotIndex,
            buildingType = slot.buildingType,
            status = ProductionSlotStatus.IDLE
        )
        
        val newSlots = currentSlots.map { 
            if (it.id == slot.id) resetSlot else it 
        }
        slotsFlow.value = newSlots
        
        ProductionOperationResult.Success(resetSlot)
    }
    
    fun startProductionSync(
        materials: Map<String, Int>,
        slots: List<ProductionSlot>,
        buildingType: BuildingType,
        slotIndex: Int,
        recipeId: String,
        recipeName: String,
        duration: Int,
        currentYear: Int,
        currentMonth: Int,
        requiredMaterials: Map<String, Int>,
        successRate: Double,
        outputItemId: String?,
        outputItemName: String,
        outputItemRarity: Int
    ): ProductionOperationResult<Triple<Map<String, Int>, List<ProductionSlot>, ProductionSlot>> {
        val currentMaterials = MaterialInventory(materials)
        
        if (!currentMaterials.hasEnough(requiredMaterials)) {
            val missing = currentMaterials.getMissing(requiredMaterials)
            return ProductionOperationResult.Failure(
                ProductionError.InsufficientMaterials(
                    message = "材料不足",
                    missingMaterials = missing
                )
            )
        }
        
        val existingSlot = slots.find { 
            it.buildingType == buildingType && it.slotIndex == slotIndex 
        }
        
        if (existingSlot != null && existingSlot.status == ProductionSlotStatus.WORKING) {
            return ProductionOperationResult.Failure(
                ProductionError.SlotBusy(
                    message = "槽位正在工作中",
                    slotIndex = slotIndex
                )
            )
        }
        
        if (!BuildingConfigs.isValidSlotIndex(buildingType, slotIndex)) {
            return ProductionOperationResult.Failure(
                ProductionError.InvalidSlot(
                    message = "无效的槽位",
                    slotIndex = slotIndex
                )
            )
        }
        
        val newMaterials = currentMaterials.consume(requiredMaterials)
        
        val newSlot = ProductionSlot(
            id = existingSlot?.id ?: java.util.UUID.randomUUID().toString(),
            slotIndex = slotIndex,
            buildingType = buildingType,
            status = ProductionSlotStatus.WORKING,
            recipeId = recipeId,
            recipeName = recipeName,
            startYear = currentYear,
            startMonth = currentMonth,
            duration = duration,
            successRate = successRate,
            requiredMaterials = requiredMaterials,
            outputItemId = outputItemId,
            outputItemName = outputItemName,
            outputItemRarity = outputItemRarity
        )
        
        val newSlots = if (existingSlot != null) {
            slots.map { if (it.id == existingSlot.id) newSlot else it }
        } else {
            slots + newSlot
        }
        
        return ProductionOperationResult.Success(
            Triple(newMaterials.materials, newSlots, newSlot)
        )
    }
}

object ProductionOperations {
    fun create(
        materialsFlow: MutableStateFlow<Map<String, Int>>,
        slotsFlow: MutableStateFlow<List<ProductionSlot>>
    ): ProductionOperation = ProductionOperation(materialsFlow, slotsFlow)
}
