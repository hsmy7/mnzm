package com.xianxia.sect.ui.game.sect

import androidx.compose.runtime.Immutable
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.GridSnapHelper

/** 放置模式状态 */
data class PlacementModeState(
    val isActive: Boolean,
    val buildingName: String,
    val gridX: Int,
    val gridY: Int,
    val worldX: Float,
    val worldY: Float,
    val size: GridSnapHelper.BuildingSize,
    val validity: GridSnapHelper.PlacementValidity
) {
    companion object {
        val INACTIVE = PlacementModeState(
            isActive = false, buildingName = "", gridX = 0, gridY = 0,
            worldX = 0f, worldY = 0f,
            size = GridSnapHelper.BuildingSize(2, 3),
            validity = GridSnapHelper.PlacementValidity.Valid
        )
    }
}

/** 移动模式状态 */
data class MoveModeState(
    val isActive: Boolean,
    val building: GridBuildingData?,
    val gridX: Int,
    val gridY: Int,
    val worldX: Float,
    val worldY: Float,
    val size: GridSnapHelper.BuildingSize,
    val validity: GridSnapHelper.PlacementValidity
) {
    companion object {
        val INACTIVE = MoveModeState(
            isActive = false, building = null, gridX = 0, gridY = 0,
            worldX = 0f, worldY = 0f,
            size = GridSnapHelper.BuildingSize(2, 3),
            validity = GridSnapHelper.PlacementValidity.Valid
        )
    }
}

/**
 * 金手指模式状态 — 一键批量建造。
 */
@Immutable
data class GoldFingerState(
    val isActive: Boolean = false,
    val startGridX: Int = 0,
    val startGridY: Int = 0,
    val endGridX: Int = 0,
    val endGridY: Int = 0,
    val buildingName: String = "",
    val buildingSize: GridSnapHelper.BuildingSize = GridSnapHelper.BuildingSize(2, 2),
    val buildingCost: Long = 0L,
    val totalCost: Long = 0L,
    val canAfford: Boolean = true,
    val canBuildCount: Int = 0,
    val cellValidity: Map<Long, Boolean> = emptyMap()
) {
    companion object {
        val INACTIVE = GoldFingerState()
    }
}
