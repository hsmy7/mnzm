package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.production.*
import com.xianxia.sect.core.util.StateFlowListUtils
import com.xianxia.sect.core.util.atomicUpdateSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已弃用：请使用 [BuildingSubsystem] 作为统一入口。
 *
 * 此子系统与 [BuildingSubsystem] 存在功能重叠，两者都管理 ProductionSlot 状态。
 * 本类维护独立的 [_productionSlots] 内部状态（MutableStateFlow），
 * 与 BuildingSubsystem 通过 ProductionSlotRepository 管理的状态可能不同步。
 *
 * **引用位置（保留不删以避免破坏 SystemManager 注册）：**
 * - [SystemManager] 第25行注入、第47行注册
 * - [CoreModule] 第56行 DI提供、第71行实例化
 *
 * **迁移指南：** 所有生产/锻造相关操作应迁移至 BuildingSubsystem，
 * 其通过 Repository + TransactionManager 提供统一的数据源和事务管理。
 */
@Deprecated(
    message = "已弃用：请使用 BuildingSubsystem 作为统一入口",
    replaceWith = ReplaceWith("BuildingSubsystem", "com.xianxia.sect.core.engine.system.BuildingSubsystem"),
    level = DeprecationLevel.WARNING
)
@Singleton
class ProductionSubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "ProductionSubsystem"
        const val SYSTEM_NAME = "ProductionSubsystem"
    }
    
    private val _productionSlots = MutableStateFlow<List<ProductionSlot>>(emptyList())
    val productionSlots: StateFlow<List<ProductionSlot>> = _productionSlots.asStateFlow()
    
    private val updateMutex = Mutex()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "ProductionSubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "ProductionSubsystem released")
    }
    
    override suspend fun clear() {
        StateFlowListUtils.clearList(_productionSlots)
    }
    
    fun loadProductionData(slots: List<ProductionSlot>) {
        StateFlowListUtils.setList(_productionSlots, slots)
    }
    
    fun getProductionSlots(): List<ProductionSlot> = _productionSlots.value
    
    fun getSlotsByBuildingType(buildingType: BuildingType): List<ProductionSlot> =
        _productionSlots.value.filter { it.buildingType == buildingType }
    
    fun getSlotByIndex(buildingType: BuildingType, slotIndex: Int): ProductionSlot? =
        _productionSlots.value.find { it.buildingType == buildingType && it.slotIndex == slotIndex }
    
    fun getSlotById(slotId: String): ProductionSlot? =
        StateFlowListUtils.findItemById(_productionSlots, slotId) { it.id }
    
    suspend fun updateSlotAtomic(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> = updateMutex.withLock {
        val currentSlot = getSlotByIndex(buildingType, slotIndex)
            ?: return Result.failure(IllegalArgumentException("Slot not found"))
        
        val newSlot = transform(currentSlot)
        
        val statusValidation = SlotStateMachine.validateTransition(
            currentSlot.status, 
            newSlot.status
        )
        
        if (statusValidation.isFailure) {
            return Result.failure(statusValidation.exceptionOrNull() ?: IllegalStateException("Slot state transition validation failed without exception"))
        }
        
        val updated = StateFlowListUtils.updateItem(_productionSlots, 
            { it.buildingType == buildingType && it.slotIndex == slotIndex },
            transform
        )
        
        if (updated) Result.success(newSlot) else Result.failure(IllegalStateException("Update failed"))
    }
    
    fun updateSlotSync(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> {
        synchronized(_productionSlots) {
            val currentSlot = getSlotByIndex(buildingType, slotIndex)
                ?: return Result.failure(IllegalArgumentException("Slot not found"))
            
            val newSlot = transform(currentSlot)
            
            val statusValidation = SlotStateMachine.validateTransition(
                currentSlot.status,
                newSlot.status
            )
            
            if (statusValidation.isFailure) {
                return Result.failure(statusValidation.exceptionOrNull() ?: IllegalStateException("Slot state transition validation failed without exception"))
            }
            
            val updated = StateFlowListUtils.updateItem(_productionSlots,
                { it.buildingType == buildingType && it.slotIndex == slotIndex },
                transform
            )
            
            return if (updated) Result.success(newSlot) else Result.failure(IllegalStateException("Update failed"))
        }
    }
    
    fun updateAllSlotsSync(transform: (List<ProductionSlot>) -> List<ProductionSlot>) {
        _productionSlots.atomicUpdateSync(transform)
    }
    
    fun initializeSlotsForBuilding(buildingType: BuildingType) {
        val config = BuildingConfigs.getConfig(buildingType)
        val currentSlots = _productionSlots.value.filter { it.buildingType == buildingType }
        
        val needsInitialization = currentSlots.size != config.slotCount ||
            (0 until config.slotCount).any { idx -> currentSlots.none { it.slotIndex == idx } }
        
        if (needsInitialization) {
            val allSlots = _productionSlots.value.filter { it.buildingType != buildingType }
            val newSlots = (0 until config.slotCount).map { idx ->
                currentSlots.find { it.slotIndex == idx } ?: ProductionSlot(
                    id = java.util.UUID.randomUUID().toString(),
                    slotIndex = idx,
                    buildingType = buildingType,
                    status = ProductionSlotStatus.IDLE
                )
            }
            StateFlowListUtils.setList(_productionSlots, allSlots + newSlots)
        }
    }
    
    fun addSlot(slot: ProductionSlot) = StateFlowListUtils.addItem(_productionSlots, slot)
    
    fun removeSlot(slotId: String): Boolean =
        StateFlowListUtils.removeItemById(_productionSlots, slotId, getId = { it.id })
    
    fun startProduction(
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
        outputItemId: String?,
        outputItemName: String,
        outputItemRarity: Int
    ): Result<ProductionSlot> {
        return updateSlotSync(buildingType, slotIndex) { slot ->
            SlotStateMachine.startProduction(
                slot = slot,
                recipeId = recipeId,
                recipeName = recipeName,
                duration = duration,
                currentYear = currentYear,
                currentMonth = currentMonth,
                discipleId = discipleId,
                discipleName = discipleName,
                successRate = successRate,
                materials = materials,
                outputItemId = outputItemId,
                outputItemName = outputItemName,
                outputItemRarity = outputItemRarity
            ).getOrThrow()
        }
    }
    
    fun completeProduction(buildingType: BuildingType, slotIndex: Int): Result<ProductionSlot> {
        return updateSlotSync(buildingType, slotIndex) { slot ->
            SlotStateMachine.completeProduction(slot).getOrThrow()
        }
    }
    
    fun resetSlot(buildingType: BuildingType, slotIndex: Int): Result<ProductionSlot> {
        return updateSlotSync(buildingType, slotIndex) { slot ->
            SlotStateMachine.resetSlot(slot).getOrThrow()
        }
    }
}
