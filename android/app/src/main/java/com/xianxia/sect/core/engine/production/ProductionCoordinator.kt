package com.xianxia.sect.core.engine.production

import android.util.Log
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.production.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.transaction.ProductionTransactionManager
import com.xianxia.sect.core.transaction.ProductionTransactionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

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

@Singleton
class ProductionCoordinator @Inject constructor(
    private val repository: ProductionSlotRepository,
    private val transactionManager: ProductionTransactionManager
) {
    companion object {
        private const val TAG = "ProductionCoordinator"
    }
    
    private val _consumptionLogs = MutableStateFlow<List<MaterialConsumptionLog>>(emptyList())
    val consumptionLogs: StateFlow<List<MaterialConsumptionLog>> = _consumptionLogs.asStateFlow()
    
    val slots: StateFlow<List<ProductionSlot>> = repository.slots
    
    fun initializeSlots(existingSlots: List<ProductionSlot>) {
        Log.d(TAG, "Initializing with ${existingSlots.size} slots")
    }
    
    fun getSlotsByBuilding(buildingType: BuildingType): List<ProductionSlot> {
        return repository.getSlotsByType(buildingType)
    }
    
    fun getSlotsByBuildingId(buildingId: String): List<ProductionSlot> {
        return repository.getSlotsByBuildingId(buildingId)
    }
    
    fun getSlot(buildingType: BuildingType, slotIndex: Int): ProductionSlot? {
        return repository.getSlotByIndex(buildingType, slotIndex)
    }
    
    fun getSlotByBuildingId(buildingId: String, slotIndex: Int): ProductionSlot? {
        return repository.getSlotByBuildingId(buildingId, slotIndex)
    }
    
    suspend fun startAlchemyAtomic(
        slotIndex: Int,
        recipeId: String,
        currentYear: Int,
        currentMonth: Int,
        herbs: List<Herb>,
        buildingId: String = "alchemy"
    ): ProductionStartResult {
        Log.d(TAG, "Starting alchemy: $buildingId[$slotIndex] recipe=$recipeId")
        
        val recipe = PillRecipeDatabase.getRecipeById(recipeId)
            ?: return ProductionStartResult(
                success = false,
                error = ProductionError.RecipeNotFound(
                    message = "配方不存在",
                    recipeId = recipeId
                )
            )
        
        val availableMaterials = mutableMapOf<String, Int>()
        herbs.forEach { herb ->
            availableMaterials["herb_${herb.name}_${herb.rarity}"] = herb.quantity
        }
        
        val txResult = transactionManager.executeStartProductionByBuildingId(
            buildingId = buildingId,
            slotIndex = slotIndex,
            recipeId = recipeId,
            recipeName = recipe.name,
            duration = recipe.duration,
            currentYear = currentYear,
            currentMonth = currentMonth,
            discipleId = null,
            discipleName = "",
            successRate = recipe.successRate,
            materials = recipe.materials,
            availableMaterials = availableMaterials,
            outputItemId = recipe.id,
            outputItemName = recipe.name,
            outputItemRarity = recipe.rarity
        )
        
        if (!txResult.success) {
            return ProductionStartResult(
                success = false,
                error = when (val err = txResult.error) {
                    is com.xianxia.sect.core.transaction.ProductionTransactionError.SlotBusy -> 
                        ProductionError.SlotBusy(message = err.message, slotIndex = slotIndex)
                    is com.xianxia.sect.core.transaction.ProductionTransactionError.InsufficientMaterials -> 
                        ProductionError.InsufficientMaterials(message = "材料不足", missingMaterials = err.missing)
                    else -> ProductionError.InvalidSlot(message = err?.toString() ?: "Unknown error", slotIndex = slotIndex)
                }
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
        
        Log.d(TAG, "Alchemy started successfully: $buildingId[$slotIndex]")
        return ProductionStartResult(
            success = true,
            slot = txResult.slot,
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
    ): ProductionStartResult {
        Log.d(TAG, "Starting forging: $buildingId[$slotIndex] recipe=$recipeId")
        
        val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
            ?: return ProductionStartResult(
                success = false,
                error = ProductionError.RecipeNotFound(
                    message = "配方不存在",
                    recipeId = recipeId
                )
            )
        
        val availableMaterials = mutableMapOf<String, Int>()
        materials.forEach { material ->
            availableMaterials["material_${material.name}_${material.rarity}"] = material.quantity
        }
        
        val duration = ForgeRecipeDatabase.getDurationByTier(recipe.tier)
        
        val txResult = transactionManager.executeStartProductionByBuildingId(
            buildingId = buildingId,
            slotIndex = slotIndex,
            recipeId = recipeId,
            recipeName = recipe.name,
            duration = duration,
            currentYear = currentYear,
            currentMonth = currentMonth,
            discipleId = null,
            discipleName = "",
            successRate = recipe.successRate,
            materials = recipe.materials,
            availableMaterials = availableMaterials,
            outputItemId = recipe.id,
            outputItemName = recipe.name,
            outputItemRarity = recipe.rarity
        )
        
        if (!txResult.success) {
            return ProductionStartResult(
                success = false,
                error = when (val err = txResult.error) {
                    is com.xianxia.sect.core.transaction.ProductionTransactionError.SlotBusy -> 
                        ProductionError.SlotBusy(message = err.message, slotIndex = slotIndex)
                    is com.xianxia.sect.core.transaction.ProductionTransactionError.InsufficientMaterials -> 
                        ProductionError.InsufficientMaterials(message = "材料不足", missingMaterials = err.missing)
                    else -> ProductionError.InvalidSlot(message = err?.toString() ?: "Unknown error", slotIndex = slotIndex)
                }
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
        
        Log.d(TAG, "Forging started successfully: $buildingId[$slotIndex]")
        return ProductionStartResult(
            success = true,
            slot = txResult.slot,
            materialUpdate = MaterialUpdate(herbs = emptyList(), materials = newMaterials)
        )
    }
    
    suspend fun completeProductionAtomic(
        buildingType: BuildingType,
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int
    ): ProductionCompleteResult {
        Log.d(TAG, "Completing production: ${buildingType.name}[$slotIndex]")
        
        val txResult = transactionManager.executeCompleteProduction(buildingType, slotIndex, currentYear, currentMonth)
        
        if (!txResult.success) {
            return ProductionCompleteResult(
                success = false,
                error = when (val err = txResult.error) {
                    is com.xianxia.sect.core.transaction.ProductionTransactionError.ProductionNotReady -> 
                        ProductionError.InvalidStateTransition(
                            message = "生产尚未完成，剩余${err.remainingTime}月",
                            fromStatus = "WORKING",
                            toStatus = "COMPLETED"
                        )
                    is com.xianxia.sect.core.transaction.ProductionTransactionError.InvalidStateTransition -> 
                        ProductionError.InvalidStateTransition(
                            message = err.message,
                            fromStatus = err.from.name,
                            toStatus = err.to.name
                        )
                    else -> ProductionError.InvalidSlot(message = err?.toString() ?: "Unknown error", slotIndex = slotIndex)
                }
            )
        }
        
        Log.d(TAG, "Production completed: ${buildingType.name}[$slotIndex]")
        return ProductionCompleteResult(
            success = true,
            outcome = txResult.outcome,
            slot = txResult.slot
        )
    }
    
    suspend fun completeProductionByBuildingIdAtomic(
        buildingId: String,
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int
    ): ProductionCompleteResult {
        Log.d(TAG, "Completing production by buildingId: $buildingId[$slotIndex]")
        
        val txResult = transactionManager.executeCompleteProductionByBuildingId(buildingId, slotIndex, currentYear, currentMonth)
        
        if (!txResult.success) {
            return ProductionCompleteResult(
                success = false,
                error = when (val err = txResult.error) {
                    is com.xianxia.sect.core.transaction.ProductionTransactionError.ProductionNotReady -> 
                        ProductionError.InvalidStateTransition(
                            message = "生产尚未完成，剩余${err.remainingTime}月",
                            fromStatus = "WORKING",
                            toStatus = "COMPLETED"
                        )
                    is com.xianxia.sect.core.transaction.ProductionTransactionError.InvalidStateTransition -> 
                        ProductionError.InvalidStateTransition(
                            message = err.message,
                            fromStatus = err.from.name,
                            toStatus = err.to.name
                        )
                    else -> ProductionError.InvalidSlot(message = err?.toString() ?: "Unknown error", slotIndex = slotIndex)
                }
            )
        }
        
        Log.d(TAG, "Production completed: $buildingId[$slotIndex]")
        return ProductionCompleteResult(
            success = true,
            outcome = txResult.outcome,
            slot = txResult.slot
        )
    }
    
    suspend fun resetSlotAtomic(
        buildingType: BuildingType,
        slotIndex: Int
    ): ProductionOperationResult<ProductionSlot> {
        Log.d(TAG, "Resetting slot: ${buildingType.name}[$slotIndex]")
        
        val txResult = transactionManager.executeResetSlot(buildingType, slotIndex)
        
        return if (txResult.success) {
            txResult.slot?.let { ProductionOperationResult.Success(it) }
                ?: ProductionOperationResult.Failure(
                    ProductionError.InvalidSlot(
                        message = "Transaction succeeded but slot data is missing",
                        slotIndex = slotIndex
                    )
                )
        } else {
            ProductionOperationResult.Failure(
                ProductionError.InvalidSlot(
                    message = txResult.error?.toString() ?: "Unknown error",
                    slotIndex = slotIndex
                )
            )
        }
    }
    
    suspend fun resetSlotByBuildingIdAtomic(
        buildingId: String,
        slotIndex: Int
    ): ProductionOperationResult<ProductionSlot> {
        Log.d(TAG, "Resetting slot by buildingId: $buildingId[$slotIndex]")
        
        val slot = repository.getSlotByBuildingId(buildingId, slotIndex)
            ?: return ProductionOperationResult.Failure(
                ProductionError.InvalidSlot(message = "Slot not found", slotIndex = slotIndex)
            )
        
        return resetSlotAtomic(slot.buildingType, slotIndex)
    }
    
    fun updateSlot(slot: ProductionSlot) {
        Log.d(TAG, "Direct slot update (deprecated): ${slot.buildingType.name}[${slot.slotIndex}]")
    }
    
    fun updateSlots(newSlots: List<ProductionSlot>) {
        Log.d(TAG, "Direct slots update (deprecated): ${newSlots.size} slots")
    }
    
    fun getCurrentSlots(): List<ProductionSlot> = repository.getSlots()
    
    fun getWorkingSlots(): List<ProductionSlot> = repository.getWorkingSlots()
    
    fun getCompletedSlots(): List<ProductionSlot> = repository.getCompletedSlots()
    
    fun getFinishedSlots(currentYear: Int, currentMonth: Int): List<ProductionSlot> =
        repository.getFinishedSlots(currentYear, currentMonth)
}

object ProductionCoordinatorFactory {
    fun create(
        repository: ProductionSlotRepository,
        transactionManager: ProductionTransactionManager
    ): ProductionCoordinator = ProductionCoordinator(repository, transactionManager)
}
