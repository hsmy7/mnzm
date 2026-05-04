package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GridBuildingData(
    val buildingId: String = "",
    val displayName: String = "",
    val gridX: Int = 0,
    val gridY: Int = 0,
    val width: Int = 2,
    val height: Int = 2
)
