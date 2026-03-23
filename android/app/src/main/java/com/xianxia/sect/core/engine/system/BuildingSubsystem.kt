package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    }
    
    fun loadBuildingData(
        buildingSlots: List<BuildingSlot>,
        alchemySlots: List<AlchemySlot>,
        forgeSlots: List<BuildingSlot> = emptyList()
    ) {
        StateFlowListUtils.setList(_buildingSlots, buildingSlots)
        StateFlowListUtils.setList(_alchemySlots, alchemySlots)
        StateFlowListUtils.setList(_forgeSlots, forgeSlots)
    }
    
    fun getBuildingSlots(): List<BuildingSlot> = _buildingSlots.value
    
    fun getBuildingSlotsByBuilding(buildingId: String): List<BuildingSlot> = 
        _buildingSlots.value.filter { it.buildingId == buildingId }
    
    fun getBuildingSlotById(slotId: String): BuildingSlot? = 
        StateFlowListUtils.findItemById(_buildingSlots, slotId) { it.id }
    
    fun updateBuildingSlots(transform: (List<BuildingSlot>) -> List<BuildingSlot>) {
        _buildingSlots.value = transform(_buildingSlots.value)
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
        _alchemySlots.value = transform(_alchemySlots.value)
    }
    
    fun updateAlchemySlot(slotIndex: Int, transform: (AlchemySlot) -> AlchemySlot): Boolean =
        StateFlowListUtils.updateItem(_alchemySlots, { it.slotIndex == slotIndex }, transform)
    
    fun initializeAlchemySlots(count: Int = 3) {
        StateFlowListUtils.setList(_alchemySlots, (0 until count).map { index ->
            AlchemySlot(slotIndex = index)
        })
    }
    
    // Forge
    
    fun getForgeSlots(): List<BuildingSlot> = _forgeSlots.value
    
    fun getForgeSlotByIndex(index: Int): BuildingSlot? = 
        _forgeSlots.value.find { it.slotIndex == index }
    
    fun updateForgeSlots(transform: (List<BuildingSlot>) -> List<BuildingSlot>) {
        _forgeSlots.value = transform(_forgeSlots.value)
    }
    
    fun updateForgeSlot(slotIndex: Int, transform: (BuildingSlot) -> BuildingSlot): Boolean =
        StateFlowListUtils.updateItem(_forgeSlots, { it.slotIndex == slotIndex }, transform)
    
    fun initializeForgeSlots(count: Int = 2) {
        StateFlowListUtils.setList(_forgeSlots, (0 until count).map { index ->
            BuildingSlot(
                id = java.util.UUID.randomUUID().toString(),
                buildingId = "forge",
                slotIndex = index
            )
        })
    }
}
