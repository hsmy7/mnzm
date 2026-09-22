package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.GridBuildingData

class GridSystem(
    val tileSize: Int,
    val gridWidthCells: Int,
    val gridHeightCells: Int,
    /** 距离地图边界不可建造的格数（0 = 无限制）。 */
    val buildableBorder: Int = 0,
    /** 固定结构禁建格（packed cell，如宗门入口门楼/阶梯占地）。 */
    val blockedCells: Set<Long> = emptySet(),
    /**
     * 弯曲地皮轮廓逐格掩码（地图边缘 v2；行主序 `cols×rows`，位组同
     * GroundBoundaryBridge：bit0 = 格四角在轮廓内）。
     *
     * 非空时：占地格任一格缺 bit0 → [GridSnapHelper.PlacementValidity.OutOfBounds]
     * （**轮廓外草地不可建**——拖拽预览据此变红）。第一阶段轮廓钳在
     * [com.xianxia.sect.core.GameConfig.SectMap.BORDER_TREE_RING] 树环带内，
     * 本判定与矩形环判定**逐格等价**（行为零变化，等价性由
     * `GridSystemGroundBoundaryTest` 逐格锁定）；第二阶段曲线加深/随机化后
     * 禁建自动跟随曲线。null = 不启用（纯矩形口径，向后兼容）。
     */
    val buildableMask: ByteArray? = null
) {
    private var _buildings: List<GridBuildingData> = emptyList()
    val buildings: List<GridBuildingData> get() = _buildings

    private var _occupiedCells: Set<Long> = emptySet()
    val occupiedCells: Set<Long> get() = _occupiedCells

    fun rebuildFrom(buildings: List<GridBuildingData>) {
        _buildings = buildings
        _occupiedCells = computeOccupiedCells(buildings)
    }

    fun validatePlacement(
        gridX: Int,
        gridY: Int,
        width: Int,
        height: Int
    ): GridSnapHelper.PlacementValidity {
        val outOfBounds = gridX < buildableBorder || gridY < buildableBorder ||
            gridX + width > gridWidthCells - buildableBorder ||
            gridY + height > gridHeightCells - buildableBorder
        if (outOfBounds) {
            return GridSnapHelper.PlacementValidity.OutOfBounds
        }
        // 弯曲地皮轮廓判定（地图边缘 v2）：占地格任一格在轮廓外（缺 bit0）
        // → OutOfBounds——边缘草地/轮廓外不可建（与矩形越界同态：红框 + 禁放）
        if (footprintOutsideBoundary(gridX, gridY, width, height)) {
            return GridSnapHelper.PlacementValidity.OutOfBounds
        }
        firstOverlap(gridX, gridY, width, height)?.let { return it }
        return GridSnapHelper.PlacementValidity.Valid
    }

    /** 弯曲地皮轮廓判定：占地格任一格在轮廓外（缺 bit0）= true */
    private fun footprintOutsideBoundary(gridX: Int, gridY: Int, width: Int, height: Int): Boolean {
        val mask = buildableMask ?: return false
        for (cx in gridX until gridX + width) {
            for (cy in gridY until gridY + height) {
                val idx = cy * gridWidthCells + cx
                val bits = if (idx in mask.indices) mask[idx].toInt() else 0
                if (bits and MASK_BIT_QUAD == 0) return true
            }
        }
        return false
    }

    /** 首个冲突：占用格（返回重叠建筑名列表）/ 固定结构（返回禁建区名）；无冲突 = null */
    private fun firstOverlap(
        gridX: Int,
        gridY: Int,
        width: Int,
        height: Int
    ): GridSnapHelper.PlacementValidity.Overlap? {
        for (cx in gridX until gridX + width) {
            for (cy in gridY until gridY + height) {
                val cell = packCell(cx, cy)
                if (cell in _occupiedCells) {
                    val overlapped = _buildings.filter { b ->
                        gridX < b.gridX + b.width &&
                            gridX + width > b.gridX &&
                            gridY < b.gridY + b.height &&
                            gridY + height > b.gridY
                    }.map { it.displayName }
                    return GridSnapHelper.PlacementValidity.Overlap(overlapped)
                }
                if (cell in blockedCells) {
                    return GridSnapHelper.PlacementValidity.Overlap(listOf(BLOCKED_REGION_LABEL))
                }
            }
        }
        return null
    }

    fun placeBuilding(building: GridBuildingData): Boolean {
        if (validatePlacement(building.gridX, building.gridY, building.width, building.height)
            != GridSnapHelper.PlacementValidity.Valid
        ) return false
        _buildings = _buildings + building
        for (cx in building.gridX until building.gridX + building.width) {
            for (cy in building.gridY until building.gridY + building.height) {
                _occupiedCells = _occupiedCells + packCell(cx, cy)
            }
        }
        return true
    }

    fun snapWorldToGrid(worldX: Float, worldY: Float): Pair<Int, Int> =
        GridSnapHelper.worldToGrid(worldX, tileSize) to
        GridSnapHelper.worldToGrid(worldY, tileSize)

    fun gridToWorld(gridX: Int, gridY: Int): Pair<Int, Int> =
        GridSnapHelper.gridToWorld(gridX, tileSize) to
        GridSnapHelper.gridToWorld(gridY, tileSize)

    companion object {
        const val DEFAULT_WORLD_WIDTH_CELLS = 28
        const val DEFAULT_WORLD_HEIGHT_CELLS = 28

        /** 轮廓掩码位：bit0 = 格四角在轮廓内（跨语言常量，值 = GroundBoundaryBridge.MASK_BIT_QUAD） */
        const val MASK_BIT_QUAD = 1

        /** 固定结构禁建区域的 UI 展示名（重叠提示用）。 */
        const val BLOCKED_REGION_LABEL = "宗门入口"

        fun packCell(x: Int, y: Int): Long =
            (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFF)
    }

    private fun computeOccupiedCells(buildings: List<GridBuildingData>): Set<Long> {
        val cells = mutableSetOf<Long>()
        buildings.forEach { b ->
            for (cx in b.gridX until b.gridX + b.width) {
                for (cy in b.gridY until b.gridY + b.height) {
                    cells.add(packCell(cx, cy))
                }
            }
        }
        return cells
    }
}
