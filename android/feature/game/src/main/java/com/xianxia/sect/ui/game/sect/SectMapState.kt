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
    /** 预过滤的灵田建筑列表 — 避免渲染时反复遍历全量建筑列表 */
    val spiritFieldBuildings: List<GridBuildingData> = emptyList(),
    val cropBitmaps: Map<String, ImageBitmap> = emptyMap(),
    val currentGameYear: Int = 1,
    val currentGameMonth: Int = 1,
    val goldenFingerBmp: ImageBitmap? = null
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

/**
 * 金手指模式状态 — 一键批量建造。
 *
 * [isActive] — 是否处于金手指模式
 * [startGridX/Y] — 长按起始格（金手指图标所在格）
 * [endGridX/Y] — 当前拖拽末端格
 * [buildingName] — 所选建筑名
 * [buildingSize] — 所选建筑尺寸
 * [buildingCost] — 单座建筑灵石消耗
 * [totalCost] — 框选区内总灵石消耗（= canBuildCount × buildingCost）
 * [canAfford] — 剩余灵石是否足够支付 totalCost
 * [canBuildCount] — 框选区内可建造数量
 * [cellValidity] — 每格有效性，键=packedCell(x,y)，值=true(可建)/false(被占)
 */
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
    /** 键=packedCell, 值=true(可建)/false(已被占) */
    val cellValidity: Map<Long, Boolean> = emptyMap()
) {
    companion object {
        val INACTIVE = GoldFingerState()
    }
}
