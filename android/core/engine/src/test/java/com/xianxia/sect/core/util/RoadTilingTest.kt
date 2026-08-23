package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.RoadData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 道路自动拼接核心逻辑单测（镜像 C++ gamecore road_system_test）。
 *
 * 覆盖：位掩码→形态映射、边框自动判断（并行不重复描边）、5 格邻接重算、
 * 每格掩码数组构建（无道路返回 null）。
 */
class RoadTilingTest {

    @Test
    fun `tileType maps all forms`() {
        assertEquals(RoadTileType.SINGLE, RoadTiling.tileTypeForBitmask(0))
        assertEquals(RoadTileType.VERTICAL, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_UP or RoadTiling.DIR_DOWN))
        assertEquals(RoadTileType.HORIZONTAL, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_LEFT or RoadTiling.DIR_RIGHT))
        assertEquals(RoadTileType.CORNER_TOP_LEFT, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_UP or RoadTiling.DIR_LEFT))
        assertEquals(RoadTileType.CORNER_TOP_RIGHT, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_UP or RoadTiling.DIR_RIGHT))
        assertEquals(RoadTileType.CORNER_BOTTOM_LEFT, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_DOWN or RoadTiling.DIR_LEFT))
        assertEquals(RoadTileType.CORNER_BOTTOM_RIGHT, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_DOWN or RoadTiling.DIR_RIGHT))
        assertEquals(RoadTileType.T_UP, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_UP or RoadTiling.DIR_LEFT or RoadTiling.DIR_RIGHT))
        assertEquals(RoadTileType.T_RIGHT, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_UP or RoadTiling.DIR_DOWN or RoadTiling.DIR_RIGHT))
        assertEquals(RoadTileType.T_DOWN, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_DOWN or RoadTiling.DIR_LEFT or RoadTiling.DIR_RIGHT))
        assertEquals(RoadTileType.T_LEFT, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_UP or RoadTiling.DIR_DOWN or RoadTiling.DIR_LEFT))
        assertEquals(RoadTileType.CROSS, RoadTiling.tileTypeForBitmask(RoadTiling.MASK_ALL))
        // 死路（道路端点）按方向归为直路
        assertEquals(RoadTileType.VERTICAL, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_UP))
        assertEquals(RoadTileType.HORIZONTAL, RoadTiling.tileTypeForBitmask(RoadTiling.DIR_LEFT))
    }

    @Test
    fun `borderMask is complement of connection mask`() {
        assertEquals(RoadTiling.MASK_ALL, RoadTiling.roadBorderMask(0))
        // 上下连接（内部不描边）→ 只描左右 = 无道路邻居的方向
        assertEquals(
            RoadTiling.DIR_LEFT or RoadTiling.DIR_RIGHT,
            RoadTiling.roadBorderMask(RoadTiling.DIR_UP or RoadTiling.DIR_DOWN)
        )
        // 十字（四边全通）→ 不描任何边
        assertEquals(0, RoadTiling.roadBorderMask(RoadTiling.MASK_ALL))
    }

    @Test
    fun `place horizontal pair both horizontal`() {
        val roads = listOf(
            RoadData(3, 4, 0, RoadTileType.SINGLE.name),
            RoadData(4, 4, 0, RoadTileType.SINGLE.name)
        )
        val mask = RoadTiling.buildRoadMaskArray(roads, 10, 10)
        val m34 = mask!![4 * 10 + 3]
        val m44 = mask!![4 * 10 + 4]
        assertEquals(RoadTileType.HORIZONTAL, RoadTiling.tileTypeForBitmask(m34))
        assertEquals(RoadTileType.HORIZONTAL, RoadTiling.tileTypeForBitmask(m44))
    }

    @Test
    fun `parallel roads internal edges no border`() {
        // 三行并行横向路：y=3,4,5，x=2..6
        val roads = mutableListOf<RoadData>()
        for (y in 3..5) for (x in 2..6) roads.add(RoadData(x, y, 0, RoadTileType.SINGLE.name))
        val mask = RoadTiling.buildRoadMaskArray(roads, 10, 10)!!
        // 中间行 y=4 的内部格（上/下/左/右皆道路）→ 十字，且描边掩码为 0
        val mid = RoadTiling.tileTypeForBitmask(mask[4 * 10 + 4])
        assertEquals(RoadTileType.CROSS, mid)
        assertEquals(0, RoadTiling.roadBorderMask(mask[4 * 10 + 4]))
        // 顶行 y=3 只需描上边（边界），内部左/右/下不描
        assertEquals(RoadTiling.DIR_UP, RoadTiling.roadBorderMask(mask[3 * 10 + 4]))
    }

    @Test
    fun `no roads returns null`() {
        assertNull(RoadTiling.buildRoadMaskArray(emptyList(), 8, 8))
    }
}
