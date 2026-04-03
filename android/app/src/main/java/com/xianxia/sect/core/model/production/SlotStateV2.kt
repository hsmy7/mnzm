package com.xianxia.sect.core.model.production

data class GameTime(
    val year: Int,
    val month: Int
) {
    fun monthsSince(other: GameTime): Int {
        val yearDiff = (year - other.year).toLong()
        val monthDiff = (month - other.month).toLong()
        val result = yearDiff * 12 + monthDiff
        return result.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
    
    fun plusMonths(months: Int): GameTime {
        val totalMonths = year.toLong() * 12 + month - 1 + months
        return GameTime(
            year = (totalMonths / 12).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            month = ((totalMonths % 12) + 1).toInt().coerceIn(1, 12)
        )
    }
    
    companion object {
        fun fromGameData(gameYear: Int, gameMonth: Int): GameTime = 
            GameTime(gameYear, gameMonth)
    }
}

sealed class SlotStateV2 {
    abstract val slotIndex: Int
    abstract val buildingType: BuildingType
    abstract val buildingId: String
    
    data class Idle(
        override val slotIndex: Int,
        override val buildingType: BuildingType,
        override val buildingId: String,
        val assignedDiscipleId: String? = null,
        val assignedDiscipleName: String = ""
    ) : SlotStateV2() {
        
        fun startProduction(
            recipeId: String,
            recipeName: String,
            duration: Int,
            currentYear: Int,
            currentMonth: Int,
            successRate: Double,
            materials: Map<String, Int>,
            outputItemId: String?,
            outputItemName: String,
            outputItemRarity: Int,
            outputItemSlot: String = ""
        ): Working = Working(
            slotIndex = slotIndex,
            buildingType = buildingType,
            buildingId = buildingId,
            recipeId = recipeId,
            recipeName = recipeName,
            startYear = currentYear,
            startMonth = currentMonth,
            duration = duration,
            assignedDiscipleId = assignedDiscipleId,
            assignedDiscipleName = assignedDiscipleName,
            successRate = successRate,
            requiredMaterials = materials,
            outputItemId = outputItemId,
            outputItemName = outputItemName,
            outputItemRarity = outputItemRarity,
            outputItemSlot = outputItemSlot
        )
        
        fun assignDisciple(discipleId: String, discipleName: String): Idle = copy(
            assignedDiscipleId = discipleId,
            assignedDiscipleName = discipleName
        )
        
        fun removeDisciple(): Idle = copy(
            assignedDiscipleId = null,
            assignedDiscipleName = ""
        )
    }
    
    data class Working(
        override val slotIndex: Int,
        override val buildingType: BuildingType,
        override val buildingId: String,
        val recipeId: String,
        val recipeName: String,
        val startYear: Int,
        val startMonth: Int,
        val duration: Int,
        val assignedDiscipleId: String?,
        val assignedDiscipleName: String,
        val successRate: Double,
        val requiredMaterials: Map<String, Int>,
        val outputItemId: String?,
        val outputItemName: String,
        val outputItemRarity: Int,
        val outputItemSlot: String = ""
    ) : SlotStateV2() {
        
        val startTime: GameTime get() = GameTime(startYear, startMonth)
        
        val endTime: GameTime get() = startTime.plusMonths(duration)
        
        fun remainingTime(currentYear: Int, currentMonth: Int): Int {
            val elapsed = (currentYear - startYear) * 12L + (currentMonth - startMonth)
            return (duration - elapsed.toInt()).coerceAtLeast(0)
        }
        
        fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
            return remainingTime(currentYear, currentMonth) == 0
        }
        
        fun getProgressPercent(currentYear: Int, currentMonth: Int): Int {
            if (duration <= 0) return 0
            val elapsed = (currentYear - startYear) * 12L + (currentMonth - startMonth)
            return ((elapsed.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
        }
        
        fun complete(): Completed = Completed(
            slotIndex = slotIndex,
            buildingType = buildingType,
            buildingId = buildingId,
            recipeId = recipeId,
            recipeName = recipeName,
            outputItemId = outputItemId,
            outputItemName = outputItemName,
            outputItemRarity = outputItemRarity,
            outputItemSlot = outputItemSlot,
            successRate = successRate,
            requiredMaterials = requiredMaterials
        )
        
        fun cancel(): Idle = Idle(
            slotIndex = slotIndex,
            buildingType = buildingType,
            buildingId = buildingId,
            assignedDiscipleId = assignedDiscipleId,
            assignedDiscipleName = assignedDiscipleName
        )
    }
    
    data class Completed(
        override val slotIndex: Int,
        override val buildingType: BuildingType,
        override val buildingId: String,
        val recipeId: String,
        val recipeName: String,
        val outputItemId: String?,
        val outputItemName: String,
        val outputItemRarity: Int,
        val outputItemSlot: String = "",
        val successRate: Double,
        val requiredMaterials: Map<String, Int>
    ) : SlotStateV2() {
        
        fun collect(): Idle = Idle(
            slotIndex = slotIndex,
            buildingType = buildingType,
            buildingId = buildingId
        )
        
        fun hasOutput(): Boolean = outputItemId != null && outputItemName.isNotEmpty()
    }
    
    inline fun <R> fold(
        idle: (Idle) -> R,
        working: (Working) -> R,
        completed: (Completed) -> R
    ): R = when (this) {
        is Idle -> idle(this)
        is Working -> working(this)
        is Completed -> completed(this)
    }
    
    val isIdle: Boolean get() = this is Idle
    val isWorking: Boolean get() = this is Working
    val isCompleted: Boolean get() = this is Completed
    
    fun toStatus(): ProductionSlotStatus = when (this) {
        is Idle -> ProductionSlotStatus.IDLE
        is Working -> ProductionSlotStatus.WORKING
        is Completed -> ProductionSlotStatus.COMPLETED
    }
    
    companion object {
        fun createIdle(
            slotIndex: Int,
            buildingType: BuildingType,
            buildingId: String = buildingType.name.lowercase()
        ): Idle = Idle(
            slotIndex = slotIndex,
            buildingType = buildingType,
            buildingId = buildingId
        )
    }
}
