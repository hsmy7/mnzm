package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.engine.HerbGardenSystem
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.PlantSlotData
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HerbGardenSubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "HerbGardenSubsystem"
        const val SYSTEM_NAME = "HerbGardenSubsystem"
    }
    
    private val _plantSlots = MutableStateFlow<List<PlantSlotData>>(emptyList())
    val plantSlots: StateFlow<List<PlantSlotData>> = _plantSlots.asStateFlow()
    
    private val _herbs = MutableStateFlow<List<Herb>>(emptyList())
    val herbs: StateFlow<List<Herb>> = _herbs.asStateFlow()
    
    private val _seeds = MutableStateFlow<List<Seed>>(emptyList())
    val seeds: StateFlow<List<Seed>> = _seeds.asStateFlow()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "HerbGardenSubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "HerbGardenSubsystem released")
    }
    
    override suspend fun clear() {
        StateFlowListUtils.clearList(_plantSlots)
        StateFlowListUtils.clearList(_herbs)
        StateFlowListUtils.clearList(_seeds)
    }
    
    fun loadHerbGardenData(
        plantSlots: List<PlantSlotData>,
        herbs: List<Herb>,
        seeds: List<Seed>
    ) {
        StateFlowListUtils.setList(_plantSlots, plantSlots)
        StateFlowListUtils.setList(_herbs, herbs)
        StateFlowListUtils.setList(_seeds, seeds)
    }
    
    fun getPlantSlots(): List<PlantSlotData> = _plantSlots.value
    
    fun getPlantSlot(index: Int): PlantSlotData? =
        _plantSlots.value.find { it.index == index }
    
    fun updatePlantSlots(transform: (List<PlantSlotData>) -> List<PlantSlotData>) {
        _plantSlots.value = transform(_plantSlots.value)
    }
    
    fun updatePlantSlot(index: Int, transform: (PlantSlotData) -> PlantSlotData): Boolean =
        StateFlowListUtils.updateItem(_plantSlots, { it.index == index }, transform)
    
    fun initializePlantSlots(count: Int = HerbGardenSystem.MAX_PLANT_SLOTS) {
        val currentSlots = _plantSlots.value
        val needsInitialization = currentSlots.isEmpty() ||
                currentSlots.size < count ||
                (0 until count).any { idx -> currentSlots.none { it.index == idx } }
        
        if (needsInitialization) {
            val initializedSlots = (0 until count).map { idx ->
                currentSlots.find { it.index == idx } ?: PlantSlotData(index = idx)
            }
            StateFlowListUtils.setList(_plantSlots, initializedSlots)
        }
    }
    
    data class PlantingResult(
        val success: Boolean,
        val updatedSlots: List<PlantSlotData>,
        val updatedSeeds: List<Seed>,
        val eventMessage: String?,
        val eventType: String
    )
    
    fun executePlantingTransaction(
        slotIndex: Int,
        seedId: String,
        gameYear: Int,
        gameMonth: Int
    ): PlantingResult {
        val slots = _plantSlots.value
        val seeds = _seeds.value
        
        val seedItemIndex = seeds.indexOfFirst { it.id == seedId }
        if (seedItemIndex < 0 || seeds[seedItemIndex].quantity <= 0) {
            return PlantingResult(
                success = false,
                updatedSlots = slots,
                updatedSeeds = seeds,
                eventMessage = "仓库中没有该种子",
                eventType = "WARNING"
            )
        }
        
        val seedItem = seeds[seedItemIndex]
        val mutableSeeds = seeds.toMutableList()
        if (seedItem.quantity > 1) {
            mutableSeeds[seedItemIndex] = seedItem.copy(quantity = seedItem.quantity - 1)
        } else {
            mutableSeeds.removeAt(seedItemIndex)
        }
        
        val newSlot = PlantSlotData(
            index = slotIndex,
            status = "growing",
            seedId = seedId,
            seedName = seedItem.name,
            startYear = gameYear,
            startMonth = gameMonth,
            growTime = seedItem.growTime,
            harvestAmount = seedItem.yield
        )
        
        val updatedSlots = slots.toMutableList()
        val existingIndex = updatedSlots.indexOfFirst { it.index == slotIndex }
        if (existingIndex >= 0) {
            updatedSlots[existingIndex] = newSlot
        } else {
            updatedSlots.add(newSlot)
        }
        
        return PlantingResult(
            success = true,
            updatedSlots = updatedSlots.toList(),
            updatedSeeds = mutableSeeds.toList(),
            eventMessage = "${seedItem.name}种植成功，预计${seedItem.growTime}个月后成熟",
            eventType = "SUCCESS"
        )
    }
}
