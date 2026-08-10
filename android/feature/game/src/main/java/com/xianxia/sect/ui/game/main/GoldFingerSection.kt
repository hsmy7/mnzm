package com.xianxia.sect.ui.game.main

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.GridSystem
import com.xianxia.sect.ui.game.sect.GoldFingerState

/**
 * 金手指框选区域内每格有效性计算。
 * 以 buildingW×buildingH 为步长遍历，每个地块不重叠。
 */
internal fun computeGoldFingerCellValidities(
    startGridX: Int, startGridY: Int,
    endGridX: Int, endGridY: Int,
    buildingW: Int, buildingH: Int,
    existingBuildings: List<GridBuildingData>,
    worldWidthCells: Int, worldHeightCells: Int,
    buildableBorder: Int = 0
): Map<Long, Boolean> {
    // 防御性检查：若 buildableBorder 过大导致有效范围为负，返回空 map（防 IllegalArgumentException）
    if (buildableBorder > worldWidthCells - 1 - buildableBorder ||
        buildableBorder > worldHeightCells - 1 - buildableBorder
    ) return emptyMap()

    val minX = clampToBuildableRange(minOf(startGridX, endGridX), worldWidthCells, buildableBorder)
    val maxX = clampToBuildableRange(maxOf(startGridX, endGridX), worldWidthCells, buildableBorder)
    val minY = clampToBuildableRange(minOf(startGridY, endGridY), worldHeightCells, buildableBorder)
    val maxY = clampToBuildableRange(maxOf(startGridY, endGridY), worldHeightCells, buildableBorder)

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

/**
 * 金手指选区 — 两角格坐标的纯数据载体（起点/终点可任意顺序）。
 */
internal data class GoldFingerSelection(
    val startGridX: Int,
    val startGridY: Int,
    val endGridX: Int,
    val endGridY: Int
)

/**
 * 单格坐标钳制到可建区 [buildableBorder, worldCells-1-buildableBorder]。
 * 与 [computeGoldFingerCellValidities] 内部钳制同公式，保证视觉框 == 实际建造区。
 * 无效范围（border 过大导致 lo>hi）返回原值 — coerceIn 在 lo>hi 时会抛 IllegalArgumentException。
 */
internal fun clampToBuildableRange(value: Int, worldCells: Int, buildableBorder: Int): Int {
    val maxAllowed = worldCells - 1 - buildableBorder
    if (buildableBorder > maxAllowed) return value
    return value.coerceIn(buildableBorder, maxAllowed)
}

/**
 * 选区两端点分别钳制到可建区（起点/终点独立钳制，顺序语义由 compute 的 minOf/maxOf 保证）。
 */
internal fun clampGoldFingerSelection(
    sel: GoldFingerSelection,
    worldWidthCells: Int,
    worldHeightCells: Int,
    buildableBorder: Int
): GoldFingerSelection = GoldFingerSelection(
    startGridX = clampToBuildableRange(sel.startGridX, worldWidthCells, buildableBorder),
    startGridY = clampToBuildableRange(sel.startGridY, worldHeightCells, buildableBorder),
    endGridX = clampToBuildableRange(sel.endGridX, worldWidthCells, buildableBorder),
    endGridY = clampToBuildableRange(sel.endGridY, worldHeightCells, buildableBorder)
)

/**
 * 选区平移（以格为单位），平移后钳制到可建区。
 * 用于放置模式拖动建筑预览时选区同步跟随（Bug 2 修复）。
 */
internal fun translateGoldFingerSelection(
    sel: GoldFingerSelection,
    dx: Int,
    dy: Int,
    worldWidthCells: Int,
    worldHeightCells: Int,
    buildableBorder: Int
): GoldFingerSelection = clampGoldFingerSelection(
    GoldFingerSelection(
        startGridX = sel.startGridX + dx,
        startGridY = sel.startGridY + dy,
        endGridX = sel.endGridX + dx,
        endGridY = sel.endGridY + dy
    ),
    worldWidthCells,
    worldHeightCells,
    buildableBorder
)

/**
 * 依选区重算金手指状态（validity/count/cost/canAfford）。
 * 激活、拖动、平移三条路径共用（DRY），避免各处重复计算。
 */
internal fun recomputeGoldFingerState(
    f: GoldFingerState,
    sel: GoldFingerSelection,
    existingBuildings: List<GridBuildingData>,
    worldWidthCells: Int,
    worldHeightCells: Int,
    buildableBorder: Int,
    spiritStones: Long
): GoldFingerState {
    val validity = computeGoldFingerCellValidities(
        startGridX = sel.startGridX,
        startGridY = sel.startGridY,
        endGridX = sel.endGridX,
        endGridY = sel.endGridY,
        buildingW = f.buildingSize.width,
        buildingH = f.buildingSize.height,
        existingBuildings = existingBuildings,
        worldWidthCells = worldWidthCells,
        worldHeightCells = worldHeightCells,
        buildableBorder = buildableBorder
    )
    val canBuildCount = validity.count { it.value }
    val totalCost = canBuildCount * f.buildingCost
    return f.copy(
        startGridX = sel.startGridX,
        startGridY = sel.startGridY,
        endGridX = sel.endGridX,
        endGridY = sel.endGridY,
        totalCost = totalCost,
        canAfford = spiritStones >= totalCost,
        canBuildCount = canBuildCount,
        cellValidity = validity
    )
}
