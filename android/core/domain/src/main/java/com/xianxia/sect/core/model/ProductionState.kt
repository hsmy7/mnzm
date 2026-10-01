package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Keep
@Serializable
@Entity(tableName = "production_state")
data class ProductionState(
    /** 单档单行标识：本表恒一行，主键恒 1 */
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: Int = 1,
    var spiritFieldPlants: List<SpiritFieldPlant> = emptyList(),
    var unlockedRecipes: List<String> = emptyList(),
    var unlockedManuals: List<String> = emptyList(),
    var manualProficiencies: Map<String, List<ManualProficiencyData>> = emptyMap()
)
