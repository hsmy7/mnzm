package com.xianxia.sect.core.model.production

import androidx.room.Entity
import androidx.room.PrimaryKey

data class MaterialConsumptionLog(
    val id: String,
    val timestamp: Long,
    val slotIndex: Int,
    val recipeId: String,
    val recipeName: String,
    val materials: Map<String, Int>,
    val reason: String,
    val buildingId: String
)

@Entity(tableName = "material_consumption_logs")
data class MaterialConsumptionLogEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val slotIndex: Int,
    val recipeId: String,
    val recipeName: String,
    val materialsSerialized: String,
    val reason: String,
    val buildingId: String
) {
    fun toLog(): MaterialConsumptionLog = MaterialConsumptionLog(
        id = id,
        timestamp = timestamp,
        slotIndex = slotIndex,
        recipeId = recipeId,
        recipeName = recipeName,
        materials = materialsSerialized.split(",")
            .filter { it.contains(":") }
            .associate {
                val parts = it.split(":")
                parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
            },
        reason = reason,
        buildingId = buildingId
    )

    companion object {
        fun fromLog(log: MaterialConsumptionLog): MaterialConsumptionLogEntity =
            MaterialConsumptionLogEntity(
                id = log.id,
                timestamp = log.timestamp,
                slotIndex = log.slotIndex,
                recipeId = log.recipeId,
                recipeName = log.recipeName,
                materialsSerialized = log.materials.entries
                    .joinToString(",") { "${it.key}:${it.value}" },
                reason = log.reason,
                buildingId = log.buildingId
            )
    }
}
