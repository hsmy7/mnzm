package com.xianxia.sect.ui.game.sect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ★ Robolectric 必需：RenderCommandBus 的帧数据依赖 android.util 类型，
 * 无 Robolectric 时未 mock 导致数据丢失断言假失败。
 */
@RunWith(RobolectricTestRunner::class)
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
        // 契约：每建筑 5 个 float（data.size >= count*5），否则 count 被钳到 0
        val dataA = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val dataB = floatArrayOf(4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f)

        bus.postBuildingData(dataA, 1)
        bus.postBuildingData(dataB, 2)
        val snapshot = bus.consumeBuildingData()

        assertArrayEquals(floatArrayOf(4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f), snapshot.data, 0f)
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
        // 契约：每建筑 5 个 float（data.size >= count*5）
        val original = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f, 14f, 15f)
        bus.postBuildingData(original, 3)

        // Modify the original array after posting
        original[0] = 99f
        original[1] = 88f
        original[2] = 77f

        val snapshot = bus.consumeBuildingData()
        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f, 13f, 14f, 15f),
            snapshot.data, 0f
        )
        assertEquals(3, snapshot.count)
    }

    @Test
    fun `multiple posts without consume in between`() {
        val bus = RenderCommandBus()
        val dataA = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
        val dataB = floatArrayOf(11f, 12f, 13f, 14f, 15f, 16f, 17f, 18f, 19f, 20f, 21f, 22f, 23f, 24f, 25f)

        bus.postBuildingData(dataA, 2)
        bus.postBuildingData(dataB, 3)
        val snapshot = bus.consumeBuildingData()

        assertArrayEquals(
            floatArrayOf(11f, 12f, 13f, 14f, 15f, 16f, 17f, 18f, 19f, 20f, 21f, 22f, 23f, 24f, 25f),
            snapshot.data, 0f
        )
        assertEquals(3, snapshot.count)
    }

    @Test
    fun `consumeBuildingData returns correct snapshot`() {
        val bus = RenderCommandBus()
        val data = floatArrayOf(10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f, 110f, 120f, 130f, 140f, 150f, 160f, 170f, 180f, 190f, 200f)

        bus.postBuildingData(data, 4)
        val snapshot = bus.consumeBuildingData()

        assertArrayEquals(data, snapshot.data, 0f)
        assertEquals(4, snapshot.count)
    }
}
