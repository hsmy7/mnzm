package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.GridBuildingData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BuildingSpatialIndexTest {

    private lateinit var index: BuildingSpatialIndex

    @Before
    fun setUp() {
        index = BuildingSpatialIndex()
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
}
