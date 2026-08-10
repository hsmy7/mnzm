package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.core.util.GridSystem
import com.xianxia.sect.ui.game.main.GoldFingerSelection
import com.xianxia.sect.ui.game.main.clampGoldFingerSelection
import com.xianxia.sect.ui.game.main.clampToBuildableRange
import com.xianxia.sect.ui.game.main.computeGoldFingerCellValidities
import com.xianxia.sect.ui.game.main.recomputeGoldFingerState
import com.xianxia.sect.ui.game.main.translateGoldFingerSelection
import com.xianxia.sect.ui.game.sect.GoldFingerState
import org.junit.Assert.*
import org.junit.Test

class GoldFingerBuildTest {

    @Test
    fun `computeGoldFingerCellValidities - empty grid returns valid for all cells`() {
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 5, endGridY = 5,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 28, worldHeightCells = 28
        )
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.value })
        // 6x6 area with 2x2 steps = 3x3 = 9 cells
        assertEquals(9, result.size)
    }

    @Test
    fun `computeGoldFingerCellValidities - occupied cell returns invalid`() {
        val existing = listOf(
            GridBuildingData(
                buildingId = "mine", displayName = "灵矿场",
                gridX = 2, gridY = 2, width = 2, height = 2
            )
        )
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 5, endGridY = 5,
            buildingW = 2, buildingH = 2,
            existingBuildings = existing,
            worldWidthCells = 28, worldHeightCells = 28
        )
        val overlapKey = GridSystem.packCell(2, 2)
        assertFalse(result[overlapKey] ?: true)
        // Other valid cells should still be valid
        val validKey = GridSystem.packCell(0, 0)
        assertTrue(result[validKey] ?: false)
    }

    @Test
    fun `computeGoldFingerCellValidities - partial cells at edge are skipped`() {
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 5, endGridY = 5,
            buildingW = 3, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 7, worldHeightCells = 7
        )
        // 6x6 area with 3x2 steps = 2x3 = 6 cells
        assertEquals(6, result.size)
        // Grid is 7x7, building is 3x2 -> at step (6,4) the building would exceed bounds
        val edgeKey = GridSystem.packCell(6, 4)
        assertNull(result[edgeKey])
    }

    @Test
    fun `computeGoldFingerCellValidities - revertible selection uses minOf and maxOf`() {
        // Dragging in reverse direction: end < start
        val result = computeGoldFingerCellValidities(
            startGridX = 6, startGridY = 6,
            endGridX = 2, endGridY = 2,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 28, worldHeightCells = 28
        )
        assertEquals(4, result.size) // 2..6 width=4, 2x2 steps = 2x2 = 4 cells
        val key22 = GridSystem.packCell(2, 2)
        val key44 = GridSystem.packCell(4, 4)
        assertTrue(result[key22] ?: false)
        assertTrue(result[key44] ?: false)
    }

    @Test
    fun `computeGoldFingerCellValidities - single cell cant fit 2x2 building`() {
        val result = computeGoldFingerCellValidities(
            startGridX = 3, startGridY = 3,
            endGridX = 3, endGridY = 3,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 28, worldHeightCells = 28
        )
        assertEquals(0, result.size)
    }

    @Test
    fun `computeGoldFingerCellValidities - out of bounds is clamped`() {
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 30, endGridY = 30,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 10, worldHeightCells = 10
        )
        // should not crash, should clamp to 10x10 = 5x5 = 25 cells
        assertEquals(25, result.size)
    }

    @Test
    fun `computeGoldFingerCellValidities - buildableBorder clamps selection inward`() {
        // 10x10 网格，buildableBorder=3，有效区为 (3..6)
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 9, endGridY = 9,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 10, worldHeightCells = 10,
            buildableBorder = 3
        )
        // 被 clamp 到 (3..6)，步长 2 → 3,5 两行/列 = 2x2 = 4 个
        assertEquals(4, result.size)
        // 3,3 和 5,5 在范围内，应有效
        assertTrue(result[GridSystem.packCell(3, 3)] ?: false)
        assertTrue(result[GridSystem.packCell(5, 5)] ?: false)
        // 0,0 不在结果中（已被 clamp）
        assertNull(result[GridSystem.packCell(0, 0)])
        // 7,7 不在结果中（6+2=8 > 10-3=7，越界步进被跳过）
        assertNull(result[GridSystem.packCell(7, 7)])
    }

    @Test
    fun `computeGoldFingerCellValidities - buildableBorder 0 same as original`() {
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 5, endGridY = 5,
            buildingW = 2, buildingH = 2,
            existingBuildings = emptyList(),
            worldWidthCells = 28, worldHeightCells = 28,
            buildableBorder = 0
        )
        assertEquals(9, result.size)
        assertTrue(result[GridSystem.packCell(0, 0)] ?: false)
    }

    @Test
    fun `computeGoldFingerCellValidities - multiple existing buildings`() {
        val existing = listOf(
            GridBuildingData(buildingId = "a", displayName = "A", gridX = 0, gridY = 0, width = 2, height = 2),
            GridBuildingData(buildingId = "b", displayName = "B", gridX = 4, gridY = 4, width = 2, height = 2)
        )
        val result = computeGoldFingerCellValidities(
            startGridX = 0, startGridY = 0,
            endGridX = 7, endGridY = 7,
            buildingW = 2, buildingH = 2,
            existingBuildings = existing,
            worldWidthCells = 10, worldHeightCells = 10
        )
        // 8x8 area, 2x2 steps = 4x4 = 16 cells, 2 overlap
        assertEquals(16, result.size)
        assertFalse(result[GridSystem.packCell(0, 0)] ?: true)
        assertFalse(result[GridSystem.packCell(4, 4)] ?: true)
        assertTrue(result[GridSystem.packCell(2, 0)] ?: false)
        assertTrue(result[GridSystem.packCell(6, 6)] ?: false)
    }

    // ========================
    // 金手指选区纯函数（Bug 1/2/3 修复支撑）
    // ========================

    @Test
    fun `clampToBuildableRange - inside range unchanged`() {
        assertEquals(5, clampToBuildableRange(5, 28, 3))
        assertEquals(3, clampToBuildableRange(3, 28, 3))   // 下界
        assertEquals(24, clampToBuildableRange(24, 28, 3)) // 上界
    }

    @Test
    fun `clampToBuildableRange - below and above are clamped`() {
        assertEquals(3, clampToBuildableRange(0, 28, 3))
        assertEquals(3, clampToBuildableRange(-5, 28, 3))
        assertEquals(24, clampToBuildableRange(100, 28, 3))
    }

    @Test
    fun `clampToBuildableRange - border 0 keeps original`() {
        assertEquals(0, clampToBuildableRange(0, 10, 0))
        assertEquals(9, clampToBuildableRange(9, 10, 0))
    }

    @Test
    fun `clampToBuildableRange - invalid border returns original value`() {
        // border=6 → maxAllowed=3，lo>hi 时 coerceIn 会抛异常 — 前置防御返回原值
        assertEquals(2, clampToBuildableRange(2, 10, 6))
    }

    @Test
    fun `clampGoldFingerSelection - both corners clamped independently`() {
        val sel = GoldFingerSelection(
            startGridX = 0, startGridY = 100,
            endGridX = 20, endGridY = -5
        )
        val clamped = clampGoldFingerSelection(sel, 28, 28, 3)
        assertEquals(3, clamped.startGridX)
        assertEquals(24, clamped.startGridY) // 100 → 24
        assertEquals(20, clamped.endGridX)   // 20 在 (3..24) 内不变
        assertEquals(3, clamped.endGridY)    // -5 → 3
    }

    @Test
    fun `translateGoldFingerSelection - moves both corners and clamps`() {
        val sel = GoldFingerSelection(startGridX = 5, startGridY = 5, endGridX = 10, endGridY = 10)
        val moved = translateGoldFingerSelection(sel, 2, 3, 28, 28, 3)
        assertEquals(GoldFingerSelection(7, 8, 12, 13), moved)
    }

    @Test
    fun `translateGoldFingerSelection - clamped at border keeps span`() {
        // 20..24 平移 +3 → 23..27，终点 27 被钳到 24（树环边界反馈）
        val sel = GoldFingerSelection(startGridX = 20, startGridY = 5, endGridX = 24, endGridY = 10)
        val moved = translateGoldFingerSelection(sel, 3, 0, 28, 28, 3)
        assertEquals(23, moved.startGridX)
        assertEquals(24, moved.endGridX)
        assertEquals(10, moved.endGridY)
    }

    @Test
    fun `translateGoldFingerSelection - fully out of bounds degenerates safely`() {
        // 完全越界：两端都钉在边界，选区塌缩为单点；recompute 返回空 map 不崩溃
        val sel = GoldFingerSelection(startGridX = 0, startGridY = 0, endGridX = 5, endGridY = 5)
        val moved = translateGoldFingerSelection(sel, -50, -50, 28, 28, 3)
        assertEquals(3, moved.startGridX)
        assertEquals(3, moved.endGridX)
        val recomputed = recomputeGoldFingerState(
            f = goldFingerState(2, 2),
            sel = moved,
            existingBuildings = emptyList(),
            worldWidthCells = 28, worldHeightCells = 28,
            buildableBorder = 3,
            spiritStones = 100L
        )
        assertTrue(recomputed.cellValidity.isEmpty())
        assertEquals(0, recomputed.canBuildCount)
    }

    @Test
    fun `recomputeGoldFingerState - 2x2 building single cell cannot build`() {
        val f = goldFingerState(2, 2)
        val sel = GoldFingerSelection(3, 3, 3, 3)
        val r = recomputeGoldFingerState(f, sel, emptyList(), 28, 28, 3, 100L)
        assertEquals(0, r.canBuildCount)
        assertEquals(0L, r.totalCost)
        assertTrue(r.canAfford)
        assertEquals(3, r.startGridX)
        assertEquals(3, r.endGridX)
    }

    @Test
    fun `recomputeGoldFingerState - multi cell cost and canAfford`() {
        val f = goldFingerState(2, 2, cost = 50)
        val sel = GoldFingerSelection(3, 3, 8, 8) // 6x6 / 2x2 步进 = 3x3 = 9 格
        val r = recomputeGoldFingerState(f, sel, emptyList(), 28, 28, 3, 100L)
        assertEquals(9, r.canBuildCount)
        assertEquals(450L, r.totalCost)
        assertFalse(r.canAfford) // 450 > 100
    }

    @Test
    fun `recomputeGoldFingerState - keys shift after translation`() {
        // 平移后 cellValidity 的 key 必须跟随新锚点（Bug 2 核心：建造区随预览移动）
        val f = goldFingerState(2, 2, cost = 10)
        val sel = GoldFingerSelection(3, 3, 8, 8) // 6x6 → 2x2 步进 = 3x3 = 9 格，key 为 3,3 / 5,5 / 7,7
        val r1 = recomputeGoldFingerState(f, sel, emptyList(), 28, 28, 3, 100L)
        assertTrue(r1.cellValidity.containsKey(GridSystem.packCell(3, 3)))
        assertEquals(9, r1.canBuildCount)
        val moved = translateGoldFingerSelection(sel, 2, 0, 28, 28, 3)
        val r2 = recomputeGoldFingerState(f, moved, emptyList(), 28, 28, 3, 100L)
        assertTrue(r2.cellValidity.containsKey(GridSystem.packCell(5, 3)))
        assertFalse(r2.cellValidity.containsKey(GridSystem.packCell(3, 3)))
        assertEquals(9, r2.canBuildCount)
    }

    private fun goldFingerState(w: Int, h: Int, cost: Long = 10): GoldFingerState =
        GoldFingerState(
            isActive = true,
            buildingName = "测试建筑",
            buildingSize = GridSnapHelper.BuildingSize(w, h),
            buildingCost = cost
        )
}
