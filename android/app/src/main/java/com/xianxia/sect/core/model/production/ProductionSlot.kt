package com.xianxia.sect.core.model.production

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.xianxia.sect.core.model.EquipmentSlot
import kotlinx.serialization.Serializable

@Serializable
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
    val slotType: SlotType get() = buildingType.toSlotType()

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

    companion object {
        fun createIdle(
            id: String = java.util.UUID.randomUUID().toString(),
            slotIndex: Int,
            buildingType: BuildingType,
            buildingId: String = ""
        ): ProductionSlot = ProductionSlot(
            id = id,
            slotIndex = slotIndex,
            buildingType = buildingType,
            buildingId = buildingId,
            status = ProductionSlotStatus.IDLE
        )

        fun fromBuildingSlot(buildingSlot: com.xianxia.sect.core.model.BuildingSlot): ProductionSlot {
            val bType = when (buildingSlot.buildingId.lowercase()) {
                "forge", "forging" -> BuildingType.FORGE
                "alchemy", "alchemyroom" -> BuildingType.ALCHEMY
                "mine", "mining" -> BuildingType.MINING
                "herb", "herbgarden", "herb_garden" -> BuildingType.HERB_GARDEN
                else -> BuildingType.ALCHEMY
            }
            return ProductionSlot(
                id = buildingSlot.id,
                slotIndex = buildingSlot.slotIndex,
                buildingType = bType,
                buildingId = buildingSlot.buildingId,
                status = when (buildingSlot.status) {
                    com.xianxia.sect.core.model.SlotStatus.IDLE -> ProductionSlotStatus.IDLE
                    com.xianxia.sect.core.model.SlotStatus.WORKING -> ProductionSlotStatus.WORKING
                    com.xianxia.sect.core.model.SlotStatus.COMPLETED -> ProductionSlotStatus.COMPLETED
                },
                recipeId = buildingSlot.recipeId,
                recipeName = buildingSlot.recipeName,
                startYear = buildingSlot.startYear,
                startMonth = buildingSlot.startMonth,
                duration = buildingSlot.duration,
                assignedDiscipleId = buildingSlot.discipleId,
                assignedDiscipleName = buildingSlot.discipleName
            )
        }

        fun fromAlchemySlot(alchemySlot: com.xianxia.sect.core.model.AlchemySlot): ProductionSlot = ProductionSlot(
            id = alchemySlot.id,
            slotIndex = alchemySlot.slotIndex,
            buildingType = BuildingType.ALCHEMY,
            buildingId = "alchemy",
            status = when (alchemySlot.status) {
                com.xianxia.sect.core.model.AlchemySlotStatus.IDLE -> ProductionSlotStatus.IDLE
                com.xianxia.sect.core.model.AlchemySlotStatus.WORKING -> ProductionSlotStatus.WORKING
                com.xianxia.sect.core.model.AlchemySlotStatus.FINISHED -> ProductionSlotStatus.COMPLETED
            },
            recipeId = alchemySlot.recipeId,
            recipeName = alchemySlot.recipeName,
            startYear = alchemySlot.startYear,
            startMonth = alchemySlot.startMonth,
            duration = alchemySlot.duration,
            assignedDiscipleId = null,
            assignedDiscipleName = "",
            successRate = alchemySlot.successRate,
            requiredMaterials = alchemySlot.requiredMaterials,
            outputItemId = alchemySlot.recipeId,
            outputItemName = alchemySlot.pillName,
            outputItemRarity = alchemySlot.pillRarity
        )

        fun fromForgeSlot(forgeSlot: com.xianxia.sect.core.model.ForgeSlot): ProductionSlot = ProductionSlot(
            id = forgeSlot.id,
            slotIndex = forgeSlot.slotIndex,
            buildingType = BuildingType.FORGE,
            buildingId = "forge",
            status = when (forgeSlot.status) {
                com.xianxia.sect.core.model.ForgeSlotStatus.IDLE -> ProductionSlotStatus.IDLE
                com.xianxia.sect.core.model.ForgeSlotStatus.WORKING -> ProductionSlotStatus.WORKING
                com.xianxia.sect.core.model.ForgeSlotStatus.FINISHED -> ProductionSlotStatus.COMPLETED
            },
            recipeId = forgeSlot.recipeId,
            recipeName = forgeSlot.recipeName,
            startYear = forgeSlot.startYear,
            startMonth = forgeSlot.startMonth,
            duration = forgeSlot.duration,
            assignedDiscipleId = null,
            assignedDiscipleName = "",
            successRate = forgeSlot.successRate,
            requiredMaterials = forgeSlot.requiredMaterials,
            outputItemId = forgeSlot.recipeId,
            outputItemName = forgeSlot.equipmentName,
            outputItemRarity = forgeSlot.equipmentRarity,
            outputItemSlot = forgeSlot.equipmentSlot.name
        )
    }
}

@Serializable
enum class BuildingType {
    ALCHEMY,
    FORGE,
    MINING,
    HERB_GARDEN,
    ADMINISTRATION,
    LIBRARY,
    WEN_DAO_PEAK,
    QINGYUN_PEAK,
    LAW_ENFORCEMENT_HALL,
    MISSION_HALL,
    REFLECTION_CLIFF;

    val displayName: String get() = when (this) {
        ALCHEMY -> "炼丹"
        FORGE -> "锻造"
        MINING -> "灵矿开采"
        HERB_GARDEN -> "灵药宛"
        ADMINISTRATION -> "天枢殿"
        LIBRARY -> "藏经阁"
        WEN_DAO_PEAK -> "问道峰"
        QINGYUN_PEAK -> "青云峰"
        LAW_ENFORCEMENT_HALL -> "执法堂"
        MISSION_HALL -> "任务阁"
        REFLECTION_CLIFF -> "思过崖"
    }

    fun toSlotType(): SlotType = when (this) {
        ALCHEMY -> SlotType.ALCHEMY
        FORGE -> SlotType.FORGING
        MINING -> SlotType.MINING
        HERB_GARDEN -> SlotType.HERB_GARDEN
        else -> SlotType.IDLE
    }
}

@Serializable
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

@Serializable
enum class SlotType {
    IDLE,
    MINING,
    ALCHEMY,
    FORGING,
    HERB_GARDEN;

    val displayName: String get() = when (this) {
        IDLE -> "空闲"
        MINING -> "灵矿开采"
        ALCHEMY -> "炼丹"
        FORGING -> "锻造"
        HERB_GARDEN -> "灵药宛"
    }

    fun toBuildingType(): BuildingType? = when (this) {
        IDLE -> null
        MINING -> BuildingType.MINING
        ALCHEMY -> BuildingType.ALCHEMY
        FORGING -> BuildingType.FORGE
        HERB_GARDEN -> BuildingType.HERB_GARDEN
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
    fun fromSlotType(value: SlotType): String = value.name

    @TypeConverter
    fun toSlotType(value: String): SlotType = 
        SlotType.entries.find { it.name == value } ?: SlotType.IDLE

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
