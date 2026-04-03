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
    
    data class HarvestResult(
        val success: Boolean,
        val herbName: String?,
        val yieldAmount: Int,
        val updatedSlots: List<PlantSlotData>,
        val updatedHerbs: List<Herb>
    )
    
    fun executeHarvestTransaction(
        slotIndex: Int,
        yieldBonus: Double = 0.0
    ): HarvestResult {
        val slots = _plantSlots.value
        
        if (slotIndex < 0 || slotIndex >= slots.size) {
            return HarvestResult(
                success = false,
                herbName = null,
                yieldAmount = 0,
                updatedSlots = slots,
                updatedHerbs = _herbs.value
            )
        }
        
        val slot = slots[slotIndex]
        
        if (slot.status != "mature") {
            return HarvestResult(
                success = false,
                herbName = null,
                yieldAmount = 0,
                updatedSlots = slots,
                updatedHerbs = _herbs.value
            )
        }
        
        val herbId = slot.harvestHerbId ?: slot.seedId?.let {
            com.xianxia.sect.core.data.HerbDatabase.getHerbIdFromSeedId(it)
        }
        if (herbId == null) {
            return HarvestResult(
                success = false,
                herbName = null,
                yieldAmount = 0,
                updatedSlots = slots,
                updatedHerbs = _herbs.value
            )
        }
        
        val herb = com.xianxia.sect.core.data.HerbDatabase.getHerbById(herbId)
            ?: return HarvestResult(
                success = false,
                herbName = null,
                yieldAmount = 0,
                updatedSlots = slots,
                updatedHerbs = _herbs.value
            )
        
        val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.harvestAmount, yieldBonus)
        
        val currentHerbs = _herbs.value.toMutableList()
        val existingHerbIndex = currentHerbs.indexOfFirst { 
            it.name == herb.name && it.rarity == herb.rarity 
        }
        
        if (existingHerbIndex >= 0) {
            currentHerbs[existingHerbIndex] = currentHerbs[existingHerbIndex].copy(
                quantity = currentHerbs[existingHerbIndex].quantity + actualYield
            )
        } else {
            currentHerbs.add(Herb(
                id = java.util.UUID.randomUUID().toString(),
                name = herb.name,
                rarity = herb.rarity,
                description = herb.description,
                category = herb.category,
                quantity = actualYield
            ))
        }
        
        val updatedSlots = slots.toMutableList()
        updatedSlots[slotIndex] = PlantSlotData(index = slotIndex)
        
        return HarvestResult(
            success = true,
            herbName = herb.name,
            yieldAmount = actualYield,
            updatedSlots = updatedSlots.toList(),
            updatedHerbs = currentHerbs.toList()
        )
    }
    
    data class MonthlyGrowthResult(
        val updatedSlots: List<PlantSlotData>,
        val updatedHerbs: List<Herb>,
        val events: List<Pair<String, String>>
    )
    
    fun processMonthlyGrowth(year: Int, month: Int): List<Pair<String, String>> {
        val result = executeMonthlyGrowthTransaction(year, month)
        _plantSlots.value = result.updatedSlots
        _herbs.value = result.updatedHerbs
        return result.events
    }
    
    fun executeMonthlyGrowthTransaction(year: Int, month: Int): MonthlyGrowthResult {
        val events = mutableListOf<Pair<String, String>>()
        
        val mutableHerbs = _herbs.value.toMutableList()
        
        val updatedSlots = _plantSlots.value.map { slot ->
            if (!slot.isGrowing) return@map slot
            if (!slot.isFinished(year, month)) return@map slot
            
            val seedId = slot.seedId ?: return@map PlantSlotData(index = slot.index)
            val seed = com.xianxia.sect.core.data.HerbDatabase.getSeedById(seedId)
                ?: return@map PlantSlotData(index = slot.index)
            val herbId = com.xianxia.sect.core.data.HerbDatabase.getHerbIdFromSeedId(seedId)
                ?: return@map PlantSlotData(index = slot.index)
            val herb = com.xianxia.sect.core.data.HerbDatabase.getHerbById(herbId)
                ?: return@map PlantSlotData(index = slot.index)
            
            val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.harvestAmount, 0.0)
            
            val existingHerbIndex = mutableHerbs.indexOfFirst { 
                it.name == herb.name && it.rarity == herb.rarity 
            }
            
            if (existingHerbIndex >= 0) {
                mutableHerbs[existingHerbIndex] = mutableHerbs[existingHerbIndex].copy(
                    quantity = mutableHerbs[existingHerbIndex].quantity + actualYield
                )
            } else {
                mutableHerbs.add(Herb(
                    id = java.util.UUID.randomUUID().toString(),
                    name = herb.name,
                    rarity = herb.rarity,
                    description = herb.description,
                    category = herb.category,
                    quantity = actualYield
                ))
            }
            
            events.add("${herb.name}已成熟，收获${actualYield}个" to "SUCCESS")
            PlantSlotData(index = slot.index)
        }
        
        return MonthlyGrowthResult(
            updatedSlots = updatedSlots,
            updatedHerbs = mutableHerbs.toList(),
            events = events.toList()
        )
    }
}
