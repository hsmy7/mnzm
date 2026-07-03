package com.xianxia.sect.ui.game.main

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.GridSystem

/**
 * 金手指框选区域内每格有效性计算。
 * 以 buildingW×buildingH 为步长遍历，每个地块不重叠。
 */
internal fun computeGoldFingerCellValidities(
    startGridX: Int, startGridY: Int,
    endGridX: Int, endGridY: Int,
    buildingW: Int, buildingH: Int,
    existingBuildings: List<GridBuildingData>,
    worldWidthCells: Int, worldHeightCells: Int
): Map<Long, Boolean> {
    val minX = minOf(startGridX, endGridX).coerceIn(0, worldWidthCells - 1)
    val maxX = maxOf(startGridX, endGridX).coerceIn(0, worldWidthCells - 1)
    val minY = minOf(startGridY, endGridY).coerceIn(0, worldHeightCells - 1)
    val maxY = maxOf(startGridY, endGridY).coerceIn(0, worldHeightCells - 1)

    // 预计算已有建筑占用格
    val occupiedCells = mutableSetOf<Long>()
    for (b in existingBuildings) {
        for (cx in b.gridX until b.gridX + b.width) {
            for (cy in b.gridY until b.gridY + b.height) {
                occupiedCells.add(GridSystem.packCell(cx, cy))
            }
        }
    }

    val result = mutableMapOf<Long, Boolean>()
    var gx = minX
    while (gx + buildingW - 1 <= maxX && gx + buildingW <= worldWidthCells) {
        var gy = minY
        while (gy + buildingH - 1 <= maxY && gy + buildingH <= worldHeightCells) {
            val startCellKey = GridSystem.packCell(gx, gy)
            val noOverlap = (gx until gx + buildingW).all { cx ->
                (gy until gy + buildingH).all { cy ->
                    GridSystem.packCell(cx, cy) !in occupiedCells
                }
            }
            result[startCellKey] = noOverlap
            gy += buildingH
        }
        gx += buildingW
    }
    return result
}
