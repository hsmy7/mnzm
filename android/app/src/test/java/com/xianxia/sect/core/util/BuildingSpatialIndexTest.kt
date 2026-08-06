package com.xianxia.sect.core.util

import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.ui.game.building.registerDefaults
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BuildingSpatialIndexTest {

    private lateinit var index: BuildingSpatialIndex

    @Before
    fun setUp() {
        index = BuildingSpatialIndex()
        // 精灵包围盒扩展命中依赖 BuildingFeatureRegistry（XianxiaApplication 在测试环境不执行）
        BuildingFeatureRegistry.registerDefaults()
    }

    @Test
    fun findBuildingAt_emptyIndex_returnsNull() {
        assertNull(index.findBuildingAt(0, 0))
    }

    @Test
    fun rebuild_singleBuilding_findsAtOccupiedCells() {
        val building = GridBuildingData(
            buildingId = "b1",
            displayName = "Hall",
            gridX = 2,
            gridY = 3,
            width = 2,
            height = 2
        )
        index.rebuild(listOf(building))

        assertEquals(building, index.findBuildingAt(2, 3))
        assertEquals(building, index.findBuildingAt(3, 3))
        assertEquals(building, index.findBuildingAt(2, 4))
        assertEquals(building, index.findBuildingAt(3, 4))
    }

    @Test
    fun rebuild_singleBuilding_returnsNullOutsideBounds() {
        val building = GridBuildingData(
            buildingId = "b1",
            displayName = "Hall",
            gridX = 0,
            gridY = 0,
            width = 2,
            height = 2
        )
        index.rebuild(listOf(building))

        assertNull(index.findBuildingAt(2, 0))
        assertNull(index.findBuildingAt(0, 2))
        assertNull(index.findBuildingAt(-1, 0))
    }

    @Test
    fun rebuild_multipleBuildings_findsCorrectBuilding() {
        val b1 = GridBuildingData(buildingId = "b1", gridX = 0, gridY = 0, width = 1, height = 1)
        val b2 = GridBuildingData(buildingId = "b2", gridX = 5, gridY = 5, width = 2, height = 2)
        index.rebuild(listOf(b1, b2))

        assertEquals(b1, index.findBuildingAt(0, 0))
        assertEquals(b2, index.findBuildingAt(5, 5))
        assertEquals(b2, index.findBuildingAt(6, 6))
        assertNull(index.findBuildingAt(3, 3))
    }

    @Test
    fun rebuild_overwritesPreviousIndex() {
        val b1 = GridBuildingData(buildingId = "b1", gridX = 0, gridY = 0, width = 1, height = 1)
        index.rebuild(listOf(b1))
        assertEquals(b1, index.findBuildingAt(0, 0))

        val b2 = GridBuildingData(buildingId = "b2", gridX = 0, gridY = 0, width = 1, height = 1)
        index.rebuild(listOf(b2))
        assertEquals(b2, index.findBuildingAt(0, 0))
    }

    @Test
    fun rebuild_emptyList_clearsIndex() {
        val building = GridBuildingData(buildingId = "b1", gridX = 0, gridY = 0, width = 2, height = 2)
        index.rebuild(listOf(building))
        assertNotNull(index.findBuildingAt(0, 0))

        index.rebuild(emptyList())
        assertNull(index.findBuildingAt(0, 0))
    }

    @Test
    fun clear_emptiesIndex() {
        val building = GridBuildingData(buildingId = "b1", gridX = 0, gridY = 0, width = 2, height = 2)
        index.rebuild(listOf(building))
        assertNotNull(index.findBuildingAt(0, 0))

        index.clear()
        assertNull(index.findBuildingAt(0, 0))
    }

    @Test
    fun rebuild_largeBuilding_coversAllCells() {
        val building = GridBuildingData(buildingId = "b1", gridX = 0, gridY = 0, width = 3, height = 3)
        index.rebuild(listOf(building))

        for (x in 0 until 3) {
            for (y in 0 until 3) {
                assertNotNull("Should find building at ($x, $y)", index.findBuildingAt(x, y))
            }
        }
        assertNull(index.findBuildingAt(3, 0))
        assertNull(index.findBuildingAt(0, 3))
    }

    // ── 2026-08-06 修复：命中区域扩展为占地 ∪ 精灵包围盒（高层建筑悬空上半身可点）──

    @Test
    fun rebuild_tallBuilding_withSpriteSizes_hitsSpriteAreaAboveFootprint() {
        // 问道塔：占地 4×3，精灵 4×8（底部对齐 → 精灵向上延伸 5 格）
        val tower = GridBuildingData(
            buildingId = "t1", displayName = "问道塔",
            gridX = 10, gridY = 10, width = 4, height = 3,
            instanceId = "t1", sectId = ""
        )
        val spriteSizes = mapOf("问道塔" to GridSnapHelper.BuildingSize(4, 8))
        index.rebuild(listOf(tower), spriteSizes)

        // 占地格仍命中
        assertEquals(tower, index.findBuildingAt(10, 10))
        assertEquals(tower, index.findBuildingAt(13, 12))
        // 精灵悬空上半身（占地上方 y=10+(3-8)=5 至占地顶 10）命中
        assertEquals("塔尖悬空部分应可点击", tower, index.findBuildingAt(10, 5))
        assertEquals(tower, index.findBuildingAt(13, 6))
        // 精灵包围盒之外（塔尖上方、两侧）不命中
        assertNull(index.findBuildingAt(10, 4))
        assertNull(index.findBuildingAt(9, 10))
        assertNull(index.findBuildingAt(14, 5))
    }

    @Test
    fun rebuild_tallBuilding_withoutSpriteSizes_fallsBackToFootprint() {
        val tower = GridBuildingData(
            buildingId = "t1", displayName = "问道塔",
            gridX = 10, gridY = 10, width = 4, height = 3,
            instanceId = "t1", sectId = ""
        )
        // 不传 spriteSizes：仅占地命中（默认参数兼容旧调用方）
        index.rebuild(listOf(tower))

        assertEquals(tower, index.findBuildingAt(10, 10))
        assertNull("未传精灵尺寸时塔尖不应命中", index.findBuildingAt(10, 5))
    }

    @Test
    fun rebuild_corruptedHugeSize_clampsHitAreaWithoutHang() {
        // 2026-08-06 对抗性审查 F2：损坏存档 width/height 异常巨大（如 Int.MAX_VALUE）时，
        // 循环范围被钳制为 MAX_HIT_EXTENT_CELLS=128 以内，防止主线程亿级迭代 ANR
        val corrupted = GridBuildingData(
            buildingId = "c1", displayName = "未知损坏建筑",
            gridX = 0, gridY = 0, width = Int.MAX_VALUE, height = Int.MAX_VALUE,
            instanceId = "c1", sectId = ""
        )
        index.rebuild(listOf(corrupted))

        // 钳制后命中区域仍覆盖起点格（128×128 内）
        assertNotNull(index.findBuildingAt(0, 0))
        assertNotNull(index.findBuildingAt(127, 127))
        assertNull("钳制边界外不应命中", index.findBuildingAt(128, 0))
    }

    @Test
    fun findBuildingAt_overlappingSprites_returnsTopmostByDrawOrder() {
        // 两座问道塔上下相邻：b1 占地 y 0-2（精灵向上 5-2），b2 占地 y 3-5（精灵 0-5）
        // 重叠格 y ∈ 0..2：渲染按 gridY+height 升序，b2（键 8）后绘制压住 b1（键 5）
        val b1 = GridBuildingData(
            buildingId = "b1", displayName = "问道塔",
            gridX = 0, gridY = 0, width = 4, height = 3,
            instanceId = "b1", sectId = ""
        )
        val b2 = GridBuildingData(
            buildingId = "b2", displayName = "问道塔",
            gridX = 0, gridY = 5, width = 4, height = 3,
            instanceId = "b2", sectId = ""
        )
        val spriteSizes = mapOf("问道塔" to GridSnapHelper.BuildingSize(4, 8))
        index.rebuild(listOf(b1, b2), spriteSizes)

        assertEquals("重叠格应命中绘制顺序更上层的 b2", b2, index.findBuildingAt(0, 0))
        assertEquals("b1 独占的塔尖格命中 b1", b1, index.findBuildingAt(0, -1))
        assertEquals("b2 独占的占地格命中 b2", b2, index.findBuildingAt(1, 5))
    }
}
