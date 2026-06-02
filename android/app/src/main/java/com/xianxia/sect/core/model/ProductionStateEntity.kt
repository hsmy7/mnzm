package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "production_state",
    primaryKeys = ["slot_id"],
    indices = [Index(value = ["slot_id"], unique = true)]
)
data class ProductionState(
    @ColumnInfo(name = "slot_id")
    var slotId: Int = 1,
    var spiritFieldPlants: List<SpiritFieldPlant> = emptyList(),
    var unlockedRecipes: List<String> = emptyList(),
    var unlockedManuals: List<String> = emptyList(),
    var manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap()
)
