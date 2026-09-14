package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.render.SpriteAtlasDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 建筑占位瓦片标记测试（applyBuildingOccupancy）。
 *
 * 语义守护：占位标记基于"纯地形基座 copyOf + 脚印标记"——
 * 脚印越界格跳过、无建筑零复制、基座不可变。
 */
class SectTileOccupancyTest {

    private val cols = 8
    private val rows = 6

    /** 纯地形基座：格值 = index % 7（可区分"恢复地形"与"占位 6"） */
    private fun base(): IntArray = IntArray(cols * rows) { (it % 7) + 10 }

    private fun building(
        gx: Int, gy: Int, w: Int = 2, h: Int = 2, id: String = "b1"
    ): GridBuildingData = GridBuildingData(
        buildingId = id, displayName = id, gridX = gx, gridY = gy,
        width = w, height = h, instanceId = id, sectId = ""
    )

    @Test
    fun `empty buildings returns base reference without copy`() {
        val base = base()
        assertSame("无建筑必须直用基座（零复制）", base, applyBuildingOccupancy(base, emptyList(), cols))
    }

    @Test
    fun `footprint cells marked terrain preserved`() {
        val base = base()
        val out = applyBuildingOccupancy(base, listOf(building(1, 1)), cols)
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val idx = y * cols + x
                val expected = if (x in 1..2 && y in 1..2) SpriteAtlasDef.TileType.TILE_BUILDING.index else base[idx]
                assertEquals("($x,$y)", expected, out[idx])
            }
        }
    }

    @Test
    fun `base array not mutated`() {
        val base = base()
        val snapshot = base.copyOf()
        applyBuildingOccupancy(base, listOf(building(0, 0), building(3, 2)), cols)
        assertEquals("基座必须不可变（每种子一次复用）", snapshot.toList(), base.toList())
    }

    @Test
    fun `out of range footprint skipped`() {
        val base = base()
        val out = applyBuildingOccupancy(
            base,
            listOf(
                building(-1, -1),               // 左上越界（4 格中仅 (0,0) 在图内）
                building(cols - 1, rows - 1),   // 右下越界（4 格中仅 (7,5) 在图内）
            ),
            cols
        )
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val covered = (x == 0 && y == 0) || (x == cols - 1 && y == rows - 1)
                assertEquals(
                    "($x,$y)",
                    if (covered) SpriteAtlasDef.TileType.TILE_BUILDING.index else base[y * cols + x],
                    out[y * cols + x]
                )
            }
        }
    }

    @Test
    fun `move building equals remove then add`() {
        val base = base()
        val moved = listOf(building(4, 3, id = "b1"))
        // 拿起（空列表）后放下（新位置）——与一次到位的新列表产物一致
        val picked = applyBuildingOccupancy(base, emptyList(), cols)
        val placed = applyBuildingOccupancy(picked, moved, cols)
        val direct = applyBuildingOccupancy(base, moved, cols)
        assertEquals(direct.toList(), placed.toList())
    }

    @Test
    fun `multiple buildings all marked`() {
        val base = base()
        val buildings = listOf(
            building(0, 0),
            building(4, 0),
            building(0, 3),
            building(5, 3, w = 3, h = 2),
        )
        val out = applyBuildingOccupancy(base, buildings, cols)
        val buildingTile = SpriteAtlasDef.TileType.TILE_BUILDING.index
        for (b in buildings) {
            for (y in b.gridY until b.gridY + b.height) {
                for (x in b.gridX until b.gridX + b.width) {
                    assertEquals("建筑 ${b.instanceId} 脚本格 ($x,$y)", buildingTile, out[y * cols + x])
                }
            }
        }
    }
}
