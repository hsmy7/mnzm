package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.GridBuildingData
import javax.inject.Inject

/**
 * 建筑空间索引 — O(1) 替代 O(n) 线性查找
 * 将建筑按网格单元索引，触控检测时直接定位到对应格子的建筑
 */
class BuildingSpatialIndex @Inject constructor() {
    private val grid = mutableMapOf<Long, MutableList<GridBuildingData>>()

    fun rebuild(buildings: List<GridBuildingData>) {
        grid.clear()
        buildings.forEach { add(it) }
    }

    fun add(building: GridBuildingData) {
        for (cx in building.gridX until building.gridX + building.width) {
            for (cy in building.gridY until building.gridY + building.height) {
                val k = key(cx, cy)
                grid.getOrPut(k) { mutableListOf() }.add(building)
            }
        }
    }

    fun remove(instanceId: String) {
        grid.values.forEach { it.removeAll { b -> b.instanceId == instanceId } }
    }

    fun findBuildingAt(gridX: Int, gridY: Int): GridBuildingData? = grid[key(gridX, gridY)]?.firstOrNull()

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFF)

    fun clear() = grid.clear()
}
