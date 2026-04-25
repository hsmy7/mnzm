package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionOutcome
import com.xianxia.sect.core.model.production.ProductionParams
import com.xianxia.sect.core.model.production.ProductionResult
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.SlotStateMachine
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.transaction.ProductionTransactionManager
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ## 唯一权威的生产/锻造子系统入口
 *
 * **职责范围：**
 * - 统一管理所有建筑类型的生产槽位（[ProductionSlot]），包括：
 *   - 炼丹 (BuildingType.ALCHEMY)
 *   - 锻造 (BuildingType.FORGE)
 *   - 阵法 (BuildingType.ARRAY)
 *   - 符箓 (BuildingType.TALISMAN)
 *   - 其他可扩展的建筑类型
 *
 * **架构优势：**
 *
 * | 特性 | BuildingSubsystem (本类) |
 * |------|--------------------------|
 * | 数据源 | ProductionSlotRepository (统一) |
 * | 类型系统 | 统一使用 ProductionSlot |
 * | 事务管理 | ProductionTransactionManager |
 * | DI 注入 | @Singleton + @Inject |
 *
 * **核心依赖：**
 * - [ProductionSlotRepository]: 持久化存储与状态流
 * - [BuildingConfigService]: 建筑配置（槽数量、配方等）
 * - [ProductionTransactionManager]: 原子性事务操作
 *
 * **关键 API：**
 * - [startProduction]: 启动生产任务
 * - [completeProduction]: 完成生产任务
 * - [assignDiscipleToSlot]: 分配弟子到槽位
 * - [productionSlots]: 所有生产槽位的 StateFlow
 * - [workingSlots]/[completedSlots]/[idleSlots]: 按状态分类的槽位流
 *
 */
@SystemPriority(order = 100)
@Singleton
class BuildingSubsystem @Inject constructor(
    private val repository: ProductionSlotRepository,
    private val configService: BuildingConfigService,
    private val transactionManager: ProductionTransactionManager
) : GameSystem {

    companion object {
        private const val TAG = "BuildingSubsystem"
        const val SYSTEM_NAME = "BuildingSubsystem"
    }

    val productionSlots: StateFlow<List<ProductionSlot>> = repository.slots
    val workingSlots: StateFlow<List<ProductionSlot>> = repository.workingSlots
    val completedSlots: StateFlow<List<ProductionSlot>> = repository.completedSlots
    val idleSlots: StateFlow<List<ProductionSlot>> = repository.idleSlots

    override val systemName: String = SYSTEM_NAME

    override fun initialize() {
        Log.d(TAG, "BuildingSubsystem initialized (sync)")
    }

    override fun release() {
        Log.d(TAG, "BuildingSubsystem released")
    }

    override suspend fun clearForSlot(slotId: Int) {
        repository.clear(slotId)
    }

    suspend fun initializeAsync() {
        repository.initialize()
        Log.d(TAG, "BuildingSubsystem initialized (async)")
    }

    suspend fun loadProductionSlots(slots: List<ProductionSlot>) {
        repository.loadSlots(slots)
    }

    fun getProductionSlots(): List<ProductionSlot> = repository.getSlots()

    fun getProductionSlotsByType(buildingType: BuildingType): List<ProductionSlot> =
        repository.getSlotsByType(buildingType)

    fun getProductionSlotsByBuildingId(buildingId: String): List<ProductionSlot> =
        repository.getSlotsByBuildingId(buildingId)

    fun getProductionSlotByIndex(buildingType: BuildingType, slotIndex: Int): ProductionSlot? =
        repository.getSlotByIndex(buildingType, slotIndex)

    fun getProductionSlotByBuildingId(buildingId: String, slotIndex: Int): ProductionSlot? =
        repository.getSlotByBuildingId(buildingId, slotIndex)

    fun getWorkingSlots(): List<ProductionSlot> = repository.getWorkingSlots()

    fun getCompletedSlots(): List<ProductionSlot> = repository.getCompletedSlots()

    fun getFinishedSlots(currentYear: Int, currentMonth: Int): List<ProductionSlot> =
        repository.getFinishedSlots(currentYear, currentMonth)

    fun getSlotById(slotId: String): ProductionSlot? = repository.getSlotById(slotId)

    suspend fun updateProductionSlot(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> = repository.updateSlot(buildingType, slotIndex, transform)

    suspend fun initializeProductionSlots(buildingType: BuildingType) {
        repository.initializeSlotsForType(buildingType)
    }

    suspend fun initializeAllProductionSlots(slotId: Int) {
        repository.initializeAllSlots(slotId)
    }

    suspend fun assignDiscipleToSlot(
        buildingType: BuildingType,
        slotIndex: Int,
        discipleId: String,
        discipleName: String
    ): Boolean {
        val result = transactionManager.executeAssignDisciple(buildingType, slotIndex, discipleId, discipleName)
        return result.success
    }

    suspend fun removeDiscipleFromSlot(buildingType: BuildingType, slotIndex: Int): Boolean {
        val result = transactionManager.executeRemoveDisciple(buildingType, slotIndex)
        return result.success
    }

    suspend fun startProduction(params: ProductionParams): ProductionResult {
        val txResult = transactionManager.executeStartProduction(
            buildingType = params.buildingType,
            slotIndex = params.slotIndex,
            recipeId = params.recipe.id,
            recipeName = params.recipe.name,
            duration = params.recipe.duration,
            currentYear = params.gameTime.year,
            currentMonth = params.gameTime.month,
            discipleId = params.disciple?.id,
            discipleName = params.disciple?.name ?: "",
            successRate = params.recipe.successRate,
            materials = params.materials,
            availableMaterials = params.availableMaterials,
            outputItemId = params.recipe.outputItemId,
            outputItemName = params.recipe.outputItemName,
            outputItemRarity = params.recipe.outputItemRarity
        )

        return if (txResult.success) {
            txResult.slot?.let { ProductionResult.success(it, txResult.outcome) }
                ?: ProductionResult.failure(ProductionResult.ProductionError.UnknownError("Transaction succeeded but slot data is missing"))
        } else {
            ProductionResult.failure(mapTransactionError(txResult.error))
        }
    }

    suspend fun startProduction(
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
    ): Result<ProductionSlot> {
        val params = ProductionParams.create(
            buildingType = buildingType,
            slotIndex = slotIndex,
            recipeId = recipeId,
            recipeName = recipeName,
            duration = duration,
            currentYear = currentYear,
            currentMonth = currentMonth,
            discipleId = discipleId,
            discipleName = discipleName,
            successRate = successRate,
            materials = materials,
            availableMaterials = availableMaterials,
            outputItemId = outputItemId,
            outputItemName = outputItemName,
            outputItemRarity = outputItemRarity
        )

        val result = startProduction(params)
        return if (result.success) {
            result.slot?.let { Result.success(it) }
                ?: Result.failure(Exception("Production succeeded but slot data is missing"))
        } else {
            Result.failure(Exception(result.error?.toString() ?: "Unknown error"))
        }
    }

    suspend fun completeProduction(
        buildingType: BuildingType,
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int
    ): Result<ProductionSlot> {
        val txResult = transactionManager.executeCompleteProduction(buildingType, slotIndex, currentYear, currentMonth)

        return if (txResult.success) {
            txResult.slot?.let { Result.success(it) }
                ?: Result.failure(Exception("Transaction succeeded but slot data is missing"))
        } else {
            Result.failure(Exception(txResult.error?.toString() ?: "Unknown error"))
        }
    }

    suspend fun resetSlot(buildingType: BuildingType, slotIndex: Int): Result<ProductionSlot> {
        val txResult = transactionManager.executeResetSlot(buildingType, slotIndex)

        return if (txResult.success) {
            txResult.slot?.let { Result.success(it) }
                ?: Result.failure(Exception("Transaction succeeded but slot data is missing"))
        } else {
            Result.failure(Exception(txResult.error?.toString() ?: "Unknown error"))
        }
    }

    suspend fun addSlot(slot: ProductionSlot): Result<ProductionSlot> {
        return repository.addSlot(slot)
    }

    suspend fun removeSlot(slotId: String): Boolean {
        return repository.removeSlot(slotId).getOrDefault(false)
    }

    suspend fun syncToDatabase() {
        repository.syncToDatabase()
    }

    fun getStatistics() = repository.getStatistics()
    
    fun getLockStatistics() = repository.getLockStatistics()

    private fun mapTransactionError(error: com.xianxia.sect.core.transaction.ProductionTransactionError?): ProductionResult.ProductionError {
        if (error == null) return ProductionResult.ProductionError.UnknownError("Unknown error")
        
        return when (error) {
            is com.xianxia.sect.core.transaction.ProductionTransactionError.SlotNotFound ->
                ProductionResult.ProductionError.SlotNotFound(error.buildingType, error.slotIndex)
            is com.xianxia.sect.core.transaction.ProductionTransactionError.SlotBusy ->
                ProductionResult.ProductionError.SlotBusy(error.slotIndex, error.message)
            is com.xianxia.sect.core.transaction.ProductionTransactionError.InsufficientMaterials ->
                ProductionResult.ProductionError.InsufficientMaterials(error.missing)
            is com.xianxia.sect.core.transaction.ProductionTransactionError.InvalidStateTransition ->
                ProductionResult.ProductionError.InvalidStateTransition(error.from, error.to, error.message)
            is com.xianxia.sect.core.transaction.ProductionTransactionError.ProductionNotReady ->
                ProductionResult.ProductionError.ProductionNotReady(error.remainingTime)
            is com.xianxia.sect.core.transaction.ProductionTransactionError.DatabaseError ->
                ProductionResult.ProductionError.DatabaseError(error.message)
            is com.xianxia.sect.core.transaction.ProductionTransactionError.UnknownError ->
                ProductionResult.ProductionError.UnknownError(error.message)
        }
    }
}
