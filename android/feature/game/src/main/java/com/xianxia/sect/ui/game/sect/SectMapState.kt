package com.xianxia.sect.ui.game.sect

import androidx.compose.ui.graphics.ImageBitmap
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.SpiritFieldPlant
import com.xianxia.sect.core.perf.GpuRenderConfig
import com.xianxia.sect.core.util.BuildingSpatialIndex
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.ui.game.map.sect.SectCameraState

data class SectMapRenderConfig(
    val cameraState: SectCameraState,
    val tileSize: Int,
    val worldWidthCells: Int,
    val worldHeightCells: Int,
    val gpuRenderConfig: GpuRenderConfig
)

data class SectMapStaticData(
    val placedBuildings: List<GridBuildingData>,
    val buildingBitmaps: Map<String, ImageBitmap>,
    val fullMapBmp: ImageBitmap,
    val buildingsBaked: Boolean,
    val spiritFieldPlants: List<SpiritFieldPlant> = emptyList(),
    val cropBitmaps: Map<String, ImageBitmap> = emptyMap(),
    val currentGameYear: Int = 1,
    val currentGameMonth: Int = 1
)

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
            size = GridSnapHelper.BuildingSize(2, 3), validity = GridSnapHelper.PlacementValidity.Valid
        )
    }
}

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
            size = GridSnapHelper.BuildingSize(2, 3), validity = GridSnapHelper.PlacementValidity.Valid
        )
    }
}
