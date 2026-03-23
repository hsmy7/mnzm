package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.HerbDatabase
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
    
    override fun clear() {
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
    
    fun initializePlantSlots(count: Int = 3) {
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
    
    fun addHerbToWarehouse(herb: Herb) {
        val currentHerbs = _herbs.value.toMutableList()
        val existingIndex = currentHerbs.indexOfFirst { 
            it.name == herb.name && it.rarity == herb.rarity 
        }
        
        if (existingIndex >= 0) {
            currentHerbs[existingIndex] = currentHerbs[existingIndex].copy(
                quantity = currentHerbs[existingIndex].quantity + herb.quantity
            )
        } else {
            currentHerbs.add(herb)
        }
        
        StateFlowListUtils.setList(_herbs, currentHerbs.toList())
    }
    
    fun addSeedToWarehouse(seed: Seed) {
        val currentSeeds = _seeds.value.toMutableList()
        val existingIndex = currentSeeds.indexOfFirst { 
            it.name == seed.name && it.rarity == seed.rarity
        }
        
        if (existingIndex >= 0) {
            currentSeeds[existingIndex] = currentSeeds[existingIndex].copy(
                quantity = currentSeeds[existingIndex].quantity + seed.quantity
            )
        } else {
            currentSeeds.add(seed)
        }
        
        StateFlowListUtils.setList(_seeds, currentSeeds.toList())
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
        gameMonth: Int,
        elderSpeedBonus: Double = 0.0,
        elderYieldBonus: Double = 0.0,
        elderGrowTimeReduction: Double = 0.0,
        discipleSpeedBonus: Double = 0.0
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
        val seed = HerbDatabase.getSeedByName(seedItem.name)
            ?: return PlantingResult(
                success = false,
                updatedSlots = slots,
                updatedSeeds = seeds,
                eventMessage = "种子数据错误",
                eventType = "WARNING"
            )
        
        val herbId = seed.id.removeSuffix("Seed")
        val herb = HerbDatabase.getHerbById(herbId)
            ?: return PlantingResult(
                success = false,
                updatedSlots = slots,
                updatedSeeds = seeds,
                eventMessage = "草药数据错误",
                eventType = "WARNING"
            )
        
        val mutableSeeds = seeds.toMutableList()
        if (seedItem.quantity > 1) {
            mutableSeeds[seedItemIndex] = seedItem.copy(quantity = seedItem.quantity - 1)
        } else {
            mutableSeeds.removeAt(seedItemIndex)
        }
        
        val baseGrowTime = ForgeRecipeDatabase.getDurationByTier(seed.tier)
        val afterElderBonus = (baseGrowTime * (1.0 - elderGrowTimeReduction)).toInt().coerceAtLeast(1)
        val totalSpeedBonus = discipleSpeedBonus + elderSpeedBonus
        val herbGardenSystem = HerbGardenSystem
        val actualGrowTime = herbGardenSystem.calculateReducedDuration(afterElderBonus, totalSpeedBonus)
        
        val newSlot = PlantSlotData(
            index = slotIndex,
            status = "growing",
            seedId = seed.id,
            seedName = seed.name,
            startYear = gameYear,
            startMonth = gameMonth,
            growTime = actualGrowTime,
            harvestAmount = seed.yield,
            harvestHerbId = herbId
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
            eventMessage = "${seed.name}种植成功，预计${actualGrowTime}个月后成熟",
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
        
        val herbId = slot.harvestHerbId ?: slot.seedId?.removeSuffix("Seed")
        if (herbId == null) {
            return HarvestResult(
                success = false,
                herbName = null,
                yieldAmount = 0,
                updatedSlots = slots,
                updatedHerbs = _herbs.value
            )
        }
        
        val herb = HerbDatabase.getHerbById(herbId)
            ?: return HarvestResult(
                success = false,
                herbName = null,
                yieldAmount = 0,
                updatedSlots = slots,
                updatedHerbs = _herbs.value
            )
        
        val herbGardenSystem = HerbGardenSystem
        val actualYield = herbGardenSystem.calculateIncreasedYield(slot.harvestAmount, yieldBonus)
        
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
    
    fun processMonthlyGrowth(year: Int, month: Int): List<Pair<String, String>> {
        val events = mutableListOf<Pair<String, String>>()
        val herbGardenSystem = HerbGardenSystem
        
        
        val updatedSlots = _plantSlots.value.map { slot ->
            if (!slot.isGrowing) return@map slot
            if (!slot.isFinished(year, month)) return@map slot
            
            
            val seedId = slot.seedId ?: return@map PlantSlotData(index = slot.index)
            val seed = HerbDatabase.getSeedById(seedId) ?: return@map PlantSlotData(index = slot.index)
            val herbId = seedId.removeSuffix("Seed")
            val herb = HerbDatabase.getHerbById(herbId) ?: return@map PlantSlotData(index = slot.index)
            
            val actualYield = herbGardenSystem.calculateIncreasedYield(slot.harvestAmount, 0.0)
            
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
            
            _herbs.value = currentHerbs.toList()
            events.add("${herb.name}已成熟，收获${actualYield}个" to "SUCCESS")
            
            val seedIndex = _seeds.value.indexOfFirst { 
                it.name == seed.name && it.rarity == seed.rarity && it.quantity > 0 
            }
            
            if (seedIndex >= 0) {
                val currentSeeds = _seeds.value.toMutableList()
                val currentSeed = currentSeeds[seedIndex]
                currentSeeds[seedIndex] = currentSeed.copy(quantity = currentSeed.quantity - 1)
                if (currentSeeds[seedIndex].quantity <= 0) {
                    currentSeeds.removeAt(seedIndex)
                }
                _seeds.value = currentSeeds.toList()
                
                val newSlot = PlantSlotData(
                    index = slot.index,
                    status = "growing",
                    seedId = seed.id,
                    seedName = seed.name,
                    startYear = year,
                    startMonth = month,
                    growTime = slot.growTime,
                    harvestAmount = slot.harvestAmount,
                    harvestHerbId = herbId
                )
                events.add("自动续种：开始种植${herb.name}" to "INFO")
                newSlot
            } else {
                events.add("种子不足，无法自动续种${herb.name}" to "WARNING")
                PlantSlotData(index = slot.index)
            }
        }
        
        _plantSlots.value = updatedSlots
        return events
    }
    
    fun getRemainingMonths(slot: PlantSlotData, currentYear: Int, currentMonth: Int): Int {
        if (!slot.isGrowing) return 0
        val yearDiff = (currentYear - slot.startYear).toLong()
        val monthDiff = (currentMonth - slot.startMonth).toLong()
        val elapsed = yearDiff * 12 + monthDiff
        return (slot.growTime - elapsed.toInt()).coerceAtLeast(0)
    }
    
    fun sellHerb(herbId: String, quantity: Int, getSpiritStonePrice: (Int) -> Long): Boolean {
        val herbIndex = _herbs.value.indexOfFirst { it.id == herbId }
        if (herbIndex < 0) return false
        
        val herb = _herbs.value[herbIndex]
        if (herb.quantity < quantity) return false
        
        val totalPrice = getSpiritStonePrice(herb.rarity) * quantity
        
        val updatedHerbs = _herbs.value.toMutableList()
        if (herb.quantity == quantity) {
            updatedHerbs.removeAt(herbIndex)
        } else {
            updatedHerbs[herbIndex] = herb.copy(quantity = herb.quantity - quantity)
        }
        
        _herbs.value = updatedHerbs
        return true
    }
    
    fun sellSeed(seedId: String, quantity: Int, getSpiritStonePrice: (Int) -> Long): Boolean {
        val seedIndex = _seeds.value.indexOfFirst { it.id == seedId }
        if (seedIndex < 0) return false
        
        val seed = _seeds.value[seedIndex]
        if (seed.quantity < quantity) return false
        
        val totalPrice = getSpiritStonePrice(seed.rarity) * quantity
        
        val updatedSeeds = _seeds.value.toMutableList()
        if (seed.quantity == quantity) {
            updatedSeeds.removeAt(seedIndex)
        } else {
            updatedSeeds[seedIndex] = seed.copy(quantity = seed.quantity - quantity)
        }
        
        _seeds.value = updatedSeeds
        return true
    }
}
