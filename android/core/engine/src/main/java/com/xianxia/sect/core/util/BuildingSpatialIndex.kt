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
        val feature = BuildingFeatureRegistry.findByDisplayName(building.displayName)
        val fpW = feature?.gridWidth ?: building.width
        val fpH = feature?.gridHeight ?: building.height
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

        /** 矩形查询单轴最大跨度（钳制损坏数据/极端外扩参数，防御 ANR） */
        const val MAX_QUERY_SPAN = 256
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
        return pickTopmost(candidates)
    }

    /**
     * 矩形范围命中：返回范围内绘制顺序最上层的建筑。
     * 供触控命中外扩（hit slop）使用——小建筑（灵田等 1×1）命中区按最小触控目标外扩后
     * 仍可命中，决胜规则与 [findBuildingAt] 完全一致。
     */
    fun findBuildingAtRect(x0: Int, y0: Int, x1: Int, y1: Int): GridBuildingData? =
        pickTopmost(queryRect(x0, y0, x1, y1))

    /**
     * 最近建筑兜底：命中点向外找最近建筑（世界坐标半径 [maxWorldDist]）。
     * 用于 tap 未直接命中时的宽容判定（手指落点/抬起点偏差仍可选中）；
     * 距离并列时按绘制顺序上层优先（确定性，避免"点 A 弹 B"）。
     *
     * @param worldX 命中点世界坐标 X
     * @param worldY 命中点世界坐标 Y
     * @param tileSize 格尺寸（世界像素）
     * @param maxWorldDist 最大搜索半径（世界像素）
     */
    fun findNearestBuilding(
        worldX: Float,
        worldY: Float,
        tileSize: Int,
        maxWorldDist: Float
    ): GridBuildingData? {
        if (tileSize <= 0 || !maxWorldDist.isFinite() || maxWorldDist <= 0f) return null
        val centerX = GridSnapHelper.worldToGrid(worldX, tileSize)
        val centerY = GridSnapHelper.worldToGrid(worldY, tileSize)
        val radius = ceil(maxWorldDist / tileSize).toInt()
        val maxDistSq = maxWorldDist * maxWorldDist
        var best: GridBuildingData? = null
        var bestDistSq = Float.MAX_VALUE
        for (b in queryRect(centerX - radius, centerY - radius, centerX + radius, centerY + radius)) {
            val bx = (b.gridX + b.width / 2f) * tileSize
            val by = (b.gridY + b.height / 2f) * tileSize
            val dx = bx - worldX
            val dy = by - worldY
            val d2 = dx * dx + dy * dy
            val isCloser = d2 < bestDistSq ||
                (d2 == bestDistSq && isDrawnAbove(b, best))
            if (d2 <= maxDistSq && isCloser) {
                bestDistSq = d2
                best = b
            }
        }
        return best
    }

    /**
     * 矩形范围查询（去重，保持首次出现顺序）。
     * 防御：单轴跨度钳制（损坏数据防御 ANR，与 [add] 的 MAX_HIT_EXTENT_CELLS 同理）。
     */
    fun queryRect(x0: Int, y0: Int, x1: Int, y1: Int): List<GridBuildingData> {
        val endX = x0 + minOf(x1 - x0, MAX_QUERY_SPAN - 1)
        val endY = y0 + minOf(y1 - y0, MAX_QUERY_SPAN - 1)
        val seen = LinkedHashSet<GridBuildingData>()
        for (cx in x0..endX) {
            for (cy in y0..endY) {
                grid[key(cx, cy)]?.let { seen.addAll(it) }
            }
        }
        return seen.toList()
    }

    /** 按绘制顺序取最上层（同键并列取后插入者）——[findBuildingAt] 与矩形命中共用决胜规则。 */
    private fun pickTopmost(candidates: List<GridBuildingData>): GridBuildingData? {
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

    /** 距离并列时的绘制顺序决胜：绘制顺序键（gridY + height）更大者在上。 */
    private fun isDrawnAbove(candidate: GridBuildingData, current: GridBuildingData?): Boolean =
        current == null || (candidate.gridY + candidate.height) > (current.gridY + current.height)

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFF)

    fun clear() = grid.clear()
}
