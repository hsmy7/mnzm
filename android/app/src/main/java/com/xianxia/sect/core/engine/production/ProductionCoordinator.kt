package com.xianxia.sect.core.engine.production

import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.engine.operations.MaterialInventory
import com.xianxia.sect.core.engine.operations.ProductionOperation
import com.xianxia.sect.core.model.production.ProductionOperationResult
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.production.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

data class MaterialSource(
    val herbs: List<Herb>,
    val materials: List<Material>
) {
    fun toMaterialMap(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        herbs.forEach { herb ->
            result["herb_${herb.name}_${herb.rarity}"] = herb.quantity
        }
        materials.forEach { material ->
            result["material_${material.name}_${material.rarity}"] = material.quantity
        }
        return result
    }
    
    fun hasHerb(name: String, rarity: Int, amount: Int): Boolean {
        val herb = herbs.find { it.name == name && it.rarity == rarity }
        return herb != null && herb.quantity >= amount
    }
    
    fun hasMaterial(name: String, rarity: Int, amount: Int): Boolean {
        val material = materials.find { it.name == name && it.rarity == rarity }
        return material != null && material.quantity >= amount
    }
    
    fun getMissingHerbs(recipeMaterials: Map<String, Int>): Map<String, Int> {
        val missing = mutableMapOf<String, Int>()
        recipeMaterials.forEach { (herbId, required) ->
            val herbData = HerbDatabase.getHerbById(herbId)
            val herbName = herbData?.name ?: return@forEach
            val herbRarity = herbData.rarity
            val herb = herbs.find { it.name == herbName && it.rarity == herbRarity }
            val have = herb?.quantity ?: 0
            if (have < required) {
                missing[herbId] = required - have
            }
        }
        return missing
    }
    
    fun getMissingMaterials(recipeMaterials: Map<String, Int>): Map<String, Int> {
        val missing = mutableMapOf<String, Int>()
        recipeMaterials.forEach { (materialId, required) ->
            val materialData = BeastMaterialDatabase.getMaterialById(materialId)
            val materialName = materialData?.name ?: return@forEach
            val materialRarity = materialData.rarity
            val material = materials.find { it.name == materialName && it.rarity == materialRarity }
            val have = material?.quantity ?: 0
            if (have < required) {
                missing[materialId] = required - have
            }
        }
        return missing
    }
}

data class MaterialUpdate(
    val herbs: List<Herb>,
    val materials: List<Material>
)

data class ProductionStartResult(
    val success: Boolean,
    val slot: ProductionSlot? = null,
    val error: ProductionError? = null,
    val materialUpdate: MaterialUpdate? = null
)

data class ProductionCompleteResult(
    val success: Boolean,
    val outcome: ProductionOutcome? = null,
    val error: ProductionError? = null,
    val slot: ProductionSlot? = null
)

class ProductionCoordinator {
    private val _slots = MutableStateFlow<List<ProductionSlot>>(emptyList())
    val slots: StateFlow<List<ProductionSlot>> = _slots.asStateFlow()
    
    private val _consumptionLogs = MutableStateFlow<List<MaterialConsumptionLog>>(emptyList())
    val consumptionLogs: StateFlow<List<MaterialConsumptionLog>> = _consumptionLogs.asStateFlow()
    
    private val mutex = Mutex()
    
    fun initializeSlots(existingSlots: List<ProductionSlot>) {
        _slots.value = existingSlots
    }
    
    fun getSlotsByBuilding(buildingType: BuildingType): List<ProductionSlot> {
        return _slots.value.filter { it.buildingType == buildingType }
    }
    
    fun getSlotsByBuildingId(buildingId: String): List<ProductionSlot> {
        return _slots.value.filter { it.buildingId == buildingId }
    }
    
    fun getSlot(buildingType: BuildingType, slotIndex: Int): ProductionSlot? {
        return _slots.value.find { it.buildingType == buildingType && it.slotIndex == slotIndex }
    }
    
    fun getSlotByBuildingId(buildingId: String, slotIndex: Int): ProductionSlot? {
        return _slots.value.find { it.buildingId == buildingId && it.slotIndex == slotIndex }
    }
    
    suspend fun startAlchemyAtomic(
        slotIndex: Int,
        recipeId: String,
        currentYear: Int,
        currentMonth: Int,
        herbs: List<Herb>,
        buildingId: String = "alchemyRoom"
    ): ProductionStartResult = mutex.withLock {
        val recipe = PillRecipeDatabase.getRecipeById(recipeId)
            ?: return ProductionStartResult(
                success = false,
                error = ProductionError.RecipeNotFound(
                    message = "配方不存在",
                    recipeId = recipeId
                )
            )
        
        val existingSlot = _slots.value.find { 
            it.buildingId == buildingId && it.slotIndex == slotIndex 
        }
        
        if (existingSlot != null && existingSlot.status == ProductionSlotStatus.WORKING) {
            return ProductionStartResult(
                success = false,
                error = ProductionError.SlotBusy(
                    message = "该炼丹槽位正在工作中",
                    slotIndex = slotIndex
                )
            )
        }
        
        val missingMaterials = mutableMapOf<String, Int>()
        recipe.materials.forEach { (herbId, requiredAmount) ->
            val herbData = HerbDatabase.getHerbById(herbId)
            val herbName = herbData?.name ?: return@forEach
            val herbRarity = herbData.rarity
            val herb = herbs.find { it.name == herbName && it.rarity == herbRarity }
            val have = herb?.quantity ?: 0
            if (have < requiredAmount) {
                missingMaterials[herbId] = requiredAmount - have
            }
        }
        
        if (missingMaterials.isNotEmpty()) {
            return ProductionStartResult(
                success = false,
                error = ProductionError.InsufficientMaterials(
                    message = "草药材料不足，无法开始炼丹",
                    missingMaterials = missingMaterials
                )
            )
        }
        
        val newHerbs = herbs.map { herb ->
            var newQuantity = herb.quantity
            recipe.materials.forEach { (herbId, requiredAmount) ->
                val herbData = HerbDatabase.getHerbById(herbId)
                if (herbData?.name == herb.name && herbData.rarity == herb.rarity) {
                    newQuantity -= requiredAmount
                }
            }
            herb.copy(quantity = newQuantity)
        }.filter { it.quantity > 0 }
        
        val consumptionLog = MaterialConsumptionLog(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            slotIndex = slotIndex,
            recipeId = recipeId,
            recipeName = recipe.name,
            materials = recipe.materials,
            reason = "炼丹开始",
            buildingId = buildingId
        )
        _consumptionLogs.value = _consumptionLogs.value + consumptionLog
        
        val newSlot = ProductionSlot(
            id = existingSlot?.id ?: java.util.UUID.randomUUID().toString(),
            slotIndex = slotIndex,
            buildingType = BuildingType.ALCHEMY,
            buildingId = buildingId,
            status = ProductionSlotStatus.WORKING,
            recipeId = recipeId,
            recipeName = recipe.name,
            startYear = currentYear,
            startMonth = currentMonth,
            duration = recipe.duration,
            successRate = recipe.successRate,
            requiredMaterials = recipe.materials,
            outputItemId = recipe.id,
            outputItemName = recipe.name,
            outputItemRarity = recipe.rarity
        )
        
        val newSlots = if (existingSlot != null) {
            _slots.value.map { if (it.id == existingSlot.id) newSlot else it }
        } else {
            _slots.value + newSlot
        }
        _slots.value = newSlots
        
        ProductionStartResult(
            success = true,
            slot = newSlot,
            materialUpdate = MaterialUpdate(herbs = newHerbs, materials = emptyList())
        )
    }
    
    suspend fun startForgingAtomic(
        slotIndex: Int,
        recipeId: String,
        currentYear: Int,
        currentMonth: Int,
        materials: List<Material>,
        buildingId: String = "forge"
    ): ProductionStartResult = mutex.withLock {
        val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
            ?: return ProductionStartResult(
                success = false,
                error = ProductionError.RecipeNotFound(
                    message = "配方不存在",
                    recipeId = recipeId
                )
            )
        
        val existingSlot = _slots.value.find { 
            it.buildingId == buildingId && it.slotIndex == slotIndex 
        }
        
        if (existingSlot != null && existingSlot.status == ProductionSlotStatus.WORKING) {
            return ProductionStartResult(
                success = false,
                error = ProductionError.SlotBusy(
                    message = "该锻造槽位正在工作中",
                    slotIndex = slotIndex
                )
            )
        }
        
        val missingMaterials = mutableMapOf<String, Int>()
        recipe.materials.forEach { (materialId, requiredAmount) ->
            val materialData = BeastMaterialDatabase.getMaterialById(materialId)
            val materialName = materialData?.name ?: return@forEach
            val materialRarity = materialData.rarity
            val material = materials.find { it.name == materialName && it.rarity == materialRarity }
            val have = material?.quantity ?: 0
            if (have < requiredAmount) {
                missingMaterials[materialId] = requiredAmount - have
            }
        }
        
        if (missingMaterials.isNotEmpty()) {
            return ProductionStartResult(
                success = false,
                error = ProductionError.InsufficientMaterials(
                    message = "材料不足，无法开始锻造",
                    missingMaterials = missingMaterials
                )
            )
        }
        
        val newMaterials = materials.map { material ->
            var newQuantity = material.quantity
            recipe.materials.forEach { (materialId, requiredAmount) ->
                val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                if (materialData?.name == material.name && materialData.rarity == material.rarity) {
                    newQuantity -= requiredAmount
                }
            }
            material.copy(quantity = newQuantity)
        }.filter { it.quantity > 0 }
        
        val duration = ForgeRecipeDatabase.getDurationByTier(recipe.tier)
        
        val newSlot = ProductionSlot(
            id = existingSlot?.id ?: java.util.UUID.randomUUID().toString(),
            slotIndex = slotIndex,
            buildingType = BuildingType.FORGE,
            buildingId = buildingId,
            status = ProductionSlotStatus.WORKING,
            recipeId = recipeId,
            recipeName = recipe.name,
            startYear = currentYear,
            startMonth = currentMonth,
            duration = duration,
            successRate = recipe.successRate,
            requiredMaterials = recipe.materials,
            outputItemId = recipe.id,
            outputItemName = recipe.name,
            outputItemRarity = recipe.rarity,
            outputItemSlot = recipe.type.name
        )
        
        val newSlots = if (existingSlot != null) {
            _slots.value.map { if (it.id == existingSlot.id) newSlot else it }
        } else {
            _slots.value + newSlot
        }
        _slots.value = newSlots
        
        ProductionStartResult(
            success = true,
            slot = newSlot,
            materialUpdate = MaterialUpdate(herbs = emptyList(), materials = newMaterials)
        )
    }
    
    suspend fun completeProductionAtomic(
        buildingType: BuildingType,
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int
    ): ProductionCompleteResult = mutex.withLock {
        val slot = _slots.value.find { 
            it.buildingType == buildingType && it.slotIndex == slotIndex 
        } ?: return ProductionCompleteResult(
            success = false,
            error = ProductionError.InvalidSlot(
                message = "槽位不存在",
                slotIndex = slotIndex
            )
        )
        
        val transitionResult = SlotStateMachine.validateTransition(
            slot.status,
            ProductionSlotStatus.COMPLETED
        )
        
        if (transitionResult.isFailure) {
            return ProductionCompleteResult(
                success = false,
                error = ProductionError.InvalidStateTransition(
                    message = transitionResult.exceptionOrNull()?.message ?: "无效的状态转换",
                    fromStatus = slot.status.name,
                    toStatus = "COMPLETED"
                )
            )
        }
        
        if (!slot.isFinished(currentYear, currentMonth)) {
            return ProductionCompleteResult(
                success = false,
                error = ProductionError.InvalidStateTransition(
                    message = "生产尚未完成",
                    fromStatus = slot.status.name,
                    toStatus = "COMPLETED"
                )
            )
        }
        
        val success = Random.nextDouble() <= slot.successRate
        val outcome: ProductionOutcome = if (success) {
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
        val newSlots = _slots.value.map { 
            if (it.id == slot.id) completedSlot else it 
        }
        _slots.value = newSlots
        
        ProductionCompleteResult(
            success = true,
            outcome = outcome,
            slot = completedSlot
        )
    }
    
    suspend fun completeProductionByBuildingIdAtomic(
        buildingId: String,
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int
    ): ProductionCompleteResult = mutex.withLock {
        val slot = _slots.value.find { 
            it.buildingId == buildingId && it.slotIndex == slotIndex 
        } ?: return ProductionCompleteResult(
            success = false,
            error = ProductionError.InvalidSlot(
                message = "槽位不存在",
                slotIndex = slotIndex
            )
        )
        
        val transitionResult = SlotStateMachine.validateTransition(
            slot.status,
            ProductionSlotStatus.COMPLETED
        )
        
        if (transitionResult.isFailure) {
            return ProductionCompleteResult(
                success = false,
                error = ProductionError.InvalidStateTransition(
                    message = transitionResult.exceptionOrNull()?.message ?: "无效的状态转换",
                    fromStatus = slot.status.name,
                    toStatus = "COMPLETED"
                )
            )
        }
        
        if (!slot.isFinished(currentYear, currentMonth)) {
            return ProductionCompleteResult(
                success = false,
                error = ProductionError.InvalidStateTransition(
                    message = "生产尚未完成",
                    fromStatus = slot.status.name,
                    toStatus = "COMPLETED"
                )
            )
        }
        
        val success = Random.nextDouble() <= slot.successRate
        val outcome: ProductionOutcome = if (success) {
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
        val newSlots = _slots.value.map { 
            if (it.id == slot.id) completedSlot else it 
        }
        _slots.value = newSlots
        
        ProductionCompleteResult(
            success = true,
            outcome = outcome,
            slot = completedSlot
        )
    }
    
    suspend fun resetSlotAtomic(
        buildingType: BuildingType,
        slotIndex: Int
    ): ProductionOperationResult<ProductionSlot> = mutex.withLock {
        val slot = _slots.value.find { 
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
            buildingId = slot.buildingId,
            status = ProductionSlotStatus.IDLE
        )
        
        val newSlots = _slots.value.map { 
            if (it.id == slot.id) resetSlot else it 
        }
        _slots.value = newSlots
        
        ProductionOperationResult.Success(resetSlot)
    }
    
    suspend fun resetSlotByBuildingIdAtomic(
        buildingId: String,
        slotIndex: Int
    ): ProductionOperationResult<ProductionSlot> = mutex.withLock {
        val slot = _slots.value.find { 
            it.buildingId == buildingId && it.slotIndex == slotIndex 
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
            buildingId = slot.buildingId,
            status = ProductionSlotStatus.IDLE
        )
        
        val newSlots = _slots.value.map { 
            if (it.id == slot.id) resetSlot else it 
        }
        _slots.value = newSlots
        
        ProductionOperationResult.Success(resetSlot)
    }
    
    fun updateSlot(slot: ProductionSlot) {
        val currentSlots = _slots.value
        val existingIndex = currentSlots.indexOfFirst { it.id == slot.id }
        val newSlots = if (existingIndex >= 0) {
            currentSlots.toMutableList().apply { set(existingIndex, slot) }
        } else {
            currentSlots + slot
        }
        _slots.value = newSlots
    }
    
    fun updateSlots(newSlots: List<ProductionSlot>) {
        _slots.value = newSlots
    }
    
    fun getCurrentSlots(): List<ProductionSlot> = _slots.value
    
    fun getWorkingSlots(): List<ProductionSlot> = 
        _slots.value.filter { it.status == ProductionSlotStatus.WORKING }
    
    fun getCompletedSlots(): List<ProductionSlot> = 
        _slots.value.filter { it.status == ProductionSlotStatus.COMPLETED }
    
    fun getFinishedSlots(currentYear: Int, currentMonth: Int): List<ProductionSlot> =
        _slots.value.filter { 
            it.status == ProductionSlotStatus.WORKING && it.isFinished(currentYear, currentMonth) 
        }
}

object ProductionCoordinatorFactory {
    fun create(): ProductionCoordinator = ProductionCoordinator()
}
