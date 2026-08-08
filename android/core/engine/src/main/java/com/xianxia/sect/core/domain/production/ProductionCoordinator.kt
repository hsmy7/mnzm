package com.xianxia.sect.core.engine.domain.production

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.MaterialConsumptionLog
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.repository.SlotUpdate
import com.xianxia.sect.core.transaction.ProductionTransactionManager
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
        if (recipe.materials.isEmpty()) {
            return DomainResult.Failure(
                AppError.Domain.Production.InvalidSlot(slotIndex = slotIndex)
            )
        }

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

        // 按 herbId 聚合消耗量，逐 herb 扣减直到满足配方要求
        val newHerbs = buildList {
            val remainingRequired = recipe.materials.toMutableMap()
            for (herb in herbs) {
                var newQty = herb.quantity
                val iter = remainingRequired.iterator()
                while (iter.hasNext()) {
                    val (herbId, requiredAmount) = iter.next()
                    val herbData = HerbDatabase.getHerbById(herbId) ?: continue
                    if (herbData.name != herb.name || herbData.rarity != herb.rarity) continue
                    val consume = minOf(newQty, requiredAmount)
                    newQty -= consume
                    val remaining = requiredAmount - consume
                    if (remaining <= 0) iter.remove()
                    else remainingRequired[herbId] = remaining
                }
                if (newQty > 0) add(herb.copy(quantity = newQty))
            }
        }

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
        // 限制日志条目数，防止无界增长
        val MAX_LOG_ENTRIES = 1000
        _consumptionLogs.value = (_consumptionLogs.value + consumptionLog).takeLast(MAX_LOG_ENTRIES)

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

        // 按 materialId 聚合消耗量，逐 material 扣减直到满足配方要求
        val newMaterials = buildList {
            val remainingRequired = recipe.materials.toMutableMap()
            for (material in materials) {
                var newQty = material.quantity
                val iter = remainingRequired.iterator()
                while (iter.hasNext()) {
                    val (matId, requiredAmount) = iter.next()
                    val matData = BeastMaterialDatabase.getMaterialById(matId) ?: continue
                    if (matData.name != material.name || matData.rarity != material.rarity) continue
                    val consume = minOf(newQty, requiredAmount)
                    newQty -= consume
                    val remaining = requiredAmount - consume
                    if (remaining <= 0) iter.remove()
                    else remainingRequired[matId] = remaining
                }
                if (newQty > 0) add(material.copy(quantity = newQty))
            }
        }

        DomainLog.d(TAG, "Forging started successfully: $buildingId[$slotIndex]")

        val forgeConsumptionLog = MaterialConsumptionLog(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            slotIndex = slotIndex,
            recipeId = recipeId,
            recipeName = recipe.name,
            materials = recipe.materials,
            reason = "锻造开始",
            buildingId = buildingId
        )
        val MAX_LOG_ENTRIES = 1000
        _consumptionLogs.value = (_consumptionLogs.value + forgeConsumptionLog).takeLast(MAX_LOG_ENTRIES)

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

    /**
     * 清理弟子在 Room 生产槽 Repository 中的占用（同步挂起版，供死亡/叛逃等
     * 必须立即生效的关键路径使用，在 IO 线程执行）。
     *
     * GameData.productionSlots 只是镜像——存档序列化/生产结算/gate 重建均以 Repository
     * 为准（SaveFacadeImpl/BootSequenceController/自愈）。各分配入口事务内只清镜像，
     * 事务成功后必须同步清 Repository，否则残留占用会经月度自动重启/读档自愈复活
     * （双槽分叉根因：弟子自动脱离槽位 + 被自动任命其他槽位）。
     *
     * @param discipleId 要清除的弟子 ID
     */
    suspend fun clearDiscipleFromRepository(discipleId: String) {
        repository.getSlots()
            .filter { it.assignedDiscipleId == discipleId }
            .forEach { slot ->
                repository.updateSlot(slot.buildingType, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
    }

    /**
     * 批量清理多个弟子在 Room 生产槽 Repository 中的占用（同步挂起版）。
     *
     * 批量死亡年（K 个死者）聚合为单次 [ProductionSlotRepository.batchUpdate]
     *（内部单次 `dao.updateAll`），替代 K 次 [clearDiscipleFromRepository] 的
     * K×M 次 `dao.update`（M = 每死者占用槽位数）。语义与单弟子版一致：
     * 仅清空 assignedDiscipleId/assignedDiscipleName，不改槽位状态。
     *
     * @param discipleIds 要清除的弟子 ID 列表（自动去重后过滤槽位）
     */
    suspend fun clearDisciplesFromRepository(discipleIds: List<String>) {
        if (discipleIds.isEmpty()) return
        val idSet = discipleIds.toSet()
        val updates = repository.getSlots()
            .filter { it.assignedDiscipleId in idSet }
            .map { slot ->
                SlotUpdate(slot.buildingType, slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        if (updates.isEmpty()) return
        repository.batchUpdate(updates)
    }

    /**
     * 清理弟子在 Room 生产槽 Repository 中的占用（fire-and-forget 版，引擎线程串行安全）。
     *
     * @param scope 执行清理的协程作用域（引擎 scope，保证串行安全）
     * @param discipleId 要清除的弟子 ID
     */
    // catch Exception：fire-and-forget 清理防御——Room 瞬时故障不应中断调用方流程
    @Suppress("TooGenericExceptionCaught")
    fun clearDiscipleInRepository(scope: CoroutineScope, discipleId: String): Job = scope.launch {
        try {
            clearDiscipleFromRepository(discipleId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainLog.w(TAG, "clearDiscipleInRepository 失败 id=$discipleId", e)
        }
    }
}

object ProductionCoordinatorFactory {
    fun create(
        repository: ProductionSlotRepository,
        transactionManager: ProductionTransactionManager
    ): ProductionCoordinator = ProductionCoordinator(repository, transactionManager)
}
