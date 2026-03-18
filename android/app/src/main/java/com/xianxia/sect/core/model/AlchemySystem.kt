package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alchemy_slots")
data class AlchemySlot(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val slotIndex: Int = 0,
    val recipeId: String? = null,
    val recipeName: String = "",
    val pillName: String = "",
    val pillRarity: Int = 1,
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val duration: Int = 0,
    val status: AlchemySlotStatus = AlchemySlotStatus.IDLE,
    val successRate: Double = 0.0,
    val requiredMaterials: Map<String, Int> = emptyMap()
) {
    val isIdle: Boolean get() = status == AlchemySlotStatus.IDLE
    val isWorking: Boolean get() = status == AlchemySlotStatus.WORKING
    val isFinished: Boolean get() = status == AlchemySlotStatus.FINISHED

    fun getRemainingMonths(currentYear: Int, currentMonth: Int): Int {
        if (status != AlchemySlotStatus.WORKING) return 0
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return (duration - elapsedMonths.toInt()).coerceAtLeast(0)
    }

    fun getProgressPercent(currentYear: Int, currentMonth: Int): Int {
        if (status != AlchemySlotStatus.WORKING || duration <= 0) return 0
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsed = yearDiff * 12 + monthDiff
        return ((elapsed.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
    }

    fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
        if (status != AlchemySlotStatus.WORKING) return status == AlchemySlotStatus.FINISHED
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return elapsedMonths >= duration
    }
}

enum class AlchemySlotStatus {
    IDLE,
    WORKING,
    FINISHED;

    val displayName: String get() = when (this) {
        IDLE -> "空闲"
        WORKING -> "炼制中"
        FINISHED -> "已完成"
    }
}

data class AlchemyRecipe(
    val id: String,
    val name: String,
    val pillId: String,
    val pillName: String,
    val pillRarity: Int,
    val tier: Int,
    val category: PillCategory,
    val description: String,
    val materials: Map<String, Int>,
    val duration: Int,
    val successRate: Double,
    val effects: PillEffect
) {
    fun hasEnoughMaterials(materials: List<Material>): Boolean {
        val materialMap = materials.associateBy { it.id }
        return this.materials.all { (materialId, requiredQuantity) ->
            val available = materialMap[materialId]
            available != null && available.quantity >= requiredQuantity
        }
    }

    fun getMissingMaterials(materials: List<Material>): List<Pair<String, Int>> {
        val materialMap = materials.associateBy { it.id }
        return this.materials.filter { (materialId, requiredQuantity) ->
            val available = materialMap[materialId]
            available == null || available.quantity < requiredQuantity
        }.map { (materialId, requiredQuantity) ->
            val available = materialMap[materialId]
            val have = available?.quantity ?: 0
            materialId to (requiredQuantity - have)
        }
    }
}

data class AlchemyResult(
    val success: Boolean,
    val pill: Pill? = null,
    val message: String = ""
)

@Entity(tableName = "forge_slots")
data class ForgeSlot(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val slotIndex: Int = 0,
    val recipeId: String? = null,
    val recipeName: String = "",
    val equipmentName: String = "",
    val equipmentRarity: Int = 1,
    val equipmentSlot: EquipmentSlot = EquipmentSlot.WEAPON,
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val duration: Int = 0,
    val status: ForgeSlotStatus = ForgeSlotStatus.IDLE,
    val successRate: Double = 0.0,
    val requiredMaterials: Map<String, Int> = emptyMap()
) {
    val isIdle: Boolean get() = status == ForgeSlotStatus.IDLE
    val isWorking: Boolean get() = status == ForgeSlotStatus.WORKING
    val isFinished: Boolean get() = status == ForgeSlotStatus.FINISHED

    fun getRemainingMonths(currentYear: Int, currentMonth: Int): Int {
        if (status != ForgeSlotStatus.WORKING) return 0
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return (duration - elapsedMonths.toInt()).coerceAtLeast(0)
    }

    fun getProgressPercent(currentYear: Int, currentMonth: Int): Float {
        if (status != ForgeSlotStatus.WORKING || duration <= 0) return 0f
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsed = yearDiff * 12 + monthDiff
        return (elapsed.toDouble() / duration).toFloat().coerceIn(0f, 1f)
    }

    fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
        if (status != ForgeSlotStatus.WORKING) return status == ForgeSlotStatus.FINISHED
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return elapsedMonths >= duration
    }
}

enum class ForgeSlotStatus {
    IDLE,
    WORKING,
    FINISHED;

    val displayName: String get() = when (this) {
        IDLE -> "空闲"
        WORKING -> "炼制中"
        FINISHED -> "已完成"
    }
}

data class ForgeRecipe(
    val id: String,
    val name: String,
    val equipmentId: String,
    val equipmentName: String,
    val equipmentRarity: Int,
    val tier: Int,
    val equipmentSlot: EquipmentSlot,
    val description: String,
    val materials: Map<String, Int>,
    val duration: Int,
    val successRate: Double
) {
    fun hasEnoughMaterials(materials: List<Material>): Boolean {
        val materialMap = materials.associateBy { it.id }
        return this.materials.all { (materialId, requiredQuantity) ->
            val available = materialMap[materialId]
            available != null && available.quantity >= requiredQuantity
        }
    }

    fun getMissingMaterials(materials: List<Material>): List<Pair<String, Int>> {
        val materialMap = materials.associateBy { it.id }
        return this.materials.filter { (materialId, requiredQuantity) ->
            val available = materialMap[materialId]
            available == null || available.quantity < requiredQuantity
        }.map { (materialId, requiredQuantity) ->
            val available = materialMap[materialId]
            val have = available?.quantity ?: 0
            materialId to (requiredQuantity - have)
        }
    }
}

data class ForgeResult(
    val success: Boolean,
    val equipment: Equipment? = null,
    val message: String = ""
)
