package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.model.production.*
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as mutexWithLock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BuildingSubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "BuildingSubsystem"
        const val SYSTEM_NAME = "BuildingSubsystem"
    }
    
    private val _buildingSlots = MutableStateFlow<List<BuildingSlot>>(emptyList())
    val buildingSlots: StateFlow<List<BuildingSlot>> = _buildingSlots.asStateFlow()
    internal val mutableBuildingSlots: MutableStateFlow<List<BuildingSlot>> get() = _buildingSlots
    
    private val _alchemySlots = MutableStateFlow<List<AlchemySlot>>(emptyList())
    val alchemySlots: StateFlow<List<AlchemySlot>> = _alchemySlots.asStateFlow()
    internal val mutableAlchemySlots: MutableStateFlow<List<AlchemySlot>> get() = _alchemySlots
    
    private val _forgeSlots = MutableStateFlow<List<BuildingSlot>>(emptyList())
    val forgeSlots: StateFlow<List<BuildingSlot>> = _forgeSlots.asStateFlow()
    internal val mutableForgeSlots: MutableStateFlow<List<BuildingSlot>> get() = _forgeSlots
    
    private val _productionSlots = MutableStateFlow<List<ProductionSlot>>(emptyList())
    val productionSlots: StateFlow<List<ProductionSlot>> = _productionSlots.asStateFlow()
    
    private val updateMutex = Mutex()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "BuildingSubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "BuildingSubsystem released")
    }
    
    override fun clear() {
        StateFlowListUtils.clearList(_buildingSlots)
        StateFlowListUtils.clearList(_alchemySlots)
        StateFlowListUtils.clearList(_forgeSlots)
        StateFlowListUtils.clearList(_productionSlots)
    }
    
    fun loadBuildingData(
        buildingSlots: List<BuildingSlot>,
        alchemySlots: List<AlchemySlot>,
        forgeSlots: List<BuildingSlot> = emptyList(),
        productionSlots: List<ProductionSlot> = emptyList()
    ) {
        StateFlowListUtils.setList(_buildingSlots, buildingSlots)
        StateFlowListUtils.setList(_alchemySlots, alchemySlots)
        StateFlowListUtils.setList(_forgeSlots, forgeSlots)
        StateFlowListUtils.setList(_productionSlots, productionSlots)
    }
    
    fun getBuildingSlots(): List<BuildingSlot> = _buildingSlots.value
    
    fun getBuildingSlotsByBuilding(buildingId: String): List<BuildingSlot> = 
        _buildingSlots.value.filter { it.buildingId == buildingId }
    
    fun getBuildingSlotById(slotId: String): BuildingSlot? = 
        StateFlowListUtils.findItemById(_buildingSlots, slotId) { it.id }
    
    fun updateBuildingSlots(transform: (List<BuildingSlot>) -> List<BuildingSlot>) {
        StateFlowListUtils.globalLock.withLock {
            _buildingSlots.value = transform(_buildingSlots.value)
        }
    }
    
    suspend fun updateBuildingSlotsAtomic(transform: suspend (List<BuildingSlot>) -> List<BuildingSlot>) {
        updateMutex.mutexWithLock {
            _buildingSlots.value = transform(_buildingSlots.value)
        }
    }
    
    fun updateBuildingSlot(slotId: String, transform: (BuildingSlot) -> BuildingSlot): Boolean =
        StateFlowListUtils.updateItemById(_buildingSlots, slotId, { it.id }, transform)
    
    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String): Boolean {
        return StateFlowListUtils.updateItem(_buildingSlots, { slot ->
            slot.buildingId == buildingId && slot.slotIndex == slotIndex
        }) { it.copy(discipleId = discipleId) }
    }
    
    fun removeDiscipleFromBuilding(buildingId: String, slotIndex: Int): Boolean {
        return StateFlowListUtils.updateItem(_buildingSlots, { slot ->
            slot.buildingId == buildingId && slot.slotIndex == slotIndex
        }) { it.copy(discipleId = null) }
    }
    
    fun addBuildingSlot(slot: BuildingSlot) = StateFlowListUtils.addItem(_buildingSlots, slot)
    
    fun removeBuildingSlot(slotId: String): Boolean = 
        StateFlowListUtils.removeItemById(_buildingSlots, slotId, getId = { it.id })
    
    // Alchemy
    
    fun getAlchemySlots(): List<AlchemySlot> = _alchemySlots.value
    
    fun getAlchemySlotByIndex(index: Int): AlchemySlot? = 
        _alchemySlots.value.find { it.slotIndex == index }
    
    fun updateAlchemySlots(transform: (List<AlchemySlot>) -> List<AlchemySlot>) {
        StateFlowListUtils.globalLock.withLock {
            _alchemySlots.value = transform(_alchemySlots.value)
        }
    }
    
    suspend fun updateAlchemySlotsAtomic(transform: suspend (List<AlchemySlot>) -> List<AlchemySlot>) {
        updateMutex.mutexWithLock {
            _alchemySlots.value = transform(_alchemySlots.value)
        }
    }
    
    fun updateAlchemySlot(slotIndex: Int, transform: (AlchemySlot) -> AlchemySlot): Boolean =
        StateFlowListUtils.updateItem(_alchemySlots, { it.slotIndex == slotIndex }, transform)
    
    fun initializeAlchemySlots(count: Int = 3) {
        val configCount = BuildingConfigs.getSlotCount(BuildingType.ALCHEMY)
        val actualCount = if (count > 0) count else configCount
        StateFlowListUtils.setList(_alchemySlots, (0 until actualCount).map { index ->
            AlchemySlot(slotIndex = index)
        })
    }
    
    // Forge
    
    fun getForgeSlots(): List<BuildingSlot> = _forgeSlots.value
    
    fun getForgeSlotByIndex(index: Int): BuildingSlot? = 
        _forgeSlots.value.find { it.slotIndex == index }
    
    fun updateForgeSlots(transform: (List<BuildingSlot>) -> List<BuildingSlot>) {
        StateFlowListUtils.globalLock.withLock {
            _forgeSlots.value = transform(_forgeSlots.value)
        }
    }
    
    suspend fun updateForgeSlotsAtomic(transform: suspend (List<BuildingSlot>) -> List<BuildingSlot>) {
        updateMutex.mutexWithLock {
            _forgeSlots.value = transform(_forgeSlots.value)
        }
    }
    
    fun updateForgeSlot(slotIndex: Int, transform: (BuildingSlot) -> BuildingSlot): Boolean =
        StateFlowListUtils.updateItem(_forgeSlots, { it.slotIndex == slotIndex }, transform)
    
    fun initializeForgeSlots(count: Int = 2) {
        val configCount = BuildingConfigs.getSlotCount(BuildingType.FORGE)
        val actualCount = if (count > 0) count else configCount
        StateFlowListUtils.setList(_forgeSlots, (0 until actualCount).map { index ->
            BuildingSlot(
                id = java.util.UUID.randomUUID().toString(),
                buildingId = "forge",
                slotIndex = index
            )
        })
    }
    
    // Production Slots (统一模型)
    
    fun getProductionSlots(): List<ProductionSlot> = _productionSlots.value
    
    fun getProductionSlotsByType(buildingType: BuildingType): List<ProductionSlot> =
        _productionSlots.value.filter { it.buildingType == buildingType }
    
    fun getProductionSlotByIndex(buildingType: BuildingType, slotIndex: Int): ProductionSlot? =
        _productionSlots.value.find { it.buildingType == buildingType && it.slotIndex == slotIndex }
    
    fun updateProductionSlots(transform: (List<ProductionSlot>) -> List<ProductionSlot>) {
        StateFlowListUtils.globalLock.withLock {
            _productionSlots.value = transform(_productionSlots.value)
        }
    }
    
    suspend fun updateProductionSlotsAtomic(transform: suspend (List<ProductionSlot>) -> List<ProductionSlot>) {
        updateMutex.mutexWithLock {
            _productionSlots.value = transform(_productionSlots.value)
        }
    }
    
    fun updateProductionSlot(
        buildingType: BuildingType,
        slotIndex: Int,
        transform: (ProductionSlot) -> ProductionSlot
    ): Result<ProductionSlot> {
        val currentSlot = getProductionSlotByIndex(buildingType, slotIndex)
            ?: return Result.failure(IllegalArgumentException("Slot not found"))
        
        val newSlot = transform(currentSlot)
        
        if (currentSlot.status != newSlot.status) {
            val validation = SlotStateMachine.validateTransition(currentSlot.status, newSlot.status)
            if (validation.isFailure) {
                return Result.failure(validation.exceptionOrNull()!!)
            }
        }
        
        val updated = StateFlowListUtils.updateItem(_productionSlots,
            { it.buildingType == buildingType && it.slotIndex == slotIndex },
            transform
        )
        
        return if (updated) Result.success(newSlot) else Result.failure(IllegalStateException("Update failed"))
    }
    
    fun initializeProductionSlots(buildingType: BuildingType) {
        val config = BuildingConfigs.getConfig(buildingType)
        val currentSlots = _productionSlots.value.filter { it.buildingType != buildingType }
        val newSlots = (0 until config.slotCount).map { idx ->
            ProductionSlot(
                id = java.util.UUID.randomUUID().toString(),
                slotIndex = idx,
                buildingType = buildingType,
                status = ProductionSlotStatus.IDLE
            )
        }
        StateFlowListUtils.setList(_productionSlots, currentSlots + newSlots)
    }
    
    fun initializeAllProductionSlots() {
        BuildingType.entries.forEach { buildingType ->
            initializeProductionSlots(buildingType)
        }
    }
}
