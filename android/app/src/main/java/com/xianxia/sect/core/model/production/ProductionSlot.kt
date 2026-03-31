package com.xianxia.sect.core.model.production

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Entity(
    tableName = "production_slots",
    indices = [
        Index(value = ["buildingId", "slotIndex"]),
        Index(value = ["buildingType"]),
        Index(value = ["status"])
    ]
)
@TypeConverters(ProductionSlotConverters::class)
data class ProductionSlot(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val slotIndex: Int = 0,
    val buildingType: BuildingType = BuildingType.ALCHEMY,
    val buildingId: String = "",
    val status: ProductionSlotStatus = ProductionSlotStatus.IDLE,
    val recipeId: String? = null,
    val recipeName: String = "",
    val startYear: Int = 0,
    val startMonth: Int = 0,
    val duration: Int = 0,
    val assignedDiscipleId: String? = null,
    val assignedDiscipleName: String = "",
    val successRate: Double = 0.0,
    val requiredMaterials: Map<String, Int> = emptyMap(),
    val outputItemId: String? = null,
    val outputItemName: String = "",
    val outputItemRarity: Int = 1,
    val outputItemSlot: String = ""
) {
    val isIdle: Boolean get() = status == ProductionSlotStatus.IDLE
    val isWorking: Boolean get() = status == ProductionSlotStatus.WORKING
    val isCompleted: Boolean get() = status == ProductionSlotStatus.COMPLETED

    fun remainingTime(currentYear: Int, currentMonth: Int): Int {
        if (status != ProductionSlotStatus.WORKING) return 0
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return (duration - elapsedMonths.toInt()).coerceAtLeast(0)
    }

    fun getProgressPercent(currentYear: Int, currentMonth: Int): Int {
        if (status != ProductionSlotStatus.WORKING || duration <= 0) return 0
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsed = yearDiff * 12 + monthDiff
        return ((elapsed.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
    }

    fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
        if (status != ProductionSlotStatus.WORKING) return status == ProductionSlotStatus.COMPLETED
        val yearDiff = (currentYear - startYear).toLong()
        val monthDiff = (currentMonth - startMonth).toLong()
        val elapsedMonths = yearDiff * 12 + monthDiff
        return elapsedMonths >= duration
    }
}

enum class BuildingType {
    ALCHEMY,
    FORGE,
    MINING,
    HERB_GARDEN;

    val displayName: String get() = when (this) {
        ALCHEMY -> "炼丹"
        FORGE -> "锻造"
        MINING -> "灵矿开采"
        HERB_GARDEN -> "灵药宛"
    }
}

enum class ProductionSlotStatus {
    IDLE,
    WORKING,
    COMPLETED;

    val displayName: String get() = when (this) {
        IDLE -> "空闲"
        WORKING -> "进行中"
        COMPLETED -> "已完成"
    }
}

class ProductionSlotConverters {
    @TypeConverter
    fun fromBuildingType(value: BuildingType): String = value.name

    @TypeConverter
    fun toBuildingType(value: String): BuildingType = 
        BuildingType.entries.find { it.name == value } ?: BuildingType.ALCHEMY

    @TypeConverter
    fun fromStatus(value: ProductionSlotStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): ProductionSlotStatus = 
        ProductionSlotStatus.entries.find { it.name == value } ?: ProductionSlotStatus.IDLE

    @TypeConverter
    fun fromMaterialMap(value: Map<String, Int>): String = 
        value.entries.joinToString(",") { "${it.key}:${it.value}" }

    @TypeConverter
    fun toMaterialMap(value: String): Map<String, Int> {
        if (value.isEmpty()) return emptyMap()
        return value.split(",").associate {
            val parts = it.split(":")
            parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }
    }
}
