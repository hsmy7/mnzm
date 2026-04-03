package com.xianxia.sect.core.engine.system

import android.util.Log
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.Equipment
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.SlotStatus
import com.xianxia.sect.core.util.StateFlowListUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已弃用：锻造功能已合并至 [BuildingSubsystem]，Equipment 管理待迁移。
 *
 * 此子系统存在以下问题：
 * 1. **类型不统一**：使用旧版 [BuildingSlot] 类型而非统一的 [ProductionSlot]
 * 2. **功能重叠**：锻造槽位管理（forgeSlots）与 BuildingSubsystem 的生产槽位重复
 * 3. **独立状态**：维护独立的 [_forgeSlots] 和 [_equipment] 内部状态，
 *    与 BuildingSubsystem 通过 Repository 管理的状态可能不同步
 *
 * **引用位置（保留不删以避免破坏 SystemManager 注册）：**
 * - [SystemManager] 第23行注入、第45行注册
 * - [CoreModule] 第54行 DI提供、第69行实例化
 *
 * **迁移计划：**
 * - 锻造槽位 → 已合并至 BuildingSubsystem（使用 ProductionSlot + BuildingType.FORGE）
 * - Equipment 列表 → 待迁移至独立的 EquipmentSubsystem 或 Repository
 */
@Deprecated(
    message = "已弃用：锻造功能已合并至 BuildingSubsystem，Equipment管理待迁移",
    replaceWith = ReplaceWith("BuildingSubsystem", "com.xianxia.sect.core.engine.system.BuildingSubsystem"),
    level = DeprecationLevel.WARNING
)
@Singleton
class ForgingSubsystem @Inject constructor() : GameSystem {
    
    companion object {
        private const val TAG = "ForgingSubsystem"
        const val SYSTEM_NAME = "ForgingSubsystem"
    }
    
    private val _forgeSlots = MutableStateFlow<List<BuildingSlot>>(emptyList())
    val forgeSlots: StateFlow<List<BuildingSlot>> = _forgeSlots.asStateFlow()
    
    private val _equipment = MutableStateFlow<List<Equipment>>(emptyList())
    val equipment: StateFlow<List<Equipment>> = _equipment.asStateFlow()
    
    override val systemName: String = SYSTEM_NAME
    
    override fun initialize() {
        Log.d(TAG, "ForgingSubsystem initialized")
    }
    
    override fun release() {
        Log.d(TAG, "ForgingSubsystem released")
    }
    
    override suspend fun clear() {
        StateFlowListUtils.clearList(_forgeSlots)
        StateFlowListUtils.clearList(_equipment)
    }
    
    fun loadForgingData(
        forgeSlots: List<BuildingSlot>,
        equipment: List<Equipment>
    ) {
        StateFlowListUtils.setList(_forgeSlots, forgeSlots)
        StateFlowListUtils.setList(_equipment, equipment)
    }
    
    fun getForgeSlots(): List<BuildingSlot> = _forgeSlots.value
    
    fun getForgeSlotByIndex(index: Int): BuildingSlot? =
        _forgeSlots.value.find { it.slotIndex == index }
    
    fun updateForgeSlots(transform: (List<BuildingSlot>) -> List<BuildingSlot>) {
        _forgeSlots.value = transform(_forgeSlots.value)
    }
    
    fun updateForgeSlot(slotIndex: Int, transform: (BuildingSlot) -> BuildingSlot): Boolean =
        StateFlowListUtils.updateItem(_forgeSlots, { it.slotIndex == slotIndex }, transform)
    
    fun initializeForgeSlots(count: Int = 2) {
        val currentSlots = _forgeSlots.value
        
        val needsInitialization = currentSlots.isEmpty() ||
                currentSlots.size < count ||
                (0 until count).any { idx -> currentSlots.none { it.slotIndex == idx } }
        
        if (needsInitialization) {
            val initializedSlots = (0 until count).map { idx ->
                currentSlots.find { it.slotIndex == idx } ?: BuildingSlot(
                    id = java.util.UUID.randomUUID().toString(),
                    buildingId = "forge",
                    slotIndex = idx
                )
            }
            StateFlowListUtils.setList(_forgeSlots, initializedSlots)
        }
    }
    
    fun getEquipment(): List<Equipment> = _equipment.value
    
    fun getEquipmentById(id: String): Equipment? =
        _equipment.value.find { it.id == id }
    
    fun updateEquipment(transform: (List<Equipment>) -> List<Equipment>) {
        _equipment.value = transform(_equipment.value)
    }
    
    fun addEquipmentToWarehouse(equipment: Equipment) {
        val currentEquipment = _equipment.value.toMutableList()
        currentEquipment.add(equipment)
        StateFlowListUtils.setList(_equipment, currentEquipment.toList())
    }
    
    fun removeEquipmentFromWarehouse(equipmentId: String): Boolean {
        val equipmentIndex = _equipment.value.indexOfFirst { it.id == equipmentId }
        if (equipmentIndex < 0) return false
        
        val updatedEquipment = _equipment.value.toMutableList()
        updatedEquipment.removeAt(equipmentIndex)
        
        _equipment.value = updatedEquipment
        return true
    }
    
    data class ForgingProgressResult(
        val slot: BuildingSlot,
        val isCompleted: Boolean,
        val producedEquipment: Equipment? = null,
        val eventMessage: String? = null
    )
    
    fun processForgingProgress(
        slotIndex: Int,
        currentYear: Int,
        currentMonth: Int,
        speedBonus: Double = 0.0
    ): ForgingProgressResult {
        val slot = getForgeSlotByIndex(slotIndex) ?: return ForgingProgressResult(
            slot = BuildingSlot(
                id = "",
                buildingId = "forge",
                slotIndex = slotIndex
            ),
            isCompleted = false
        )
        
        if (slot.discipleId == null || slot.recipeId == null) {
            return ForgingProgressResult(slot = slot, isCompleted = false)
        }
        
        val recipe = ForgeRecipeDatabase.getRecipeById(slot.recipeId)
            ?: return ForgingProgressResult(slot = slot, isCompleted = false)
        
        val remainingTime = calculateRemainingTime(slot, currentYear, currentMonth)
        
        if (remainingTime > 0) {
            return ForgingProgressResult(slot = slot, isCompleted = false)
        }
        
        val equipment = Equipment(
            id = java.util.UUID.randomUUID().toString(),
            name = recipe.name,
            rarity = recipe.rarity,
            description = recipe.description,
            slot = recipe.type
        )
        
        val completedSlot = slot.copy(
            status = SlotStatus.IDLE
        )
        
        return ForgingProgressResult(
            slot = completedSlot,
            isCompleted = true,
            producedEquipment = equipment,
            eventMessage = "锻造成功：${recipe.name}"
        )
    }
    
    private fun calculateRemainingTime(
        slot: BuildingSlot,
        currentYear: Int,
        currentMonth: Int
    ): Int {
        if (slot.startYear == null || slot.startMonth == null || slot.duration == null) {
            return 0
        }
        
        val yearDiff = currentYear - slot.startYear
        val monthDiff = currentMonth - slot.startMonth
        val elapsed = yearDiff * 12 + monthDiff
        
        return (slot.duration - elapsed).coerceAtLeast(0)
    }
}
