package com.xianxia.sect.core.model

import androidx.annotation.Keep
import com.xianxia.sect.core.model.production.ProductionSlot
import kotlinx.serialization.Serializable

/**
 * 建筑与槽位状态
 *
 * 包含所有建筑相关数据，从 GameData 上帝对象中拆分出来，降低 copy() 时的复制开销。
 */
@Keep
@Serializable
data class BuildingState(
    val placedBuildings: List<GridBuildingData> = emptyList(),
    val productionSlots: List<ProductionSlot> = emptyList(),
    val spiritMineSlots: List<SpiritMineSlot> = emptyList(),
    val librarySlots: List<LibrarySlot> = emptyList(),
    val residenceSlots: List<ResidenceSlot> = emptyList(),
    val warehouseGarrisons: List<WarehouseGarrisonSlot> = emptyList(),
    val patrolSlots: List<PatrolSlot> = emptyList(),
    val spiritFieldPlants: List<SpiritFieldPlant> = emptyList(),
    val activeBloodRefinements: Map<String, BloodRefinementProgress> = emptyMap()
)
