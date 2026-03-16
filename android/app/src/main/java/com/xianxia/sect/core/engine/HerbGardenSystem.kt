package com.xianxia.sect.core.engine

import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.model.Disciple
import kotlin.random.Random

object HerbGardenSystem {
    
    const val MAX_PLANT_SLOTS = 3
    const val MAX_DISCIPLE_SLOTS = 3
    
    data class PlantSlot(
        val index: Int,
        var status: PlantStatus = PlantStatus.IDLE,
        var seedId: String? = null,
        var seedName: String? = null,
        var startYear: Int = 0,
        var startMonth: Int = 0,
        var growTime: Int = 0,
        var harvestAmount: Int = 0,
        var harvestHerbId: String? = null
    ) {
        val isIdle: Boolean get() = status == PlantStatus.IDLE
        val isGrowing: Boolean get() = status == PlantStatus.GROWING
        val isReady: Boolean get() = status == PlantStatus.READY
        
        fun getProgress(currentYear: Int, currentMonth: Int): Double {
            if (growTime <= 0) return 0.0
            val elapsed = (currentYear - startYear) * 12 + (currentMonth - startMonth)
            return (elapsed.toDouble() / growTime).coerceIn(0.0, 1.0)
        }
        
        fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
            val elapsed = (currentYear - startYear) * 12 + (currentMonth - startMonth)
            return elapsed >= growTime
        }
    }
    
    data class DiscipleSlot(
        val index: Int,
        var discipleId: String? = null,
        var discipleName: String? = null
    ) {
        val isEmpty: Boolean get() = discipleId == null
    }
    
    enum class PlantStatus {
        IDLE, GROWING, READY;
        
        val displayName: String get() = when (this) {
            IDLE -> "空闲"
            GROWING -> "生长中"
            READY -> "可收获"
        }
    }
    
    fun initializePlantSlots(): List<PlantSlot> {
        return (0 until MAX_PLANT_SLOTS).map { PlantSlot(index = it) }
    }
    
    fun initializeDiscipleSlots(): List<DiscipleSlot> {
        return (0 until MAX_DISCIPLE_SLOTS).map { DiscipleSlot(index = it) }
    }
    
    fun startPlanting(
        slot: PlantSlot,
        seedId: String,
        currentYear: Int,
        currentMonth: Int,
        speedBonus: Double = 0.0
    ): PlantSlot? {
        if (!slot.isIdle) return null
        
        val seed = HerbDatabase.getSeedById(seedId) ?: return null
        
        val baseGrowTime = ForgeRecipeDatabase.getDurationByTier(seed.tier)
        val actualGrowTime = calculateReducedDuration(baseGrowTime, speedBonus)
        
        val herb = HerbDatabase.getHerbById(seedId.removeSuffix("Seed"))
        
        return slot.copy(
            status = PlantStatus.GROWING,
            seedId = seedId,
            seedName = seed.name,
            startYear = currentYear,
            startMonth = currentMonth,
            growTime = actualGrowTime,
            harvestAmount = seed.yield,
            harvestHerbId = herb?.id
        )
    }
    
    fun checkPlantProgress(
        slot: PlantSlot,
        currentYear: Int,
        currentMonth: Int
    ): PlantSlot {
        if (slot.status != PlantStatus.GROWING) return slot
        
        return if (slot.isFinished(currentYear, currentMonth)) {
            slot.copy(status = PlantStatus.READY)
        } else {
            slot
        }
    }
    
    /**
     * 计算种植速度加成（内门弟子加成已移除）
     */
    fun calculatePlantingSpeedBonus(
        discipleSlots: List<DiscipleSlot>,
        disciples: List<Disciple>,
        manualProficiencies: Map<String, List<com.xianxia.sect.core.model.ManualProficiencyData>> = emptyMap(),
        manuals: List<com.xianxia.sect.core.model.Manual> = emptyList()
    ): Double {
        return 0.0
    }
    
    /**
     * 计算产量加成（内门弟子加成已移除）
     */
    fun calculateYieldBonus(
        discipleSlots: List<DiscipleSlot>,
        disciples: List<Disciple>,
        manualProficiencies: Map<String, List<com.xianxia.sect.core.model.ManualProficiencyData>> = emptyMap(),
        manuals: List<com.xianxia.sect.core.model.Manual> = emptyList()
    ): Double {
        return 0.0
    }
    
    /**
     * 计算减少后的时间（无限制）
     */
    fun calculateReducedDuration(baseDuration: Int, speedBonus: Double): Int {
        if (speedBonus <= 0) return baseDuration
        
        val reductionPercent = (speedBonus / 2.5).coerceAtMost(0.80)
        val reducedMonths = (baseDuration * reductionPercent).toInt()
        return (baseDuration - reducedMonths).coerceAtLeast(1)
    }
    
    /**
     * 计算增加后的产量（无限制）
     */
    fun calculateIncreasedYield(baseYield: Int, yieldBonus: Double): Int {
        if (yieldBonus <= 0) return baseYield
        
        val bonusYield = (baseYield * yieldBonus).toInt()
        return (baseYield + bonusYield).coerceAtLeast(1)
    }
    
    fun getSlotStatusDescription(
        slot: PlantSlot,
        currentYear: Int,
        currentMonth: Int
    ): String {
        return when (slot.status) {
            PlantStatus.IDLE -> "空闲"
            PlantStatus.GROWING -> {
                val progress = (slot.getProgress(currentYear, currentMonth) * 100).toInt()
                "${slot.seedName ?: "未知"} ($progress%)"
            }
            PlantStatus.READY -> "${slot.seedName ?: "未知"} (可收获)"
        }
    }
    
    fun getEstimatedHarvestTime(
        slot: PlantSlot,
        currentYear: Int,
        currentMonth: Int
    ): String {
        if (slot.status != PlantStatus.GROWING) return "-"
        
        val elapsed = (currentYear - slot.startYear) * 12 + (currentMonth - slot.startMonth)
        val remaining = (slot.growTime - elapsed).coerceAtLeast(0)
        
        val years = remaining / 12
        val months = remaining % 12
        
        return when {
            years > 0 -> "${years}年${months}月"
            months > 0 -> "${months}月"
            else -> "即将完成"
        }
    }
}
