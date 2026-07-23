package com.xianxia.sect.ui.game.sect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderCommandBusTest {

    @Test
    fun `post and consume returns same data`() {
        val bus = RenderCommandBus()
        val data = floatArrayOf(1f, 2f, 3f, 4f, 5f)

        bus.postBuildingData(data, 1)
        val snapshot = bus.consumeBuildingData()

        assertNotNull(snapshot.data)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), snapshot.data, 0f)
        assertEquals(1, snapshot.count)
    }

    @Test
    fun `post overwrites previous data`() {
        val bus = RenderCommandBus()
        val dataA = floatArrayOf(1f, 2f, 3f)
        val dataB = floatArrayOf(4f, 5f, 6f)

        bus.postBuildingData(dataA, 1)
        bus.postBuildingData(dataB, 2)
        val snapshot = bus.consumeBuildingData()

        assertArrayEquals(floatArrayOf(4f, 5f, 6f), snapshot.data, 0f)
        assertEquals(2, snapshot.count)
    }

    @Test
    fun `post with null clears data`() {
        val bus = RenderCommandBus()
        bus.postBuildingData(floatArrayOf(1f, 2f, 3f), 1)

        bus.postBuildingData(null, 0)
        val snapshot = bus.consumeBuildingData()

        assertNull(snapshot.data)
        assertEquals(0, snapshot.count)
    }

    @Test
    fun `buildingDirty is set on post, cleared on consume`() {
        val bus = RenderCommandBus()

        assertFalse(bus.buildingDirty.get())

        bus.postBuildingData(floatArrayOf(1f), 1)
        assertTrue(bus.buildingDirty.get())

        bus.consumeBuildingData()
        assertFalse(bus.buildingDirty.get())
    }

    @Test
    fun `reset clears all state`() {
        val bus = RenderCommandBus()
        bus.postBuildingData(floatArrayOf(1f, 2f, 3f), 3)

        bus.reset()

        assertNull(bus.buildingData)
        assertEquals(0, bus.buildingCount)
        assertFalse(bus.buildingDirty.get())
    }

    @Test
    fun `copyOf prevents mutation after post`() {
        val bus = RenderCommandBus()
        val original = floatArrayOf(1f, 2f, 3f)
        bus.postBuildingData(original, 3)

        // Modify the original array after posting
        original[0] = 99f
        original[1] = 88f
        original[2] = 77f

        val snapshot = bus.consumeBuildingData()
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), snapshot.data, 0f)
        assertEquals(3, snapshot.count)
    }

    @Test
    fun `multiple posts without consume in between`() {
        val bus = RenderCommandBus()
        val dataA = floatArrayOf(1f, 2f)
        val dataB = floatArrayOf(3f, 4f, 5f)

        bus.postBuildingData(dataA, 2)
        bus.postBuildingData(dataB, 3)
        val snapshot = bus.consumeBuildingData()

        assertArrayEquals(floatArrayOf(3f, 4f, 5f), snapshot.data, 0f)
        assertEquals(3, snapshot.count)
    }

    @Test
    fun `consumeBuildingData returns correct snapshot`() {
        val bus = RenderCommandBus()
        val data = floatArrayOf(10f, 20f, 30f, 40f)

        bus.postBuildingData(data, 4)
        val snapshot = bus.consumeBuildingData()

        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 40f), snapshot.data, 0f)
        assertEquals(4, snapshot.count)
    }
}
