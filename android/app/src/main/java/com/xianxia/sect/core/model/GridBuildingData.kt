package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GridBuildingData(
    val instanceId: String = "",
    val buildingId: String = "",
    val displayName: String = "",
    val gridX: Int = 0,
    val gridY: Int = 0,
    val width: Int = 2,
    val height: Int = 3
) {
    fun withInstanceId(): GridBuildingData =
        if (instanceId.isNotBlank()) this else copy(instanceId = java.util.UUID.randomUUID().toString())

    companion object {
        fun ensureAllHaveInstanceId(buildings: List<GridBuildingData>): List<GridBuildingData> =
            buildings.map { it.withInstanceId() }
    }
}
