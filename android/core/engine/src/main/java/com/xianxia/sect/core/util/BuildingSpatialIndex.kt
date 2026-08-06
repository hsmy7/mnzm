package com.xianxia.sect.core.util

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GridBuildingData
import kotlin.math.ceil
import kotlin.math.floor
import javax.inject.Inject

/**
 * 建筑空间索引 — O(1) 替代 O(n) 线性查找
 * 将建筑按网格单元索引，触控检测时直接定位到对应格子的建筑
 */
class BuildingSpatialIndex @Inject constructor() {
    private val grid = mutableMapOf<Long, MutableList<GridBuildingData>>()

    /**
     * @param spriteSizes 建筑精灵视觉尺寸映射（displayName → 精灵宽高格数）。
     * 命中区域 = 占地 ∪ 精灵包围盒：精灵按"水平居中 + 底部对齐"绘制在占地之上
     * （渲染公式 offsetX=(fpW-sw)/2、offsetY=(fpH-sh)，与 SoftwareCanvasBackend /
     * NativeBridge 一致），高层建筑（塔楼/藏经阁等）上半身悬空在占地上方，
     * 此前点击无效。缺省时仅索引占地（旧调用方与未知建筑回退语义）。
     */
    fun rebuild(
        buildings: List<GridBuildingData>,
        spriteSizes: Map<String, GridSnapHelper.BuildingSize> = emptyMap()
    ) {
        grid.clear()
        buildings.forEach { add(it, spriteSizes) }
    }

    fun add(building: GridBuildingData, spriteSizes: Map<String, GridSnapHelper.BuildingSize> = emptyMap()) {
        val fpW = BuildingFeatureRegistry.findByDisplayName(building.displayName)?.gridWidth ?: building.width
        val fpH = BuildingFeatureRegistry.findByDisplayName(building.displayName)?.gridHeight ?: building.height
        val sprite = spriteSizes[building.displayName]
        val sw = sprite?.width ?: building.width
        val sh = sprite?.height ?: building.height

        // 精灵包围盒的格子范围（与渲染偏移公式对齐；水平居中可能产生半格偏移，须上下取整）
        val offsetX = (fpW - sw) / 2.0
        val spriteXStart = floor(building.gridX + offsetX).toInt()
        val spriteXEnd = ceil(building.gridX + offsetX + sw).toInt()
        val spriteYStart = building.gridY + (fpH - sh)
        val spriteYEnd = spriteYStart + sh

        // 命中区域 = 占地 ∪ 精灵包围盒（精灵小于占地时保留占地可点区域）
        val xStart = minOf(spriteXStart, building.gridX)
        val xEnd = maxOf(spriteXEnd, building.gridX + building.width)
        val yStart = minOf(spriteYStart, building.gridY)
        val yEnd = maxOf(spriteYEnd, building.gridY + building.height)

        // 防御损坏存档：width/height 异常巨大时钳制循环范围，
        // 防止主线程亿级迭代 ANR（合法建筑占地 ≤8 格、精灵 ≤6×8，128 远大于任何合法尺寸）
        val xEndClamped = minOf(xEnd, xStart + MAX_HIT_EXTENT_CELLS)
        val yEndClamped = minOf(yEnd, yStart + MAX_HIT_EXTENT_CELLS)
        for (cx in xStart until xEndClamped) {
            for (cy in yStart until yEndClamped) {
                val k = key(cx, cy)
                grid.getOrPut(k) { mutableListOf() }.add(building)
            }
        }
    }

    private companion object {
        /** 单建筑命中区域的单轴最大扩展格数（钳制损坏数据，防御 ANR） */
        const val MAX_HIT_EXTENT_CELLS = 128
    }

    fun remove(instanceId: String) {
        grid.values.forEach { it.removeAll { b -> b.instanceId == instanceId } }
    }

    /**
     * 命中判定。重叠格（精灵包围盒相交）时返回**渲染绘制顺序最上层**的建筑：
     * 绘制顺序 = `gridY + height` 升序（与 buildBuildingDataArray 的 sortedBy 键一致，
     * 下方建筑后绘制压住上方），同键并列时后插入者后绘制（stable sort 语义）。
     * 避免"点 A 弹 B"的确定性误触。
     */
    fun findBuildingAt(gridX: Int, gridY: Int): GridBuildingData? {
        val candidates = grid[key(gridX, gridY)] ?: return null
        var best: GridBuildingData? = null
        var bestKey = Int.MIN_VALUE
        for (c in candidates) {
            val k = c.gridY + c.height
            if (k >= bestKey) {  // >=：同键并列取后插入者（绘制顺序更上层）
                bestKey = k
                best = c
            }
        }
        return best
    }

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFF)

    fun clear() = grid.clear()
}
