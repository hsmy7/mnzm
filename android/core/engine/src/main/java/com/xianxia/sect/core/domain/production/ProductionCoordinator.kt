package com.xianxia.sect.core.engine.domain.production

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.production.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.transaction.ProductionTransactionManager
import com.xianxia.sect.core.transaction.ProductionTransactionResult
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
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
            val herbData = HerbDatabase.getHerbByName(herb.name)
            if (herbData != null) {
                result[herbData.id] = herb.quantity
            }
        }
        materials.forEach { material ->
            val materialData = BeastMaterialDatabase.getMaterialByName(material.name)
            if (materialData != null) {
                result[materialData.id] = material.quantity
            }
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

/**
 * 生产启动成功的数据载体。
 *
 * @param slot 已启动的生产槽位
 * @param materialUpdate 材料扣除后的更新（herbs 用于炼丹，materials 用于锻造）
 */
data class ProductionStartData(
    val slot: ProductionSlot,
    val materialUpdate: MaterialUpdate
)

@Singleton
class ProductionCoordinator @Inject constructor(
    val repository: ProductionSlotRepository,
    private val transactionManager: ProductionTransactionManager
) {
    companion object {
        private const val TAG = "ProductionCoordinator"
    }
    
    private val _consumptionLogs = MutableStateFlow<List<MaterialConsumptionLog>>(emptyList())
    val consumptionLogs: StateFlow<List<MaterialConsumptionLog>> = _consumptionLogs.asStateFlow()
    
    val slots: StateFlow<List<ProductionSlot>> = repository.slots
    
    fun initializeSlots(existingSlots: List<ProductionSlot>) {
        DomainLog.d(TAG, "Initializing with ${existingSlots.size} slots")
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
        buildingId: String = "alchemy",
        alchemyPolicyBonus: Double = 0.0
    ): DomainResult<ProductionStartData> {
        DomainLog.d(TAG, "Starting alchemy: $buildingId[$slotIndex] recipe=$recipeId")

        val recipe = PillRecipeDatabase.getRecipeById(recipeId)
            ?: return DomainResult.Failure(
                AppError.Domain.Production.RecipeNotFound(recipeId = recipeId)
            )

        val availableMaterials = mutableMapOf<String, Int>()
        herbs.forEach { herb ->
            val herbData = HerbDatabase.getHerbByName(herb.name)
            if (herbData != null) {
                availableMaterials[herbData.id] = (availableMaterials[herbData.id] ?: 0) + herb.quantity
            } else {
                DomainLog.w(TAG, "Herb not found in database: ${herb.name}, skipping")
            }
        }

        val currentSlot = repository.getSlotByBuildingId(buildingId, slotIndex)
        val txResult = transactionManager.executeStartProductionByBuildingId(
            buildingId = buildingId,
            slotIndex = slotIndex,
            recipeId = recipeId,
            recipeName = recipe.name,
            duration = recipe.duration,
            currentYear = currentYear,
            currentMonth = currentMonth,
            discipleId = currentSlot?.assignedDiscipleId,
            discipleName = currentSlot?.assignedDiscipleName ?: "",
            successRate = recipe.successRate + alchemyPolicyBonus,
            materials = recipe.materials,
            availableMaterials = availableMaterials,
            outputItemId = recipe.id,
            outputItemName = recipe.name,
            outputItemRarity = recipe.rarity
        )

        if (!txResult.success) {
            val appError = txResult.error
                ?: AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
            return DomainResult.Failure(appError)
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

        DomainLog.d(TAG, "Alchemy started successfully: $buildingId[$slotIndex]")
        return DomainResult.Success(
            ProductionStartData(
                slot = txResult.slot ?: ProductionSlot(
                    slotIndex = slotIndex,
                    buildingType = BuildingType.ALCHEMY,
                    buildingId = buildingId
                ),
                materialUpdate = MaterialUpdate(herbs = newHerbs, materials = emptyList())
            )
        )
    }
    
    suspend fun startForgingAtomic(
        slotIndex: Int,
        recipeId: String,
        currentYear: Int,
        currentMonth: Int,
        materials: List<Material>,
        buildingId: String = "forge",
        forgePolicyBonus: Double = 0.0
    ): DomainResult<ProductionStartData> {
        DomainLog.d(TAG, "Starting forging: $buildingId[$slotIndex] recipe=$recipeId")

        val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
            ?: return DomainResult.Failure(
                AppError.Domain.Production.RecipeNotFound(recipeId = recipeId)
            )

        val availableMaterials = mutableMapOf<String, Int>()
        materials.forEach { material ->
            val materialData = BeastMaterialDatabase.getMaterialByName(material.name)
            if (materialData != null) {
                availableMaterials[materialData.id] = (availableMaterials[materialData.id] ?: 0) + material.quantity
            } else {
                DomainLog.w(TAG, "Material not found in database: ${material.name}, skipping")
            }
        }

        val duration = ForgeRecipeDatabase.getDurationByTier(recipe.tier)

        val currentSlot = repository.getSlotByBuildingId(buildingId, slotIndex)
        val txResult = transactionManager.executeStartProductionByBuildingId(
            buildingId = buildingId,
            slotIndex = slotIndex,
            recipeId = recipeId,
            recipeName = recipe.name,
            duration = duration,
            currentYear = currentYear,
            currentMonth = currentMonth,
            discipleId = currentSlot?.assignedDiscipleId,
            discipleName = currentSlot?.assignedDiscipleName ?: "",
            successRate = recipe.successRate + forgePolicyBonus,
            materials = recipe.materials,
            availableMaterials = availableMaterials,
            outputItemId = recipe.id,
            outputItemName = recipe.name,
            outputItemRarity = recipe.rarity
        )

        if (!txResult.success) {
            val appError = txResult.error
                ?: AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
            return DomainResult.Failure(appError)
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

        DomainLog.d(TAG, "Forging started successfully: $buildingId[$slotIndex]")
        return DomainResult.Success(
            ProductionStartData(
                slot = txResult.slot ?: ProductionSlot(
                    slotIndex = slotIndex,
                    buildingType = BuildingType.FORGE,
                    buildingId = buildingId
                ),
                materialUpdate = MaterialUpdate(herbs = emptyList(), materials = newMaterials)
            )
        )
    }
    
    suspend fun completeProductionAtomic(
        buildingType: BuildingType,
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int
    ): DomainResult<ProductionOutcome> {
        DomainLog.d(TAG, "Completing production: ${buildingType.name}[$slotIndex]")

        val txResult = transactionManager.executeCompleteProduction(
            buildingType, slotIndex, currentYear, currentMonth
        )

        if (!txResult.success) {
            val appError = txResult.error
                ?: AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
            return DomainResult.Failure(appError)
        }

        DomainLog.d(TAG, "Production completed: ${buildingType.name}[$slotIndex]")
        return DomainResult.Success(
            txResult.outcome
                ?: ProductionOutcome.Failure("outcome data missing")
        )
    }

    suspend fun completeProductionByBuildingIdAtomic(
        buildingId: String,
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int
    ): DomainResult<ProductionOutcome> {
        DomainLog.d(TAG, "Completing production by buildingId: $buildingId[$slotIndex]")

        val txResult = transactionManager.executeCompleteProductionByBuildingId(
            buildingId, slotIndex, currentYear, currentMonth
        )

        if (!txResult.success) {
            val appError = txResult.error
                ?: AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
            return DomainResult.Failure(appError)
        }

        DomainLog.d(TAG, "Production completed: $buildingId[$slotIndex]")
        return DomainResult.Success(
            txResult.outcome
                ?: ProductionOutcome.Failure("outcome data missing")
        )
    }

    suspend fun resetSlotAtomic(
        buildingType: BuildingType,
        slotIndex: Int
    ): DomainResult<ProductionSlot> {
        DomainLog.d(TAG, "Resetting slot: ${buildingType.name}[$slotIndex]")

        val txResult = transactionManager.executeResetSlot(buildingType, slotIndex)

        return if (txResult.success) {
            txResult.slot?.let { DomainResult.Success(it) }
                ?: DomainResult.Failure(
                    AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
                )
        } else {
            DomainResult.Failure(
                txResult.error
                    ?: AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
            )
        }
    }

    suspend fun resetSlotByBuildingIdAtomic(
        buildingId: String,
        slotIndex: Int
    ): DomainResult<ProductionSlot> {
        DomainLog.d(TAG, "Resetting slot by buildingId: $buildingId[$slotIndex]")

        val slot = repository.getSlotByBuildingId(buildingId, slotIndex)
            ?: return DomainResult.Failure(
                AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
            )

        return resetSlotAtomic(slot.buildingType, slotIndex)
    }
    
    fun updateSlot(slot: ProductionSlot) {
        DomainLog.d(TAG, "Direct slot update (deprecated): ${slot.buildingType.name}[${slot.slotIndex}]")
    }
    
    fun updateSlots(newSlots: List<ProductionSlot>) {
        DomainLog.d(TAG, "Direct slots update (deprecated): ${newSlots.size} slots")
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
