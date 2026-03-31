package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.model.AlchemySlotStatus
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlchemySubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "AlchemySubsystem"
        const val SYSTEM_NAME = "AlchemySubsystem"
    }
    
    private val _alchemySlots = MutableStateFlow<List<AlchemySlot>>(emptyList())
    val alchemySlots: StateFlow<List<AlchemySlot>> = _alchemySlots.asStateFlow()
    
    private val _pills = MutableStateFlow<List<Pill>>(emptyList())
    val pills: StateFlow<List<Pill>> = _pills.asStateFlow()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "AlchemySubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "AlchemySubsystem released")
    }
    
    override fun clear() {
        StateFlowListUtils.clearList(_alchemySlots)
        StateFlowListUtils.clearList(_pills)
    }
    
    fun loadAlchemyData(
        alchemySlots: List<AlchemySlot>,
        pills: List<Pill>
    ) {
        StateFlowListUtils.setList(_alchemySlots, alchemySlots)
        StateFlowListUtils.setList(_pills, pills)
    }
    
    fun getAlchemySlots(): List<AlchemySlot> = _alchemySlots.value
    
    fun getAlchemySlotByIndex(index: Int): AlchemySlot? =
        _alchemySlots.value.find { it.slotIndex == index }
    
    fun updateAlchemySlots(transform: (List<AlchemySlot>) -> List<AlchemySlot>) {
        _alchemySlots.value = transform(_alchemySlots.value)
    }
    
    fun updateAlchemySlot(slotIndex: Int, transform: (AlchemySlot) -> AlchemySlot): Boolean =
        StateFlowListUtils.updateItem(_alchemySlots, { it.slotIndex == slotIndex }, transform)
    
    fun initializeAlchemySlots(count: Int = 3) {
        val currentSlots = _alchemySlots.value
        
        val needsInitialization = currentSlots.isEmpty() ||
                currentSlots.size < count ||
                (0 until count).any { idx -> currentSlots.none { it.slotIndex == idx } }
        
        if (needsInitialization) {
            val initializedSlots = (0 until count).map { idx ->
                currentSlots.find { it.slotIndex == idx } ?: AlchemySlot(
                    slotIndex = idx
                )
            }
            StateFlowListUtils.setList(_alchemySlots, initializedSlots)
        }
    }
    
    fun getPills(): List<Pill> = _pills.value
    
    fun updatePills(transform: (List<Pill>) -> List<Pill>) {
        _pills.value = transform(_pills.value)
    }
    
    fun addPillToWarehouse(pill: Pill) {
        val currentPills = _pills.value.toMutableList()
        val existingIndex = currentPills.indexOfFirst { 
            it.name == pill.name && it.rarity == pill.rarity 
        }
        
        if (existingIndex >= 0) {
            currentPills[existingIndex] = currentPills[existingIndex].copy(
                quantity = currentPills[existingIndex].quantity + pill.quantity
            )
        } else {
            currentPills.add(pill)
        }
        
        StateFlowListUtils.setList(_pills, currentPills.toList())
    }
    
    fun removePillFromWarehouse(pillId: String, quantity: Int): Boolean {
        val pillIndex = _pills.value.indexOfFirst { it.id == pillId }
        if (pillIndex < 0) return false
        
        val pill = _pills.value[pillIndex]
        if (pill.quantity < quantity) return false
        
        val updatedPills = _pills.value.toMutableList()
        if (pill.quantity == quantity) {
            updatedPills.removeAt(pillIndex)
        } else {
            updatedPills[pillIndex] = pill.copy(quantity = pill.quantity - quantity)
        }
        
        _pills.value = updatedPills
        return true
    }
    
    data class AlchemyProgressResult(
        val slot: AlchemySlot,
        val isCompleted: Boolean,
        val producedPill: Pill? = null,
        val eventMessage: String? = null
    )
    
    fun processAlchemyProgress(
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int,
        speedBonus: Double = 0.0
    ): AlchemyProgressResult {
        val slot = getAlchemySlotByIndex(slotIndex) ?: return AlchemyProgressResult(
            slot = AlchemySlot(slotIndex = slotIndex),
            isCompleted = false
        )
        
        if (slot.recipeId == null) {
            return AlchemyProgressResult(slot = slot, isCompleted = false)
        }
        
        val recipe = PillRecipeDatabase.getRecipeById(slot.recipeId)
            ?: return AlchemyProgressResult(slot = slot, isCompleted = false)
        
        val remainingTime = calculateRemainingTime(slot, currentYear, currentMonth)
        
        if (remainingTime > 0) {
            return AlchemyProgressResult(slot = slot, isCompleted = false)
        }
        
        val pill = Pill(
            id = java.util.UUID.randomUUID().toString(),
            name = recipe.name,
            rarity = recipe.rarity,
            description = recipe.description,
            category = recipe.category,
            breakthroughChance = recipe.breakthroughChance,
            targetRealm = recipe.targetRealm,
            cultivationSpeed = recipe.cultivationSpeed,
            duration = recipe.effectDuration,
            physicalAttackPercent = recipe.physicalAttackPercent,
            magicAttackPercent = recipe.magicAttackPercent,
            physicalDefensePercent = recipe.physicalDefensePercent,
            magicDefensePercent = recipe.magicDefensePercent,
            hpPercent = recipe.hpPercent,
            mpPercent = recipe.mpPercent,
            speedPercent = recipe.speedPercent
        )
        
        val completedSlot = slot.copy(
            status = AlchemySlotStatus.FINISHED
        )
        
        return AlchemyProgressResult(
            slot = completedSlot,
            isCompleted = true,
            producedPill = pill,
            eventMessage = "炼制成功：${recipe.name}"
        )
    }
    
    private fun calculateRemainingTime(
        slot: AlchemySlot,
        currentYear: Int,
        currentMonth: Int
    ): Int {
        if (slot.status != AlchemySlotStatus.WORKING) {
            return 0
        }
        
        val yearDiff = currentYear - slot.startYear
        val monthDiff = currentMonth - slot.startMonth
        val elapsed = yearDiff * 12 + monthDiff
        
        return (slot.duration - elapsed).coerceAtLeast(0)
    }
}
